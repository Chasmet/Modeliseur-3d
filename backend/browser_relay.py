"""Isolated Android WebView ↔ ChatGPT MCP relay for CHK Agent Browser.

Only /agentbrowser/* paths are owned by this module. Existing Modéliseur 3D
routes stay untouched. Browser authentication/cookies remain on Android.
Temporary upload staging is short-lived, per-device, size-bounded and removed.
"""
import asyncio
import base64
import binascii
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import socket
import time
from contextvars import ContextVar
from urllib.parse import urljoin, urlparse

import httpx
from mcp.server.fastmcp import FastMCP, Image
from mcp.server.transport_security import TransportSecuritySettings
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import FileResponse, JSONResponse
from starlette.routing import Route
from backend.browser_signing import issue_signing

HOST = os.environ.get("RENDER_EXTERNAL_HOSTNAME", "modeliseur-trellis-mcp.onrender.com")
BASE = "https://" + HOST
MASTER = os.environ.get("BROWSER_RELAY_SECRET", "")
DEVICE_RE = re.compile(r"^[a-f0-9]{64}$")
TRANSFER_RE = re.compile(r"^[a-f0-9]{24}$")
OWNER = ContextVar("agent_browser_device", default="")
ONLINE_TTL = 120
COMMAND_TTL = 300
CLAIM_TTL = 35
ISSUE_TIMEOUT = 210
MAX_DEVICES = 12
MAX_TRANSFER = 12 * 1024 * 1024
MAX_TRANSFER_TOTAL = 32 * 1024 * 1024
TRANSFER_TTL = 600
TRANSFER_ROOT = Path("/tmp/agentbrowser-transfers")
TRANSFER_ROOT.mkdir(parents=True, exist_ok=True)
state = {}


def signature(device_id: str) -> str:
    if len(MASTER) < 32:
        raise ValueError("Configuration de sécurité du relais absente")
    return hmac.new(
        MASTER.encode("utf-8"),
        ("agentbrowser:mcp:" + device_id).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


def _remove_transfer(row, transfer_id: str):
    item = row.get("transfers", {}).pop(transfer_id, None)
    if not item:
        return
    try:
        Path(item["path"]).unlink(missing_ok=True)
    except OSError:
        pass


def _cleanup_transfers(row):
    now = time.time()
    for transfer_id, item in list(row.get("transfers", {}).items()):
        if now - float(item.get("created", 0)) > TRANSFER_TTL:
            _remove_transfer(row, transfer_id)


def get_device(device_id: str):
    now = time.monotonic()
    for key, row in list(state.items()):
        _cleanup_transfers(row)
        if now - row["seen"] > 1800 and not row["pending"]:
            for transfer_id in list(row.get("transfers", {})):
                _remove_transfer(row, transfer_id)
            state.pop(key, None)
    if device_id not in state:
        if len(state) >= MAX_DEVICES:
            return None
        state[device_id] = {
            "seen": 0.0,
            "commands": [],
            "pending": {},
            "types": {},
            "page": "",
            "title": "",
            "session_source": "unknown",
            "autonomous": False,
            "receipts": {},
            "transfers": {},
        }
    return state[device_id]


def authenticated(request: Request):
    token = request.headers.get("authorization", "")
    if not token.startswith("Bearer "):
        return None, None
    token = token[7:].strip()
    if not DEVICE_RE.fullmatch(token):
        return None, None
    device_id = hashlib.sha256(bytes.fromhex(token)).hexdigest()
    return device_id, get_device(device_id)


def bad(reason, code=400):
    return JSONResponse({"ok": False, "error": reason}, status_code=code)


def current_host(row):
    try:
        parsed = urlparse(row.get("page", ""))
        if parsed.scheme != "https" or not parsed.hostname:
            return ""
        return parsed.hostname.lower()
    except Exception:
        return ""


def destination_host(row, expected_host=""):
    active = current_host(row)
    expected = (expected_host or "").strip().lower()
    if not active:
        raise ValueError("Aucune page HTTPS active sur le téléphone.")
    if expected and expected != active:
        raise ValueError("La page active ne correspond pas à la destination autorisée.")
    return active


def validate_public_https(value: str) -> str:
    value = (value or "").strip()
    parsed = urlparse(value)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or len(value) > 4000
    ):
        raise ValueError("URL HTTPS publique requise.")
    host = parsed.hostname.lower()
    if host == "localhost" or host.endswith(".local") or host.endswith(".internal"):
        raise ValueError("Adresse locale interdite.")
    try:
        ip = ipaddress.ip_address(host)
        if not ip.is_global:
            raise ValueError("Adresse réseau privée interdite.")
    except ValueError as exc:
        if str(exc) == "Adresse réseau privée interdite.":
            raise
    return value


