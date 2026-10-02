import io
import json
import os
from pathlib import Path
import tempfile
import time

os.environ['MODELISEUR_DATA'] = tempfile.mkdtemp(prefix='modeliseur-tests-')
from backend import server
from PIL import Image
from starlette.testclient import TestClient
import pytest

@pytest.fixture(scope="module")
def running_client():
    with TestClient(server.app) as client: yield client

@pytest.fixture
def client(running_client):
    with server.db() as c:
        for table in ('commands', 'jobs', 'refs', 'devices'): c.execute(f'DELETE FROM {table}')
    yield running_client

def enroll(client):
    result = client.post('/api/register').json()
    return {'Authorization': 'Bearer ' + result['token']}

def image(client, headers):
    out = io.BytesIO()
    Image.new('RGB', (1600, 800), 'purple').save(out, 'PNG')
    r = client.post('/api/images', headers=headers, content=out.getvalue())
    assert r.status_code == 200
    return r.json()['reference_id']

def rpc(client, token, method, params=None):
    return client.post('/mcp/' + token, headers={'Accept': 'application/json, text/event-stream'},
                       json={'jsonrpc': '2.0', 'id': 1, 'method': method, 'params': params or {}})

def mcp_result(response):
    assert response.status_code == 200, response.text
    return response.json()['result']

def test_private_images_bounded_upload_and_invalid_json(client):
    a, b = enroll(client), enroll(client)
    ref = image(client, a)
    with Image.open(server.DATA / (ref + '.png')) as im: assert im.size == (1024, 512)
    assert client.get('/api/jobs', headers=b).json()['references'] == []
    assert client.post('/api/jobs', headers=b, json={'reference_id': ref}).status_code == 404
    assert client.get('/api/jobs').status_code == 401
    assert client.post('/api/images', headers=a, content=b'not an image').status_code == 400
    assert client.post('/api/images', headers=a, content=b'x' * (server.MAX_IMAGE + 1)).status_code == 413
    assert client.post('/api/jobs', headers=a, json=[]).status_code == 400

def test_mcp_protocol_tools_device_isolation_and_revocation(client):
    a, b = enroll(client), enroll(client)
    ref = image(client, a)
    token = client.post('/api/mcp', headers=a, json={'enabled': True}).json()['mcp_token']
    init = mcp_result(rpc(client, token, 'initialize', {'protocolVersion': '2025-06-18',
                      'capabilities': {}, 'clientInfo': {'name': 'test', 'version': '1'}}))
    assert 'tools' in init['capabilities']
    listed = mcp_result(rpc(client, token, 'tools/list'))
    assert {t['name'] for t in listed['tools']} == {
        'application_status', 'list_models_and_images', 'generate_model', 'open_model_on_phone'}
    own = mcp_result(rpc(client, token, 'tools/call', {'name': 'list_models_and_images', 'arguments': {}}))
    assert ref in str(own)
    token_b = client.post('/api/mcp', headers=b, json={'enabled': True}).json()['mcp_token']
    other = mcp_result(rpc(client, token_b, 'tools/call', {'name': 'list_models_and_images', 'arguments': {}}))
    assert ref not in str(other)
    client.post('/api/poll', headers=a)  # GET only: no accidental state-changing method accepted.
    client.get('/api/poll', headers=a)
    status = mcp_result(rpc(client, token, 'tools/call', {'name': 'application_status', 'arguments': {}}))
    assert 'true' in str(status).lower()
    client.post('/api/mcp', headers=a, json={'enabled': False})
    assert rpc(client, token, 'tools/list').status_code == 401
    assert rpc(client, 'invalid', 'tools/list').status_code == 401

def test_job_download_and_mcp_command_round_trip(client, monkeypatch):
    a, b = enroll(client), enroll(client)
    ref = image(client, a)
    def fake_generate(job):
        folder = server.DATA / job['id']; folder.mkdir(exist_ok=True)
        (folder / 'model.glb').write_bytes(b'glTF-test')
        server.update_job(job['id'], 'ready', 'GLB prêt.')
    monkeypatch.setattr(server, 'generate_sync', fake_generate)
    token = client.post('/api/mcp', headers=a, json={'enabled': True}).json()['mcp_token']
    result = mcp_result(rpc(client, token, 'tools/call', {
        'name': 'generate_model', 'arguments': {'reference_id': ref}}))
    job = json.loads(result['content'][0]['text'])['id']
    for _ in range(30):
        if client.get('/api/jobs', headers=a).json()['jobs'][0]['status'] == 'ready': break
        time.sleep(.01)
    assert client.get('/api/jobs/' + job + '/file', headers=a).content == b'glTF-test'
    assert client.get('/api/jobs/' + job + '/file', headers=b).status_code == 404
    mcp_result(rpc(client, token, 'tools/call', {'name': 'open_model_on_phone', 'arguments': {'job_id': job}}))
    assert client.get('/api/poll', headers=b).json()['commands'] == []
    command = client.get('/api/poll', headers=a).json()['commands'][0]['id']
    client.post('/api/commands/' + command + '/ack', headers=b)
    assert len(client.get('/api/poll', headers=a).json()['commands']) == 1
    client.post('/api/commands/' + command + '/ack', headers=a)
    assert client.get('/api/poll', headers=a).json()['commands'] == []
    client.post('/api/clear', headers=a)
    assert not (server.DATA / job).exists()
    assert not (server.DATA / (ref + '.png')).exists()

def test_gpu_quota_does_not_retry_or_leak_credentials(client, monkeypatch):
    a = enroll(client); ref = image(client, a); calls = []
    def quota(job):
        calls.append(job['id'])
        raise RuntimeError('ZeroGPU quota exceeded https://secret/token hf_private')
    monkeypatch.setattr(server, 'generate_sync', quota)
    r = client.post('/api/jobs', headers=a, json={'reference_id': ref})
    assert r.status_code == 202
    for _ in range(30):
        result = client.get('/api/jobs', headers=a).json()['jobs'][0]
        if result['status'] == 'quota': break
        time.sleep(.01)
    assert result['status'] == 'quota'
    assert len(calls) == 1
    assert 'hf_private' not in json.dumps(result)
    assert 'https://' not in json.dumps(result)
