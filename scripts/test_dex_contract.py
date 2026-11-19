"""In-memory negative controls for the test-owned subclass/main-owned member boundary."""
import unittest
from types import SimpleNamespace
from dex_contract import unresolved,main_member_targets
from generate_instrumentation_keep import generate

PARENT='Lkotlin/Parent;'
CHILD='Lfixture/Child;'
OBJECT='Ljava/lang/Object;'
METHOD=('required','()V')
FIELD=('requiredField','I')

def row(parent=None,methods=(),fields=(),interfaces=()):
    return {'super':parent,'interfaces':list(interfaces),'methods':set(methods),'fields':set(fields)}

def fixture():
    app=SimpleNamespace(classes={PARENT:row(OBJECT)})
    test=SimpleNamespace(classes={CHILD:row(PARENT)},types={CHILD,PARENT},
                         methods={(CHILD,*METHOD)},fields={(CHILD,*FIELD)})
    return app,test

class Platform:
    def get(self,owner): return row() if owner==OBJECT else None

class CrossBoundaryContractTest(unittest.TestCase):
    def test_missing_main_members_cannot_hide_behind_test_owned_child(self):
        app,test=fixture(); result=unresolved(app,test,Platform())
        self.assertEqual([],result['missing_types'])
        self.assertEqual([(CHILD,*METHOD)],result['missing_methods'])
        self.assertEqual([(CHILD,*FIELD)],result['missing_fields'])
    def test_restored_main_members_resolve_through_child(self):
        app,test=fixture(); app.classes[PARENT]=row(OBJECT,[METHOD],[FIELD])
        self.assertFalse(any(unresolved(app,test,Platform()).values()))
    def test_child_override_is_valid_but_does_not_mask_missing_inherited_field(self):
        app,test=fixture(); test.classes[CHILD]['methods'].add(METHOD)
        result=unresolved(app,test,Platform())
        self.assertEqual([],result['missing_methods'])
        self.assertEqual([(CHILD,*FIELD)],result['missing_fields'])
    def test_generator_roots_the_main_declaration_for_child_symbol(self):
        app,test=fixture()
        self.assertIn(PARENT,main_member_targets(app,test,CHILD,METHOD,'methods'))
        text,_=generate(test,app)
        self.assertIn('void required();',text)
        self.assertIn('int requiredField;',text)
        self.assertIn('-keepclassmembers class kotlin.Parent',text)
    def test_program_owned_namespace_outside_curated_prefixes_is_checked(self):
        app,test=fixture(); custom='Ljavax/inject/Provider;'
        app.classes={custom:row(OBJECT)}; test.classes[CHILD]=row(custom); test.types={CHILD,custom}
        self.assertEqual([(CHILD,*METHOD)],unresolved(app,test,Platform())['missing_methods'])
        self.assertIn(custom,main_member_targets(app,test,CHILD,METHOD,'methods'))
    def test_platform_stub_is_not_misclassified_as_main_ancestor(self):
        app,test=fixture(); sdk='Landroid/app/AppComponentFactory;'
        app.classes={sdk:row(OBJECT)}; test.classes[CHILD]=row(sdk); test.types={CHILD,sdk}
        test.methods={(CHILD,*METHOD)}; test.fields=set()
        self.assertFalse(any(unresolved(app,test,Platform()).values()))
        self.assertEqual(set(),main_member_targets(app,test,CHILD,METHOD,'methods'))
    def test_test_interface_bridge_to_main_is_checked(self):
        app,test=fixture(); test.classes[CHILD]=row(OBJECT,interfaces=[PARENT])
        self.assertEqual([(CHILD,*METHOD)],unresolved(app,test,Platform())['missing_methods'])

if __name__=='__main__': unittest.main()
