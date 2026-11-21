import sys

path = sys.argv[1]
data = open(path, 'rb').read()
n = len(data)//188
pkts = [data[i*188:(i+1)*188] for i in range(n) if data[i*188] == 0x47]
# video pid 101
buf = bytearray()
for p in pkts:
    if ((p[1] & 0x1F) << 8 | p[2]) == 101:
        afc = (p[3] >> 4) & 3
        off = 4
        if afc & 2:
            off += 1 + p[4]
        if (afc & 1) and off < 188:
            buf += p[off:]

class BR:
    def __init__(self, b):
        self.b = b; self.pos = 0
    def bit(self):
        v = (self.b[self.pos >> 3] >> (7 - (self.pos & 7))) & 1
        self.pos += 1
        return v
    def bits(self, k):
        v = 0
        for _ in range(k):
            v = (v << 1) | self.bit()
        return v
    def ue(self):
        z = 0
        while self.bit() == 0:
            z += 1
            if z > 32: raise ValueError('bad ue')
        if z == 0: return 0
        return (1 << z) - 1 + self.bits(z)
    def se(self):
        k = self.ue()
        return (k + 1) // 2 if k % 2 else -(k // 2)

def rbsp(b):
    out = bytearray(); i = 0
    while i < len(b):
        if i + 2 < len(b) and b[i] == 0 and b[i+1] == 0 and b[i+2] == 3:
            out += b[i:i+2]; i += 3
        else:
            out.append(b[i]); i += 1
    return bytes(out)

b = bytes(buf)
sps = None
i = 0
while True:
    j = b.find(b'\x00\x00\x01', i)
    if j < 0 or j + 4 >= len(b): break
    t = b[j+3] & 0x1F
    k = b.find(b'\x00\x00\x01', j+3)
    if k < 0: break
    if t == 7 and sps is None:
        sps = rbsp(b[j+4:k])
    i = k
print('video_bytes=%d sps_len=%s' % (len(b), len(sps) if sps else None))
if sps:
    br = BR(sps)
    profile = br.bits(8); constraints = br.bits(8); level = br.bits(8)
    sps_id = br.ue()
    chroma_format = 1
    bit_depth_luma = 8; bit_depth_chroma = 8
    if profile in (100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135):
        chroma_format = br.ue()
        if chroma_format == 3: br.bit()
        bit_depth_luma = br.ue() + 8
        bit_depth_chroma = br.ue() + 8
        br.bit()
        scaling = br.bit()
        if scaling:
            cnt = 8 if chroma_format != 3 else 12
            for _ in range(cnt):
                if br.bit():
                    size = 16 if _ < 6 else 64
                    last = 8; nxt = 8
                    for _ in range(size):
                        if nxt != 0: nxt = (last + br.se() + 256) % 256
                        last = nxt if nxt != 0 else last
    log2_max_frame_num = br.ue() + 4
    poc_type = br.ue()
    if poc_type == 0:
        br.ue()
    elif poc_type == 1:
        br.bit(); br.se(); br.se()
        for _ in range(br.ue()):
            br.se()
    max_ref = br.ue()
    gaps = br.bit()
    w_mbs = br.ue() + 1
    h_map = br.ue() + 1
    frame_mbs_only = br.bit()
    print('profile_idc=%d level_idc=%.1f sps_id=%d chroma=%d bitdepth=%d/%d' % (profile, level/10.0, sps_id, chroma_format, bit_depth_luma, bit_depth_chroma))
    print('log2_max_frame_num=%d poc_type=%d max_num_ref_frames=%d gaps=%d' % (log2_max_frame_num, poc_type, max_ref, gaps))
    print('width_mbs=%d height_map_units=%d frame_mbs_only_flag=%d -> %s' % (w_mbs, h_map, frame_mbs_only, 'progressive' if frame_mbs_only else 'INTERLACED'))
    print('approx_pixels=%dx%d' % (w_mbs*16, h_map*16*(2-frame_mbs_only)))
