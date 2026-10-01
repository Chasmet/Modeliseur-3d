"""Private Android relay and stateless Streamable HTTP MCP. One GPU job at a time.

Capability URLs are secrets. Disable access logging; never publish the link.
Local SQLite/files survive process restarts, not ephemeral Render redeploys.
"""
import asyncio
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
import subprocess
import time

from PIL import Image, UnidentifiedImageError
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
tasks = set()
gpu_lock = asyncio.Lock()
MAX_IMAGE = 8 * 1024 * 1024

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
    UPDATE jobs SET status='interrupted',message='Relais redémarré : relance manuelle nécessaire.'
      WHERE status IN ('queued','running');
    ''')

def digest(token):
    return hashlib.sha256(token.encode()).hexdigest()

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

def job_for(owner, job_id):
    with db() as c:
        row = c.execute('SELECT * FROM jobs WHERE id=? AND device=?', (job_id, owner)).fetchone()
    if not row:
        raise HTTPException(404, 'Modèle introuvable.')
    return dict(row)

def update_job(job_id, status, message):
    with db() as c:
        c.execute('UPDATE jobs SET status=?,message=? WHERE id=?', (status, message, job_id))

def generate_sync(job):
    # Fixed official endpoint only: never fetch an arbitrary URL supplied by MCP.
    from gradio_client import Client, handle_file
    folder = DATA / job['id']
    folder.mkdir(exist_ok=True)
    client = Client('https://microsoft-trellis-2.hf.space/', token=os.environ.get('HF_TOKEN'),
                    verbose=False, download_files=str(folder), httpx_kwargs={'timeout': 90})
    client.predict(api_name='/start_session')
    prepared = client.predict(input=handle_file(str(DATA / (job['reference'] + '.png'))), api_name='/preprocess_image')
    update_job(job['id'], 'running', 'Calcul TRELLIS.2 sur le GPU distant…')
    client.predict(image=handle_file(prepared), seed=secrets.randbelow(2**31), resolution='512', api_name='/image_to_3d')
    update_job(job['id'], 'running', 'Export mobile : 100 000 triangles, texture 1 024 pixels…')
    result = client.predict(decimation_target=100_000, texture_size=1024, api_name='/extract_glb')
    source = Path(result[0] if isinstance(result, (list, tuple)) else result)
    if source.stat().st_size > 64 * 1024 * 1024:
        raise ValueError('Export exceeds mobile limit')
    output = folder / 'model.glb'
    process = subprocess.run(['node', str(ROOT / 'tools/trellis/mobile.mjs'), str(source), str(output),
                              'humanoid' if job['rig'] else 'none'],
                             capture_output=True, text=True, timeout=180, check=True)
    info = json.loads(process.stdout.strip().splitlines()[-1])
    update_job(job['id'], 'ready', info.get('warning') or 'GLB prêt. Animation procédurale ajoutée.' if info.get('added')
               else info.get('warning') or 'GLB prêt sans animation.')
    # Gradio intermediate files and latents are not exposed by the API.
    for child in folder.iterdir():
        if child != output:
            if child.is_dir(): shutil.rmtree(child)
            else: child.unlink()

async def run_job(job):
    async with gpu_lock:
        try:
            update_job(job['id'], 'running', 'Connexion au moteur distant…')
            await asyncio.to_thread(generate_sync, job)
        except Exception as error:
            # Do not leak tokens, URLs, file paths or uploaded images from upstream errors.
            quota = any(s in str(error).lower() for s in ('quota', 'zerogpu'))
            update_job(job['id'], 'quota' if quota else 'error',
                       'Quota GPU atteint. Réessaye après sa remise à zéro ; aucun nouvel essai automatique.' if quota
                       else 'Le moteur distant est indisponible ou son export a échoué. Relance manuelle nécessaire.')

def start_job(owner, reference_id, humanoid=False):
    with db() as c:
        ref = c.execute('SELECT id FROM refs WHERE id=? AND device=?', (reference_id, owner)).fetchone()
        count = c.execute("SELECT COUNT(*) FROM jobs WHERE status IN ('queued','running')").fetchone()[0]
        own = c.execute('SELECT COUNT(*) FROM jobs WHERE device=?', (owner,)).fetchone()[0]
        if not ref or not (DATA / (reference_id + '.png')).is_file():
            raise HTTPException(404, 'Image introuvable.')
        if count >= 2 or own >= 12:
            raise HTTPException(429, 'Limite de travaux atteinte. Attends ou efface les anciens travaux.')
        job_id = secrets.token_hex(16)
        c.execute('INSERT INTO jobs VALUES(?,?,?,?,?,?,?)',
                  (job_id, owner, reference_id, 'queued', 'En attente du GPU distant…', int(humanoid), time.time()))
    job = job_for(owner, job_id)
    task = asyncio.create_task(run_job(job))
    tasks.add(task)
    task.add_done_callback(tasks.discard)
    return job

async def payload(request):
    try:
        value = await request.json()
        if not isinstance(value, dict): raise ValueError()
        return value
    except (ValueError, UnicodeError):
        raise HTTPException(400, 'Corps JSON invalide.')

async def health(request):
    return JSONResponse({'ok': True, 'version': '6.0.0', 'generation': 'remote_trellis2', 'mcp': 'streamable_http'})

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
    if sum(p.stat().st_size for p in DATA.rglob('*') if p.is_file()) > 256 * 1024 * 1024:
        raise HTTPException(429, 'Stockage du relais rempli. Efface les anciens travaux.')
    if len(rows('refs', owner)) >= 12:
        raise HTTPException(429, 'Limite de 12 images atteinte. Efface les travaux pour continuer.')
    raw = bytearray()
    async for chunk in request.stream():
        raw.extend(chunk)
        if len(raw) > MAX_IMAGE:
            raise HTTPException(413, 'Image supérieure à 8 Mo.')
    try:
        with Image.open(io.BytesIO(raw)) as image:
            image.load()
            image.thumbnail((1024, 1024))
            reference_id = secrets.token_hex(16)
            image.convert('RGB').save(DATA / (reference_id + '.png'))
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError):
        raise HTTPException(400, 'Image invalide.')
    with db() as c:
        c.execute('INSERT INTO refs VALUES(?,?,?)', (reference_id, owner, time.time()))
    return JSONResponse({'reference_id': reference_id})

async def jobs_api(request):
    owner = device(request)
    if request.method == 'GET':
        return JSONResponse({'jobs': rows('jobs', owner), 'references': rows('refs', owner)})
    data = await payload(request)
    return JSONResponse(start_job(owner, data.get('reference_id', ''), data.get('humanoid') is True), status_code=202)

async def download(request):
    owner = device(request)
    job = job_for(owner, request.path_params['job_id'])
    output = DATA / job['id'] / 'model.glb'
    if job['status'] != 'ready' or not output.is_file():
        raise HTTPException(409, 'GLB pas encore disponible.')
    return FileResponse(output, media_type='model/gltf-binary', filename='TRELLIS_' + job['id'][:8] + '.glb')

async def poll(request):
    owner = device(request)
    with db() as c:
        c.execute('UPDATE devices SET seen=? WHERE id=?', (time.time(), owner))
        commands = [dict(r) for r in c.execute('SELECT * FROM commands WHERE device=? AND acknowledged=0', (owner,))]
    return JSONResponse({'commands': commands, 'jobs': rows('jobs', owner)})

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
        for row in rows('refs', owner): (DATA / (row['id'] + '.png')).unlink(missing_ok=True)
        for row in rows('jobs', owner): shutil.rmtree(DATA / row['id'], ignore_errors=True)
        for table in ('refs', 'jobs', 'commands'): c.execute(f'DELETE FROM {table} WHERE device=?', (owner,))
    return JSONResponse({'ok': True})

# Explicit production host allowlist; SDK protection remains on against DNS rebinding.
allowed = os.environ.get('RENDER_EXTERNAL_HOSTNAME', '')
mcp = FastMCP('Modéliseur 3D', stateless_http=True, json_response=True, streamable_http_path='/',
    transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=True,
        allowed_hosts=['localhost:*', '127.0.0.1:*', 'testserver'] + ([allowed + ':*', allowed] if allowed else []),
        allowed_origins=['https://' + allowed] if allowed else []))

@mcp.tool(annotations={'readOnlyHint': True, 'openWorldHint': False})
def application_status() -> dict:
    """Read this paired phone's connection. Commands work while its TRELLIS/MCP screen is open."""
    owner = owner_context.get()
    with db() as c: seen = c.execute('SELECT seen FROM devices WHERE id=?', (owner,)).fetchone()[0]
    return {'phone_online': time.time() - seen < 20, 'last_seen': seen, 'generation_runs_on': 'remote GPU',
            'profile': '512 generation / 100000 triangles / 1024 texture', 'local_trellis_supported': False}

@mcp.tool(annotations={'readOnlyHint': True, 'openWorldHint': False})
def list_models_and_images() -> dict:
    """List generation jobs and images already uploaded from this paired phone only."""
    owner = owner_context.get()
    return {'jobs': rows('jobs', owner), 'images': rows('refs', owner)}

@mcp.tool(annotations={'readOnlyHint': False, 'destructiveHint': False, 'openWorldHint': True})
async def generate_model(reference_id: str, humanoid: bool = False) -> dict:
    """Generate a mobile GLB from an image already uploaded by the paired phone. Uses remote GPU quota.
    Optional humanoid animations are approximate. Do not retry GPU quota errors automatically.
    """
    return start_job(owner_context.get(), reference_id, humanoid)

@mcp.tool(annotations={'readOnlyHint': False, 'destructiveHint': False, 'openWorldHint': False})
def open_model_on_phone(job_id: str) -> dict:
    """Ask the paired phone to download/open its ready GLB in its built-in 3D viewer.
    Requires the app's TRELLIS/MCP screen to be open. Does not control other applications.
    """
    owner = owner_context.get()
    if job_for(owner, job_id)['status'] != 'ready': raise ValueError('GLB pas encore prêt.')
    with db() as c:
        if c.execute('SELECT COUNT(*) FROM commands WHERE device=? AND acknowledged=0', (owner,)).fetchone()[0] >= 8:
            raise ValueError('Trop de commandes en attente.')
        command_id = secrets.token_hex(16)
        c.execute('INSERT INTO commands(id,device,job,created) VALUES(?,?,?,?)', (command_id, owner, job_id, time.time()))
    return {'command_id': command_id, 'status': 'pending_phone_acknowledgement'}

@asynccontextmanager
async def lifespan(app):
    async with mcp.session_manager.run(): yield

async def error_response(request, error):
    return JSONResponse({'error': error.detail}, status_code=error.status_code)

api = Starlette(routes=[Route('/health', health), Route('/api/register', register, methods=['POST']),
    Route('/api/mcp', mcp_toggle, methods=['POST']), Route('/api/images', upload, methods=['POST']),
    Route('/api/jobs', jobs_api, methods=['GET', 'POST']), Route('/api/jobs/{job_id}/file', download),
    Route('/api/poll', poll), Route('/api/commands/{command_id}/ack', ack, methods=['POST']),
    Route('/api/clear', clear, methods=['POST'])], exception_handlers={HTTPException: error_response}, lifespan=lifespan)
mcp_app = mcp.streamable_http_app()

async def app(scope, receive, send):
    """Pure ASGI routing preserves the MCP context and streaming (no BaseHTTPMiddleware)."""
    path = scope.get('path', '')
    if scope['type'] == 'http' and path.startswith('/mcp/'):
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
        finally: owner_context.reset(context_token)
    else:
        await api(scope, receive, send)
