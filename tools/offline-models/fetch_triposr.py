"""Build-only, pinned TripoSR ONNX export, weight-only INT4; no Android model downloads."""
from pathlib import Path
import hashlib, json, logging, os, shutil, time, urllib.request

ASSETS = Path('app/src/main/assets/models')
SOURCE = Path('build/offline-model-source')
REVISION = 'f0c7507db372e147d97f7d3e502d67b022a0c751'
BASE = f'https://huggingface.co/jc-builds/triposr-ios/resolve/{REVISION}/'
ORIGINAL = {
    'triposr_encoder.onnx': ('51d2b7bbee160645162b8b46f03cba3a328cfa537d1c544fba5cce826e68af6d', 3910200),
    'triposr_encoder.onnx.data': ('bf4c3f96de3553fb407bbafcbb539179d207f4f3a494e2fdaaebc38f1a30b74e', 1674575872),
    'triposr_decoder.onnx': ('90b322cae570324f1d699f7307d4f056275b3bc8564db8568a5aebe403691424', 58986),
    'triposr_decoder.onnx.data': ('9c3b1412ecbc803983003091939e449f9be3078c1c9a1880d5c5196a40a31d6c', 162816),
}
FINAL = {
    'triposr_encoder_int4.onnx': ('76dab077ff2768898ace523c72e0a017134bfd3628c4b15fd6f65cf304439089', 3422474),
    'triposr_encoder_int4.onnx.data': ('fd5be249ab455b368812ba25095a2720e561ab0dcdeb42c81d922f7f67669a00', 237861888),
    **{k:v for k,v in ORIGINAL.items() if 'decoder' in k},
}
def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b''): h.update(chunk)
    return h.hexdigest()
def valid(path, expected):
    return path.is_file() and path.stat().st_size==expected[1] and digest(path)==expected[0]
def download(name, expected):
    target=SOURCE/name
    if valid(target,expected): return target
    part=Path(str(target)+'.part');SOURCE.mkdir(parents=True,exist_ok=True)
    for attempt in range(5):
        try:
            request=urllib.request.Request(BASE+name+'?download=true',headers={'User-Agent':'Modeliseur3D-Build'})
            count=0
            with urllib.request.urlopen(request,timeout=90) as response,part.open('wb') as out:
                while chunk:=response.read(1024*1024):
                    count+=len(chunk)
                    if count>expected[1]:raise ValueError('Poids TripoSR trop volumineux.')
                    out.write(chunk)
                out.flush();os.fsync(out.fileno())
            if not valid(part,expected):raise ValueError('Empreinte des poids TripoSR incorrecte.')
            part.replace(target);return target
        except Exception:
            part.unlink(missing_ok=True)
            if attempt==4:raise
            time.sleep(2+attempt*2)

def main():
    ASSETS.mkdir(parents=True,exist_ok=True)
    if not all(valid(ASSETS/name,expected) for name,expected in FINAL.items()):
        import onnx, onnxruntime, numpy
        if (onnx.__version__,onnxruntime.__version__,numpy.__version__)!=('1.17.0','1.20.0','2.3.5'):
            raise RuntimeError('Installer tools/offline-models/requirements.txt avec Python 3.12 pour une conversion reproductible.')
        for name,expected in ORIGINAL.items():download(name,expected)
        from onnxruntime.quantization.matmul_4bits_quantizer import MatMul4BitsQuantizer
        logging.getLogger('onnxruntime.quantization.matmul_4bits_quantizer').setLevel(logging.WARNING)
        model=onnx.load(str(SOURCE/'triposr_encoder.onnx'))
        quantizer=MatMul4BitsQuantizer(model,block_size=128,is_symmetric=True,accuracy_level=4)
        quantizer.process();quantizer.model.save_model_to_file(str(ASSETS/'triposr_encoder_int4.onnx'),True)
        for name in ORIGINAL:
            if 'decoder' in name:shutil.copyfile(SOURCE/name,ASSETS/name)
    for name,expected in FINAL.items():
        if not valid(ASSETS/name,expected):raise ValueError('Export TripoSR inattendu : '+name)
        print('TripoSR embarqué, SHA-256 vérifié :',name,expected[1],'octets')
    # Keep only small pinned graph plus the final APK assets; full precision is not shipped.
    shutil.rmtree(SOURCE,ignore_errors=True)
if __name__=='__main__':main()
