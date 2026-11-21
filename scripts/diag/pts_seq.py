import sys
path = sys.argv[1]
data = open(path,'rb').read()
n=len(data)//188
pkts=[data[i*188:(i+1)*188] for i in range(n) if data[i*188]==0x47]
def pid(p): return ((p[1]&0x1F)<<8)|p[2]
def pusi(p): return bool(p[1]&0x40)
def payload(p):
    afc=(p[3]>>4)&3; off=4
    if afc&2: off+=1+p[4]
    if not(afc&1) or off>=188: return b''
    return p[off:]
def pts(b):
    if len(b)<5 or not(b[0]&1): return None
    return (((b[0]>>1)&7)<<30)|(b[1]<<22)|((b[2]>>1)<<15)|(b[3]<<7)|(b[4]>>1)
seq=[]
for p in pkts:
    if pid(p)!=101 or not pusi(p): continue
    pl=payload(p)
    if len(pl)<14 or pl[0] or pl[1] or pl[2]!=1: continue
    if pl[7]&0x80:
        seq.append(pts(pl[9:14]))
seq=[s for s in seq if s]
print('video PTS first 40 deltas (ms):')
d=[(seq[i+1]-seq[i])/90.0 for i in range(len(seq)-1)]
print([round(x) for x in d[:40]])
print('all deltas sorted unique:', sorted(set(round(x) for x in d)))
# check audio-video interleave
print('video pts span ms=', (seq[-1]-seq[0])/90.0, 'count=', len(seq))
