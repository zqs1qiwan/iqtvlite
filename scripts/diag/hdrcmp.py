import sys
for path in sys.argv[1:]:
    data=open(path,'rb').read(); n=len(data)//188
    pkts=[data[i*188:(i+1)*188] for i in range(n) if data[i*188]==0x47]
    def pid(p): return ((p[1]&0x1F)<<8)|p[2]
    def pusi(p): return bool(p[1]&0x40)
    def payload(p):
        afc=(p[3]>>4)&3; off=4
        if afc&2: off+=1+p[4]
        if not(afc&1) or off>=188: return b''
        return p[off:]
    vpid = 0x65 if 'jingcai' in path else 0x1011
    hdrlens=[]
    for p in pkts:
        if pid(p)!=vpid or not pusi(p): continue
        pl=payload(p)
        if len(pl)<9 or pl[0] or pl[1] or pl[2]!=1: continue
        hdrlens.append((pl[8], pl[7]))
    print('===',path.split('\\')[-1], 'PES_with_PUSI=%d' % len(hdrlens))
    import collections
    print('  header_data_length histogram:', collections.Counter(h for h,_ in hdrlens).most_common(6))
    print('  PES flags histogram:', collections.Counter(f for _,f in hdrlens).most_common(6))