async def assert_public_dns(url: str):
    parsed = urlparse(url)
    try:
        infos = await asyncio.to_thread(
            socket.getaddrinfo, parsed.hostname, 443, type=socket.SOCK_STREAM
        )
    except OSError as exc:
        raise ValueError("Hôte de fichier introuvable.") from exc
    for info in infos:
        address = info[4][0].split("%", 1)[0]
        if not ipaddress.ip_address(address).is_global:
            raise ValueError("Adresse privée ou locale refusée.")


def safe_name(value: str) -> str:
    value = re.sub(r"[^A-Za-z0-9._-]", "_", str(value or "file"))
    if not value:
        value = "file"
    return value[-120:]


async def read_remote_file(url: str) -> tuple[bytes, str]:
    current = validate_public_https(url)
    timeout = httpx.Timeout(35.0, connect=12.0)
    async with httpx.AsyncClient(
        follow_redirects=False, timeout=timeout, trust_env=False
    ) as client:
        for hop in range(4):
            await assert_public_dns(current)
            async with client.stream(
                "GET", current, headers={"User-Agent": "CHK-Agent-Browser-Relay/2.1"}
            ) as response:
                if response.status_code in (301, 302, 303, 307, 308):
                    if hop >= 3:
                        raise ValueError("Trop de redirections pour le fichier source.")
                    location = response.headers.get("location", "")
                    if not location:
                        raise ValueError("Redirection de fichier invalide.")
                    current = validate_public_https(urljoin(current, location))
                    continue
                if response.status_code != 200:
                    raise ValueError(
                        "Téléchargement source refusé (HTTP %d)." % response.status_code
                    )
                length = response.headers.get("content-length")
                if length and int(length) > MAX_TRANSFER:
                    raise ValueError("Fichier supérieur à 12 Mo.")
                raw = bytearray()
                async for chunk in response.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw) > MAX_TRANSFER:
                        raise ValueError("Fichier supérieur à 12 Mo.")
                mime = response.headers.get("content-type", "").split(";", 1)[0].strip()
                return bytes(raw), mime
    raise ValueError("Fichier source indisponible.")


