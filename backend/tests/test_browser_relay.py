"""Regression tests for isolated CHK Agent Browser relay.

Run with: pytest -q backend/tests/test_browser_relay.py
No phone, paid API, or live Render service required.
"""
import asyncio
import base64
import json
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


@pytest.mark.asyncio
async def test_private_preview_round_trip_as_mcp_image():
    """A JPEG is returned to this MCP caller only, not written to disk or public endpoint."""
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=relay.browser_http),
                                 base_url="https://modeliseur-trellis-mcp.onrender.com") as client:
        await client.post("/agentbrowser/api/register", json={"device_token": PHONE})
        headers = {"Authorization": "Bearer " + PHONE}
        await client.get("/agentbrowser/api/poll", headers=headers)
        owner = relay.OWNER.set(DEVICE)
        try:
            task = asyncio.create_task(relay.browser_preview())
            await asyncio.sleep(0)
            response = await client.get("/agentbrowser/api/poll", headers=headers)
            cmd = response.json()["command"]
            assert cmd["action"] == "screenshot"
            jpeg = b"\xff\xd8\xff\xe0TEST_JPEG\xff\xd9"
            payload = json.dumps({
                "url": "https://example.com/",
                "jpeg_base64": base64.b64encode(jpeg).decode()
            })
            completed = await client.post("/agentbrowser/api/result",
                headers=headers, json={"id": cmd["id"], "ok": True, "result": payload})
            assert completed.status_code == 200
            result = await asyncio.wait_for(task, timeout=1)
            assert isinstance(result, relay.Image)
            assert result.data == jpeg
        finally:
            relay.OWNER.reset(owner)


@pytest.mark.asyncio
async def test_preview_denies_oversized_image():
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=relay.browser_http),
                                 base_url="https://modeliseur-trellis-mcp.onrender.com") as client:
        await client.post("/agentbrowser/api/register", json={"device_token": PHONE})
        headers = {"Authorization": "Bearer " + PHONE}
        await client.get("/agentbrowser/api/poll", headers=headers)
        owner = relay.OWNER.set(DEVICE)
        try:
            task = asyncio.create_task(relay.browser_preview())
            await asyncio.sleep(0)
            response = await client.get("/agentbrowser/api/poll", headers=headers)
            cmd = response.json()["command"]
            huge = "A" * 222000
            completed = await client.post("/agentbrowser/api/result",
                headers=headers, json={"id": cmd["id"], "ok": True, "result": json.dumps({"jpeg_base64": huge})})
            assert completed.status_code == 200
            result = await asyncio.wait_for(task, timeout=1)
            assert "taille excessive" in result
        finally:
            relay.OWNER.reset(owner)


@pytest.mark.asyncio
async def test_retry_result_after_lost_ack_is_idempotent():
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=relay.browser_http),base_url=relay.BASE) as client:
        headers={"Authorization":"Bearer "+PHONE}
        await client.get("/agentbrowser/api/poll",headers=headers)
        owner=relay.OWNER.set(DEVICE)
        try:
            task=asyncio.create_task(relay.issue("click",{"selector":"button"}))
            await asyncio.sleep(0)
            command=(await client.get("/agentbrowser/api/poll",headers=headers)).json()["command"]
            payload={"id":command["id"],"ok":True,"result":"Clic effectué"}
            assert (await client.post("/agentbrowser/api/result",headers=headers,json=payload)).status_code==200
            await task
            repeated=await client.post("/agentbrowser/api/result",headers=headers,json=payload)
            assert repeated.status_code==200 and repeated.json()["duplicate"]
            payload["result"]="Different"
            assert (await client.post("/agentbrowser/api/result",headers=headers,json=payload)).status_code==409
            assert not (await client.get("/agentbrowser/api/poll",headers=headers)).json()["command"]
        finally:
            relay.OWNER.reset(owner)


@pytest.mark.asyncio
async def test_saved_autonomy_and_connectivity_status():
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=relay.browser_http),base_url=relay.BASE) as client:
        headers={"Authorization":"Bearer "+PHONE}
        response=await client.post("/agentbrowser/api/heartbeat",headers=headers,
            json={"url":"https://example.org","title":"Example","autonomous":True})
        assert response.status_code==200
        owner=relay.OWNER.set(DEVICE)
        try:
            status=relay.browser_status()
            assert status["online"] and status["autonomous"]
            assert status["approval_mode"]=="saved_owner_consent"
            assert status["last_seen_seconds"]<1
            relay.state[DEVICE]["seen"]-=relay.ONLINE_TTL+1
            assert not relay.browser_status()["online"]
        finally:
            relay.OWNER.reset(owner)


@pytest.mark.asyncio
async def test_full_store_description_and_unknown_result():
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=relay.browser_http),base_url=relay.BASE) as client:
        headers={"Authorization":"Bearer "+PHONE}
        await client.get("/agentbrowser/api/poll",headers=headers)
        owner=relay.OWNER.set(DEVICE)
        try:
            task=asyncio.create_task(relay.browser_type("textarea","a"*4000))
            await asyncio.sleep(0)
            command=(await client.get("/agentbrowser/api/poll",headers=headers)).json()["command"]
            assert len(command["args"]["text"])==4000
            await client.post("/agentbrowser/api/result",headers=headers,
                json={"id":command["id"],"ok":True,"result":"Saisie effectuée"})
            assert (await task)["ok"]
            assert (await client.post("/agentbrowser/api/result",headers=headers,
                json={"id":"unknown","ok":True,"result":"late"})).status_code==404
            tools=await relay.browser_mcp.list_tools()
            for tool in tools:
                if tool.name in ["browser_read_page","browser_open_url","browser_click","browser_type","browser_scroll"]:
                    assert tool.description and tool.annotations is not None
        finally:
            relay.OWNER.reset(owner)
