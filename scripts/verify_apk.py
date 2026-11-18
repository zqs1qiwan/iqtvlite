"""Validate shipped native ABI identity and required JNI symbol names, not just filenames."""
import hashlib
import json
import struct
import sys
from pathlib import Path
from zipfile import ZipFile

path = Path(sys.argv[1])
max_bytes = 14454072  # floor(13,140,066 * 1.10), same universal optimized-test ABI set.
assert path.stat().st_size <= max_bytes, f'APK size {path.stat().st_size} exceeds {max_bytes}; report variant/dependency/resource deltas'
required = [b'Java_tv_iqtvlite_app_RustBridge_' + name for name in [b'version', b'parseM3u', b'stableId', b'parseXmltvFile']]
report = {'apk': path.name, 'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'native': []}
with ZipFile(path) as apk:
    dex = b''.join(apk.read(name) for name in apk.namelist() if name.endswith('.dex'))
    for descriptor in [b'Ltv/iqtvlite/app/RustBridge;', b'Lcom/cjlhll/iptv/EpgData;', b'Lcom/cjlhll/iptv/EpgProgram;', b'Lcom/cjlhll/iptv/SplashActivity;']:
        assert descriptor in dex, f'R8 removed/renamed required class {descriptor.decode()}'
    report['required_dex_classes'] = 'present (runtime tests still required)'
    for abi, elf_class, machine in [('armeabi-v7a', 1, 40), ('arm64-v8a', 2, 183)]:
        data = apk.read(f'lib/{abi}/libiqtvlite_capi.so')
        assert data[:4] == b'\x7fELF' and data[4] == elf_class, f'Invalid ELF class for {abi}'
        assert struct.unpack_from('<H', data, 18)[0] == machine, f'Wrong machine for {abi}'
        for symbol in required:
            assert symbol + b'\0' in data, f'{abi} missing {symbol.decode()}'
        report['native'].append({'abi': abi, 'bytes': len(data), 'jni_symbols': 'present'})
print(json.dumps(report, indent=2))
