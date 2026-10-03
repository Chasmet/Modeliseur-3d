"""Android local-engine relay and stateless Streamable HTTP MCP.

The public /mcp endpoint deliberately has no authentication for the owner's private setup.
Legacy per-device capability URLs remain supported for backward compatibility.
Local SQLite/files survive process restarts, not ephemeral Render redeploys.
"""
import asyncio
import base64
import binascii
import ipaddress
import socket
from contextlib import asynccontextmanager, contextmanager
from contextvars import ContextVar
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import sqlite3
import time
from urllib.parse import urlsplit

import httpx
from PIL import Image, UnidentifiedImageError
from backend.signing import signing_key
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from starlette.applications import Starlette
from starlette.exceptions import HTTPException
from starlette.requests import Request
from starlette.responses import FileResponse, JSONResponse
from starlette.routing import Route

ROOT = Path(__file__).resolve().parents[1]
DATA = Path(os.environ.get('MODELISEUR_DATA', '/tmp/modeliseur-data'))
DATA.mkdir(parents=True, exist_ok=True)
Image.MAX_IMAGE_PIXELS = 16_000_000
owner_context = ContextVar('device')
MAX_IMAGE = 8 * 1024 * 1024
PHONE_ONLINE_TTL = 90
PUBLIC_OWNER = '__public__'

@contextmanager
def db():
    c = sqlite3.connect(DATA / 'state.sqlite', timeout=10)
    c.row_factory = sqlite3.Row
    try:
        with c: yield c
    finally: c.close()

with db() as c:
    c.executescript('''
    CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, app_hash TEXT UNIQUE,
      mcp_hash TEXT UNIQUE, seen REAL DEFAULT 0, created REAL);
    CREATE TABLE IF NOT EXISTS refs(id TEXT PRIMARY KEY, device TEXT, created REAL);
    CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, device TEXT, reference TEXT,
      status TEXT, message TEXT, rig INTEGER, created REAL);
    CREATE TABLE IF NOT EXISTS commands(id TEXT PRIMARY KEY, device TEXT, job TEXT,
      acknowledged INTEGER DEFAULT 0, created REAL);
    CREATE TABLE IF NOT EXISTS local_commands(
      id TEXT PRIMARY KEY, device TEXT, refs_json TEXT, mode TEXT, options_json TEXT,
      status TEXT, message TEXT, created REAL, updated REAL);
    UPDATE jobs SET status='interrupted',message='Relais redémarré : relance manuelle nécessaire.'
      WHERE status IN ('queued','running');
    UPDATE local_commands SET status='pending',
      message='Application à reconnecter pour reprendre la génération locale.',updated=strftime('%s','now')
      WHERE status='running';
    ''')

def digest(token):
    return hashlib.sha256(token.encode()).hexdigest()

def active_owner():
    """Open MCP uses the most recently active Android device, or a public workspace."""
    with db() as c:
        row = c.execute(
            "SELECT id FROM devices ORDER BY CASE WHEN seen > 0 THEN seen ELSE created END DESC LIMIT 1"
        ).fetchone()
    return row['id'] if row else PUBLIC_OWNER

def identity(token, kind='app_hash'):
    if kind not in ('app_hash', 'mcp_hash') or not token:
        raise HTTPException(401, 'Connexion requise.')
    with db() as c:
        row = c.execute(f'SELECT id FROM devices WHERE {kind}=?', (digest(token),)).fetchone()
    if not row:
        raise HTTPException(401, 'Connexion expirée ou révoquée.')
    return row['id']

def device(request):
    header = request.headers.get('authorization', '')
    return identity(header[7:] if header.startswith('Bearer ') else '')

def rows(table, owner):
    if table not in ('refs', 'jobs'):
        raise ValueError('Unsupported collection')
    with db() as c:
        return [dict(r) for r in c.execute(f'SELECT * FROM {table} WHERE device=? ORDER BY created DESC', (owner,))]

