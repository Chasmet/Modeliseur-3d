"""Strictly scoped GitHub Actions OIDC signer for CHK Camera-maison.

Only the owner's main-branch build.yml may read the existing permanent signer.
No smartphone, MCP request or untrusted workflow can obtain the key.
Uses the shared Render signing vault, without modifying any previous route.
"""
import asyncio
import json
import os
import jwt
from starlette.responses import JSONResponse
from backend.browser_signing import ISSUER, JWKS

AUDIENCE = "https://modeliseur-trellis-mcp.onrender.com/camera-maison/ci/signing"
REPOSITORY = "Chasmet/Camera-maison"
ALLOWED_WORKFLOW = REPOSITORY + "/.github/workflows/build.yml@refs/heads/main"
EXPECTED = {
    "repository": REPOSITORY,
    "repository_id": "1467502821",
    "repository_owner_id": "160381695",
    "actor_id": "160381695",
    "ref": "refs/heads/main",
    "workflow_ref": ALLOWED_WORKFLOW,
    "runner_environment": "github-hosted",
}
FIELDS = ("keystoreBase64", "alias", "storePassword", "keyPassword")


def validate_claims(claims):
    return (
        all(str(claims.get(k, "")) == v for k, v in EXPECTED.items())
        and claims.get("event_name") in {"push", "workflow_dispatch"}
    )


def authenticate(token):
    key = JWKS.get_signing_key_from_jwt(token).key
    claims = jwt.decode(
        token, key, algorithms=["RS256"], issuer=ISSUER, audience=AUDIENCE,
        leeway=30, options={"require": ["exp", "iat", "nbf", "sub", "iss", "aud"]},
    )
    return validate_claims(claims)


async def issue_camera_signing(request):
    header = request.headers.get("authorization", "")
    if not header.startswith("Bearer ") or len(header) > 20000:
        return JSONResponse({"error": "Identité GitHub Actions requise."},
                            status_code=401)
    try:
        allowed = await asyncio.to_thread(authenticate, header[7:])
    except (jwt.PyJWTError, ValueError, KeyError):
        return JSONResponse({"error": "Identité GitHub Actions invalide."},
                            status_code=401)
    if not allowed:
        return JSONResponse({"error": "Workflow Camera-maison non autorisé."},
                            status_code=403)

    # Existing permanent signing identity, already configured for CHK Android.
    # The signer never enters the public GitHub repository or any server logs.
    try:
        data = json.loads(os.environ.get("ANDROID_SIGNING_JSON", "null"))
        if not isinstance(data, dict) or any(
            not isinstance(data.get(key), str) or not data[key] for key in FIELDS
        ):
            raise ValueError("Signature unavailable")
    except (ValueError, TypeError):
        return JSONResponse({"error": "Signature permanente non configurée."},
                            status_code=503)
    return JSONResponse({key: data[key] for key in FIELDS},
                        headers={"Cache-Control": "no-store",
                                 "Pragma": "no-cache"})
