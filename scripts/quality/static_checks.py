#!/usr/bin/env python3
"""Light static checks for the app's Kotlin sources (Q11). No dependencies; reads files only.

    python scripts/quality/static_checks.py            check, exit 1 on any finding
    python scripts/quality/static_checks.py --list     also print every site a reviewed entry covers
    python scripts/quality/static_checks.py --write-measures   record the present count of raw measures per screen file

Rules
  cancellation   A catch of Exception or Throwable, or a runCatching, around work that can suspend can swallow
                 coroutine cancellation. Each such site must rethrow it (an earlier catch of
                 CancellationException, ensureActive() or a throw in its body, or suspendCatching in place of
                 runCatching), or be on the reviewed list with the reason. "Can suspend" is read from the text:
                 a call to a function the sources declare as suspend, or to a known suspending library call.
  blocking       runBlocking is allowed only at reviewed sites.
  direction      A file is a screen file when it has @Composable or imports Compose UI, foundation, material or
                 animation. A file is a store, client or service file when it opens the database, builds an HTTP
                 client or a player, reads cookies, mints stream tokens or declares a service or worker. No file
                 may be both: screens ask a repository or the provider facade, and policies never import screens.
  lifecycle      A screen collects a flow with collectAsStateWithLifecycle, so it stops while the app is not shown.
  position       The playback position and the player's drag position change every frame. Each place a screen
                 file reads one is reviewed, so a new read in a screen's own composition is noticed.
  size           A source file over the ceiling needs an entry saying why it is one thing.
  measures       Q12: a screen takes spacing, radius and type sizes from DesignTokens.kt. The raw measures
                 (12.dp, 14.sp) each screen file still writes are counted in static_checks_measures.json. A
                 file may not have more than its count, and a file not listed may have none. A file that has
                 fewer must have its count lowered, so the number only goes down.
  motion         Feature 35: animation timing comes from GlassMotion.kt, so the whole app moves at one pace. A
                 tween() or spring() with a literal duration or stiffness, or GlassMotion.timed() with a literal
                 duration, outside GlassMotion.kt is a finding. Motion that follows a finger or the music, and
                 designed sequences such as the sign-in and startup logo, are reviewed with the reason.
  strings        Q13: words the app shows or speaks come from res/values/strings.xml, through string() or
                 quantity() (Strings.kt), so that they can be translated. A quoted literal with a word in it,
                 handed straight to something that shows or speaks it (Text, a content or state description,
                 a notification's title or text, a notice), is a finding. A word the code looks things up by
                 is shown through keyLabel(). Names that are the same in every language are reviewed.

The reviewed lists are in static_checks_reviewed.json next to this file. An entry names a file and a
fragment of the line, with a reason; it never suppresses a whole file or a whole rule. An entry that no
longer matches anything is itself a finding, so the lists cannot go stale.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "app" / "src" / "main" / "java"
REVIEWED = Path(__file__).with_name("static_checks_reviewed.json")

UI_IMPORTS = ("androidx.compose.ui.", "androidx.compose.foundation", "androidx.compose.material", "androidx.compose.animation")
INFRASTRUCTURE = ("LibraryStateDb(", "SQLiteDatabase", "SQLiteOpenHelper", "writableDatabase", "CookieManager", "AndroidStreamTokenProvider(",
                  "AndroidTokenRuntime(", "RemoteCipherClient(", "InnerTube(", "HttpClient(", "OkHttpClient", "ExoPlayer.Builder",
                  "MediaSessionService", "CoroutineWorker", "ProviderSessionManager(", "ProviderStreamResolver(",
                  "ProviderCatalogRepository(", "ProviderLibraryRepository(")
POSITION_READS = (re.compile(r"\.positionMs\b(?!\s*=[^=])"), re.compile(r"\.position\.value\b"))
SIZE_CEILING = 700
MEASURES = Path(__file__).with_name("static_checks_measures.json")
RAW_MEASURE = re.compile(r"(?<![\w.])\d+(?:\.\d+)?f?\.(?:dp|sp)\b")
TOKEN_FILE = "DesignTokens.kt"
SHOWN_LITERAL = re.compile(r'(?:\bText\(|\b(?:contentDescription|stateDescription|paneTitle)\s*=\s*|\.setContent(?:Title|Text)\(|\bonNotice\(|\bToast\.makeText\([^,]+,)\s*"((?:[^"\\$]|\\.|\$\{(?:[^{}"]|"(?:[^"\\]|\\.)*"|\{(?:[^{}"]|"(?:[^"\\]|\\.)*")*\})*\}|\$)*)"')
TEMPLATE = re.compile(r"\$\{(?:[^{}\"]|\"(?:[^\"\\]|\\.)*\"|\{(?:[^{}\"]|\"(?:[^\"\\]|\\.)*\")*\})*\}|\$\w+|\\u[0-9A-Fa-f]{4}|\\.")
WORD = re.compile(r"[^\W\d_]{2,}")
MOTION_LITERAL = re.compile(r"\b(?:tween|spring)\s*(?:<[^<>()]*>)?\s*\([^()]*?(?<![\w.])\.?\d|\bGlassMotion\.(?:timed|exit)\s*(?:<[^<>()]*>)?\s*\(\s*\d")
MOTION_FILE = "GlassMotion.kt"

SUSPENDING_LIBRARY = {"await", "awaitAll", "withContext", "withTimeout", "withTimeoutOrNull", "delay", "join", "joinAll", "receive",
                      "receiveCatching", "send", "collect", "collectLatest", "first", "firstOrNull", "withLock", "yield", "coroutineScope",
                      "supervisorScope", "bodyAsText", "cancelAndJoin", "awaitClose", "suspendCancellableCoroutine"}
# Names that are also ordinary functions on common types; a call by one of these alone says nothing.
AMBIGUOUS = {"get", "set", "run", "invoke", "close", "clear", "remove", "add", "load", "save", "read", "write", "open", "start", "stop",
             "resolve", "search", "post", "first", "firstOrNull", "send"}
SUSPEND_DECL = re.compile(r"\bsuspend\s+(?:inline\s+)?fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(")
SUSPENDING_RECEIVER = re.compile(r"\b(?:innerTube|http|extractor)\.(?!close\b)\w+\s*\(")
CALL = re.compile(r"\b(\w+)\s*[({]")

BROAD_CATCH = re.compile(r"catch\s*\(\s*\w+\s*:\s*(?:kotlin\.)?(Exception|Throwable)\s*\)\s*\{")
PRIOR_CATCH = re.compile(r"catch\s*\(\s*\w+\s*:\s*(?:[\w.]*\.)?(\w+)\s*\)\s*\{[^{}]*\}\s*$")
RUN_CATCHING = re.compile(r"\brunCatching\s*\{")
RUN_BLOCKING = re.compile(r"\brunBlocking\b\s*[({]")
TRY = re.compile(r"\btry\s*\{")


def strip_comments(text):
    """Blanks comments and string contents, keeping every offset and line break."""
    out = []
    i, n = 0, len(text)
    while i < n:
        two = text[i:i + 2]
        if two == "//":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i)); i = j
        elif two == "/*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(c if c == "\n" else " " for c in text[i:j])); i = j
        elif text[i:i + 3] == '"""':
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append("".join(c if c == "\n" else " " for c in text[i:j])); i = j
        elif text[i] == '"':
            j = i + 1
            while j < n and text[j] != '"' and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            out.append('"' + " " * (j - i - 2) + '"' if j - i >= 2 else text[i:j]); i = j
        elif text[i] == "'" and i + 2 < n and (text[i + 2] == "'" or (text[i + 1] == "\\" and text[i + 3:i + 4] == "'")):
            j = i + (4 if text[i + 1] == "\\" else 3)
            out.append(" " * (j - i)); i = j
        else:
            out.append(text[i]); i += 1
    return "".join(out)