def local_rows(owner):
    with db() as c:
        result = [dict(r) for r in c.execute(
            'SELECT * FROM local_commands WHERE device=? ORDER BY created DESC', (owner,))]
    for item in result:
        item['references'] = json.loads(item.pop('refs_json'))
        item['options'] = json.loads(item.pop('options_json'))
    return result

def local_command_for(owner, command_id):
    with db() as c:
        row = c.execute('SELECT * FROM local_commands WHERE id=? AND device=?',
                        (command_id, owner)).fetchone()
    if not row:
        raise HTTPException(404, 'Commande locale introuvable.')
    item = dict(row)
    item['references'] = json.loads(item.pop('refs_json'))
    item['options'] = json.loads(item.pop('options_json'))
    return item

def phone_online(owner):
    if owner == PUBLIC_OWNER:
        return False
    with db() as c:
        row = c.execute('SELECT seen FROM devices WHERE id=?', (owner,)).fetchone()
    return bool(row) and time.time() - float(row['seen'] or 0) < PHONE_ONLINE_TTL

def create_local_command(owner, references, mode, options=None):
    if not phone_online(owner):
        raise ValueError("Le téléphone Modéliseur 3D n'est pas en ligne. Ouvre l'application puis réessaie.")
    if mode not in ('triposr_single', 'triposr_four', 'silhouettes_single', 'silhouettes_four'):
        raise ValueError('Mode local invalide.')
    expected = 1 if mode.endswith('_single') else 4
    if len(references) != expected:
        raise ValueError('Nombre de vues incompatible avec le moteur local.')
    for reference_id in references:
        with db() as c:
            row = c.execute('SELECT id FROM refs WHERE id=? AND device=?',
                            (reference_id, owner)).fetchone()
        if not row or not (DATA / (reference_id + '.png')).is_file():
            raise ValueError('Image locale de commande introuvable.')
    with db() as c:
        pending = c.execute(
            "SELECT COUNT(*) FROM local_commands WHERE device=? AND status IN ('pending','running')",
            (owner,)).fetchone()[0]
        if pending >= 4:
            raise ValueError('Trop de créations locales en attente.')
        command_id = secrets.token_hex(16)
        now = time.time()
        c.execute('INSERT INTO local_commands VALUES(?,?,?,?,?,?,?,?,?)',
                  (command_id, owner, json.dumps(references), mode,
                   json.dumps(options or {}), 'pending',
                   'Commande reçue. En attente de l’application Android.', now, now))
    return local_command_for(owner, command_id)

def update_local_command(owner, command_id, status, message):
    if status not in ('pending', 'running', 'ready', 'error'):
        raise ValueError('État de commande invalide.')
    with db() as c:
        changed = c.execute(
            'UPDATE local_commands SET status=?,message=?,updated=? WHERE id=? AND device=?',
            (status, str(message)[:500], time.time(), command_id, owner)).rowcount
    if not changed:
        raise HTTPException(404, 'Commande locale introuvable.')
    return local_command_for(owner, command_id)

def store_reference_bytes(owner, raw):
    if not raw:
        raise ValueError('Image vide.')
    if len(raw) > MAX_IMAGE:
        raise ValueError('Image supérieure à 8 Mo.')
    if sum(p.stat().st_size for p in DATA.rglob('*') if p.is_file()) > 256 * 1024 * 1024:
        raise ValueError('Stockage du relais rempli. Efface les anciens travaux.')
    if len(rows('refs', owner)) >= 12:
        raise ValueError('Limite de 12 images atteinte. Efface les anciens travaux pour continuer.')
    try:
        with Image.open(io.BytesIO(raw)) as image:
            image.load()
            image.thumbnail((1024, 1024))
            reference_id = secrets.token_hex(16)
            image.convert('RGBA' if image.mode in ('RGBA', 'LA', 'P') else 'RGB').save(DATA / (reference_id + '.png'))
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as error:
        raise ValueError('Image invalide.') from error
    with db() as c:
        c.execute('INSERT INTO refs VALUES(?,?,?)', (reference_id, owner, time.time()))
    return reference_id

