import sys, collections

path = sys.argv[1]
data = open(path, 'rb').read()
n = len(data) // 188
pkts = [data[i*188:(i+1)*188] for i in range(n) if data[i*188] == 0x47]

def pid(p): return ((p[1] & 0x1F) << 8) | p[2]
def pusi(p): return bool(p[1] & 0x40)
def cc(p): return p[3] & 0x0F
def payload(p):
    afc = (p[3] >> 4) & 0x3
    off = 4
    if afc & 0x2:
        off += 1 + p[4]
    if not (afc & 0x1) or off >= 188:
        return b''
    return p[off:]

# PAT
pmt_pid = None
for p in pkts:
    if pid(p) == 0 and pusi(p):
        pl = payload(p)
        if len(pl) < 8: continue
        ptr = pl[0]; sec = pl[1+ptr:]
        if len(sec) < 12 or sec[0] != 0: continue
        slen = ((sec[1] & 0x0F) << 8) | sec[2]
        body = sec[8:3+slen-4]
        for i in range(0, len(body)-3, 4):
            prog = (body[i] << 8) | body[i+1]
            ppid = ((body[i+2] & 0x1F) << 8) | body[i+3]
            if prog != 0:
                pmt_pid = ppid
                break
    if pmt_pid: break

print('packets=%d pmt_pid=%s' % (len(pkts), pmt_pid))

# PMT
streams = {}
pcr_pid = None
for p in pkts:
    if pmt_pid is not None and pid(p) == pmt_pid and pusi(p):
        pl = payload(p)
        if len(pl) < 8: continue
        ptr = pl[0]; sec = pl[1+ptr:]
        if len(sec) < 12 or sec[0] != 2: continue
        slen = ((sec[1] & 0x0F) << 8) | sec[2]
        pcr_pid = ((sec[8] & 0x1F) << 8) | sec[9]
        pinfo = ((sec[10] & 0x0F) << 8) | sec[11]
        i = 12 + pinfo
        end = 3 + slen - 4
        while i + 5 <= end:
            st = sec[i]
            epid = ((sec[i+1] & 0x1F) << 8) | sec[i+2]
            es = ((sec[i+3] & 0x0F) << 8) | sec[i+4]
            streams[epid] = st
            i += 5 + es
        break

print('pcr_pid=%s' % pcr_pid)
names = {0x1B: 'H.264/AVC', 0x24: 'HEVC/H.265', 0x02: 'MPEG-2 video', 0x0F: 'AAC(ADTS)', 0x11: 'AAC(LATM)', 0x03: 'MPEG-1 audio', 0x04: 'MPEG-2 audio', 0x81: 'AC-3', 0x06: 'PES private', 0x15: 'metadata'}
for epid, st in sorted(streams.items()):
    print('es pid=0x%X(%d) stream_type=0x%02X %s' % (epid, epid, st, names.get(st, '?')))

# per-pid counts and continuity errors
cnt = collections.Counter()
lastcc = {}
ccerr = collections.Counter()
for p in pkts:
    k = pid(p)
    cnt[k] += 1
    c = cc(p)
    if k in lastcc and not ((p[3] >> 4) & 0x2):
        if c != ((lastcc[k] + 1) & 0x0F):
            ccerr[k] += 1
    lastcc[k] = c
print('top pids:', cnt.most_common(8))
print('cc_errors:', dict(ccerr))

# scan video NAL types
video_pids = [e for e, s in streams.items() if s in (0x1B, 0x24, 0x02)]
nalcnt = collections.Counter()
for vp in video_pids:
    buf = bytearray()
    for p in pkts:
        if pid(p) == vp:
            buf += payload(p)
    b = bytes(buf)
    i = 0
    while True:
        j = b.find(b'\x00\x00\x01', i)
        if j < 0 or j + 3 >= len(b): break
        h = b[j+3]
        if streams[vp] == 0x24:
            t = (h >> 1) & 0x3F
        else:
            t = h & 0x1F
        nalcnt[t] += 1
        i = j + 3
    print('video pid=0x%X bytes=%d nal_types=%s' % (vp, len(buf), dict(sorted(nalcnt.items()))))