def decode_base64_file(value: str) -> bytes:
    if not isinstance(value, str) or not value:
        raise ValueError("Contenu base64 manquant.")
    if len(value) > (MAX_TRANSFER * 4 // 3) + 8192:
        raise ValueError("Fichier encodé trop volumineux.")
    payload = value
    if value.startswith("data:"):
        try:
            header, payload = value.split(",", 1)
        except ValueError as exc:
            raise ValueError("Data URL invalide.") from exc
        if ";base64" not in header.lower():
            raise ValueError("Data URL base64 requise.")
    elif value.startswith("base64:"):
        payload = value[7:]
    try:
        raw = base64.b64decode(payload, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError("Base64 invalide.") from exc
    if not raw or len(raw) > MAX_TRANSFER:
        raise ValueError("Taille de fichier refusée.")
    return raw


async def stage_file(device_id: str, row: dict, item: dict) -> dict:
    if not isinstance(item, dict):
        raise ValueError("Description de fichier invalide.")
    name = safe_name(item.get("name") or "file")
    mime = str(item.get("mime_type") or "application/octet-stream")[:160]
    source_url = str(item.get("source_url") or item.get("url") or "")
    encoded = item.get("base64") or item.get("data_base64") or ""
    if bool(source_url) == bool(encoded):
        raise ValueError(
            "Chaque fichier doit fournir exactement source_url ou base64."
        )
    if source_url:
        raw, detected = await read_remote_file(source_url)
        if mime == "application/octet-stream" and detected:
            mime = detected[:160]
    else:
        raw = decode_base64_file(encoded)
    transfer_id = secrets.token_hex(12)
    folder = TRANSFER_ROOT / device_id
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / transfer_id
    path.write_bytes(raw)
    digest = hashlib.sha256(raw).hexdigest()
    descriptor = {
        "id": transfer_id,
        "name": name,
        "mime_type": mime,
        "size": len(raw),
        "sha256": digest,
        "created": time.time(),
        "path": str(path),
    }
    row["transfers"][transfer_id] = descriptor
    return {
        "id": transfer_id,
        "name": name,
        "mime_type": mime,
        "size": len(raw),
        "sha256": digest,
        "url": BASE + "/agentbrowser/api/transfers/" + transfer_id,
    }


async def register(request: Request):
    if not request.headers.get("content-type", "").startswith("application/json"):
        return bad("JSON requis")
    try:
        payload = await request.json()
        token = payload.get("device_token", "")
        if not isinstance(token, str) or not DEVICE_RE.fullmatch(token):
            return bad("Jeton d'installation local invalide")
        device_id = hashlib.sha256(bytes.fromhex(token)).hexdigest()
        row = get_device(device_id)
        if row is None:
            return bad("Capacité du relais atteinte", 503)
        cap = signature(device_id)
    except (ValueError, TypeError):
        return bad("Configuration ou demande incorrecte", 503)
    return JSONResponse(
        {
            "ok": True,
            "device_id": device_id,
            "mcp_url": BASE + "/agentbrowser/mcp/" + device_id + "/" + cap,
            "poll_seconds": 3,
        }
    )


async def heartbeat(request: Request):
    device_id, row = authenticated(request)
    if not row:
        return bad("Appareil non autorisé", 401)
    row["seen"] = time.monotonic()
    if request.method == "POST":
        try:
            payload = await request.json()
            row["title"] = str(payload.get("title", ""))[:150]
            row["page"] = str(payload.get("url", ""))[:500]
            row["session_source"] = str(payload.get("session_source", "unknown"))[:30]
            if isinstance(payload.get("autonomous"), bool):
                row["autonomous"] = payload["autonomous"]
            executing = str(payload.get("executing_id", ""))
            if executing:
                for command in row["commands"]:
                    if command["id"] == executing:
                        command["claimed_at"] = time.monotonic()
                        break
        except (ValueError, TypeError):
            pass
    return JSONResponse({"ok": True, "online": True})


async def poll(request: Request):
    device_id, row = authenticated(request)
    if not row:
        return bad("Appareil non autorisé", 401)
    row["seen"] = time.monotonic()
    now = time.monotonic()
    row["commands"][:] = [
        c for c in row["commands"] if now - c["created"] < COMMAND_TTL
    ]
    command = None
    for candidate in row["commands"]:
        claimed = float(candidate.get("claimed_at") or 0)
        if claimed == 0 or now - claimed > CLAIM_TTL:
            candidate["claimed_at"] = now
            candidate["attempts"] = int(candidate.get("attempts") or 0) + 1
            command = {
                "id": candidate["id"],
                "action": candidate["action"],
                "args": candidate["args"],
                "attempt": candidate["attempts"],
            }
            break
    return JSONResponse({"ok": True, "command": command})


async def result(request: Request):
    device_id, row = authenticated(request)
    if not row:
        return bad("Appareil non autorisé", 401)
    length_header = request.headers.get("content-length", "0")
    if length_header.isdigit() and int(length_header) > 260000:
        return bad("Réponse trop longue", 413)
    try:
        data = await request.body()
        if len(data) > 260000:
            return bad("Réponse trop longue", 413)
        payload = json.loads(data)
        ident = str(payload.get("id", ""))
        digest = hashlib.sha256(data).hexdigest()
        receipt = row["receipts"].get(ident)
        if receipt:
            if not hmac.compare_digest(receipt, digest):
                return bad("Résultat déjà reçu avec un contenu différent", 409)
            row["seen"] = time.monotonic()
            return JSONResponse({"ok": True, "duplicate": True})
        future = row["pending"].get(ident)
        if not future or future.done():
            return bad("Commande expirée ou inconnue", 404)
        is_preview = row["types"].get(ident) in ("preview", "screenshot")
        maximum = 240000 if is_preview else 32000
        if not is_preview and len(data) > 48000:
            return bad("Résultat trop long pour cette commande", 413)
        answer = {
            "ok": bool(payload.get("ok", False)),
            "result": str(payload.get("result", ""))[:maximum],
            "error": str(payload.get("error", ""))[:1000],
        }
        row["receipts"][ident] = digest
        while len(row["receipts"]) > 64:
            row["receipts"].pop(next(iter(row["receipts"])))
        row["commands"][:] = [c for c in row["commands"] if c["id"] != ident]
        row["seen"] = time.monotonic()
        future.set_result(answer)
    except (ValueError, TypeError):
        return bad("Résultat invalide")
    return JSONResponse({"ok": True})


async def transfer_download(request: Request):
    device_id, row = authenticated(request)
    if not row:
        return bad("Appareil non autorisé", 401)
    _cleanup_transfers(row)
    transfer_id = request.path_params["transfer_id"]
    if not TRANSFER_RE.fullmatch(transfer_id):
        return bad("Transfert invalide", 404)
    item = row["transfers"].get(transfer_id)
    if not item:
        return bad("Transfert expiré", 404)
    path = Path(item["path"])
    if not path.is_file():
        return bad("Fichier temporaire introuvable", 404)
    return FileResponse(
        path,
        media_type=item["mime_type"],
        filename=item["name"],
        headers={"Cache-Control": "no-store"},
    )


async def health(request: Request):
    online = sum(
        time.monotonic() - row["seen"] < ONLINE_TTL for row in state.values()
    )
    return JSONResponse(
        {
            "ok": True,
            "module": "agentbrowser-mcp",
            "version": "2.1-upload-resume",
            "online_devices": online,
            "configured": len(MASTER) >= 32,
            "command_ttl_seconds": COMMAND_TTL,
            "claim_ttl_seconds": CLAIM_TTL,
        }
    )


browser_http = Starlette(
    routes=[
        Route("/agentbrowser/ci/signing", issue_signing, methods=["GET"]),
        Route("/agentbrowser/api/health", health, methods=["GET"]),
        Route("/agentbrowser/api/register", register, methods=["POST"]),
        Route("/agentbrowser/api/heartbeat", heartbeat, methods=["POST"]),
        Route("/agentbrowser/api/poll", poll, methods=["GET"]),
        Route("/agentbrowser/api/result", result, methods=["POST"]),
        Route(
            "/agentbrowser/api/transfers/{transfer_id}",
            transfer_download,
            methods=["GET"],
        ),
    ]
)

browser_mcp = FastMCP(
    "CHK Agent Browser",
    stateless_http=True,
    json_response=True,
    streamable_http_path="/",
    transport_security=TransportSecuritySettings(
        enable_dns_rebinding_protection=True,
        allowed_hosts=["localhost:*", "127.0.0.1:*", "testserver", HOST, HOST + ":*"],
        allowed_origins=[BASE],
    ),
)
browser_mcp_app = browser_mcp.streamable_http_app()


async def issue(action: str, args: dict = None) -> dict:
    device_id = OWNER.get()
    row = state.get(device_id)
    if not row or time.monotonic() - row["seen"] > ONLINE_TTL:
        return {
            "ok": False,
            "error": "Navigateur hors ligne. Ouvrir CHK Agent Browser sur le téléphone.",
        }
    if len(row["pending"]) >= 4:
        return {"ok": False, "error": "Trop de commandes simultanées (maximum 4)."}
    ident = secrets.token_hex(12)
    future = asyncio.get_running_loop().create_future()
    row["pending"][ident] = future
    row["types"][ident] = action
    row["commands"].append(
        {
            "id": ident,
            "action": action,
            "args": args or {},
            "created": time.monotonic(),
            "claimed_at": 0.0,
            "attempts": 0,
        }
    )
    try:
        return await asyncio.wait_for(future, timeout=ISSUE_TIMEOUT)
    except asyncio.TimeoutError:
        return {
            "ok": False,
            "error": "Le téléphone n'a pas confirmé le résultat avant expiration de la commande.",
        }
    finally:
        row["pending"].pop(ident, None)
        row["types"].pop(ident, None)
        row["commands"][:] = [c for c in row["commands"] if c["id"] != ident]


@browser_mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": False})
def browser_status() -> dict:
    """Check Android phone connectivity, relay state and current page metadata."""
    row = state.get(OWNER.get())
    return {
        "online": bool(row and time.monotonic() - row["seen"] < ONLINE_TTL),
        "title": row["title"] if row else "",
        "session_source": row.get("session_source", "unknown") if row else "unknown",
        "url": row["page"] if row else "",
        "transport": "Android WebView via isolated Render relay",
        "relay_version": "2.1-upload-resume",
        "credits": "no AI API or paid browser service",
        "autonomous": row["autonomous"] if row else False,
        "last_seen_seconds": round(time.monotonic() - row["seen"], 1)
        if row and row["seen"]
        else None,
        "approval_mode": "saved_owner_consent"
        if row and row["autonomous"]
        else "manual_on_phone",
        "queued_commands": len(row["commands"]) if row else 0,
    }


@browser_mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": True})
async def browser_read_page() -> dict:
    """Read page text and accessible control selectors, including accessible same-origin frames and shadow roots."""
    return await issue("read_page")


@browser_mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": False})
async def browser_tabs() -> dict:
    """List titles and addresses of Android WebView tabs and popup windows."""
    return await issue("tabs")


@browser_mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": True})
async def browser_open_url(url: str) -> dict:
    """Open one public HTTPS URL in the owner-authorized Android WebView."""
    try:
        url = validate_public_https(url)
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}
    return await issue("open_url", {"url": url})


@browser_mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": True})
async def browser_click(selector: str) -> dict:
    """Click one accessible CSS selector on the current page."""
    if not 0 < len(selector) < 350:
        return {"ok": False, "error": "Sélecteur CSS invalide."}
    return await issue("click", {"selector": selector})


@browser_mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": True})
async def browser_click_verified(
    selector: str,
    expected_selector: str = "",
    expected_text: str = "",
    expected_url_contains: str = "",
    timeout_ms: int = 10000,
) -> dict:
    """Click no more than once, only if the requested postcondition is not already true.

    The Android browser waits for the selected element, text or URL to become
    observable. On timeout it reports an UNCONFIRMED result; do not retry a
    possibly completed click without inspecting the page.
    """
    if not selector or len(selector) > 340:
        return {"ok": False, "error": "Sélecteur CSS invalide."}
    if not any((expected_selector, expected_text, expected_url_contains)):
        return {"ok": False, "error": "Condition de réussite obligatoire."}
    if len(expected_selector) > 340 or len(expected_text) > 250 or len(expected_url_contains) > 500:
        return {"ok": False, "error": "Condition de réussite trop longue."}
    return await issue(
        "click_verified",
        {
            "selector": selector,
            "expected_selector": expected_selector,
            "expected_text": expected_text,
            "expected_url_contains": expected_url_contains,
            "timeout_ms": min(20000, max(800, int(timeout_ms))),
        },
    )


@browser_mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": True})
async def browser_type(selector: str, text: str) -> dict:
    """Fill an ordinary field. Password, hidden and file fields remain blocked."""
    if not 0 < len(selector) < 350 or len(text) > 8000:
        return {"ok": False, "error": "Sélecteur ou texte trop long."}
    return await issue("type", {"selector": selector, "text": text})


@browser_mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": False})
async def browser_scroll(direction: str = "down") -> dict:
    """Scroll the current page up or down."""
    if direction not in ("up", "down"):
        return {"ok": False, "error": "Direction attendue : up ou down."}
    return await issue("scroll", {"direction": direction})


@browser_mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": True})
async def browser_select_option(
    selector: str, value: str = "", label: str = "", index: int = -1
) -> dict:
    """Select an option in an HTML select element by value, visible label or index."""
    if not 0 < len(selector) < 350 or len(value) > 1000 or len(label) > 1000:
        return {"ok": False, "error": "Paramètres de sélection invalides."}
    return await issue(
        "select_option",
        {"selector": selector, "value": value, "label": label, "index": index},
    )


@browser_mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": True})
async def browser_check(selector: str, checked: bool = True) -> dict:
    """Check or uncheck a checkbox/radio-like control and verify its resulting state."""
    if not 0 < len(selector) < 350:
        return {"ok": False, "error": "Sélecteur invalide."}
    return await issue("check", {"selector": selector, "checked": bool(checked)})


@browser_mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": True})
async def browser_wait_for_element(selector: str, timeout_ms: int = 10000) -> dict:
    """Wait until an accessible selector appears, up to 30 seconds."""
    if not 0 < len(selector) < 350:
        return {"ok": False, "error": "Sélecteur invalide."}
    timeout_ms = max(500, min(int(timeout_ms), 30000))
    return await issue(
        "wait_for_element", {"selector": selector, "timeout_ms": timeout_ms}
    )


@browser_mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": True})
async def browser_get_form() -> dict:
    """Inspect non-sensitive form controls without exposing password or hidden fields."""
    return await issue("get_form")


@browser_mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": True})
async def browser_get_page_status() -> dict:
    """Return document readiness, dialogs, forms and visible success/error messages."""
    return await issue("get_page_status")


@browser_mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": False})
async def browser_switch_tab(index: int = -1, url_contains: str = "") -> dict:
    """Switch the active Android WebView tab by zero-based index or URL fragment."""
    if index < -1 or index > 20 or len(url_contains) > 500:
        return {"ok": False, "error": "Cible d'onglet invalide."}
    return await issue(
        "switch_tab", {"index": int(index), "url_contains": url_contains}
    )


@browser_mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": False})
async def browser_close_popup() -> dict:
    """Close the current secondary popup/tab, never the last remaining tab."""
    return await issue("close_popup")


@browser_mcp.tool(
    annotations={"readOnlyHint": False, "destructiveHint": False, "openWorldHint": True}
)
async def browser_upload_file(
    selector: str, files: list[dict], expected_host: str = ""
) -> dict:
    """Upload one to eight owner-authorized files into input[type=file].

    Each files item must contain name and exactly one source:
    source_url (public HTTPS) or base64/data_base64. mime_type is optional.
    Multiple items are delivered in one file chooser operation. The Android app
    verifies that the page reports the expected number of received files before
    success is returned.
    """
    if not 0 < len(selector) < 350:
        return {"ok": False, "error": "Sélecteur de fichier invalide."}
    if not isinstance(files, list) or not 1 <= len(files) <= 8:
        return {"ok": False, "error": "Entre 1 et 8 fichiers sont requis."}
    row = state.get(OWNER.get())
    if not row:
        return {"ok": False, "error": "Navigateur hors ligne."}
    try:
        host = destination_host(row, expected_host)
        staged = []
        total = 0
        for item in files:
            descriptor = await stage_file(OWNER.get(), row, item)
            total += descriptor["size"]
            if total > MAX_TRANSFER_TOTAL:
                raise ValueError("Ensemble de fichiers supérieur à 32 Mo.")
            staged.append(descriptor)
        reply = await issue(
            "upload_file",
            {"selector": selector, "files": staged, "expected_host": host},
        )
        return reply
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}
    finally:
        if row:
            for transfer_id in list(row.get("transfers", {})):
                item = row["transfers"].get(transfer_id)
                if item and time.time() - item.get("created", 0) < 60:
                    _remove_transfer(row, transfer_id)