def decode_inline_image(value):
    if not isinstance(value, str) or not value:
        raise ValueError('Image manquante.')
    if len(value) > 12 * 1024 * 1024:
        raise ValueError('Image encodée trop volumineuse.')
    payload_value = value
    if value.startswith('data:'):
        try:
            header, payload_value = value.split(',', 1)
        except ValueError as error:
            raise ValueError('Data URL image invalide.') from error
        if not header.lower().startswith('data:image/') or ';base64' not in header.lower():
            raise ValueError('Seules les Data URL image en base64 sont acceptées.')
    elif value.startswith('base64:'):
        payload_value = value[7:]
    elif value.startswith('https://'):
        return None
    else:
        # Raw base64 is accepted for MCP clients that cannot emit data URLs.
        payload_value = value
    try:
        raw = base64.b64decode(payload_value, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ValueError('Base64 image invalide.') from error
    if len(raw) > MAX_IMAGE:
        raise ValueError('Image supérieure à 8 Mo.')
    return raw

async def read_image_source(value):
    inline = decode_inline_image(value)
    if inline is not None:
        return inline
    parsed = urlsplit(value)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError('URL HTTPS image invalide.')
    if parsed.port not in (None, 443):
        raise ValueError('Seul le port HTTPS standard est accepté.')
    try:
        infos = await asyncio.to_thread(socket.getaddrinfo, parsed.hostname, 443, type=socket.SOCK_STREAM)
    except OSError as error:
        raise ValueError('Hôte image introuvable.') from error
    for info in infos:
        address = info[4][0].split('%', 1)[0]
        ip = ipaddress.ip_address(address)
        if not ip.is_global:
            raise ValueError('Adresse image privée ou locale refusée.')
    timeout = httpx.Timeout(30.0, connect=10.0)
    async with httpx.AsyncClient(follow_redirects=False, timeout=timeout, trust_env=False) as client:
        async with client.stream('GET', value, headers={'User-Agent': 'Modeliseur3D-MCP/1'}) as response:
            if response.status_code != 200:
                raise ValueError('Téléchargement image refusé (HTTP %d).' % response.status_code)
            content_type = response.headers.get('content-type', '').split(';', 1)[0].lower()
            if content_type and not content_type.startswith('image/'):
                raise ValueError('Le lien ne pointe pas vers une image.')
            length = response.headers.get('content-length')
            if length and int(length) > MAX_IMAGE:
                raise ValueError('Image supérieure à 8 Mo.')
            raw = bytearray()
            async for chunk in response.aiter_bytes():
                raw.extend(chunk)
                if len(raw) > MAX_IMAGE:
                    raise ValueError('Image supérieure à 8 Mo.')
    return bytes(raw)

def job_for(owner, job_id):
    with db() as c:
        row = c.execute('SELECT * FROM jobs WHERE id=? AND device=?', (job_id, owner)).fetchone()
    if not row:
        raise HTTPException(404, 'Modèle introuvable.')
    return dict(row)

def update_job(job_id, status, message):
    with db() as c:
        c.execute('UPDATE jobs SET status=?,message=? WHERE id=?', (status, message, job_id))

async def payload(request):
    try:
        value = await request.json()
        if not isinstance(value, dict): raise ValueError()
        return value
    except (ValueError, UnicodeError):
        raise HTTPException(400, 'Corps JSON invalide.')

async def health(request):
    return JSONResponse({'ok': True, 'version': '6.3.8-mcp.background',
                         'generation': 'android_local_triposr',
                         'mcp': 'streamable_http', 'mcp_auth': 'none',
                         'mcp_path': '/mcp', 'direct_images': True})

async def register(request):
    # Device enrollment only creates an isolated empty account, never grants access to existing devices.
    with db() as c:
        if c.execute('SELECT COUNT(*) FROM devices').fetchone()[0] >= 64:
            raise HTTPException(429, 'Relais complet.')
        owner, token = secrets.token_hex(16), secrets.token_urlsafe(32)
        c.execute('INSERT INTO devices(id,app_hash,created) VALUES(?,?,?)', (owner, digest(token), time.time()))
    return JSONResponse({'device_id': owner, 'token': token})

async def mcp_toggle(request):
    owner = device(request)
    data = await payload(request)
    token = secrets.token_urlsafe(32) if data.get('enabled') is True else None
    with db() as c:
        c.execute('UPDATE devices SET mcp_hash=? WHERE id=?', (digest(token) if token else None, owner))
    return JSONResponse({'mcp_token': token, 'enabled': bool(token)})

async def upload(request):
    owner = device(request)
    raw = bytearray()
    async for chunk in request.stream():
        raw.extend(chunk)
        if len(raw) > MAX_IMAGE:
            raise HTTPException(413, 'Image supérieure à 8 Mo.')
    try:
        reference_id = store_reference_bytes(owner, bytes(raw))
    except ValueError as error:
        message = str(error)
        status = 429 if 'Limite' in message or 'Stockage' in message else 400
        raise HTTPException(status, message)
    return JSONResponse({'reference_id': reference_id})

async def jobs_api(request):
    owner = device(request)
    if request.method == 'GET':
        return JSONResponse({'jobs': rows('jobs', owner), 'references': rows('refs', owner)})
    raise HTTPException(410, 'Le calcul distant a été désactivé. Utilise les moteurs locaux Android via le MCP.')

async def download(request):
    owner = device(request)
    job = job_for(owner, request.path_params['job_id'])
    output = DATA / job['id'] / 'model.glb'
    if job['status'] != 'ready' or not output.is_file():
        raise HTTPException(409, 'GLB pas encore disponible.')
    return FileResponse(output, media_type='model/gltf-binary', filename='TRELLIS_' + job['id'][:8] + '.glb')

async def public_download(request):
    job_id = request.path_params['job_id']
    if len(job_id) != 32 or any(ch not in '0123456789abcdef' for ch in job_id):
        raise HTTPException(404, 'Modèle introuvable.')
    with db() as c:
        job = c.execute('SELECT status FROM jobs WHERE id=?', (job_id,)).fetchone()
    output = DATA / job_id / 'model.glb'
    if not job:
        raise HTTPException(404, 'Modèle introuvable.')
    if job['status'] != 'ready' or not output.is_file():
        raise HTTPException(409, 'GLB pas encore disponible.')
    return FileResponse(output, media_type='model/gltf-binary', filename='Modeliseur_' + job_id[:8] + '.glb')

async def local_image_download(request):
    owner = device(request)
    command = local_command_for(owner, request.path_params['command_id'])
    reference_id = request.path_params['reference_id']
    if reference_id not in command['references']:
        raise HTTPException(404, 'Image de commande introuvable.')
    image_path = DATA / (reference_id + '.png')
    if not image_path.is_file():
        raise HTTPException(404, 'Image de commande introuvable.')
    return FileResponse(image_path, media_type='image/png', filename=reference_id + '.png')

async def local_status(request):
    owner = device(request)
    command_id = request.path_params['command_id']
    data = await payload(request)
    status = data.get('status', '')
    message = data.get('message', '')
    if status not in ('pending', 'running', 'error'):
        raise HTTPException(400, 'État local invalide.')
    try:
        command = update_local_command(owner, command_id, status, message)
    except ValueError as error:
        raise HTTPException(400, str(error))
    return JSONResponse(command)

async def local_result(request):
    owner = device(request)
    command_id = request.path_params['command_id']
    command = local_command_for(owner, command_id)
    raw = bytearray()
    async for chunk in request.stream():
        raw.extend(chunk)
        if len(raw) > 64 * 1024 * 1024:
            raise HTTPException(413, 'GLB supérieur à 64 Mo.')
    if len(raw) < 12 or bytes(raw[:4]) != b'glTF':
        raise HTTPException(400, 'GLB invalide.')
    if int.from_bytes(raw[4:8], 'little') != 2:
        raise HTTPException(400, 'Version GLB invalide.')
    declared = int.from_bytes(raw[8:12], 'little', signed=False)
    if declared != len(raw):
        raise HTTPException(400, 'GLB incomplet.')
    folder = DATA / ('local-' + command_id)
    folder.mkdir(exist_ok=True)
    part = folder / 'model.glb.part'
    output = folder / 'model.glb'
    part.write_bytes(raw)
    part.replace(output)
    command = update_local_command(owner, command_id, 'ready',
                                   'Modèle créé localement sur le téléphone et synchronisé.')
    return JSONResponse(command)

async def public_local_download(request):
    command_id = request.path_params['command_id']
    if len(command_id) != 32 or any(ch not in '0123456789abcdef' for ch in command_id):
        raise HTTPException(404, 'Modèle introuvable.')
    with db() as c:
        row = c.execute('SELECT status FROM local_commands WHERE id=?', (command_id,)).fetchone()
    output = DATA / ('local-' + command_id) / 'model.glb'
    if not row:
        raise HTTPException(404, 'Modèle introuvable.')
    if row['status'] != 'ready' or not output.is_file():
        raise HTTPException(409, 'GLB local pas encore disponible.')
    return FileResponse(output, media_type='model/gltf-binary',
                        filename='Modeliseur_Local_' + command_id[:8] + '.glb')

async def disconnect(request):
    owner = device(request)
    with db() as c:
        c.execute('UPDATE devices SET seen=0 WHERE id=?', (owner,))
    return JSONResponse({'ok': True, 'connected': False})

async def heartbeat(request):
    owner = device(request)
    now = time.time()
    with db() as c:
        c.execute('UPDATE devices SET seen=? WHERE id=?', (now, owner))
    return JSONResponse({'ok': True, 'seen': now})

async def poll(request):
    owner = device(request)
    with db() as c:
        c.execute('UPDATE devices SET seen=? WHERE id=?', (time.time(), owner))
        commands = [dict(r) for r in c.execute(
            'SELECT * FROM commands WHERE device=? AND acknowledged=0', (owner,))]
        local = [dict(r) for r in c.execute(
            "SELECT * FROM local_commands WHERE device=? AND status='pending' ORDER BY created ASC",
            (owner,))]
    for item in local:
        item['references'] = json.loads(item.pop('refs_json'))
        item['options'] = json.loads(item.pop('options_json'))
    return JSONResponse({'commands': commands, 'local_commands': local, 'jobs': rows('jobs', owner)})

async def ack(request):
    owner = device(request)
    with db() as c:
        c.execute('UPDATE commands SET acknowledged=1 WHERE id=? AND device=?', (request.path_params['command_id'], owner))
    return JSONResponse({'ok': True})

async def clear(request):
    owner = device(request)
    with db() as c:
        if c.execute("SELECT COUNT(*) FROM jobs WHERE device=? AND status IN ('queued','running')", (owner,)).fetchone()[0]:
            raise HTTPException(409, 'Attends la fin du travail en cours.')
        if c.execute("SELECT COUNT(*) FROM local_commands WHERE device=? AND status IN ('pending','running')", (owner,)).fetchone()[0]:
            raise HTTPException(409, 'Attends la fin des commandes locales en cours.')
        for row in rows('refs', owner): (DATA / (row['id'] + '.png')).unlink(missing_ok=True)
        for row in rows('jobs', owner): shutil.rmtree(DATA / row['id'], ignore_errors=True)
        for row in local_rows(owner): shutil.rmtree(DATA / ('local-' + row['id']), ignore_errors=True)
        for table in ('refs', 'jobs', 'commands', 'local_commands'):
            c.execute(f'DELETE FROM {table} WHERE device=?', (owner,))
    return JSONResponse({'ok': True})

# Explicit production host allowlist; SDK protection remains on against DNS rebinding.
allowed = os.environ.get('RENDER_EXTERNAL_HOSTNAME', '')
mcp = FastMCP('Modéliseur 3D', stateless_http=True, json_response=True, streamable_http_path='/',
    transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=True,
        allowed_hosts=['localhost:*', '127.0.0.1:*', 'testserver'] + ([allowed + ':*', allowed] if allowed else []),
        allowed_origins=['https://' + allowed] if allowed else []))

