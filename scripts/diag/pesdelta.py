import sys
path=sys.argv[1]
data=open(path,'rb').read(); n=len(data)//188
pkts=[data[i*188:(i+1)*188] for i in range(n) if data[i*188]==0x47]
def pid(p): return ((p[1]&0x1F)<<8)|p[2]
def pusi(p): return bool(p[1]&0x40)
def payload(p):
    afc=(p[3]>>4)&3; off=4
    if afc&2: off+=1+p[4]
    if not(afc&1) or off>=188: return b''
    return p[off:]
vpid=0x65
pes=[]; cur=None
for p in pkts:
    if pid(p)!=vpid: continue
    pl=payload(p)
    if not pl: continue
    if pusi(p):
        if cur is not None: pes.append(cur)
        if len(pl)>=9 and pl[0]==0 and pl[1]==0 and pl[2]==1:
            declen=(pl[4]<<8)|pl[5]; hlen=pl[8]
            cur={'declen':declen,'hlen':hlen,'body':bytearray(pl[9+hlen:])}
        else:
            cur={'declen':-1,'hlen':-1,'body':bytearray()}
    elif cur is not None:
        cur['body']+=pl
if cur is not None: pes.append(cur)
print('total pes=%d' % len(pes))
diffs=[]
for x in pes:
    if x['declen']>0:
        # declared length counts bytes after the length field: flags(2)+hdrlen+hdr+payload
        expected_total_after_len = x['declen']
        actual_after_len = 2 + x['hlen'] + len(x['body'])  # flags+hl flags(1)+hdrlen(1)... approx
        diffs.append((x['declen'], len(x['body'])))
print('nonzero declared=%d' % len(diffs))
print('first 15 (declared, body_bytes):', diffs[:15])
over = [d for d in diffs if d[0] > d[1]]
print('declared>body=%d / %d' % (len(over), len(diffs)))
# Is declared == body + small constant?
import collections
c=collections.Counter(d[0]-d[1] for d in diffs)
print('delta histogram (declared-body):', c.most_common(8))
