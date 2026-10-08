"""Regression tests for isolated CHK Agent Browser relay.

Run with: pytest -q backend/tests/test_browser_relay.py
No phone, paid API, or live Render service required.
"""
import asyncio
import hashlib
import hmac
import os

os.environ.setdefault("BROWSER_RELAY_SECRET", "test-only-secret-not-for-production-123456789")
os.environ.setdefault("RENDER_EXTERNAL_HOSTNAME", "modeliseur-trellis-mcp.onrender.com")

import httpx
import pytest
from backend import browser_relay as relay

PHONE = "af" * 32
DEVICE = hashlib.sha256(bytes.fromhex(PHONE)).hexdigest()


@pytest.fixture(autouse=True)
def empty_state():
    relay.state.clear()
    yield
    relay.state.clear()


@pytest.mark.asyncio
async def test_pairing_and_no_auth_leak():
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=relay.browser_http),
                                 base_url="https://modeliseur-trellis-mcp.onrender.com") as client:
        health = await client.get("/agentbrowser/api/health")
        assert health.status_code == 200
        assert health.json()["configured"]
        untrusted = await client.post("/agentbrowser/api/register", json={"device_token": "not-secret"})
        assert untrusted.status_code == 400
        paired = await client.post("/agentbrowser/api/register", json={"device_token": PHONE})
        assert paired.status_code == 200
        data = paired.json()
        assert data["device_id"] == DEVICE
        assert data["mcp_url"].startswith(
            "https://modeliseur-trellis-mcp.onrender.com/agentbrowser/mcp/" + DEVICE + "/")
        assert PHONE not in data["mcp_url"]
        no_auth = await client.get("/agentbrowser/api/poll")
        denied_signing=await client.get("/agentbrowser/ci/signing")
        assert denied_signing.status_code == 401
        assert no_auth.status_code == 401
        wrong = await client.get("/agentbrowser/api/poll",
                                 headers={"Authorization": "Bearer " + "00"*32})
        assert wrong.status_code == 200
        assert wrong.json()["command"] is None


@pytest.mark.asyncio
async def test_command_round_trip_and_isolation():
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=relay.browser_http),
                                 base_url="https://modeliseur-trellis-mcp.onrender.com") as client:
        paired = await client.post("/agentbrowser/api/register", json={"device_token": PHONE})
        assert paired.status_code == 200
        headers = {"Authorization": "Bearer " + PHONE}
        first = await client.get("/agentbrowser/api/poll", headers=headers)
        assert first.status_code == 200
        owner = relay.OWNER.set(DEVICE)
        try:
            task = asyncio.create_task(relay.issue("tabs", {}))
            await asyncio.sleep(0)
            response = await client.get("/agentbrowser/api/poll", headers=headers)
            command = response.json()["command"]
            assert command["action"] == "tabs"
            assert command["id"]
            foreign = await client.post("/agentbrowser/api/result",
                                        headers={"Authorization": "Bearer " + "cd"*32},
                                        json={"id": command["id"], "ok": True, "result": "bad"})
            assert foreign.status_code == 404
            answer = await client.post("/agentbrowser/api/result", headers=headers,
                                       json={"id": command["id"], "ok": True, "result": "[]"})
            assert answer.status_code == 200
            result = await asyncio.wait_for(task, timeout=1)
            assert result == {"ok": True, "result": "[]", "error": ""}
            assert relay.state[DEVICE]["pending"] == {}
        finally:
            relay.OWNER.reset(owner)


@pytest.mark.asyncio
async def test_reject_private_navigation_and_unpaired_mcp():
    owner = relay.OWNER.set(DEVICE)
    try:
        data = await relay.browser_open_url("http://example.org")
        assert not data["ok"]
        data = await relay.browser_open_url("https://127.0.0.1:8080/")
        assert not data["ok"]
        data = await relay.browser_open_url("https://localhost/")
        assert not data["ok"]
    finally:
        relay.OWNER.reset(owner)
    valid = relay.signature(DEVICE)
    assert len(valid) == 64
    assert not hmac.compare_digest(valid, relay.signature(hashlib.sha256(b"other").hexdigest()))
    scope = {"type": "http", "method": "POST", "path":
             "/agentbrowser/mcp/" + DEVICE + "/" + "0"*64,
             "headers": [], "query_string": b""}
    msgs = []
    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}
    async def send(message):
        msgs.append(message)
    await relay.relay_dispatch(scope, receive, send)
    assert msgs[0]["status"] == 401
