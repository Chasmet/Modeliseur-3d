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
    "video_editor_status", "video_editor_project_read", "video_editor_project_save",
    "video_editor_preset_alpha_omega", "video_editor_export", "video_editor_cancel",
    "video_editor_verify_output", "media_info", "media_frame", "media_codecs", "files_catalog", "files_copy",
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
        if row and row.get("workspace_version",0)<2 and (action.startswith("media_") or action.startswith("video_editor_") or action in {"files_catalog","files_copy"}):
            return {"ok":False,"error":"Mets à jour l’APK CHK Agent Browser pour utiliser le nouveau Studio et les outils média."}
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


    # CHK Studio vidéo — editor runs entirely on the Android device.
    # These tools use the existing authenticated, owner-bound MCP relay.
    @mcp.tool(annotations=read)
    async def browser_video_editor_status() -> dict:
        """Read local video rendering status (idle/running/completed/failed), MP4 path and percent.
        Use while checking a long mobile Media3 export; no access to unrelated phone files."""
        return await call("video_editor_status")

    @mcp.tool(annotations=write)
    async def browser_video_editor_preset_alpha_omega() -> dict:
        """Create an editable 60 s project directly from the existing GROK_01..06.mp4 and
        cut_1..6.wav files in CHK Files. Does NOT export or overwrite video."""
        return await call("video_editor_preset_alpha_omega")

    @mcp.tool(annotations=read)
    async def browser_video_editor_project_read() -> dict:
        """Read the local editing timeline: ordered clips, audio cuts, filters, trims and output."""
        return await call("video_editor_project_read")

    @mcp.tool(annotations=write)
    async def browser_video_editor_project_save(project: dict, expected_revision: int | None = None) -> dict:
        """Save a complete offline video editing project. Provide name, output (relative MP4
        path in Files), clips list ({path,start_ms,duration_ms,filter,fade_ms}) and audio
        list ({path,start_ms,duration_ms}). Clip options: speed (0.25–4), rotation
        (-180–180), text (500 chars), mute, filter (aucun/noir/cinema/chaud/froid/contraste/nuit/vintage),
        fade_ms (0–1500). Project: aspect_ratio (source or width:height, e.g. 9:16,16:9,1:1),
        aspect_mode (fit/crop), resolution (480/720/1080), mute_original (default false;
        default true when external audio exists). Empty drafts may be saved.
        Audio clips play sequentially from zero and must not outlast video; shorter tracks stop.
        Original video audio is retained by default, or can be mixed with external audio
        by setting mute_original=false. Existing video sources are not modified.
        Read current project first and pass its revision as expected_revision to avoid overwriting
        a newer edit. All paths must be relative to CHK Files, no URLs or absolute paths."""
        if not isinstance(project, dict):
            return {"ok": False, "error": "Projet JSON obligatoire."}
        if len(json.dumps(project)) > 64000:
            return {"ok": False, "error": "Projet trop grand."}
        try:
            validate_path(project.get("output", ""))
            clips = project.get("clips")
            audio = project.get("audio", [])
            if not isinstance(clips, list) or not 0 <= len(clips) <= 40:
                raise ValueError("0 à 40 vidéos requises (au moins une pour exporter).")
            if not isinstance(audio, list) or len(audio) > 40:
                raise ValueError("0 à 40 fichiers audio.")
            for element in clips + audio:
                if not isinstance(element, dict):
                    raise ValueError("Média non structuré.")
                validate_path(element.get("path", ""))
        except (ValueError, TypeError) as exc:
            return {"ok": False, "error": str(exc)}
        return await call("video_editor_project_save", {"project": project, **({"expected_revision":expected_revision} if expected_revision is not None else {})})

    @mcp.tool(annotations=write)
    async def browser_video_editor_export(replace: bool = False) -> dict:
        """START a background MP4 export using the saved mobile project, media and filters.
        Immediate acknowledgment; use browser_video_editor_status to track progress.
        Refuses existing output unless replace=true. May use significant phone battery."""
        return await call("video_editor_export", {"replace": replace})

    @mcp.tool(annotations=read)
    async def browser_video_editor_verify_output() -> dict:
        """Verify the exported local MP4: actual duration, video/audio streams, size,
        dimensions and availability of source files. Returns valid=false when missing or
        incomplete. Does not claim visual quality or lip-sync verification."""
        return await call("video_editor_verify_output")

    @mcp.tool(annotations=write)
    async def browser_video_editor_cancel() -> dict:
        """Cancel current local MP4 export. The unfinished partial MP4 is deleted."""
        return await call("video_editor_cancel")


    @mcp.tool(annotations=read)
    async def browser_files_catalog(category: str = "all", folder: str = "", query: str = "", sort: str = "name", offset: int = 0) -> dict:
        """Browse imported CHK files by category: all/image/video/audio/document/archive/recent/other.
        'all' lists one folder; categories traverse below folder (max 5000 entries, reports truncated).
        Sort name/date/size; pages of 50; includes free phone storage and category counts.
        Uses CHK workspace only; import phone/cloud content first through Files."""
        if category not in {"all","image","video","audio","document","archive","recent","other"} or sort not in {"name","date","size"}:
            return {"ok":False,"error":"Catégorie ou tri invalide."}
        try:
            validate_path(folder,root=True)
        except ValueError as exc:
            return {"ok":False,"error":str(exc)}
        return await call("files_catalog",{"category":category,"folder":folder,"query":query[:120],"sort":sort,"offset":max(0,offset)})

    @mcp.tool(annotations=write)
    async def browser_files_copy(path: str, destination: str) -> dict:
        """Copy a file inside CHK Files (max 512 MiB). Parent must exist. Refuses overwrite and folders."""
        try:
            validate_path(destination)
        except ValueError as exc:
            return {"ok":False,"error":str(exc)}
        return await path_call("files_copy",path,{"destination":destination})

    @mcp.tool(annotations=read)
    async def browser_media_info(path: str) -> dict:
        """Inspect a requested phone video/audio of any Android extractor-supported container.
        Reports dimensions, duration, rotation, audio/video track MIME and decoder availability.
        Arbitrary ratios are read; container/codec support depends on the phone. No transcription."""
        return await path_call("media_info",path)

    @mcp.tool(annotations=read)
    async def browser_media_codecs() -> dict:
        """List the actual phone's available decoder and encoder names/MIME types.
        Use before promising a format can play or export. Export target is MP4 H.264/AAC."""
        return await call("media_codecs")

    @mcp.tool(annotations=read)
    async def browser_media_frame(path: str, time_ms: int = 0):
        """Read a bounded JPEG video frame near requested time (nearest keyframe, max 640 px).
        Allows visual analysis across portrait/landscape/square ratios. Phone preview sharing
        must be enabled. Repeated frames sample video; this is not exhaustive motion or audio analysis."""
        if not 0 <= time_ms <= 86400000:
            return {"ok":False,"error":"Instant invalide."}
        reply=await path_call("media_frame",path,{"time_ms":time_ms})
        if not reply.get("ok"):
            return reply
        try:
            data=json.loads(reply["result"])
            raw=base64.b64decode(data["jpeg_base64"],validate=True)
            if len(raw)>150000 or not raw.startswith(b"\xff\xd8"):
                raise ValueError("Image invalide")
            return Image(data=raw,format="jpeg")
        except (ValueError,KeyError,TypeError):
            return {"ok":False,"error":"Image vidéo invalide."}
