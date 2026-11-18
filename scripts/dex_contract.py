"""Read DEX class/member tables; no target execution or substring-presence guesses."""
import struct
from zipfile import ZipFile

class Dex:
    def __init__(self, data):
        self.data=data
        assert data[:4]==b'dex\n', 'Unsupported DEX format'
        def u4(at): return struct.unpack_from('<I',data,at)[0]
        def u2(at): return struct.unpack_from('<H',data,at)[0]
        ns,os=u4(56),u4(60)
        self.strings=[]
        for i in range(ns):
            at=u4(os+4*i); _,at=self.uleb(at)
            self.strings.append(data[at:data.index(b'\0',at)].decode('utf-8',errors='replace'))
        nt,ot=u4(64),u4(68)
        self.types=[self.strings[u4(ot+4*i)] for i in range(nt)]
        def type_list(at): return [] if not at else [self.types[u2(at+4+2*i)] for i in range(u4(at))]
        np,op=u4(72),u4(76)
        protos=['('+''.join(type_list(u4(op+12*i+8)))+')'+self.types[u4(op+12*i+4)] for i in range(np)]
        nf,of=u4(80),u4(84)
        self.fields=[(self.types[u2(of+8*i)],self.strings[u4(of+8*i+4)],self.types[u2(of+8*i+2)]) for i in range(nf)]
        nm,om=u4(88),u4(92)
        self.methods=[(self.types[u2(om+8*i)],self.strings[u4(om+8*i+4)],protos[u2(om+8*i+2)]) for i in range(nm)]
        nc,oc=u4(96),u4(100)
        self.classes={}
        for i in range(nc):
            at=oc+32*i; name=self.types[u4(at)]; parent=u4(at+8)
            row={'super': None if parent==0xffffffff else self.types[parent], 'interfaces':type_list(u4(at+12)), 'methods':set(), 'fields':set()}
            self.classes[name]=row
            cursor=u4(at+24)
            if not cursor: continue
            counts=[]
            for _ in range(4): value,cursor=self.uleb(cursor); counts.append(value)
            for count in counts[:2]:
                index=0
                for _ in range(count):
                    delta,cursor=self.uleb(cursor); index+=delta
                    _,cursor=self.uleb(cursor)
                    row['fields'].add(self.fields[index][1:])
            for count in counts[2:]:
                index=0
                for _ in range(count):
                    delta,cursor=self.uleb(cursor); index+=delta
                    _,cursor=self.uleb(cursor); _,cursor=self.uleb(cursor)
                    row['methods'].add(self.methods[index][1:])
    def uleb(self, at):
        result=shift=0
        for _ in range(5):
            value=self.data[at]; at+=1; result|=(value&127)<<shift
            if value<128: return result,at
            shift+=7
        raise ValueError('Invalid ULEB128')

class Apk:
    def __init__(self,path):
        self.classes={}; self.types=set(); self.methods=set(); self.fields=set()
        with ZipFile(path) as z:
            for name in z.namelist():
                if not name.endswith('.dex'): continue
                dex=Dex(z.read(name)); self.classes.update(dex.classes)
                self.types.update(dex.types); self.methods.update(dex.methods); self.fields.update(dex.fields)

SHARED=('Lkotlin/','Lkotlinx/','Lcom/cjlhll/iptv/','Ltv/iqtvlite/app/','Lokhttp3/','Lokio/','Landroidx/','Lcom/google/','Lfi/iki/elonen/','Lorg/jetbrains/')
METADATA=('Landroidx/annotation/','Lorg/jetbrains/annotations/','Lcom/google/errorprone/annotations/',
          'Lkotlin/jvm/JvmField;','Lkotlin/jvm/JvmName;','Lkotlin/jvm/JvmOverloads;','Lkotlin/jvm/JvmStatic;',
          'Lkotlin/jvm/internal/SourceDebugExtension;','Landroidx/compose/runtime/internal/StabilityInferred;')
def shared(name):
    name=name.lstrip('[')
    return name.startswith(SHARED) and not name.startswith(METADATA)

def unresolved(app,test,platform=None):
    definitions=app.classes|test.classes
    def find(owner,signature,kind,seen=None):
        seen=set() if seen is None else seen
        if owner.startswith('['):
            return kind=='methods' and (signature==('clone','()Ljava/lang/Object;') or find('Ljava/lang/Object;',signature,kind,seen))
        if owner in seen: return False
        seen.add(owner)
        row=definitions.get(owner)
        if row is None and platform is not None and owner.startswith(('Ljava/','Landroid/','Ljavax/','Lorg/xml/','Lorg/w3c/')):
            row=platform.get(owner)
        if row is None: return False
        if signature in row[kind]: return True
        if signature[0]=='<init>': return False
        return any(find(parent,signature,kind,seen) for parent in [row['super'],*row['interfaces']] if parent)
    missing_types=sorted(t for t in test.types if shared(t) and t.lstrip('[') not in definitions)
    missing_methods=sorted(m for m in test.methods if shared(m[0]) and m[0] not in test.classes and not find(m[0],m[1:],'methods'))
    missing_fields=sorted(f for f in test.fields if shared(f[0]) and f[0] not in test.classes and not find(f[0],f[1:],'fields'))
    return {'missing_types':missing_types,'missing_methods':missing_methods,'missing_fields':missing_fields}