@mcp.tool(annotations={'readOnlyHint': True, 'openWorldHint': False})
def application_status() -> dict:
    """Read whether the Android Modéliseur 3D application is currently connected."""
    owner = owner_context.get()
    with db() as c:
        row = c.execute('SELECT seen FROM devices WHERE id=?', (owner,)).fetchone()
    seen = float(row['seen'] or 0) if row else 0
    online = bool(row) and time.time() - seen < PHONE_ONLINE_TTL
    return {
        'phone_online': online,
        'last_seen': seen,
        'presence_ttl_seconds': PHONE_ONLINE_TTL,
        'workspace': 'android' if row else 'public',
        'authentication': 'none',
        'generation_runs_on': 'Android phone / local engines',
        'engines': ['TripoSR local', 'Silhouettes local', 'IS-Net local', 'Depth Anything V2 local'],
        'remote_gpu_generation': False
    }

@mcp.tool(annotations={'readOnlyHint': True, 'openWorldHint': False})
def application_capabilities() -> dict:
    """List the 3D tools available inside the Android application."""
    return {
        'reconstruction': {
            'triposr_single': '1 image -> TripoSR local -> GLB',
            'triposr_four': '4 views -> TripoSR local + app fusion -> textured GLB',
            'silhouettes_single': '1 image -> lightweight local silhouette volume',
            'silhouettes_four': '4 views -> lightweight local silhouette reconstruction'
        },
        'image_tools': ['IS-Net local cutout', 'Depth Anything V2 local depth', 'rotation', 'horizontal mirror'],
        'output': ['GLB local storage', 'built-in viewer', 'export'],
        'network_role': 'The MCP only transfers commands/images/results. 3D inference runs inside the Android app.'
    }

