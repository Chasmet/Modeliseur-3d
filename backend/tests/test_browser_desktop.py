"""Actual MCP registry, negotiated capability, queue and lossless desktop receipts."""
import asyncio
import hashlib
import json
import os
import time
import httpx
import pytest
os.environ.setdefault("BROWSER_RELAY_SECRET", "test-only-secret-not-for-production-123456789")
from backend import browser_relay as relay
from backend.browser_desktop import validate

PHONE = "ab" * 32
DEVICE = hashlib.sha256(bytes.fromhex(PHONE)).hexdigest()


@pytest.fixture(autouse=True)
def clear():
    relay.state.clear()
    yield
    relay.state.clear()

def test_extended_workflows_and_pause_are_accepted():
    validate({"tool":"tasks","id":"all","operation":"list"})
    validate({"tool":"tasks","id":"job","operation":"pause"})
    for action in ("scroll","collect_public_comments","publication_prepare","cut_resume","video_editor_resume","files_archive"):
        validate({"tool":"tasks","id":"job","operation":"submit","steps":[{"id":"one","action":action,"args":{}}]})


@pytest.mark.asyncio
async def test_registry_has_all_five_capabilities_and_context_schema():
    tools = {tool.name: tool for tool in await relay.browser_mcp.list_tools()}
    assert tools["browser_desktop_inspect"].annotations.readOnlyHint
    assert tools["browser_desktop_screenshot"].annotations.readOnlyHint
    for name in ("browser_pointer", "browser_files_batch", "browser_external_files", "browser_task_submit", "browser_task_control", "browser_terminal"):
        assert not tools[name].annotations.readOnlyHint
    assert "expected_context" in tools["browser_pointer"].inputSchema["required"]
    assert "other-app" in tools["browser_pointer"].description
    assert "no shell" in tools["browser_terminal"].description


@pytest.mark.parametrize("payload", [
    {"tool": "shell"}, {"tool": "vision", "target": "system"},
    {"tool": "pointer", "gesture": "tap", "x": 2, "y": 3},
    {"tool": "pointer", "gesture": "tap", "expected_context": "screen:x", "x": True, "y": 1},
    {"tool": "pointer", "gesture": "drag", "expected_context": "screen:x", "x": 0, "y": 1, "end_x": -1, "end_y": 2},
    {"tool": "files", "operation": "plan", "id": "x", "paths": ["../secret"]},
    {"tool": "files", "operation": "plan", "id": "x", "paths": ["a", "a"]},
    {"tool": "files", "operation": "external_list", "root_id": "file:///sdcard"},
    {"tool": "tasks", "id": "x", "operation": "submit", "steps": []},
    {"tool": "tasks", "id": "x", "operation": "submit", "steps": [{"id": "step", "action": "execute_js", "args": {}}]},
    {"tool": "tasks", "id": "x", "operation": "submit", "steps": [{"id": "step", "action": "desktop", "args": {"tool": "tasks"}}]},
    {"tool": "terminal", "id": "x", "script": {"version": 1, "commands": [{"command": "shell", "text": "rm -rf /"}]}},
    {"tool": "terminal", "id": "../x", "script": {"version": 1, "commands": []}},
])
def test_reject_invalid_requests_before_queueing(payload):
    with pytest.raises((ValueError, TypeError)):
        validate(payload)


@pytest.mark.asyncio
async def test_old_phone_does_not_receive_a_desktop_command():
    row = relay.get_device(DEVICE)
    row["seen"] = time.monotonic()
    token = relay.OWNER.set(DEVICE)
    try:
        reply = await relay.desktop_call({"tool": "vision"})
        assert not reply["ok"] and "APK" in reply["error"]
        assert not row["commands"]
    finally:
        relay.OWNER.reset(token)


@pytest.mark.asyncio
async def test_heartbeat_version_is_strict_and_owner_scoped():
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=relay.browser_http), base_url=relay.BASE) as client:
        headers = {"Authorization": "Bearer " + PHONE}
        await client.post("/agentbrowser/api/heartbeat", headers=headers, json={"desktop_version": 1})
        assert relay.state[DEVICE]["desktop_version"] == 1
        await client.post("/agentbrowser/api/heartbeat", headers=headers, json={"desktop_version": True})
        assert relay.state[DEVICE]["desktop_version"] == 0
        assert len(relay.state) == 1


@pytest.mark.asyncio
async def test_existing_carrier_has_capability_guard_and_structured_partial_results():
    row = relay.get_device(DEVICE)
    row.update(seen=time.monotonic(), desktop_version=1)
    token = relay.OWNER.set(DEVICE)
    try:
        task = asyncio.create_task(relay.browser_type("chk://desktop", json.dumps({"tool": "files", "operation": "run", "id": "batch"})))
        await asyncio.sleep(0)
        command = row["commands"][0]
        assert command["action"] == "desktop"
        assert command["args"]["id"] == "batch"
        payload = {"ok": False, "succeeded": 199, "failed": 1, "uncertain": 0, "items": [{"path": "idée 📝.txt", "state": "failed"}]}
        row["pending"][command["id"]].set_result({"ok": True, "result": json.dumps(payload)})
        reply = await task
        assert not reply["ok"] and reply["succeeded"] == 199
        assert reply["items"][0]["path"] == "idée 📝.txt"
        assert not row["commands"]
    finally:
        relay.OWNER.reset(token)


