import unittest

import check_registrars

MAPPING = """com.google.firebase.components.ComponentRegistrar -> com.google.firebase.components.ComponentRegistrar:
com.google.mlkit.common.internal.CommonComponentRegistrar -> com.google.mlkit.common.internal.CommonComponentRegistrar:
    void <init>() -> <init>
com.google.mlkit.nl.translate.NaturalLanguageTranslateRegistrar -> com.google.mlkit.nl.translate.NaturalLanguageTranslateRegistrar:
com.google.mlkit.nl.languageid.internal.zzg -> fk6:
"""

# rc6's usage.txt, as it was (Q18-072): the classes kept, their constructors removed.
RC6_USAGE = """com.google.mlkit.common.internal.CommonComponentRegistrar:
    public void <init>()
com.google.mlkit.common.internal.model.ModelUtils:
    private void <init>()
com.google.mlkit.nl.translate.NaturalLanguageTranslateRegistrar:
    public void <init>()
com.google.mlkit.nl.translate.R
"""


class CheckRegistrarsTest(unittest.TestCase):
    def test_rc6_lost_the_constructors_and_fails(self):
        problems = check_registrars.check(MAPPING, RC6_USAGE)
        self.assertEqual(2, len(problems))
        self.assertIn("NaturalLanguageTranslateRegistrar lost its public no-argument constructor", problems[1])

    def test_kept_constructors_pass(self):
        usage = "com.google.mlkit.common.internal.model.ModelUtils:\n    private void <init>()\n"
        self.assertEqual([], check_registrars.check(MAPPING, usage))

    def test_the_interface_itself_is_not_a_registrar_to_create(self):
        self.assertNotIn("com.google.firebase.components.ComponentRegistrar", check_registrars.registrars(MAPPING))

    def test_a_build_without_ml_kit_fails(self):
        self.assertEqual(["no ML Kit registrar is in the release build"], check_registrars.check("a.B -> c:\n", ""))


if __name__ == "__main__":
    unittest.main()
