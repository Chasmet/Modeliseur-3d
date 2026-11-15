"""Provide the Coran Android signer to its production GitHub Actions workflow."""
import asyncio
import json
import os

import jwt
from starlette.responses import JSONResponse

from backend.browser_signing import ISSUER, JWKS

REPOSITORY = "Chasmet/Coran"
AUDIENCE = "https://modeliseur-trellis-mcp.onrender.com/coran/ci/signing"
CLAIMS = {
    "repository": REPOSITORY,
    "repository_id": "1456244792",
    "repository_owner_id": "160381695",
    "ref": "refs/heads/main",
    "workflow_ref": REPOSITORY + "/.github/workflows/android.yml@refs/heads/main",
    "runner_environment": "github-hosted",
}


def authenticate(token):
    key = JWKS.get_signing_key_from_jwt(token).key
    claims = jwt.decode(
        token, key, algorithms=["RS256"], issuer=ISSUER, audience=AUDIENCE,
        options={"require": ["exp", "iat", "nbf", "sub", "iss", "aud"]},
    )
    return (all(claims.get(name) == value for name, value in CLAIMS.items())
            and claims.get("event_name") in {"push", "workflow_dispatch"})


async def issue_coran_signing(request):
    header = request.headers.get("authorization", "")
    if not header.startswith("Bearer ") or len(header) > 20000:
        return JSONResponse({"error": "Identité GitHub Actions requise."}, status_code=401)
    try:
        allowed = await asyncio.to_thread(authenticate, header[7:])
    except jwt.PyJWTError:
        return JSONResponse({"error": "Identité GitHub Actions invalide."}, status_code=401)
    if not allowed:
        return JSONResponse({"error": "Workflow Coran non autorisé."}, status_code=403)

    fields = ("keystoreBase64", "alias", "storePassword", "keyPassword")
    try:
        data = json.loads(os.environ.get("CORAN_ANDROID_SIGNING_JSON", "null"))
        if not isinstance(data, dict) or any(
            not isinstance(data.get(field), str) or not data[field] for field in fields
        ):
            raise ValueError("Signature indisponible")
    except ValueError:
        return JSONResponse({"error": "Signature Coran indisponible."}, status_code=503)
    return JSONResponse({field: data[field] for field in fields},
                        headers={"Cache-Control": "no-store", "Pragma": "no-cache"})
