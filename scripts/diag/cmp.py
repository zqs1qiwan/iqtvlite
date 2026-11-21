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
    # PAT
    pmt=None
    for p in pkts:
        if pid(p)==0 and pusi(p):
            pl=payload(p)
            if len(pl)<8: continue
            ptr=pl[0]; sec=pl[1+ptr:]
            if len(sec)<12 or sec[0]!=0: continue
            slen=((sec[1]&0x0F)<<8)|sec[2]; body=sec[8:3+slen-4]
            for i in range(0,len(body)-3,4):
                prog=(body[i]<<8)|body[i+1]; pp=((body[i+2]&0x1F)<<8)|body[i+3]
                if prog: pmt=pp; break
        if pmt: break
    # PMT descriptors
    vpid=None; desc_info=''
    for p in pkts:
        if pmt and pid(p)==pmt and pusi(p):
            pl=payload(p)
            if len(pl)<8: continue
            ptr=pl[0]; sec=pl[1+ptr:]
            if len(sec)<12 or sec[0]!=2: continue
            slen=((sec[1]&0x0F)<<8)|sec[2]
            pinfo=((sec[10]&0x0F)<<8)|sec[11]
            i=12+pinfo; end=3+slen-4
            while i+5<=end:
                st=sec[i]; epid=((sec[i+1]&0x1F)<<8)|sec[i+2]; es=((sec[i+3]&0x0F)<<8)|sec[i+4]
                if st in (0x1B,0x24): vpid=epid
                descs=sec[i+5:i+5+es]
                desc_info += ' pid=0x%X st=0x%02X es=%d descs=%s' % (epid,st,es,descs.hex())
                i+=5+es
            break
    # first video PES header details
    cnt=0; first=None
    for p in pkts:
        if vpid and pid(p)==vpid and pusi(p):
            pl=payload(p)
            if len(pl)<9: continue
            if pl[0]==0 and pl[1]==0 and pl[2]==1:
                cnt+=1
                if first is None:
                    stream_id=pl[3]; pes_len=(pl[4]<<8)|pl[5]
                    flags=pl[6]; hlen=pl[7]
                    first=(stream_id,pes_len,flags,hlen,pl[8:8+hlen].hex())
        if cnt>=3: break
    print('===', path)
    print('  pmt_pid=%s video_pid=0x%X video_pes_count_first3=%d' % (pmt, vpid or 0, cnt))
    print('  descriptors:%s' % desc_info)
    print('  first_video_pes stream_id=0x%02X pes_len=%d flags=0x%02X hdr_len=%d hdr=%s' % first if first else '  none')
