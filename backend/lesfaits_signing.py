"""Provide the LES FAITS Android signer to its production GitHub Actions workflow."""
import asyncio
import json
import os

import jwt
from starlette.responses import JSONResponse

from backend.browser_signing import ISSUER, JWKS

REPOSITORY = "Chasmet/Probl-me-r-curent-travail-"
AUDIENCE = "https://modeliseur-trellis-mcp.onrender.com/lesfaits/ci/signing"
CLAIMS = {
    "repository": REPOSITORY,
    "repository_id": "1467654431",
    "repository_owner_id": "160381695",
    "actor_id": "160381695",
    "ref": "refs/heads/main",
    "runner_environment": "github-hosted",
}

# Deux canaux légitimes, même dépôt, même branche protégée par OIDC.
# Aucun motif générique : chaque workflow doit correspondre exactement.
ALLOWED_WORKFLOW_REFS = frozenset({
    REPOSITORY + "/.github/workflows/release.yml@refs/heads/main",
})


def validate_claims(claims):
    return (all(claims.get(name) == value for name, value in CLAIMS.items())
            and claims.get("workflow_ref") in ALLOWED_WORKFLOW_REFS
            and claims.get("event_name") in {"push", "workflow_dispatch"})


def authenticate(token):
    key = JWKS.get_signing_key_from_jwt(token).key
    claims = jwt.decode(
        token, key, algorithms=["RS256"], issuer=ISSUER, audience=AUDIENCE,
        options={"require": ["exp", "iat", "nbf", "sub", "iss", "aud"]},
    )
    return validate_claims(claims)


async def issue_lesfaits_signing(request):
    header = request.headers.get("authorization", "")
    if not header.startswith("Bearer ") or len(header) > 20000:
        return JSONResponse({"error": "Identité GitHub Actions requise."}, status_code=401)
    try:
        allowed = await asyncio.to_thread(authenticate, header[7:])
    except Exception:
        return JSONResponse({"error": "Identité GitHub Actions invalide."}, status_code=401)
    if not allowed:
        return JSONResponse({"error": "Workflow LES FAITS non autorisé."}, status_code=403)

    fields = ("keystoreBase64", "storePassword", "keyPassword", "certificateSha256", "storeType")
    try:
        data = json.loads(os.environ.get("LESFAITS_ANDROID_SIGNING_JSON", "null"))
        if not isinstance(data, dict) or any(
            not isinstance(data.get(field), str) or not data[field] for field in fields
        ):
            raise ValueError("Signature indisponible")
        if data["storeType"] != "PKCS12" or data["certificateSha256"].replace(":", "").lower() != "0b9374e1b4cc30e2b6a9d177817038d806c65637a15f47936fb5ed514ede6cd8":
            raise ValueError("Certificat non reconnu")
    except ValueError:
        return JSONResponse({"error": "Signature LES FAITS indisponible."}, status_code=503)
    return JSONResponse({field: data[field] for field in fields},
                        headers={"Cache-Control": "no-store", "Pragma": "no-cache"})