@browser_mcp.tool(
    annotations={"readOnlyHint": False, "destructiveHint": False, "openWorldHint": True}
)
async def browser_download_file(
    selector: str = "",
    url: str = "",
    mime_type: str = "",
    expected_host: str = "",
) -> dict:
    """Download a document into CHK Agent Browser storage and wait for Android to confirm completion."""
    row = state.get(OWNER.get())
    if not row:
        return {"ok": False, "error": "Navigateur hors ligne."}
    try:
        host = destination_host(row, expected_host)
    except ValueError as exc:
        return {"ok": False, "error": str(exc)}
    if bool(selector) == bool(url):
        return {
            "ok": False,
            "error": "Fournis exactement un sélecteur de lien ou une URL directe.",
        }
    if selector and not 0 < len(selector) < 350:
        return {"ok": False, "error": "Sélecteur invalide."}
    if url:
        try:
            url = validate_public_https(url)
        except ValueError as exc:
            return {"ok": False, "error": str(exc)}
    return await issue(
        "download_file",
        {
            "selector": selector,
            "url": url,
            "mime_type": mime_type[:160],
            "expected_host": host,
        },
    )


async def _snapshot_reply():
    reply = await issue("screenshot")
    if not reply.get("ok"):
        return "Aperçu indisponible : " + reply.get(
            "error", "pas de réponse Android"
        )
    try:
        payload = json.loads(reply["result"])
        encoded = payload["jpeg_base64"]
        if not isinstance(encoded, str) or len(encoded) > 220000:
            return "Image rejetée : taille excessive"
        image_bytes = base64.b64decode(encoded, validate=True)
        if len(image_bytes) > 165000 or not image_bytes.startswith(b"\xff\xd8"):
            return "Image JPEG invalide"
        return Image(data=image_bytes, format="jpeg")
    except (KeyError, ValueError, TypeError, binascii.Error):
        return "La capture reçue ne peut pas être affichée"


