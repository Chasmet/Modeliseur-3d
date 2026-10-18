"""Five agent desktop capabilities, scoped to CHK and the owner's Android grants."""
import base64
import binascii
import json
import math
import re
from mcp.server.fastmcp import Image
from backend.browser_workspace import validate_path

TOOLS = {"vision", "pointer", "files", "tasks", "terminal"}
GESTURES = {"tap", "double_tap", "long_press", "drag", "swipe", "back"}
FILE_OPS = {"plan", "run", "apply", "status", "cancel", "stat", "external_roots", "external_request_access", "external_revoke", "external_list", "external_import", "external_export", "external_read_text", "external_rename"}
ACTIONS = {
    "keyboard", "desktop", "files_list", "files_catalog", "files_mkdir",
    "files_copy", "files_move", "files_trash", "files_read_text",
    "files_write_text", "notes_list", "notes_read", "notes_save",
    "media_info", "workspace_status", "tabs", "list_tabs",
    "get_page_status", "get_form", "wait_for_element", "read_page",
    "open_url", "new_tab", "open_tab", "switch_tab", "close_tab",
    "translate_page", "click_verified", "type", "select_option",
    "check", "upload_file",
}
READ_ONLY_TASKS = {
    "files_list", "files_catalog", "files_read_text", "notes_list",
    "notes_read", "media_info", "workspace_status", "tabs",
    "list_tabs", "get_page_status", "get_form", "wait_for_element", "read_page",
}
VERIFIED_MUTATIONS = {"type", "select_option", "check", "click_verified", "upload_file"}


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", value):
        raise ValueError("Identifiant requis (lettres, chiffres, tiret ou souligné, 80 maximum).")


