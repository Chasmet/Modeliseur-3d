"""Isolated free Android WebView ↔ ChatGPT MCP relay.

New paths only: /agentbrowser/api/* and /agentbrowser/mcp/<id>/<capability>.
Existing Modéliseur MCP and API paths are untouched. Commands remain in
memory; browser data and authentication never persist on Render.
"""
import asyncio
from contextvars import ContextVar
import hashlib
import hmac
import os
import re
import secrets
import time
from urllib.parse import urlparse

from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

HOST = os.environ.get("RENDER_EXTERNAL_HOSTNAME", "modeliseur-trellis-mcp.onrender.com")
BASE = "https://" + HOST
MASTER = os.environ.get("BROWSER_RELAY_SECRET", "")
DEVICE_RE = re.compile(r"^[a-f0-9]{64}$")
OWNER = ContextVar("agent_browser_device", default="")
ONLINE_TTL = 35
COMMAND_TTL = 48
MAX_DEVICES = 12
state = {}


def signature(device_id: str) -> str:
    if len(MASTER) < 32:
        raise ValueError("Configuration de sécurité du relais absente")
    return hmac.new(MASTER.encode("utf-8"), ("agentbrowser:mcp:" + device_id).encode("utf-8"), hashlib.sha256).hexdigest()


def get_device(device_id: str):
    now = time.monotonic()
    # Bound memory without ever modifying another application's database.
    for key, row in list(state.items()):
        if now - row["seen"] > 1800 and not row["pending"]:
            state.pop(key, None)
    if device_id not in state:
        if len(state) >= MAX_DEVICES:
            return None
        state[device_id] = {"seen": 0., "commands": [], "pending": {}, "page": "", "title": ""}
    return state[device_id]


def authenticated(request: Request):
    token = request.headers.get("authorization", "")
    if not token.startswith("Bearer "):
        return None, None
    token = token[7:].strip()
    if not DEVICE_RE.fullmatch(token):
        return None, None
    device_id = hashlib.sha256(bytes.fromhex(token)).hexdigest()
    # A random secret held only by the Android application proves possession.
    # Knowing the public device identifier cannot authenticate phone requests.
    return device_id, get_device(device_id)


def bad(reason, code=400):
    return JSONResponse({"ok": False, "error": reason}, status_code=code)


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
    return JSONResponse({"ok": True, "device_id": device_id,
                         "mcp_url": BASE + "/agentbrowser/mcp/" + device_id + "/" + cap,
                         "poll_seconds": 3})


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
        except (ValueError, TypeError):
            pass
    return JSONResponse({"ok": True, "online": True})


async def poll(request: Request):
    device_id, row = authenticated(request)
    if not row:
        return bad("Appareil non autorisé", 401)
    row["seen"] = time.monotonic()
    now = time.monotonic()
    row["commands"][:] = [c for c in row["commands"] if now-c["created"] < COMMAND_TTL]
    command = row["commands"].pop(0) if row["commands"] else None
    if command:
        command = {k: v for k, v in command.items() if k != "created"}
    return JSONResponse({"ok": True, "command": command})


async def result(request: Request):
    device_id, row = authenticated(request)
    if not row:
        return bad("Appareil non autorisé", 401)
    if request.headers.get("content-length", "0").isdigit() and int(request.headers["content-length"]) > 48000:
        return bad("Réponse trop longue", 413)
    try:
        data = await request.body()
        if len(data) > 48000:
            return bad("Réponse trop longue", 413)
        import json
        payload = json.loads(data)
        ident = str(payload.get("id", ""))
        future = row["pending"].get(ident)
        if not future or future.done():
            return bad("Commande expirée ou inconnue", 404)
        answer = {"ok": bool(payload.get("ok", False)),
                  "result": str(payload.get("result", ""))[:32000],
                  "error": str(payload.get("error", ""))[:1000]}
        future.set_result(answer)
    except (ValueError, TypeError):
        return bad("Résultat invalide")
    return JSONResponse({"ok": True})


async def health(request: Request):
    online = sum(time.monotonic()-row["seen"] < ONLINE_TTL for row in state.values())
    return JSONResponse({"ok": True, "module": "agentbrowser-mcp", "online_devices": online,
                         "configured": len(MASTER) >= 32})


