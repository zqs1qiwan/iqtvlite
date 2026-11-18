"""Recompress PNG IDAT losslessly while preserving every non-IDAT chunk verbatim."""
import struct
import sys
import zlib
from pathlib import Path

for name in sys.argv[1:]:
    path = Path(name)
    original = path.read_bytes()
    assert original[:8] == b'\x89PNG\r\n\x1a\n'
    chunks = []
    offset = 8
    while offset < len(original):
        length = struct.unpack_from('>I', original, offset)[0]
        kind = original[offset+4:offset+8]
        data = original[offset+8:offset+8+length]
        chunks.append((kind, data, original[offset:offset+12+length]))
        offset += 12+length
    raw = zlib.decompress(b''.join(data for kind, data, _ in chunks if kind == b'IDAT'))
    compressed = zlib.compress(raw, 9)
    replaced = False
    output = bytearray(original[:8])
    for kind, data, chunk in chunks:
        if kind == b'IDAT':
            if not replaced:
                output += struct.pack('>I', len(compressed)) + b'IDAT' + compressed + struct.pack('>I', zlib.crc32(b'IDAT'+compressed))
                replaced = True
        else:
            output += chunk
    assert zlib.decompress(compressed) == raw
    if len(output) < len(original):
        path.write_bytes(output)
    print(f'{path.name}: {len(original)} -> {min(len(original),len(output))} bytes; filtered pixel data and all metadata unchanged')