@mcp.tool(annotations={'readOnlyHint': True, 'openWorldHint': False})
def list_models_and_images() -> dict:
    """List local Android MCP creation commands and their uploaded reference images."""
    owner = owner_context.get()
    return {'commands': local_rows(owner), 'images': rows('refs', owner)}

@mcp.tool(annotations={'readOnlyHint': False, 'destructiveHint': False, 'openWorldHint': True})
async def create_model_from_images(images: list[str], engine: str = 'auto',
                                   quality: str = 'balanced', smoothing: bool = True) -> dict:
    """Send one to four user images to the Android app and create GLB models with its built-in local engines.
    With four images, provide exactly: front, back, right profile, left profile, in the same pose.
    Auto/TripoSR uses the app's four-view TripoSR fusion.
    With one image, it uses local single-image TripoSR.
    With two or three images, one independent single-image model is queued per image.
    Set engine to 'silhouettes' to use the lighter local silhouette engine instead.
    """
    if not isinstance(images, list) or not 1 <= len(images) <= 4:
        raise ValueError('Fournis entre 1 et 4 images.')
    owner = owner_context.get()
    if not phone_online(owner):
        raise ValueError("Le téléphone Modéliseur 3D n'est pas en ligne. Ouvre l'application puis réessaie.")
    engine = (engine or 'auto').lower()
    if engine not in ('auto', 'triposr', 'silhouettes'):
        raise ValueError("Moteur invalide : auto, triposr ou silhouettes.")
    quality = (quality or 'balanced').lower()
    if quality not in ('fast', 'balanced', 'precise'):
        raise ValueError("Qualité invalide : fast, balanced ou precise.")

    references = []
    for source in images:
        raw = await read_image_source(source)
        references.append(store_reference_bytes(owner, raw))

    options = {'quality': quality, 'smoothing': bool(smoothing)}
    commands = []
    if len(references) == 4:
        selected = 'silhouettes_four' if engine == 'silhouettes' else 'triposr_four'
        commands.append(create_local_command(owner, references, selected, options))
    else:
        selected = 'silhouettes_single' if engine == 'silhouettes' else 'triposr_single'
        for reference_id in references:
            commands.append(create_local_command(owner, [reference_id], selected, options))

    return {
        'primary_model_id': commands[0]['id'],
        'models': commands,
        'count': len(commands),
        'execution': 'Android local',
        'engine': engine,
        'quality': quality
    }