def validate(request):
    if not isinstance(request, dict) or request.get("tool") not in TOOLS:
        raise ValueError("Outil IA inconnu.")
    if len(json.dumps(request, ensure_ascii=False).encode()) > 60000:
        raise ValueError("Commande IA limitée à 60 Ko.")
    tool = request["tool"]
    for key, maximum in (("expected_context", 160), ("expected_revision", 64), ("expected_host", 250)):
        if not isinstance(request.get(key, ""), str) or len(request.get(key, "")) > maximum:
            raise ValueError("Paramètre IA invalide : " + key)
    if tool in {"vision", "pointer"}:
        if request.get("target", "native") not in {"native", "web"}:
            raise ValueError("Les gestes et la vision concernent uniquement CHK ou son onglet web.")
    if tool == "pointer":
        gesture = request.get("gesture", "tap")
        if gesture not in GESTURES or not request.get("expected_context"):
            raise ValueError("Observe l’écran et fournis son contexte avant un geste.")
        if request.get("coordinate_space", "view") not in {"view", "image"}:
            raise ValueError("Coordonnées attendues : view ou image.")
        if gesture != "back":
            keys = ("x", "y", "end_x", "end_y") if gesture in {"drag", "swipe"} else ("x", "y")
            for key in keys:
                value = request.get(key)
                if type(value) not in (float, int) or not math.isfinite(value) or not 0 <= value <= 20000:
                    raise ValueError("Coordonnée invalide : " + key)
        if "duration_ms" in request and (type(request["duration_ms"]) is not int or not 40 <= request["duration_ms"] <= 5000):
            raise ValueError("Durée de geste invalide.")
    if tool == "files":
        operation = request.get("operation", "plan")
        if operation not in FILE_OPS:
            raise ValueError("Opération de fichiers inconnue.")
        if operation not in {"stat", "external_roots", "external_request_access"} and not operation.startswith("external_"):
            identifier(request.get("id"))
        if operation.startswith("external_") and operation not in {"external_roots", "external_request_access"}:
            if not isinstance(request.get("root_id"), str) or not re.fullmatch("[a-f0-9]{16}", request["root_id"]):
                raise ValueError("Dossier externe autorisé requis.")
        for key in ("path", "source", "destination", "folder"):
            if key in request:
                validate_path(request[key], root=key in {"path", "folder", "destination"})
        if "paths" in request:
            paths = request["paths"]
            if not isinstance(paths, list) or not 1 <= len(paths) <= 500 or len(set(paths)) != len(paths):
                raise ValueError("Sélection limitée à 500 chemins uniques.")
            for path in paths:
                validate_path(path)
        if operation in {"plan", "apply"} and request.get("mode", "rename") not in {"rename", "organize", "copy", "move", "trash", "transform"}:
            raise ValueError("Traitement de fichiers non autorisé.")
    if tool == "tasks":
        identifier(request.get("id"))
        if request.get("operation", "status") not in {"submit", "status", "resume", "cancel"}:
            raise ValueError("Opération de tâche inconnue.")
        if request.get("operation") == "submit":
            steps = request.get("steps")
            if not isinstance(steps, list) or not 1 <= len(steps) <= 50:
                raise ValueError("Une tâche contient de 1 à 50 étapes.")
            ids = set()
            for step in steps:
                if not isinstance(step, dict) or step.get("action") not in ACTIONS or not isinstance(step.get("args"), dict):
                    raise ValueError("Action de tâche non autorisée.")
                identifier(step.get("id"))
                if step["id"] in ids or len(step["id"]) > 45:
                    raise ValueError("Identifiant d’étape répété ou trop long.")
                ids.add(step["id"])
                args = step["args"]
                if step["action"] == "desktop" and (args.get("tool") not in {"vision", "pointer", "files"} or args.get("capture")):
                    raise ValueError("Pas de tâches imbriquées ni de captures dans le journal.")
                if step["action"] in {"type", "select_option", "check", "click_verified", "upload_file", "wait_for_element"}:
                    selector = args.get("selector", "")
                    if not isinstance(selector, str) or not 0 < len(selector) <= 340 or not selector.strip():
                        raise ValueError("Une cible CSS inspectée est obligatoire.")
                if step["action"] == "type" and len(str(args.get("text", ""))) > 8000:
                    raise ValueError("Saisie limitée à 8000 caractères.")
                if step["action"] == "click_verified":
                    if int(args.get("timeout_ms", 10000)) > 20000:
                        raise ValueError("Vérification du clic limitée à 20 secondes.")
                    if not any(args.get(key) for key in ("expected_selector", "expected_text", "expected_url_contains")):
                        raise ValueError("Le clic doit préciser une condition observable.")
                if step["action"] == "upload_file":
                    host, files = args.get("expected_host"), args.get("files")
                    if not isinstance(host, str) or not re.fullmatch(r"[A-Za-z0-9.-]{3,253}", host) or host.startswith(".") or host.endswith("."):
                        raise ValueError("Domaine autorisé obligatoire pour l’importation.")
                    if not isinstance(files, list) or not 1 <= len(files) <= 8:
                        raise ValueError("Une sélection explicite de 1 à 8 fichiers est requise.")
                verify = step.get("verify")
                if verify is not None:
                    if not isinstance(verify, dict):
                        raise ValueError("Vérification de tâche invalide.")
                    action, verify_args = verify.get("action"), verify.get("args")
                    if not isinstance(verify_args, dict):
                        raise ValueError("Paramètres de vérification manquants.")
                    if action not in READ_ONLY_TASKS and not (
                        action == "keyboard" and verify_args.get("operation") == "inspect"
                        or action == "desktop" and (
                            verify_args.get("tool") == "vision"
                            or verify_args.get("tool") == "files" and
                            verify_args.get("operation") in {"stat", "status", "external_list", "external_read_text"}
                        )
                    ):
                        raise ValueError("La vérification doit uniquement lire l’état.")
                    if not isinstance(verify.get("expect"), dict) and not isinstance(verify.get("contains"), dict):
                        raise ValueError("Définis un résultat attendu pour la vérification.")
                if step["action"] == "desktop" and args.get("tool") == "pointer" and verify is None:
                    raise ValueError("Geste tactile : lecture de vérification obligatoire.")
                if step["action"] in VERIFIED_MUTATIONS and verify is None:
                    raise ValueError("Modification du web : lecture de vérification obligatoire.")
    if tool == "terminal":
        identifier(request.get("id"))
        if request.get("operation", "validate") not in {"validate", "run", "status", "resume", "cancel"}:
            raise ValueError("Opération de terminal inconnue.")
        if request.get("operation", "validate") in {"validate", "run"}:
            script = request.get("script")
            if not isinstance(script, dict) or type(script.get("version")) is not int or script["version"] != 1:
                raise ValueError("Langage autorisé : CHK Script version 1.")
            commands = script.get("commands")
            if not isinstance(commands, list) or not 1 <= len(commands) <= 50:
                raise ValueError("Un script contient de 1 à 50 commandes.")
            for command in commands:
                if not isinstance(command, dict) or command.get("command") not in {"rename", "organize", "copy", "move", "trash", "transform"}:
                    raise ValueError("Commande de script non autorisée.")
    return request


