"""Exercise the real MCP registry and phone queue, including partial outcomes and capability gating."""
import asyncio
import hashlib
import json
import os
import time

os.environ.setdefault("BROWSER_RELAY_SECRET", "test-only-secret-not-for-production-123456789")

import httpx
import pytest
from backend import browser_relay as relay
from backend.browser_keyboard import validate

PHONE = "af" * 32
DEVICE = hashlib.sha256(bytes.fromhex(PHONE)).hexdigest()


@pytest.fixture(autouse=True)
def clear():
    relay.state.clear()
    yield
    relay.state.clear()


@pytest.mark.asyncio
async def test_registry_distinguishes_inspection_from_editing():
    tools = {tool.name: tool for tool in await relay.browser_mcp.list_tools()}
    assert tools["browser_keyboard_inspect"].annotations.readOnlyHint
    assert not tools["browser_keyboard_input"].annotations.readOnlyHint
    assert not tools["browser_fill_fields"].annotations.readOnlyHint
    assert "expected_context" in tools["browser_keyboard_input"].inputSchema["properties"]
    assert "Google keyboard" in tools["browser_keyboard_inspect"].description


@pytest.mark.parametrize("payload", [
    {"target": "another_app"},
    {"operation": "shell"},
    {"operation": "key", "key": "CTRL_ALT_DELETE"},
    {"operation": "select", "start": True},
    {"operation": "select", "start": -1},
    {"operation": "fill", "fields": []},
    {"operation": "fill", "fields": [{"selector": "#a"}], "expected_context": ""},
    {"operation": "fill", "fields": [{"selector": "#a", "operation": "click"}], "expected_context": "web:x"},
    {"operation": "fill", "fields": [{"selector": "#a"}, {"selector": "#a"}], "expected_context": "web:x"},
    {"operation": "replace", "text": "a" * 8001},
])
def test_invalid_command_is_rejected_before_queueing(payload):
    with pytest.raises(ValueError):
        validate(payload)


@pytest.mark.asyncio
async def test_old_apk_requires_update_without_queueing():
    row = relay.get_device(DEVICE)
    row["seen"] = time.monotonic()
    token = relay.OWNER.set(DEVICE)
    try:
        result = await relay.browser_mcp.call_tool("browser_keyboard_inspect", {"target": "native"})
        assert "APK" in str(result)
        assert not row["commands"]
    finally:
        relay.OWNER.reset(token)


@pytest.mark.asyncio
async def test_heartbeat_advertises_keyboard_and_rejects_boolean_version():
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=relay.browser_http), base_url=relay.BASE) as client:
        headers = {"Authorization": "Bearer " + PHONE}
        token = relay.OWNER.set(DEVICE)
        try:
            await client.post("/agentbrowser/api/heartbeat", headers=headers, json={"keyboard_version": 1})
            assert relay.browser_status()["keyboard_version"] == 1
            await client.post("/agentbrowser/api/heartbeat", headers=headers, json={"keyboard_version": True})
            assert relay.browser_status()["keyboard_version"] == 0
        finally:
            relay.OWNER.reset(token)


@pytest.mark.asyncio
async def test_native_batch_preserves_unicode_and_reports_partial_success():
    row = relay.get_device(DEVICE)
    row.update(seen=time.monotonic(), keyboard_version=1)
    token = relay.OWNER.set(DEVICE)
    try:
        task = asyncio.create_task(relay.browser_mcp.call_tool("browser_fill_fields", {
            "fields": [{"selector": "native:x:a", "text": "Idée 📝"}, {"selector": "native:x:b", "text": "12,5"}],
            "target": "native", "expected_context": "native:x",
        }))
        for _ in range(20):
            await asyncio.sleep(0)
            if row["commands"]:
                break
        command = row["commands"][0]
        assert command["action"] == "keyboard"
        assert command["args"]["fields"][0]["text"] == "Idée 📝"
        outcome = {"ok": False, "succeeded": 1, "failed": 1, "results": [{"ok": True}, {"ok": False, "error": "readonly"}]}
        row["pending"][command["id"]].set_result({"ok": True, "result": json.dumps(outcome)})
        result = str(await task)
        assert '"succeeded": 1' in result and '"failed": 1' in result
        assert '"ok": false' in result
        assert not row["commands"]
    finally:
        relay.OWNER.reset(token)


@pytest.mark.asyncio
async def test_compatibility_carrier_still_uses_existing_tool():
    row = relay.get_device(DEVICE)
    row["seen"] = time.monotonic()
    token = relay.OWNER.set(DEVICE)
    try:
        text = json.dumps({"target": "native", "operation": "inspect"})
        task = asyncio.create_task(relay.browser_type("chk://keyboard", text))
        await asyncio.sleep(0)
        command = row["commands"][0]
        assert command["action"] == "type" and command["args"]["text"] == text
        row["pending"][command["id"]].set_result({"ok": True, "result": "{}"})
        assert (await task)["ok"]
    finally:
        relay.OWNER.reset(token)
