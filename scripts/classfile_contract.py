"""Platform class/member lookup from the selected Android SDK stub JAR."""
import struct
from zipfile import ZipFile

def read_class(data):
    at=8
    def u2():
        nonlocal at
        value=struct.unpack_from('>H',data,at)[0]; at+=2; return value
    def u4():
        nonlocal at
        value=struct.unpack_from('>I',data,at)[0]; at+=4; return value
    count=u2(); cp=[None]*count; index=1
    while index<count:
        tag=data[at]; at+=1
        if tag==1:
            size=u2(); cp[index]=data[at:at+size].decode('utf-8',errors='replace'); at+=size
        elif tag==7: cp[index]=u2()
        elif tag in (3,4,9,10,11,12,17,18): at+=4
        elif tag in (5,6): at+=8; index+=1
        elif tag in (8,16,19,20): at+=2
        elif tag==15: at+=3
        else: raise ValueError(f'Unsupported class constant tag {tag}')
        index+=1
    def descriptor(index): return 'L'+cp[cp[index]]+';' if index else None
    u2(); u2(); parent=descriptor(u2()); interfaces=[descriptor(u2()) for _ in range(u2())]
    def members():
        nonlocal at
        result=set()
        for _ in range(u2()):
            u2(); name=cp[u2()]; signature=cp[u2()]; result.add((name,signature))
            for _ in range(u2()): u2(); size=u4(); at+=size
        return result
    fields=members(); methods=members()
    return {'super':parent,'interfaces':interfaces,'methods':methods,'fields':fields}

class Platform:
    def __init__(self,path): self.zip=ZipFile(path); self.cache={}
    def get(self,owner):
        if owner not in self.cache:
            name=owner[1:-1]+'.class'
            try: self.cache[owner]=read_class(self.zip.read(name))
            except KeyError: self.cache[owner]=None
        return self.cache[owner]