@browser_mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": True})
async def browser_screenshot():
    """Capture the current Android browser page as a verified JPEG snapshot."""
    return await _snapshot_reply()


@browser_mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": True})
async def browser_preview():
    """Backward-compatible alias for browser_screenshot."""
    return await _snapshot_reply()


async def relay_dispatch(scope, receive, send):
    """Called only for the /agentbrowser/ prefix by the existing ASGI app."""
    path = scope.get("path", "")
    if scope.get("type") != "http":
        await bad("HTTP seulement", 404)(scope, receive, send)
        return
    prefix = "/agentbrowser/mcp/"
    if path.startswith(prefix):
        parts = path[len(prefix) :].strip("/").split("/")
        if (
            len(parts) != 2
            or not DEVICE_RE.fullmatch(parts[0])
            or not DEVICE_RE.fullmatch(parts[1])
        ):
            await bad("URL MCP invalide", 404)(scope, receive, send)
            return
        try:
            valid = hmac.compare_digest(signature(parts[0]), parts[1])
        except ValueError:
            valid = False
        if not valid:
            await bad("Accès MCP refusé", 401)(scope, receive, send)
            return
        context = OWNER.set(parts[0])
        try:
            inner = dict(scope, path="/", raw_path=b"/", root_path="")
            await browser_mcp_app(inner, receive, send)
        finally:
            OWNER.reset(context)
    else:
        await browser_http(scope, receive, send)
