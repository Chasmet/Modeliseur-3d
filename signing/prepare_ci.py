"""Fetch the existing keystore over TLS using the native GitHub Actions OIDC identity."""
import base64
import json
import os
from pathlib import Path
import urllib.request
import urllib.parse
import urllib.error
import time

url = 'https://modeliseur-trellis-mcp.onrender.com/ci/signing'
request_url = os.environ['ACTIONS_ID_TOKEN_REQUEST_URL'] + '&audience=' + urllib.parse.quote(url, safe='')
req = urllib.request.Request(request_url, headers={'Authorization': 'Bearer ' + os.environ['ACTIONS_ID_TOKEN_REQUEST_TOKEN']})
with urllib.request.urlopen(req, timeout=45) as response:
    identity = json.load(response)['value']
req = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + identity})
for attempt in range(18):
    try:
        with urllib.request.urlopen(req, timeout=90) as response:
            signing = json.load(response)
        break
    except urllib.error.HTTPError as error:
        if error.code not in (404, 503) or attempt == 17: raise
        time.sleep(10)
folder = Path('.ci-signing'); folder.mkdir(exist_ok=True)
keystore = folder / 'debug.keystore'; keystore.write_bytes(base64.b64decode(signing['keystoreBase64'], validate=True)); keystore.chmod(0o600)
# The current preserved key is the V6.0.0 debug signer. Refuse an unexpected identity.
if signing['alias'] != 'androiddebugkey' or signing['storePassword'] != 'android' or signing['keyPassword'] != 'android':
    raise RuntimeError('Identité de signature inattendue ; aucune APK publiée.')
print('Signature existante récupérée via GitHub OIDC.')
