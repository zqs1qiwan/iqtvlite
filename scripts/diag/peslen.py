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
# collect PES packets, check if declared pes_len matches actual bytes accumulated before next PUSI
pes=[]
cur=None
for p in pkts:
    if pid(p)!=vpid: continue
    pl=payload(p)
    if not pl: continue
    if pusi(p):
        if cur is not None: pes.append(cur)
        # parse header
        if len(pl)>=9 and pl[0]==0 and pl[1]==0 and pl[2]==1:
            declen=(pl[4]<<8)|pl[5]
            hlen=pl[8]
            cur={'declen':declen,'hlen':hlen,'body':bytearray(pl[9+hlen:])}
        else:
            cur={'declen':-1,'hlen':-1,'body':bytearray()}
    else:
        if cur is not None: cur['body'] += pl
if cur is not None: pes.append(cur)
print('pes_count=%d' % len(pes))
bad=0; ok=0
for x in pes:
    if x['declen']==0:
        ok+=1
    else:
        actual=len(x['body'])+3  # + stream_id/flags overhead approx
        if x['declen'] <= len(x['body'])+8: ok+=1
        else: bad+=1
print('declared_len==0 (live style)=%d, declared_len>0=%d, of which declared>actual(broken)=%d' % (ok, len(pes)-ok, bad))
print('sample declen values:', [x['declen'] for x in pes[:10]])
print('sample body sizes:', [len(x['body']) for x in pes[:10]])
# video PES spread across how many TS packets