browser_http = Starlette(routes=[
    Route("/agentbrowser/api/health", health, methods=["GET"]),
    Route("/agentbrowser/api/register", register, methods=["POST"]),
    Route("/agentbrowser/api/heartbeat", heartbeat, methods=["POST"]),
    Route("/agentbrowser/api/poll", poll, methods=["GET"]),
    Route("/agentbrowser/api/result", result, methods=["POST"]),
])

browser_mcp = FastMCP(
    "CHK Agent Browser",
    stateless_http=True, json_response=True, streamable_http_path="/",
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
        return {"ok": False, "error": "Navigateur hors ligne. Ouvrir CHK Agent Browser sur le téléphone."}
    if len(row["pending"]) >= 4:
        return {"ok": False, "error": "Trop de commandes simultanées (maximum 4)."}
    ident = secrets.token_hex(12)
    future = asyncio.get_running_loop().create_future()
    row["pending"][ident] = future
    row["commands"].append({"id": ident, "action": action, "args": args or {},
                            "created": time.monotonic()})
    try:
        return await asyncio.wait_for(future, timeout=COMMAND_TTL)
    except asyncio.TimeoutError:
        return {"ok": False, "error": "Le téléphone n'a pas répondu sous 48 secondes."}
    finally:
        row["pending"].pop(ident, None)
        row["commands"][:] = [c for c in row["commands"] if c["id"] != ident]


@browser_mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": False})
def browser_status() -> dict:
    """Check phone connectivity; does not require reading private webpages."""
    row = state.get(OWNER.get())
    return {"online": bool(row and time.monotonic()-row["seen"] < ONLINE_TTL),
            "title": row["title"] if row else "", "url": row["page"] if row else "",
            "transport": "Android WebView via isolated Render relay",
            "credits": "no AI API or paid browser service"}


@browser_mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": True})
async def browser_read_page() -> dict:
    """Read visible text and current page URL with explicit confirmation on Android."""
    return await issue("read_page")


@browser_mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": False})
async def browser_tabs() -> dict:
    """List titles and addresses of tabs opened on the Android phone."""
    return await issue("tabs")


@browser_mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": True})
async def browser_open_url(url: str) -> dict:
    """Open an HTTPS URL in Android browser after phone-owner confirmation."""
    url = url.strip()
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or len(url) > 2000:
        return {"ok": False, "error": "URL HTTPS publique requise."}
    host = parsed.hostname.lower()
    try:
        import ipaddress
        if ipaddress.ip_address(host).is_global is False:
            return {"ok": False, "error": "Adresses réseau privées non autorisées."}
    except ValueError:
        if host == "localhost" or host.endswith(".local") or host.endswith(".internal"):
            return {"ok": False, "error": "Adresse locale interdite."}
    return await issue("open_url", {"url": url})


@browser_mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": True})
async def browser_click(selector: str) -> dict:
    """Click one CSS selector on current page, after approval on phone."""
    if not 0 < len(selector) < 350:
        return {"ok": False, "error": "Sélecteur CSS invalide."}
    return await issue("click", {"selector": selector})


@browser_mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": True})
async def browser_type(selector: str, text: str) -> dict:
    """Write text into an ordinary field with approval; password fields are blocked."""
    if not 0 < len(selector) < 350 or len(text) > 500:
        return {"ok": False, "error": "Sélecteur ou texte trop long."}
    return await issue("type", {"selector": selector, "text": text})


@browser_mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": False})
async def browser_scroll(direction: str = "down") -> dict:
    """Scroll the current web page up or down with Android owner's approval."""
    if direction not in ("up", "down"):
        return {"ok": False, "error": "Direction attendue : up ou down."}
    return await issue("scroll", {"direction": direction})


async def relay_dispatch(scope, receive, send):
    """Called only for the /agentbrowser/ prefix by the existing ASGI app."""
    path = scope.get("path", "")
    if scope.get("type") != "http":
        await bad("HTTP seulement", 404)(scope, receive, send)
        return
    prefix = "/agentbrowser/mcp/"
    if path.startswith(prefix):
        parts = path[len(prefix):].strip("/").split("/")
        if len(parts) != 2 or not DEVICE_RE.fullmatch(parts[0]) or not DEVICE_RE.fullmatch(parts[1]):
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
