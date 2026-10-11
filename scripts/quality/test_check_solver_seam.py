import unittest

from check_solver_seam import check

GOOD = """# compiler: R8
com.metrolist.innertubex.extraction.ExtractionCipherService -> eb.q:
com.metrolist.innertubex.extraction.InnerTubeExtractor -> eb.w:
    com.metrolist.innertubex.extraction.YtConfigParser configParser -> a
    com.metrolist.innertubex.extraction.ExtractionCipherService cipherService -> cipherService
    1:5:void <init>(com.metrolist.innertubex.extraction.YtConfigParser):0:0 -> <init>
com.musicglass.personal.RemoteCipherClient -> com.musicglass.personal.b:
    1:9:java.lang.Object com.musicglass.personal.CipherBridgeInstaller$Bridge.processFormats(java.lang.String,java.util.List,kotlin.coroutines.Continuation):59:59 -> h
com.musicglass.personal.SolverService -> com.musicglass.personal.SolverService:
"""


class SolverSeamTest(unittest.TestCase):
    def test_intact_seam_passes(self):
        self.assertEqual([], check(GOOD))

    def test_renamed_field_fails(self):
        problems = check(GOOD.replace("cipherService -> cipherService", "cipherService -> b"))
        self.assertEqual(["field cipherService was renamed to b"], problems)

    def test_removed_field_fails(self):
        text = "\n".join(line for line in GOOD.splitlines() if "cipherService ->" not in line)
        self.assertEqual(["field cipherService was removed from com.metrolist.innertubex.extraction.InnerTubeExtractor"], check(text))

    def test_changed_type_fails(self):
        problems = check(GOOD.replace("ExtractionCipherService cipherService", "YouTubeCipherService cipherService"))
        self.assertEqual(["field cipherService has type com.metrolist.innertubex.extraction.YouTubeCipherService"], problems)

    def test_missing_classes_fail(self):
        self.assertEqual(["com.metrolist.innertubex.extraction.InnerTubeExtractor is not in the release build"], check("# empty\n"))
        problems = check(GOOD.replace("com.musicglass.personal.SolverService -> com.musicglass.personal.SolverService:",
                                      "com.musicglass.personal.SolverService -> com.musicglass.personal.c:"))
        self.assertEqual(["SolverService was renamed to com.musicglass.personal.c"], problems)
        problems = check("\n".join(line for line in GOOD.splitlines() if "Bridge.processFormats" not in line))
        self.assertEqual(["the bridge to the solver process is not in the release build"], problems)


if __name__ == "__main__":
    unittest.main()
