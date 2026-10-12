"""CI-only key delivery. GitHub OIDC, no PAT or long-lived token in the repository.
Never accessible through MCP or the Android API. Key stays in Render environment.
"""
import asyncio
import json
import os
import jwt
from jwt import PyJWKClient
from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse

AUDIENCE = 'https://modeliseur-trellis-mcp.onrender.com/ci/signing'
ISSUER = 'https://token.actions.githubusercontent.com'
REF = 'refs/heads/agent/trellis-third-tab-auto-update'
WORKFLOW = 'Chasmet/Modeliseur-3d/.github/workflows/release.yml@' + REF
jwks = PyJWKClient(ISSUER + '/.well-known/jwks', cache_keys=True)

def validate_claims(claims):
    expected = {'repository': 'Chasmet/Modeliseur-3d', 'repository_id': '1320257918',
                'repository_owner_id': '160381695', 'actor_id': '160381695',
                'ref': REF, 'workflow_ref': WORKFLOW, 'runner_environment': 'github-hosted'}
    if any(claims.get(k) != v for k, v in expected.items()):
        raise HTTPException(403, 'Ce workflow ne peut pas récupérer la signature.')
    if claims.get('event_name') not in ('push', 'workflow_dispatch'):
        raise HTTPException(403, 'Déclencheur CI refusé.')

def authenticate(token):
    try:
        key = jwks.get_signing_key_from_jwt(token).key
        claims = jwt.decode(token, key, algorithms=['RS256'], audience=AUDIENCE, issuer=ISSUER,
                            leeway=30, options={'require': ['exp', 'iat', 'nbf', 'sub', 'iss', 'aud']})
    except Exception:
        raise HTTPException(401, 'Identité GitHub Actions invalide.')
    validate_claims(claims)

async def signing_key(request):
    header = request.headers.get('authorization', '')
    if not header.startswith('Bearer ') or len(header) > 20_000:
        raise HTTPException(401, 'Identité GitHub Actions requise.')
    await asyncio.to_thread(authenticate, header[7:])
    raw = os.environ.get('ANDROID_SIGNING_JSON')
    if not raw:
        raise HTTPException(503, 'Signature persistante non configurée. Aucune nouvelle clé ne sera créée.')
    return JSONResponse(json.loads(raw), headers={'Cache-Control': 'no-store', 'Pragma': 'no-cache'})