@mcp.tool(annotations={'readOnlyHint': True, 'openWorldHint': False})
def model_status(model_id: str) -> dict:
    """Read the status of a model being generated by the Android app."""
    return local_command_for(owner_context.get(), model_id)

@mcp.tool(annotations={'readOnlyHint': True, 'openWorldHint': True})
def model_download(model_id: str) -> dict:
    """Return the GLB download URL after the Android app has finished local generation."""
    command = local_command_for(owner_context.get(), model_id)
    if command['status'] != 'ready':
        return {'status': command['status'], 'message': command.get('message', '')}
    host = os.environ.get('RENDER_EXTERNAL_HOSTNAME', '')
    path = '/public/local/' + model_id + '/file'
    return {'status': 'ready', 'url': ('https://' + host + path) if host else path,
            'generated_on': 'Android phone'}

@asynccontextmanager
async def lifespan(app):
    async with mcp.session_manager.run(): yield

async def error_response(request, error):
    return JSONResponse({'error': error.detail}, status_code=error.status_code)

api = Starlette(routes=[Route('/health', health), Route('/ci/signing', signing_key), Route('/api/register', register, methods=['POST']),
    Route('/public/jobs/{job_id}/file', public_download),
    Route('/public/local/{command_id}/file', public_local_download),
    Route('/api/local/{command_id}/images/{reference_id}', local_image_download),
    Route('/api/local/{command_id}/status', local_status, methods=['POST']),
    Route('/api/local/{command_id}/result', local_result, methods=['POST']),
    Route('/api/mcp', mcp_toggle, methods=['POST']), Route('/api/images', upload, methods=['POST']),
    Route('/api/jobs', jobs_api, methods=['GET', 'POST']), Route('/api/jobs/{job_id}/file', download),
    Route('/api/disconnect', disconnect, methods=['POST']), Route('/api/heartbeat', heartbeat), Route('/api/poll', poll), Route('/api/commands/{command_id}/ack', ack, methods=['POST']),
    Route('/api/clear', clear, methods=['POST'])], exception_handlers={HTTPException: error_response}, lifespan=lifespan)
mcp_app = mcp.streamable_http_app()

async def app(scope, receive, send):
    """Pure ASGI routing preserves the MCP context and streaming (no BaseHTTPMiddleware)."""
    path = scope.get('path', '')
    if scope['type'] == 'http' and path.rstrip('/') == '/mcp':
        context_token = owner_context.set(active_owner())
        try:
            inner = dict(scope, path='/', raw_path=b'/', root_path='')
            await mcp_app(inner, receive, send)
        finally:
            owner_context.reset(context_token)
    elif scope['type'] == 'http' and path.startswith('/mcp/'):
        capability = path[5:]
        try:
            owner = identity(capability, 'mcp_hash')
        except HTTPException:
            await JSONResponse({'error': 'Connexion MCP expirée ou désactivée.'}, status_code=401)(scope, receive, send)
            return
        context_token = owner_context.set(owner)
        try:
            inner = dict(scope, path='/', raw_path=b'/', root_path='')
            await mcp_app(inner, receive, send)
        finally:
            owner_context.reset(context_token)
    else:
        await api(scope, receive, send)
