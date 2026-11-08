import unittest
import check_release_content as content


class PreviewContentTest(unittest.TestCase):
    def test_both_preview_labels_are_distinct_and_checked_in_all_locales(self):
        for edition, name in (("riff", "Riff Preview"), ("plus", "Riff+ Preview")):
            valid = f"application-label:'{name}'\napplication-label-fr:'{name}'"
            self.assertTrue(all(ok for ok, _ in content.check_edition_label(valid, edition, "preview")))
            for bad in ("", valid + "\napplication-label-ja:'Riff'", valid.replace(" Preview", "")):
                self.assertFalse(all(ok for ok, _ in content.check_edition_label(bad, edition, "preview")))
            self.assertFalse(all(ok for ok, _ in content.check_edition_label(valid, edition, "release")))



if __name__ == "__main__":
    unittest.main()
