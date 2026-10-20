#!/usr/bin/env python3
"""Independently verify files pulled after the signed APK's real picker flow."""
import base64, hashlib, json, pathlib
root=pathlib.Path(__file__).resolve().parent
pulled=root/'pulled'
original=(root/'sample.bin').read_bytes()
source=pulled/'Input'/'sample.bin'
encoded=list((pulled/'Encoded').rglob('sample.bin.b64'))
decoded=list((pulled/'Decoded').rglob('sample.bin'))
assert len(encoded)==1, encoded
assert len(decoded)==1, decoded
assert source.read_bytes()==original, 'Original source changed'
assert encoded[0].read_bytes()==base64.b64encode(original), 'Encoded bytes differ from Python Base64'
assert decoded[0].read_bytes()==original, 'Decoded bytes differ from source'
residuals=[str(p.relative_to(pulled)) for p in pulled.rglob('*') if p.is_file() and p.name.startswith('.easybase64-')]
assert not residuals, residuals
result={'passed':True,'source_unchanged':True,'residual_files':residuals}
for label,path in [('source',source),('encoded',encoded[0]),('decoded',decoded[0])]:
    data=path.read_bytes()
    result[label]={'path':str(path.relative_to(pulled)),'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
(root/'result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
