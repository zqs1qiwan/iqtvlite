"""Derive the validation-only shared API roots from the shipped instrumentation DEX.
Run explicitly after changing test/dependency APIs; CI independently checks the resulting pair.
"""
import argparse
from pathlib import Path
from collections import defaultdict
from dex_contract import Apk, shared

def java_type(value):
    dimensions=len(value)-len(value.lstrip('[')); value=value.lstrip('[')
    names={'V':'void','Z':'boolean','B':'byte','C':'char','S':'short','I':'int','J':'long','F':'float','D':'double'}
    return (names[value] if value in names else value[1:-1].replace('/','.'))+'[]'*dimensions

def signature(value):
    result=[]; at=1
    while value[at]!=')':
        start=at
        while value[at]=='[': at+=1
        if value[at]=='L': at=value.index(';',at)+1
        else: at+=1
        result.append(java_type(value[start:at]))
    return java_type(value[at+1:]), ','.join(result)

def generate(apk, app):
    roots={t.lstrip('[') for t in apk.types if shared(t) and t.lstrip('[') not in apk.classes}
    methods=defaultdict(set); fields=defaultdict(set)
    for owner,name,proto in apk.methods:
        if owner not in roots: continue
        result,args=signature(proto)
        methods[owner].add(f'{name}({args});' if name=='<init>' else f'{result} {name}({args});')
    for owner,name,kind in apk.fields:
        if owner in roots: fields[owner].add(f'{java_type(kind)} {name};')
    # Main-owned inherited APIs (for example ResolvableFuture.set/get) must keep their
    # actual ancestor declarations too; a child-only member rule does not cover them.
    def parents(owner, seen=None):
        seen=set() if seen is None else seen
        if owner in seen: return set()
        seen.add(owner); row=app.classes.get(owner)
        if not row: return set()
        result={p for p in [row['super'],*row['interfaces']] if p and shared(p)}
        return result | set().union(*(parents(p,seen) for p in list(result)))
    for owner in list(roots):
        for parent in parents(owner):
            methods[parent].update(m for m in methods[owner] if not m.startswith('<init>'))
            fields[parent].update(fields[owner])
    roots.update(methods.keys()); roots.update(fields.keys())
    lines=['# Validation-only ABI roots consumed by the unshrunk Android instrumentation APK.',
           '# Generated from test DEX definitions/references, not a blanket Kotlin/AndroidX keep.',
           '# Facade family member matching preserves declarations inherited from Kotlin multifile parts.',
           '# Regenerate explicitly with scripts/generate_instrumentation_keep.py after test API changes.','']
    for owner in sorted(roots):
        name=java_type(owner)
        if owner.startswith(('Lcom/cjlhll/iptv/','Ltv/iqtvlite/app/')):
            # Test assertions/serialization also call Object.equals/toString virtually outside the app graph.
            lines.append(f'-keep class {name} {{ *; }}')
            continue
        lines.append(f'-keep class {name}')
        members=methods[owner]|fields[owner]
        if members:
            pattern=name+'**' if name.endswith('Kt') else name
            lines.append('-keepclassmembers class '+pattern+' {')
            lines.extend('    '+member for member in sorted(members))
            lines.append('}')
    return '\n'.join(lines)+'\n',len(roots)

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('test_apk'); parser.add_argument('app_apk'); parser.add_argument('output'); args=parser.parse_args()
    text,count=generate(Apk(args.test_apk),Apk(args.app_apk)); Path(args.output).write_text(text,encoding='utf-8',newline='\n')
    print(f'Generated {count} shared class roots from instrumentation DEX')
