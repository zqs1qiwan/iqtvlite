import sys

path = sys.argv[1]
data = open(path, 'rb').read()
n = len(data)//188
pkts = [data[i*188:(i+1)*188] for i in range(n) if data[i*188] == 0x47]

def pid(p): return ((p[1] & 0x1F) << 8) | p[2]
def pusi(p): return bool(p[1] & 0x40)
def payload(p):
    afc = (p[3] >> 4) & 3
    off = 4
    if afc & 2:
        off += 1 + p[4]
    if not (afc & 1) or off >= 188:
        return b''
    return p[off:]

def parse_pts(b):
    # b: 5 bytes PTS
    if len(b) < 5: return None
    if not (b[0] & 0x01): return None
    p = ((b[0] >> 1) & 0x07) << 30
    p |= (b[1] << 22)
    p |= ((b[2] >> 1) << 15)
    p |= (b[3] << 7)
    p |= (b[4] >> 1)
    return p

for target, name in ((101, 'video'), (102, 'audio')):
    starts = []
    for p in pkts:
        if pid(p) != target or not pusi(p): continue
        pl = payload(p)
        if len(pl) < 14: continue
        if pl[0] != 0 or pl[1] != 0 or pl[2] != 1: continue
        flags = pl[7]
        hlen = pl[8]
        if flags & 0x80:
            pts = parse_pts(pl[9:14])
            dts = parse_pts(pl[14:19]) if (flags & 0x40) else None
            starts.append((pts, dts, pl[3], pl[4]))
    print('%s PES count=%d' % (name, len(starts)))
    if starts:
        ptss = [s[0] for s in starts if s[0] is not None]
        print('  with_pts=%d  first=%s last=%s' % (len(ptss), ptss[0] if ptss else None, ptss[-1] if ptss else None))
        if len(ptss) >= 2:
            deltas = [(ptss[i+1]-ptss[i])/90.0 for i in range(len(ptss)-1)]
            deltas = [d for d in deltas if -10000 < d < 10000]
            if deltas:
                deltas_sorted = sorted(deltas)
                print('  delta_ms min=%.1f p50=%.1f max=%.1f' % (deltas_sorted[0], deltas_sorted[len(deltas_sorted)//2], deltas_sorted[-1]))
                neg = sum(1 for d in deltas if d < 0)
                print('  negative_deltas=%d (wrap/discontinuity)' % neg)
            # stream ids
            print('  stream_ids=%s' % sorted({(s[2], s[3]) for s in starts})[:5])
        # PTS vs DTS
        with_dts = [s for s in starts if s[1] is not None]
        if with_dts:
            d = (with_dts[0][0] - with_dts[0][1]) / 90.0
            print('  first pts-dts=%.1f ms' % d)
        else:
            print('  no DTS present')
