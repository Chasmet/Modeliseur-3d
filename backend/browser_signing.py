"""Return existing Android signer only to the owner's CHK-Agent-Browser GitHub Actions.

Strict GitHub OIDC JWT: no PAT or API keys in the mobile application.
Other services, browser MCP and unauthenticated requests cannot read signing data.
"""
import asyncio
import json
import os
import jwt
from jwt import PyJWKClient
from starlette.responses import JSONResponse
from starlette.exceptions import HTTPException

AUDIENCE = "https://modeliseur-trellis-mcp.onrender.com/agentbrowser/ci/signing"
ISSUER = "https://token.actions.githubusercontent.com"
REPOSITORY = "Chasmet/CHK-Agent-Browser"
WORKFLOW = REPOSITORY + "/.github/workflows/android.yml@refs/heads/main"
JWKS = PyJWKClient(ISSUER + "/.well-known/jwks", cache_keys=True)


def validate(claims):
    expected = {
        "repository": REPOSITORY,
        "repository_id": "1410631793",
        "repository_owner_id": "160381695",
        "actor_id": "160381695",
        "ref": "refs/heads/main",
        "workflow_ref": WORKFLOW,
        "runner_environment": "github-hosted",
    }
    if any(str(claims.get(k, "")) != v for k, v in expected.items()):
        raise HTTPException(403, "Workflow GitHub non autorisé.")
    if claims.get("event_name") not in ("push", "workflow_dispatch"):
        raise HTTPException(403, "Événement GitHub refusé.")


def authenticate(token):
    try:
        key = JWKS.get_signing_key_from_jwt(token).key
        claims = jwt.decode(
            token,key,algorithms=["RS256"],audience=AUDIENCE,issuer=ISSUER,
            leeway=30,options={"require": ["exp","iat","nbf","sub","iss","aud"]})
    except Exception:
        raise HTTPException(401,"Identité GitHub Actions invalide.")
    validate(claims)


async def issue_signing(request):
    header=request.headers.get("authorization","")
    if not header.startswith("Bearer ") or len(header)>20000:
        return JSONResponse({"error":"Identité GitHub Actions requise"},status_code=401)
    try:
        await asyncio.to_thread(authenticate,header[7:])
    except HTTPException as error:
        return JSONResponse({"error":error.detail},status_code=error.status_code)
    raw=os.environ.get("ANDROID_SIGNING_JSON")
    if not raw:
        return JSONResponse({"error":"Clé permanente non configurée."},status_code=503)
    try:
        data=json.loads(raw)
        if not all(k in data for k in ("keystoreBase64","alias","storePassword","keyPassword")):
            raise ValueError("Champs manquants")
    except (ValueError,TypeError):
        return JSONResponse({"error":"Identité de signature invalide."},status_code=503)
    return JSONResponse(data,headers={"Cache-Control":"no-store","Pragma":"no-cache"})
