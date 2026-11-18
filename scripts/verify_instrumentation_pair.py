"""Check shared linkage in the exact minified app + unshrunk instrumentation pair.
This is a static class/member contract, not a substitute for device instrumentation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
from classfile_contract import Platform
from dex_contract import Apk,unresolved,shared

parser=argparse.ArgumentParser()
parser.add_argument('app_apk'); parser.add_argument('test_apk')
parser.add_argument('--platform-jar',default=str(Path(os.environ.get('ANDROID_HOME',''))/'platforms/android-36/android.jar'))
parser.add_argument('--report')
args=parser.parse_args()
app=Apk(args.app_apk); test=Apk(args.test_apk)
result=unresolved(app,test,Platform(args.platform_jar))
report={
    'app_sha256':hashlib.sha256(Path(args.app_apk).read_bytes()).hexdigest(),
    'test_sha256':hashlib.sha256(Path(args.test_apk).read_bytes()).hexdigest(),
    'app_class_definitions':len(app.classes),'test_class_definitions':len(test.classes),
    'shared_type_references':sum(shared(t) for t in test.types),
    'shared_external_type_references':sum(shared(t) and t.lstrip('[') not in test.classes for t in test.types),
    'shared_method_references':sum(shared(m[0]) and m[0] not in test.classes for m in test.methods),
    'shared_field_references':sum(shared(f[0]) and f[0] not in test.classes for f in test.fields),
    'kotlin_stdlib_definitions_main':sum(c.startswith('Lkotlin/') for c in app.classes),
    'kotlin_stdlib_definitions_test':sum(c.startswith('Lkotlin/') for c in test.classes),
    'metadata_only_annotations':'excluded explicitly; platform inheritance resolved against selected SDK stub',
    'limitations':'Reflection/dynamic loading, API28 platform compatibility and execution are not proved by static linkage.',
    **result,
}
if args.report: Path(args.report).write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if not k.startswith('missing_')},indent=2))
counts={k:len(v) for k,v in result.items()}
print(json.dumps(counts))
if any(counts.values()):
    for kind,values in result.items():
        for value in values[:8]: print(kind,value)
    raise SystemExit('Shared test runtime linkage is incomplete; do not deploy this pair')
print('Shared class/method/field runtime contract passed')
