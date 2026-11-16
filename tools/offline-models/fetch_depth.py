"""Build-time only. The APK embeds the weights; Android never downloads them."""
from pathlib import Path
import hashlib, os, time, urllib.request

MODEL = Path('app/src/main/assets/models/depth_anything_v2_small_fp32.onnx')
SHA = 'afb6a5c28f3b6bf1618c6e43f02073ef9dfdc70e937502d51603e57b0a1df10c'
URL = 'https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/64fe43eba7f8a384b02fe3fadaa26cbba35548d8/onnx/model.onnx?download=true'

def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''): result.update(chunk)
    return result.hexdigest()

if MODEL.is_file() and digest(MODEL) == SHA:
    print('Depth Anything V2 Small embarqué : SHA-256 vérifié.')
else:
    MODEL.parent.mkdir(parents=True, exist_ok=True)
    part = Path(str(MODEL) + '.part')
    for attempt in range(5):
        try:
            count = 0
            request = urllib.request.Request(URL, headers={'User-Agent': 'Modeliseur3D-Build'})
            with urllib.request.urlopen(request, timeout=90) as source, part.open('wb') as output:
                while chunk := source.read(1024 * 1024):
                    count += len(chunk)
                    if count > 110_000_000: raise ValueError('Modèle trop volumineux.')
                    output.write(chunk)
                output.flush(); os.fsync(output.fileno())
            if digest(part) != SHA: raise ValueError('Empreinte du modèle de profondeur incorrecte.')
            part.replace(MODEL)
            print('Depth Anything V2 Small embarqué :', MODEL.stat().st_size, 'octets, SHA-256 vérifié.')
            break
        except Exception:
            part.unlink(missing_ok=True)
            if attempt == 4: raise
            time.sleep(2 + attempt * 2)
