"""Phone-only Files and Notes tools. Registered on the existing browser MCP instance.
No database, billing, new Render service or access to arbitrary phone paths.
"""
import json
import base64
from mcp.server.fastmcp import Image

WORKSPACE_ACTIONS = frozenset({
    "workspace_status", "files_list", "files_mkdir", "files_write_text",
    "files_read_text", "files_read_bytes", "files_move", "files_trash",
    "files_trash_list", "files_restore", "files_import", "files_downloads",
    "files_import_download", "files_preview", "notes_list", "notes_read", "notes_save",
    "notes_trash", "notes_restore", "notes_export",
})


def validate_path(path: str, root: bool = False) -> str:
    if not isinstance(path, str) or len(path) > 700 or path.startswith("/") or "\\" in path or "\x00" in path:
        raise ValueError("Chemin relatif invalide.")
    if not path and root:
        return path
    if not path or any(not p.strip() or p.startswith(".") or len(p) > 120 for p in path.split("/")):
        raise ValueError("Chemin ou nom interdit.")
    return path


def register_workspace(mcp, issue, owner, state, stage_file, remove_transfer):
    read = {"readOnlyHint": True, "openWorldHint": False}
    write = {"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False}

    async def call(action, args=None):
        row = state.get(owner.get())
        if row and not row.get("workspace_version"):
            return {"ok": False, "error": "Mets à jour l’APK CHK Agent Browser pour activer Fichiers et Notes."}
        return await issue(action, args or {})

    async def path_call(action, path, args=None, root=False):
        try:
            validate_path(path, root)
            return await call(action, {"path": path, **(args or {})})
        except ValueError as exc:
            return {"ok": False, "error": str(exc)}

    @mcp.tool(annotations=read)
    async def browser_workspace_status() -> dict:
        """Check installed phone Files/Notes capabilities and actual storage limits."""
        return await call("workspace_status")

    @mcp.tool(annotations=read)
    async def browser_files_list(path: str = "", query: str = "", offset: int = 0) -> dict:
        """List up to 50 phone workspace entries, folders first. path is relative; empty means root.
        Search filters names in this folder. Continue using next_offset. No arbitrary phone paths."""
        return await path_call("files_list", path, {"query": query[:120], "offset": max(0, offset)}, root=True)

    @mcp.tool(annotations=write)
    async def browser_files_create_folder(path: str) -> dict:
        """Create a folder on the phone. Parent must exist; existing content is never overwritten."""
        return await path_call("files_mkdir", path)

    @mcp.tool(annotations=write)
    async def browser_files_write_text(path: str, text: str, overwrite: bool = False) -> dict:
        """Create UTF-8 text, Markdown, CSV, JSON, HTML or source documents (max 64 KiB).
        Explicit overwrite=true is required to replace an existing file. Does not create DOCX/PDF."""
        if len(text.encode("utf-8")) > 65536:
            return {"ok": False, "error": "Document supérieur à 64 Ko."}
        return await path_call("files_write_text", path, {"text": text, "overwrite": overwrite})

    @mcp.tool(annotations=read)
    async def browser_files_read_text(path: str, offset: int = 0) -> dict:
        """Read UTF-8 workspace text (file max 64 KiB), 12,000 characters per page. Use next_offset."""
        return await path_call("files_read_text", path, {"offset": max(0, offset)})

    @mcp.tool(annotations=read)
    async def browser_files_read_bytes(path: str, offset: int = 0) -> dict:
        """Read an 8 KiB base64 chunk of any owner-requested file. Includes MIME, total size and next_offset.
        Use for exact binary retrieval; it does not parse a PDF or transcribe a video."""
        return await path_call("files_read_bytes", path, {"offset": max(0, offset)})

    @mcp.tool(annotations=write)
    async def browser_files_move(path: str, destination: str) -> dict:
        """Rename or move an entry within Files. Refuses overwrite and moving a folder into itself."""
        try:
            validate_path(destination)
        except ValueError as exc:
            return {"ok": False, "error": str(exc)}
        return await path_call("files_move", path, {"destination": destination})

    @mcp.tool(annotations=write)
    async def browser_files_trash(path: str) -> dict:
        """Move a requested file/folder to recoverable phone trash. Returns its restore ID."""
        return await path_call("files_trash", path)

    @mcp.tool(annotations=read)
    async def browser_files_trash_list() -> dict:
        """List up to 100 recoverable deleted Files entries and restore IDs."""
        return await call("files_trash_list")

    @mcp.tool(annotations=write)
    async def browser_files_restore(id: str) -> dict:
        """Restore a trashed file/folder to its original path. Parent must exist; refuses overwrite."""
        return await call("files_restore", {"id": id[:100]})

    @mcp.tool(annotations={**write, "openWorldHint": True})
    async def browser_files_import(files: list[dict], folder: str = "") -> dict:
        """Save 1–8 requested documents/images/videos to persistent Files on the phone.
        Each item: name, and exactly source_url (public HTTPS) or base64. Max 12 MiB/file and 32 MiB total.
        Imported files remain after app updates. Existing destinations are refused."""
        row = state.get(owner.get())
        if not row:
            return {"ok": False, "error": "Navigateur hors ligne."}
        if not row.get("workspace_version"):
            return {"ok": False, "error": "Mets à jour l’APK pour activer Fichiers."}
        staged = []
        try:
            validate_path(folder, root=True)
            if not isinstance(files, list) or not 1 <= len(files) <= 8:
                raise ValueError("Entre 1 et 8 fichiers requis.")
            for f in files:
                descriptor = await stage_file(owner.get(), row, f)
                staged.append(descriptor)
                if sum(x["size"] for x in staged) > 32 * 1024 * 1024:
                    raise ValueError("Ensemble supérieur à 32 Mo.")
            return await call("files_import", {"files": staged, "folder": folder})
        except (ValueError, TypeError) as exc:
            return {"ok": False, "error": str(exc)}
        finally:
            for item in staged:
                remove_transfer(row, item["id"])

    @mcp.tool(annotations=read)
    async def browser_files_downloads() -> dict:
        """List completed app downloads available to copy into Files. Does not access other app folders."""
        return await call("files_downloads")

    @mcp.tool(annotations=write)
    async def browser_files_import_download(name: str, destination: str) -> dict:
        """Copy a named completed CHK browser download into persistent Files. Refuses overwrite."""
        try:
            validate_path(name)
            if "/" in name:
                raise ValueError("Nom de téléchargement simple requis.")
        except ValueError as exc:
            return {"ok": False, "error": str(exc)}
        return await path_call("files_import_download", destination, {"name": name})

    @mcp.tool(annotations=read)
    async def browser_files_preview(path: str):
        """Show an image file from Files as a bounded JPEG thumbnail. Phone preview sharing must be enabled."""
        reply = await path_call("files_preview", path)
        if not reply.get("ok"):
            return reply
        try:
            data = json.loads(reply["result"])
            raw = base64.b64decode(data["jpeg_base64"], validate=True)
            if len(raw) > 150000 or not raw.startswith(b"\xff\xd8"):
                raise ValueError("Aperçu invalide")
            return Image(data=raw, format="jpeg")
        except (ValueError, KeyError, TypeError):
            return {"ok": False, "error": "Aperçu invalide."}

    @mcp.tool(annotations={**write, "openWorldHint": True})
    async def browser_upload_workspace_file(selector: str, paths: list[str], expected_host: str = "") -> dict:
        """Send 1–8 existing Files documents to input[type=file] without the phone picker.
        Confirms the site's received file count. Check active destination before upload.
        Local files are passed by read-only content URI; maximum total 512 MiB."""
        try:
            if not 0 < len(selector) < 350 or not 1 <= len(paths) <= 8:
                raise ValueError("Sélecteur ou nombre de fichiers invalide.")
            row = state.get(owner.get())
            if not row:
                raise ValueError("Navigateur hors ligne.")
            from urllib.parse import urlparse
            active = urlparse(row.get("page", "")).hostname
            if not active or (expected_host and active.lower() != expected_host.lower()):
                raise ValueError("Page active différente de la destination autorisée.")
            descriptors = [{"workspace_path": validate_path(p)} for p in paths]
            return await call("upload_file", {"selector": selector, "files": descriptors, "expected_host": active})
        except (ValueError, TypeError) as exc:
            return {"ok": False, "error": str(exc)}

    @mcp.tool(annotations=read)
    async def browser_notes_list(query: str = "", deleted: bool = False, offset: int = 0) -> dict:
        """List 50 phone note summaries, pinned first, including IDs and revision numbers.
        Search matches title/body. deleted=true lists recoverable trash. Body requires notes_read."""
        return await call("notes_list", {"query": query[:120], "deleted": deleted, "offset": max(0, offset)})

    @mcp.tool(annotations=read)
    async def browser_notes_read(id: str) -> dict:
        """Read full owner-requested note including body, source URL and optimistic revision."""
        return await call("notes_read", {"id": id[:100]})

    @mcp.tool(annotations=write)
    async def browser_notes_save(title: str, body: str, id: str = "", revision: int = 0, source_url: str = "", pinned: bool = False) -> dict:
        """Create a phone note with empty id; update an existing note using its last read revision.
        Max title 120 / body 12,000 characters. Preserves concurrent edits by refusing stale revisions.
        Read before updating; include the source URL and pinned status to preserve them."""
        if len(title) > 120 or len(body) > 12000 or len(source_url) > 2000:
            return {"ok": False, "error": "Note trop longue."}
        return await call("notes_save", {"title": title, "body": body, "id": id[:100], "revision": revision, "source_url": source_url, "pinned": pinned})

    @mcp.tool(annotations=write)
    async def browser_notes_trash(id: str, revision: int) -> dict:
        """Move a requested note to recoverable trash. Requires its current revision."""
        return await call("notes_trash", {"id": id[:100], "revision": revision})

    @mcp.tool(annotations=write)
    async def browser_notes_restore(id: str, revision: int) -> dict:
        """Restore a note from trash. Requires its current revision returned by deleted notes list."""
        return await call("notes_restore", {"id": id[:100], "revision": revision})

    @mcp.tool(annotations=write)
    async def browser_notes_export(id: str, path: str) -> dict:
        """Export a saved phone note to a new Markdown file in Files. Includes source URL; no overwrite."""
        return await path_call("notes_export", path, {"id": id[:100]})
