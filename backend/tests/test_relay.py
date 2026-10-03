import base64
import io
import json
import os
from pathlib import Path
import tempfile
import time

os.environ['MODELISEUR_DATA'] = tempfile.mkdtemp(prefix='modeliseur-tests-')
from backend import server
from PIL import Image, ImageColor
from starlette.testclient import TestClient
import pytest

@pytest.fixture(scope="module")
def running_client():
    with TestClient(server.app) as client: yield client

@pytest.fixture
def client(running_client):
    with server.db() as c:
        for table in ('local_commands', 'commands', 'jobs', 'refs', 'devices'): c.execute(f'DELETE FROM {table}')
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

def rpc_open(client, method, params=None):
    return client.post('/mcp', headers={'Accept': 'application/json, text/event-stream'},
                       json={'jsonrpc': '2.0', 'id': 1, 'method': method, 'params': params or {}})

def mcp_result(response):
    assert response.status_code == 200, response.text
    return response.json()['result']

def test_private_images_bounded_upload_and_invalid_json(client):
    a, b = enroll(client), enroll(client)
    ref = image(client, a)
    with Image.open(server.DATA / (ref + '.png')) as im: assert im.size == (1024, 512)
    assert client.get('/api/jobs', headers=b).json()['references'] == []
    assert client.post('/api/jobs', headers=b, json={'reference_id': ref}).status_code == 410
    assert client.get('/api/jobs').status_code == 401
    assert client.post('/api/images', headers=a, content=b'not an image').status_code == 400
    assert client.post('/api/images', headers=a, content=b'x' * (server.MAX_IMAGE + 1)).status_code == 413
    assert client.post('/api/jobs', headers=a, json=[]).status_code == 410

def test_android_heartbeat_marks_device_online(client):
    a = enroll(client)
    with server.db() as c:
        before = c.execute('SELECT seen FROM devices').fetchone()[0]
    assert before == 0
    r = client.get('/api/heartbeat', headers=a)
    assert r.status_code == 200
    assert r.json()['ok'] is True
    with server.db() as c:
        after = c.execute('SELECT seen FROM devices').fetchone()[0]
    assert after > 0


def test_mcp_protocol_tools_device_isolation_and_revocation(client):
    a, b = enroll(client), enroll(client)
    ref = image(client, a)
    token = client.post('/api/mcp', headers=a, json={'enabled': True}).json()['mcp_token']
    init = mcp_result(rpc(client, token, 'initialize', {'protocolVersion': '2025-06-18',
                      'capabilities': {}, 'clientInfo': {'name': 'test', 'version': '1'}}))
    assert 'tools' in init['capabilities']
    listed = mcp_result(rpc(client, token, 'tools/list'))
    assert {t['name'] for t in listed['tools']} == {
        'application_status', 'application_capabilities', 'list_models_and_images',
        'create_model_from_images', 'model_status', 'model_download'}
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

def test_open_mcp_queues_android_local_generation(client):
    a = enroll(client)
    client.get('/api/poll', headers=a)

    init = mcp_result(rpc_open(client, 'initialize', {
        'protocolVersion': '2025-06-18', 'capabilities': {},
        'clientInfo': {'name': 'test-open', 'version': '1'}}))
    assert 'tools' in init['capabilities']

    out = io.BytesIO()
    Image.new('RGB', (320, 240), 'orange').save(out, 'PNG')
    data_url = 'data:image/png;base64,' + base64.b64encode(out.getvalue()).decode()
    created = mcp_result(rpc_open(client, 'tools/call', {
        'name': 'create_model_from_images',
        'arguments': {'images': [data_url], 'engine': 'triposr', 'quality': 'balanced'}}))
    payload = json.loads(created['content'][0]['text'])
    model_id = payload['primary_model_id']

    poll = client.get('/api/poll', headers=a).json()
    assert poll['local_commands'][0]['id'] == model_id
    assert poll['local_commands'][0]['mode'] == 'triposr_single'
    reference = poll['local_commands'][0]['references'][0]
    image_response = client.get(f'/api/local/{model_id}/images/{reference}', headers=a)
    assert image_response.status_code == 200
    assert image_response.content.startswith(b'\x89PNG')

    running = client.post(f'/api/local/{model_id}/status', headers=a,
                          json={'status': 'running', 'message': 'Calcul local'}).json()
    assert running['status'] == 'running'

    glb = b'glTF' + (2).to_bytes(4, 'little') + (12).to_bytes(4, 'little')
    ready = client.post(f'/api/local/{model_id}/result', headers=a, content=glb)
    assert ready.status_code == 200
    assert ready.json()['status'] == 'ready'

    download = mcp_result(rpc_open(client, 'tools/call', {
        'name': 'model_download', 'arguments': {'model_id': model_id}}))
    assert '/public/local/' + model_id + '/file' in str(download)
    assert client.get('/public/local/' + model_id + '/file').content == glb


def test_remote_generation_is_disabled(client):
    a = enroll(client)
    reference = image(client, a)
    response = client.post('/api/jobs', headers=a, json={'reference_id': reference})
    assert response.status_code == 410
    assert 'locaux' in response.json()['error']
    assert client.get('/api/jobs', headers=a).json()['jobs'] == []
    assert not hasattr(server, 'generate_sync')


def test_transparency_and_four_view_order_survive_mcp(client):
    a = enroll(client)
    client.get('/api/poll', headers=a)
    images = []
    for color in ['red', 'green', 'blue', 'yellow']:
        out = io.BytesIO()
        image = Image.new('RGBA', (20, 20), (0, 0, 0, 0))
        image.putpixel((10, 10), ImageColor.getrgb(color) + (255,))
        image.save(out, 'PNG')
        images.append('base64:' + base64.b64encode(out.getvalue()).decode())
    result = mcp_result(rpc_open(client, 'tools/call', {
        'name': 'create_model_from_images',
        'arguments': {'images': images, 'quality': 'precise'}}))
    created = json.loads(result['content'][0]['text'])
    assert created['count'] == 1
    command = client.get('/api/poll', headers=a).json()['local_commands'][0]
    assert command['mode'] == 'triposr_four'
    assert command['options']['quality'] == 'precise'
    for reference, expected in zip(command['references'], [(255,0,0), (0,128,0), (0,0,255), (255,255,0)]):
        with Image.open(server.DATA / (reference + '.png')) as actual:
            assert actual.getpixel((0,0))[3] == 0
            assert actual.getpixel((10,10))[:3] == expected
    assert client.post('/api/clear', headers=a).status_code == 409


def test_three_images_queue_three_independent_models_and_reject_invalid_glb(client):
    a = enroll(client)
    client.get('/api/poll', headers=a)
    out = io.BytesIO()
    Image.new('RGB', (20,20), 'red').save(out, 'PNG')
    data = 'base64:' + base64.b64encode(out.getvalue()).decode()
    result = mcp_result(rpc_open(client, 'tools/call', {
        'name': 'create_model_from_images', 'arguments': {'images': [data]*3}}))
    assert json.loads(result['content'][0]['text'])['count'] == 3
    commands = client.get('/api/poll', headers=a).json()['local_commands']
    assert len(commands) == 3
    assert all(c['mode'] == 'triposr_single' for c in commands)
    bad_glb = b'glTF' + (1).to_bytes(4, 'little') + (12).to_bytes(4, 'little')
    assert client.post('/api/local/' + commands[0]['id'] + '/result', headers=a, content=bad_glb).status_code == 400