def block(code, open_brace):
    """The text between a brace and the brace that closes it."""
    depth, i = 0, open_brace
    while i < len(code):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[open_brace + 1:i]
        i += 1
    return code[open_brace + 1:]


def cancellation_caught_before(code, offset):
    """Whether an earlier catch clause of the same try names CancellationException."""
    before = code[:offset]
    while True:
        match = PRIOR_CATCH.search(before)
        if not match:
            return False
        if match.group(1) == "CancellationException":
            return True
        before = before[:match.start()]


def may_suspend(body, suspending):
    return any(name in suspending for name in CALL.findall(body)) or SUSPENDING_RECEIVER.search(body) is not None


def line_of(code, offset):
    return code.count("\n", 0, offset) + 1


class Findings:
    def __init__(self, reviewed, measures=None):
        self.reviewed = reviewed
        # None: the measures rule is off. Otherwise the allowed count of raw measures per screen file.
        self.measures = measures
        self.counted = {}
        self.used = set()
        self.items = []
        self.covered = []

    def site(self, rule, path, line_number, line, message):
        name = path.name
        for index, entry in enumerate(self.reviewed.get(rule, [])):
            if entry["file"] == name and entry["contains"] in line:
                self.used.add((rule, index))
                self.covered.append((rule, name, line_number, entry["reason"]))
                return
        shown = path.relative_to(ROOT).as_posix() if ROOT in path.parents else name
        self.items.append("%s  %s:%d  %s\n      %s" % (rule, shown, line_number, message, line.strip()[:160]))

    def stale(self):
        for rule, entries in self.reviewed.items():
            if rule.startswith("_"):
                continue
            for index, entry in enumerate(entries):
                if (rule, index) not in self.used:
                    self.items.append("reviewed  %s: the entry for %s containing %r matches nothing; remove it" % (rule, entry["file"], entry["contains"]))