@pytest.mark.asyncio
async def test_desktop_capture_receipt_is_not_truncated_to_32k():
    row = relay.get_device(DEVICE)
    row.update(seen=time.monotonic(), desktop_version=1)
    token = relay.OWNER.set(DEVICE)
    try:
        task = asyncio.create_task(relay.desktop_call({"tool": "vision", "capture": True}))
        await asyncio.sleep(0)
        command = row["commands"][0]
        payload = {"ok": True, "jpeg_base64": "A" * 100000, "context": "screen:fixture"}
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=relay.browser_http), base_url=relay.BASE) as client:
            response = await client.post("/agentbrowser/api/result", headers={"Authorization": "Bearer " + PHONE}, json={"id": command["id"], "ok": True, "result": json.dumps(payload)})
        assert response.status_code == 200
        reply = await task
        assert len(reply["jpeg_base64"]) == 100000
    finally:
        relay.OWNER.reset(token)


def test_fifty_step_task_and_hundreds_of_file_selection_fit_contract():
    steps = [{"id": str(i), "action": "files_write_text", "args": {"path": f"file-{i}.txt", "text": "ok"}, "verify": {"action": "files_read_text", "args": {"path": f"file-{i}.txt"}, "expect": {"text": "ok"}}} for i in range(50)]
    validate({"tool": "tasks", "operation": "submit", "id": "fixture", "steps": steps})
    validate({"tool": "files", "operation": "plan", "id": "fixture", "paths": [f"file-{i}.txt" for i in range(500)]})


@pytest.mark.asyncio
async def test_fifty_steps_work_through_the_installed_carrier_without_repairing():
    row = relay.get_device(DEVICE)
    row.update(seen=time.monotonic(), desktop_version=1)
    token = relay.OWNER.set(DEVICE)
    try:
        steps = [{"id": str(i), "action": "files_write_text", "args": {"path": f"file-{i}.txt", "text": "v" * 120}} for i in range(50)]
        text = json.dumps({"tool": "tasks", "operation": "submit", "id": "fixture", "steps": steps})
        assert 8000 < len(text) < 60000
        task = asyncio.create_task(relay.browser_type("chk://desktop", text))
        await asyncio.sleep(0)
        command = row["commands"][0]
        assert len(command["args"]["steps"]) == 50
        row["pending"][command["id"]].set_result({"ok": True, "result": '{"ok":true,"state":"running"}'})
        assert (await task)["state"] == "running"
    finally:
        relay.OWNER.reset(token)


@pytest.mark.asyncio
async def test_screenshot_tool_returns_mcp_image_and_coordinate_metadata():
    row = relay.get_device(DEVICE)
    row.update(seen=time.monotonic(), desktop_version=1)
    token = relay.OWNER.set(DEVICE)
    try:
        task = asyncio.create_task(relay.browser_mcp.call_tool("browser_desktop_screenshot", {}))
        for _ in range(30):
            await asyncio.sleep(0)
            if row["commands"]:
                break
        command = row["commands"][0]
        row["pending"][command["id"]].set_result({"ok": True, "result": json.dumps({"ok": True, "jpeg_base64": "/9j/2Q==", "context": "screen:x", "width": 480, "image_width": 480})})
        result = await task
        content = result[0] if isinstance(result, tuple) else result
        assert any(getattr(block, "type", None) == "image" for block in content)
        assert "screen:x" in str(content)
    finally:
        relay.OWNER.reset(token)


def test_modern_form_workflows_match_android_policy():
    """The relay must not silently reject actions added to AgentTasks.java."""
    verify = {"action": "get_page_status", "args": {}, "contains": {"url": "play.google.com"}}
    steps = [
        {"id": "inspect", "action": "get_form", "args": {}},
        {"id": "wait", "action": "wait_for_element", "args": {"selector": "#apk"}},
        {"id": "choose", "action": "select_option",
         "args": {"selector": "#category", "value": "tools"}, "verify": verify},
        {"id": "type", "action": "type",
         "args": {"selector": "#title", "text": "Sync 30"}, "verify": verify},
        {"id": "check", "action": "check",
         "args": {"selector": "#terms", "checked": True}, "verify": verify},
        {"id": "click", "action": "click_verified",
         "args": {"selector": "#save", "expected_text": "Enregistré"}, "verify": verify},
        {"id": "upload", "action": "upload_file",
         "args": {"selector": "#apk", "expected_host": "play.google.com",
                  "files": [{"path": "build/release.aab"}]}, "verify": verify},
    ]
    assert validate({"tool": "tasks", "operation": "submit", "id": "google-play-workflow", "steps": steps})["steps"] == steps


@pytest.mark.parametrize("step", [
    {"id": "one", "action": "click_verified", "args": {"selector": "#confirm", "expected_text": "Terminé"}},
    {"id": "one", "action": "type", "args": {"selector": "#title", "text": "texte"}},
    {"id": "one", "action": "upload_file", "args": {"selector": "#apk", "expected_host": "play.google.com", "files": [{"path": "x.apk"}]}},
    {"id": "one", "action": "upload_file", "args": {"selector": "#apk", "files": []},
     "verify": {"action": "get_form", "args": {}, "contains": {"count": "1"}}},
    {"id": "one", "action": "click_verified", "args": {"selector": "#publish"},
     "verify": {"action": "get_page_status", "args": {}, "contains": {"url": "example.com"}}},
    {"id": "one", "action": "type", "args": {"selector": "#x", "text": "hello"},
     "verify": {"action": "check", "args": {"selector": "#confirm"}, "expect": {"checked": True}}},
    {"id": "one", "action": "desktop", "args": {"tool": "pointer", "gesture": "tap", "x": 2, "y": 3}},
])
def test_unverified_mutations_are_rejected_at_relay(step):
    with pytest.raises((ValueError, TypeError)):
        validate({"tool": "tasks", "operation": "submit", "id": "unsafe", "steps": [step]})
