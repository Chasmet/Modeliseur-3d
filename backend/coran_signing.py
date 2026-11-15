"""Signature Android permanente pour Coran via GitHub OIDC.

Service Render déjà existant, module séparé de celui de CHK Agent Browser.
Aucune clé dans le dépôt GitHub, aucun PAT, aucune API Android.
L'identité GitHub doit correspondre exactement au workflow principal de Coran.
"""
import asyncio
import json
import os

import jwt
from jwt import PyJWKClient
from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse

AUDIENCE = "https://modeliseur-trellis-mcp.onrender.com/coran/ci/signing"
ISSUER = "https://token.actions.githubusercontent.com"
REPOSITORY = "Chasmet/Coran"
WORKFLOW = REPOSITORY + "/.github/workflows/android.yml@refs/heads/main"
JWKS = PyJWKClient(ISSUER + "/.well-known/jwks", cache_keys=True)


def verify_identity(claims):
    expected = {
        "repository": REPOSITORY,
        "repository_id": "1456244792",
        "repository_owner_id": "160381695",
        "actor_id": "160381695",
        "ref": "refs/heads/main",
        "workflow_ref": WORKFLOW,
        "runner_environment": "github-hosted",
    }
    if any(str(claims.get(k, "")) != value for k, value in expected.items()):
        raise HTTPException(403, "Ce workflow GitHub ne peut pas obtenir la signature Coran.")
    if claims.get("event_name") not in ("push", "workflow_dispatch"):
        raise HTTPException(403, "Déclencheur CI refusé.")


def authenticate(token):
    try:
        key = JWKS.get_signing_key_from_jwt(token).key
        claims = jwt.decode(
            token, key, algorithms=["RS256"], audience=AUDIENCE, issuer=ISSUER,
            leeway=30, options={"require": ["exp", "iat", "nbf", "sub", "iss", "aud"]}
        )
    except Exception:
        raise HTTPException(401, "Identité GitHub OIDC invalide.")
    verify_identity(claims)


async def issue_coran_signing(request):
    auth = request.headers.get("authorization", "")
    if not auth.startswith("Bearer ") or len(auth) > 20_000:
        return JSONResponse({"error": "Authentification GitHub OIDC requise"}, status_code=401)
    try:
        await asyncio.to_thread(authenticate, auth[7:])
    except HTTPException as error:
        return JSONResponse({"error": error.detail}, status_code=error.status_code)
    # Réutiliser l'identité persistante déjà stockée dans le service Render.
    # Aucune génération de nouvelle clé ni écriture de fichier durable.
    raw = os.environ.get("ANDROID_SIGNING_JSON")
    if not raw:
        return JSONResponse({"error": "Signature existante indisponible"}, status_code=503)
    try:
        data = json.loads(raw)
        if not all(isinstance(data.get(k), str) and data[k] for k in
                   ("keystoreBase64", "alias", "storePassword", "keyPassword")):
            raise ValueError("Données de signature invalides")
    except (TypeError, ValueError):
        return JSONResponse({"error": "Configuration de signature invalide"}, status_code=503)
    return JSONResponse(data, headers={"Cache-Control": "no-store", "Pragma": "no-cache"})
