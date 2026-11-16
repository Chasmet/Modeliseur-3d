"""Agent-only keyboard on CHK native screens and WebViews; no system IME or new service."""
import json

OPERATIONS = frozenset({"replace", "insert", "clear", "select", "key", "check", "select_option", "click"})
KEYS = frozenset({"SELECT_ALL", "BACKSPACE", "DELETE", "LEFT", "RIGHT", "HOME", "END", "TAB", "SHIFT_TAB", "ENTER", "UNDO", "REDO"})


def validate(request: dict) -> dict:
    if request.get("target", "web") not in {"web", "native", "auto"}:
        raise ValueError("Cible attendue : web, native ou auto.")
    for key, maximum in (("selector", 350), ("expected_context", 160), ("expected_host", 250), ("text", 8000)):
        value = request.get(key, "")
        if not isinstance(value, str) or len(value) > maximum:
            raise ValueError("Paramètre clavier invalide : " + key)
    operation = request.get("operation", "inspect")
    if operation == "inspect":
        if type(request.get("offset", 0)) is not int or not 0 <= request.get("offset", 0) <= 5000:
            raise ValueError("Pagination de champs invalide.")
        return request
    if operation == "fill":
        fields = request.get("fields")
        if not isinstance(fields, list) or not 1 <= len(fields) <= 25:
            raise ValueError("Un lot doit contenir entre 1 et 25 champs.")
        selectors = set()
        for field in fields:
            if not isinstance(field, dict) or field.get("operation", "replace") not in {"replace", "insert", "clear", "check", "select_option"}:
                raise ValueError("Un lot remplit des champs sans cliquer ni envoyer.")
            selected = field.get("selector")
            if not isinstance(selected, str) or not selected or selected in selectors:
                raise ValueError("Chaque champ du lot doit avoir un sélecteur unique.")
            selectors.add(selected)
            validate({**field, "operation": field.get("operation", "replace")})
        if not request.get("expected_context"):
            raise ValueError("Inspecte le formulaire et fournis expected_context avant un lot.")
    elif operation not in OPERATIONS:
        raise ValueError("Opération clavier inconnue.")
    if operation == "key" and request.get("key", "").upper() not in KEYS:
        raise ValueError("Touche clavier non prise en charge.")
    if operation == "select":
        for name in ("start", "end"):
            if type(request.get(name, 0)) is not int or not 0 <= request.get(name, 0) <= 12000:
                raise ValueError("Position de curseur invalide.")
    # The compatibility carrier and new tools share the same bounded, lossless request format.
    if len(json.dumps(request, ensure_ascii=False).encode("utf-8")) > 30000:
        raise ValueError("Lot trop volumineux : utilise plusieurs lots.")
    return request


def register_keyboard(mcp, issue, owner, state):
    async def call(request):
        try:
            validate(request)
        except (ValueError, TypeError, AttributeError) as exc:
            return {"ok": False, "error": str(exc)}
        row = state.get(owner.get())
        if row and row.get("keyboard_version", 0) < 1:
            return {"ok": False, "error": "Mets à jour l’APK CHK Agent Browser pour activer le clavier agent."}
        reply = await issue("keyboard", request)
        try:
            result = json.loads(reply.get("result") or reply.get("error") or "")
            if isinstance(result, dict):
                result["ok"] = bool(reply.get("ok") and result.get("ok"))
                return result
        except (ValueError, TypeError):
            pass
        return reply

    @mcp.tool(annotations={"readOnlyHint": True, "openWorldHint": True})
    async def browser_keyboard_inspect(target: str = "web", offset: int = 0) -> dict:
        """Inspect ordinary fields in CHK's own native screen/dialog or current web page.
        Returns unique selectors, context, input type, current value preview, validation and cursor.
        Up to 60 controls per page; use next_offset if present and nonnegative.
        target: web, native or auto. Password/hidden/file controls are excluded.
        Native screens may be paused in ChatGPT, but must still exist in CHK; no other apps are inspected.
        Use returned selectors/context for edits. This agent tool does not replace the owner's Google keyboard.
        """
        return await call({"operation": "inspect", "target": target, "offset": offset})

    @mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": True})
    async def browser_keyboard_input(
        operation: str = "replace", selector: str = "", text: str = "", key: str = "",
        start: int = 0, end: int = -1, target: str = "web", expected_context: str = "",
        expected_host: str = "", checked: bool = True,
    ) -> dict:
        """Agent keyboard: replace/insert/clear/select text, select_option/check, or send a key.
        Keys: SELECT_ALL BACKSPACE DELETE LEFT RIGHT HOME END TAB SHIFT_TAB ENTER UNDO REDO.
        Cursor positions count Unicode code points (emoji count as one). end=-1 selects a caret at start.
        Use inspected selector and context; optional expected_host protects the web destination.
        Decimal commas normalize in numeric fields. Values are verified, including deferred web rendering.
        click is for inspected native CHK action buttons only; inspect again to confirm the task's outcome.
        ENTER can activate a field's declared action or submit a web form; key_sent is not publication confirmation.
        Existing owner consent applies. No Gboard replacement, clipboard history, system injection or AI API.
        """
        request = {"operation": operation, "selector": selector, "text": text, "key": key.upper(),
                   "start": start, "end": start if end == -1 else end, "target": target,
                   "expected_context": expected_context, "expected_host": expected_host, "checked": checked}
        return await call(request)

    @mcp.tool(annotations={"readOnlyHint": False, "openWorldHint": True})
    async def browser_fill_fields(fields: list[dict], target: str = "web", expected_context: str = "", expected_host: str = "") -> dict:
        """Fill 1–25 inspected fields in one bounded command, without submitting the form.
        Each item: selector, text, optional operation (replace/insert/clear/check/select_option), optional checked.
        Returns individual verified successes/errors and actual value previews; partial success is not all-success.
        Provide context from keyboard_inspect; retry only failed fields after inspecting their current state.
        Works with CHK native dialogs/notes/Cut fields and ordinary web forms, including same-origin frames/shadow DOM.
        """
        return await call({"operation": "fill", "fields": fields, "target": target,
                           "expected_context": expected_context, "expected_host": expected_host})