def check(path, findings, suspending):
    raw = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    lines = raw.split("\n")
    code = strip_comments(raw)
    code_lines = code.split("\n")

    for match in BROAD_CATCH.finditer(code):
        body = block(code, match.end() - 1)
        if cancellation_caught_before(code, match.start()) or "ensureActive()" in body or re.search(r"\bthrow\b", body):
            continue
        tries = [m for m in TRY.finditer(code, 0, match.start())]
        if not tries or not may_suspend(code[tries[-1].start():match.start()], suspending):
            continue
        n = line_of(code, match.start())
        findings.site("cancellation", path, n, lines[n - 1], "catch of %s around suspending work without rethrowing cancellation" % match.group(1))
    for match in RUN_CATCHING.finditer(code):
        if not may_suspend(block(code, match.end() - 1), suspending):
            continue
        n = line_of(code, match.start())
        findings.site("cancellation", path, n, lines[n - 1], "runCatching around suspending work")
    for match in RUN_BLOCKING.finditer(code):
        n = line_of(code, match.start())
        findings.site("blocking", path, n, lines[n - 1], "runBlocking")

    screen = "@Composable" in code or any(line.startswith("import ") and line[7:].strip().startswith(UI_IMPORTS) for line in lines)
    if screen:
        for n, line in enumerate(code_lines, 1):
            if line.startswith("import "):
                continue
            for token in INFRASTRUCTURE:
                if token in line:
                    findings.site("direction", path, n, lines[n - 1], "a screen file reaches %s" % token.rstrip("("))
            if ".collectAsState(" in line:
                findings.site("lifecycle", path, n, lines[n - 1], "collect with collectAsStateWithLifecycle so a stopped screen stops collecting")
            if any(pattern.search(line) for pattern in POSITION_READS):
                findings.site("position", path, n, lines[n - 1], "a per-frame value is read here")
        if findings.measures is not None and path.name != TOKEN_FILE:
            count = len(RAW_MEASURE.findall(code))
            allowed = findings.measures.get(path.name, 0)
            if count:
                findings.counted[path.name] = count
            if count > allowed:
                findings.site("measures", path, 1, path.name, "%d raw measures, %d allowed: take the new one from DesignTokens.kt" % (count, allowed))
            elif count < allowed:
                findings.site("measures", path, 1, path.name, "%d raw measures, %d allowed: lower the count in %s" % (count, allowed, MEASURES.name))
    if path.name != MOTION_FILE:
        for match in MOTION_LITERAL.finditer(code):
            n = line_of(code, match.start())
            findings.site("motion", path, n, lines[n - 1], "animation timing written here: take it from GlassMotion.kt (Feature 35)")
    for match in SHOWN_LITERAL.finditer(raw):
        # The same characters in the stripped text means the call is code, not part of a comment or a string.
        if code[match.start():match.start(1) - 1] != raw[match.start():match.start(1) - 1]:
            continue
        if WORD.search(TEMPLATE.sub(" ", match.group(1))):
            n = line_of(raw, match.start())
            findings.site("strings", path, n, lines[n - 1], "the words \"%s\" are written in the code: take them from strings.xml" % match.group(1)[:40])
    if len(lines) > SIZE_CEILING:
        findings.site("size", path, 1, path.name, "%d lines, over %d" % (len(lines), SIZE_CEILING))


def main():
    reviewed = json.loads(REVIEWED.read_text(encoding="utf-8")) if REVIEWED.exists() else {}
    measures = json.loads(MEASURES.read_text(encoding="utf-8")) if MEASURES.exists() else {}
    findings = Findings(reviewed, {name: count for name, count in measures.items() if not name.startswith("_")})
    files = sorted(SOURCES.rglob("*.kt"))
    declared = set()
    for path in files:
        declared.update(SUSPEND_DECL.findall(strip_comments(path.read_text(encoding="utf-8"))))
    suspending = (declared | SUSPENDING_LIBRARY) - AMBIGUOUS
    for path in files:
        check(path, findings, suspending)
    findings.stale()
    if "--write-measures" in sys.argv:
        note = {"_note": "Raw dp and sp measures still written in each screen file. See the measures rule in static_checks.py. Counts only go down."}
        MEASURES.write_text(json.dumps({**note, **dict(sorted(findings.counted.items()))}, indent=1) + "\n", encoding="utf-8")
        print("measures: %d files, %d raw measures recorded" % (len(findings.counted), sum(findings.counted.values())))
        return 0
    for name in sorted(set(findings.measures) - {path.name for path in files}):
        findings.items.append("measures  %s is listed in %s but is not a source file; remove it" % (name, MEASURES.name))
    if "--list" in sys.argv:
        for rule, name, line, reason in findings.covered:
            print("%s  %s:%d  %s" % (rule, name, line, reason))
    for item in findings.items:
        print(item)
    print("static checks: %d files, %d reviewed sites, %d raw measures in %d screen files, %d findings"
          % (len(files), len(findings.covered), sum(findings.counted.values()), len(findings.counted), len(findings.items)))
    return 1 if findings.items else 0


if __name__ == "__main__":
    sys.exit(main())