def register_desktop(mcp, issue, owner, state):
    async def call(request):
        try:
            validate(request)
        except (ValueError, TypeError, AttributeError) as exc:
            return {"ok": False, "error": str(exc)}
        row = state.get(owner.get())
        if row and row.get("desktop_version", 0) < 1:
            return {"ok": False, "error": "Mets à jour l’APK CHK pour activer souris, vision, fichiers, tâches et terminal IA."}
        reply = await issue("desktop", request)
        try:
            payload = json.loads(reply.get("result") or "")
            if isinstance(payload, dict):
                payload["ok"] = bool(reply.get("ok") and payload.get("ok", True))
                return payload
        except (ValueError, TypeError):
            pass
        return reply

    @mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": True})
    async def browser_desktop_inspect(target: str = "native") -> dict:
        """Observe CHK's current activity/dialog, or its web viewport: text, buttons, menus, bounds, context and revision.
        Coordinates are view pixels; image_width/image_height describe the reduced screenshot.
        Paused retained CHK screens remain available; another app and Android's picker are never inspected.
        Use context/revision for pointer gestures. Canvas content is available through desktop_screenshot.
        """
        return await call({"tool": "vision", "target": target})

    @mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": True})
    async def browser_desktop_screenshot(target: str = "native"):
        """Return CHK screen pixels plus context and coordinate metadata. Existing preview consent applies.
        capture_source distinguishes current window pixels from a retained view render; protected windows refuse capture.
        """
        payload = await call({"tool": "vision", "target": target, "capture": True})
        encoded = payload.pop("jpeg_base64", "")
        try:
            if not payload.get("ok") or len(encoded) > 200000:
                return payload
            data = base64.b64decode(encoded, validate=True)
            if not data.startswith(b"\xff\xd8") or len(data) > 150000:
                raise ValueError("Capture JPEG invalide.")
            return [Image(data=data, format="jpeg"), json.dumps(payload, ensure_ascii=False)]
        except (ValueError, TypeError, binascii.Error) as exc:
            return {"ok": False, "error": str(exc)}

    @mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": True})
    async def browser_pointer(gesture: str, expected_context: str, x: float = 0, y: float = 0,
                              end_x: float = 0, end_y: float = 0, target: str = "native",
                              expected_revision: str = "", expected_host: str = "",
                              coordinate_space: str = "view", duration_ms: int = 0) -> dict:
        """Actual Android tap/double_tap/long_press/drag/swipe/back in CHK or its WebView, including canvas.
        Observe first; supply expected_context and preferably expected_revision. Coordinates: view or reduced image pixels.
        Use duration_ms=0 for the appropriate default. gesture_sent/touch_accepted do not certify publication or task outcome.
        Inspect and verify after each gesture; no system-wide accessibility or other-app input injection.
        """
        request = dict(tool="pointer", gesture=gesture, expected_context=expected_context, x=x, y=y,
                       end_x=end_x, end_y=end_y, target=target, expected_revision=expected_revision,
                       expected_host=expected_host, coordinate_space=coordinate_space)
        if duration_ms:
            request["duration_ms"] = duration_ms
        return await call(request)

    @mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": False})
    async def browser_files_batch(request: dict) -> dict:
        """Preview or execute a 1–500 item workspace selection: operation=plan/run/status/cancel/stat, unique id.
        plan modes: rename(prefix/start), organize(destination, MIME categories), copy/move(destination), trash, transform.
        Sources: explicit paths, or folder and optional suffix. Run uses the saved id; destinations never overwrite.
        Every operation is checkpointed; repeat run skips confirmed items and exposes failed/uncertain items individually.
        Transform: format_json/text_replace(find,replace)/text_to_markdown(title)/lines_to_json; bounded ordinary text.
        Plan first, inspect collisions and stamps, run only that plan. Results are paginated, 25 items per page.
        """
        return await call({**request, "tool": "files"})

    @mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": False})
    async def browser_external_files(request: dict) -> dict:
        """Only Android directories explicitly granted by the owner: operation=external_roots/request_access/list/read_text/import/export/rename/revoke.
        Use the full external_ prefix, root_id from external_roots, relative path; list is paginated.
        request_access opens Android's picker and reports owner_action_required, never claims permission obtained.
        Import specifies workspace destination; export specifies workspace source, external directory path and new name.
        Revoked/read-only grants and existing destinations are respected. No raw arbitrary content URI or /sdcard access.
        """
        if not str(request.get("operation", "")).startswith("external_"):
            return {"ok": False, "error": "Une opération external_ est requise."}
        return await call({**request, "tool": "files"})

    @mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": True})
    async def browser_task_submit(id: str, steps: list[dict]) -> dict:
        """Start a durable local 1–50 step CHK workflow. Unique step id, action and args; optional read-only verify {action,args,expect/contains}.
        Prior JSON results are referenced by exact $step_id.field.path strings in later args (arrays use numeric indexes).
        Pointer steps require explicit verification; no nested tasks/scripts or screenshot data in task journals.
        Status returns progress; MCP must remain enabled and autonomous. Interruption pauses, never replays uncertain effects.
        """
        return await call(dict(tool="tasks", operation="submit", id=id, steps=steps))

    @mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": False})
    async def browser_task_control(id: str, operation: str = "status", offset: int = 0,
                                   retry_failed: bool = False, retry_uncertain: bool = False) -> dict:
        """Read/resume/cancel a local workflow. Confirmed steps never repeat. Failed verification retries verification only.
        Resume uncertain steps first verifies any declared expected result without replaying the effect.
        retry_uncertain explicitly repeats an unconfirmed effect: inspect first; never set it blindly for submissions.
        Paginated step excerpts are marked if truncated, not represented as full JSON results.
        """
        return await call(dict(tool="tasks", operation=operation, id=id, offset=offset,
                               retry_failed=retry_failed, retry_uncertain=retry_uncertain))

    @mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": False})
    async def browser_terminal(id: str, operation: str = "validate", script: dict | None = None,
                                retry_failed: bool = False, retry_uncertain: bool = False) -> dict:
        """CHK Script v1 terminal: validate (dry preview), run, status, resume, cancel.
        script={version:1,commands:[{command:rename/organize/copy/move/trash/transform,folder or paths,...}]}; up to 50 commands, 500 files each.
        Renames hundreds of selected files and transforms JSON/plain text/Markdown/line lists through authorized operations.
        The language has no shell, Python, JavaScript, executable, network, root, arbitrary OS path or private app data access.
        Unique id and per-file journals prevent confirmed operations from repeating. Existing MCP owner consent applies.
        """
        request = dict(tool="terminal", id=id, operation=operation, retry_failed=retry_failed, retry_uncertain=retry_uncertain)
        if script is not None:
            request["script"] = script
        return await call(request)

    return call
