"""Schema, capability negotiation, privacy boundaries and lossless workspace transport."""
import asyncio
import hashlib
import json
import time
import httpx
import pytest
from backend import browser_relay as relay
from backend.browser_workspace import validate_path

PHONE = "af" * 32
DEVICE = hashlib.sha256(bytes.fromhex(PHONE)).hexdigest()

@pytest.fixture(autouse=True)
def clear():
    relay.state.clear()
    yield
    relay.state.clear()

@pytest.mark.parametrize("path", ["../secret", "/sdcard/file", "docs/../../secret", "docs/.hidden", "docs//x", "docs\\x", "x\x00"])
def test_path_escape(path):
    with pytest.raises(ValueError):
        validate_path(path)

def test_unicode_and_root_paths():
    assert validate_path("Projets/Mon idée.md") == "Projets/Mon idée.md"
    assert validate_path("", root=True) == ""
    with pytest.raises(ValueError):
        validate_path("")

@pytest.mark.asyncio
async def test_tool_registry_and_revision_schema():
    tools = {t.name:t for t in await relay.browser_mcp.list_tools()}
    required = ["browser_files_list", "browser_files_write_text", "browser_files_import", "browser_upload_workspace_file", "browser_notes_save", "browser_notes_export", "browser_files_preview"]
    assert all(x in tools for x in required)
    assert tools["browser_notes_save"].inputSchema["properties"]["revision"]["type"] == "integer"
    assert tools["browser_files_list"].annotations.readOnlyHint is True
    assert tools["browser_files_trash"].annotations.destructiveHint is False

@pytest.mark.asyncio
async def test_old_apk_gets_update_message_without_queueing():
    relay.get_device(DEVICE)["seen"] = time.monotonic()
    token = relay.OWNER.set(DEVICE)
    try:
        result = await relay.browser_mcp.call_tool("browser_notes_list", {})
        text = str(result)
        assert "APK" in text
        assert relay.state[DEVICE]["commands"] == []
    finally:
        relay.OWNER.reset(token)

@pytest.mark.asyncio
async def test_large_unicode_note_roundtrip_not_truncated():
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=relay.browser_http),base_url=relay.BASE) as client:
        headers={"Authorization":"Bearer "+PHONE}
        await client.post("/agentbrowser/api/heartbeat",headers=headers,json={"workspace_version":1,"autonomous":True,"session_source":"background"})
        token=relay.OWNER.set(DEVICE)
        try:
            assert relay.browser_status()["workspace_version"] == 1
            task=asyncio.create_task(relay.issue("notes_read", {"id":"test"}))
            await asyncio.sleep(0)
            cmd=(await client.get("/agentbrowser/api/poll",headers=headers)).json()["command"]
            expected=json.dumps({"body":"é📝"*6000,"revision":12},ensure_ascii=False)
            response=await client.post("/agentbrowser/api/result",headers=headers,json={"id":cmd["id"],"ok":True,"result":expected})
            assert response.status_code == 200
            reply=await task
            assert reply["result"] == expected
            assert json.loads(reply["result"])["revision"] == 12
        finally:
            relay.OWNER.reset(token)

@pytest.mark.asyncio
async def test_import_removes_only_its_own_transfers():
    row=relay.get_device(DEVICE);row["seen"]=time.monotonic();row["workspace_version"]=1
    unrelated=await relay.stage_file(DEVICE,row,{"name":"other.txt","base64":"b3RoZXI="})
    token=relay.OWNER.set(DEVICE)
    try:
        task=asyncio.create_task(relay.browser_mcp.call_tool("browser_files_import",{"folder":"Documents","files":[{"name":"hello.txt","base64":"aGVsbG8="}]}))
        await asyncio.sleep(0)
        cmd=row["commands"][0]
        assert cmd["action"]=="files_import"
        relay.state[DEVICE]["pending"][cmd["id"]].set_result({"ok":True,"result":"imported"})
        await task
        assert list(row["transfers"])==[unrelated["id"]]
    finally:
        relay._remove_transfer(row,unrelated["id"])
        relay.OWNER.reset(token)

@pytest.mark.asyncio
async def test_v2_tools_and_capability_negotiation():
    tools={tool.name:tool for tool in await relay.browser_mcp.list_tools()}
    for name in ["browser_media_info","browser_media_codecs","browser_media_frame","browser_files_catalog","browser_files_copy"]:
        assert name in tools
    assert tools["browser_media_frame"].annotations.readOnlyHint
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=relay.browser_http),base_url=relay.BASE) as client:
        headers={"Authorization":"Bearer "+PHONE}
        await client.post("/agentbrowser/api/heartbeat",headers=headers,json={"workspace_version":2})
        assert relay.state[DEVICE]["workspace_version"] == 2
        await client.post("/agentbrowser/api/heartbeat",headers=headers,json={"workspace_version":True})
        assert relay.state[DEVICE]["workspace_version"] == 0

@pytest.mark.asyncio
async def test_new_media_commands_reject_old_phone_and_invalid_queries():
    row=relay.get_device(DEVICE);row["seen"]=time.monotonic();row["workspace_version"]=1
    token=relay.OWNER.set(DEVICE)
    try:
        result=await relay.browser_mcp.call_tool("browser_media_info",{"path":"vidéo.mov"})
        assert "APK" in str(result)
        assert not row["commands"]
        for args in [{"category":"../../secret"},{"folder":"../phone"},{"sort":"drop_table"}]:
            result=await relay.browser_mcp.call_tool("browser_files_catalog",args)
            assert "False" in str(result) or '"ok": false' in str(result)
        assert not row["commands"]
    finally:
        relay.OWNER.reset(token)

@pytest.mark.asyncio
async def test_media_frame_is_image_and_revision_is_forwarded():
    row=relay.get_device(DEVICE);row["seen"]=time.monotonic();row["workspace_version"]=2
    token=relay.OWNER.set(DEVICE)
    try:
        project={"output":"Draft.mp4","clips":[],"audio":[]}
        task=asyncio.create_task(relay.browser_mcp.call_tool("browser_video_editor_project_save",{"project":project,"expected_revision":7}))
        await asyncio.sleep(0)
        command=row["commands"][0]
        assert command["args"]["expected_revision"] == 7
        row["pending"][command["id"]].set_result({"ok":True,"result":"{}"})
        await task
        task=asyncio.create_task(relay.browser_mcp.call_tool("browser_media_frame",{"path":"movie.mkv","time_ms":1200}))
        await asyncio.sleep(0)
        command=row["commands"][-1]
        assert command["action"] == "media_frame"
        assert command["args"]["time_ms"] == 1200
        row["pending"][command["id"]].set_result({"ok":True,"result":json.dumps({"jpeg_base64":"/9j/2Q=="})})
        result=await task
        assert "image/jpeg" in str(result)
    finally:
        relay.OWNER.reset(token)
