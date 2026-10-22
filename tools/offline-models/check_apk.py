"""Check actual packaged bytes and the old updater's maximum before publication."""
import hashlib,sys,zipfile
from pathlib import Path
from fetch_triposr import FINAL
apk=Path(sys.argv[1])
if apk.stat().st_size>512*1024*1024:raise SystemExit('APK incompatible avec la limite de mise à jour des versions 6.0.x.')
with zipfile.ZipFile(apk) as archive:
    runtime=archive.read('lib/arm64-v8a/libonnxruntime.so')
    if b'MatMulNBits' not in runtime:raise SystemExit('Opérateur TripoSR 4 bits absent du runtime Android ARM64.')
    for name,(sha,size) in FINAL.items():
        info=archive.getinfo('assets/models/'+name)
        if info.file_size!=size:raise SystemExit('Poids absents ou tronqués : '+name)
        digest=hashlib.sha256()
        with archive.open(info) as stream:
            for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
        if digest.hexdigest()!=sha:raise SystemExit('Empreinte embarquée incorrecte : '+name)
        print('Poids IA dans APK vérifiés :',name,size)
print('APK compatible avec ancienne mise à jour :',apk.stat().st_size,'octets <= 512 Mio')
