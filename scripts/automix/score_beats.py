"""Score Riff's beat tracker against reference marks on an Automix test set (engine report 6.1).

Usage: python scripts/automix/score_beats.py <set.json> <pcm folder> <marks.json> <report.md>

Reads windows.json and riff-beats.json from the pcm folder (extract_windows.py, AutomixSetEvaluation) and the
reference marks (mark_beats.py). Each song's head (first 30 s) and tail (last 45 s) is scored on its own:
- tempo Acc1: within 4 % of the reference tempo (60 / median gap between the reference beats in the window);
- tempo Acc2: Acc1, also allowing x2, x1/2, x3, x1/3;
- beat and downbeat F-measure: a mark matched one to one within 70 ms.
A window whose reference has fewer than 8 beats counts as "no beat": Riff should not be sure of one there.
"""
import json
import os
import statistics
import sys

TOLERANCE_S = 0.070
HEAD_S, TAIL_S = 30.0, 45.0
# SmartTransition's gate for moving a blend onto the beat.
BEAT_SURE, DOWNBEAT_SURE = 0.35, 0.30


def f_measure(reference, estimate):
    if not reference and not estimate:
        return 1.0
    if not reference or not estimate:
        return 0.0
    used, hits = set(), 0
    for r in reference:
        best = None
        for i, e in enumerate(estimate):
            if i not in used and abs(e - r) <= TOLERANCE_S and (best is None or abs(e - r) < abs(estimate[best] - r)):
                best = i
        if best is not None:
            used.add(best)
            hits += 1
    precision, recall = hits / len(estimate), hits / len(reference)
    return 0.0 if hits == 0 else 2 * precision * recall / (precision + recall)


def tempo_match(reference_bpm, bpm, factors=(1.0,)):
    return any(abs(bpm - reference_bpm * f) <= 0.04 * reference_bpm * f for f in factors)


def main(set_path, pcm, marks_path, report_path):
    songs = json.load(open(set_path, encoding="utf-8"))
    windows = {w["n"]: w for w in json.load(open(os.path.join(pcm, "windows.json"), encoding="utf-8"))}
    marks = {m["n"]: m for m in json.load(open(marks_path, encoding="utf-8"))}
    riff = {(r["n"], r["part"]): r for r in json.load(open(os.path.join(pcm, "riff-beats.json"), encoding="utf-8"))}
    rows = []
    for n, song in enumerate(songs):
        w, m = windows[n], marks[n]
        for part, start, length in (("head", 0.0, HEAD_S), ("tail", w["tailStartMs"] / 1000, TAIL_S)):
            end = start + length
            ref = [b for b in m["beats"] if start <= b < end]
            ref_down = [d for d in m["downbeats"] if start <= d < end]
            r = riff[(n, part)]
            est = [b / 1000 for b in r.get("beats", [])]
            est_down = [d / 1000 for d in r.get("downbeats", [])]
            sure = r.get("confidence", 0) >= BEAT_SURE
            sure_down = sure and r.get("downbeatConfidence", 0) >= DOWNBEAT_SURE
            row = dict(n=n, part=part, group=song["group"], genre=song["genre"], file=song["file"], sure=sure,
                       sure_down=sure_down, bpm=r.get("bpm"), confidence=r.get("confidence", 0),
                       downbeat_confidence=r.get("downbeatConfidence", 0), ms=r["ms"], beat_free=len(ref) < 8)
            if not row["beat_free"]:
                ref_bpm = 60 / statistics.median(b - a for a, b in zip(ref, ref[1:]))
                row.update(ref_bpm=ref_bpm, beat_f=f_measure(ref, est), down_f=f_measure(ref_down, est_down),
                           acc1=r.get("bpm") is not None and tempo_match(ref_bpm, r["bpm"]),
                           acc2=r.get("bpm") is not None and tempo_match(ref_bpm, r["bpm"], (1, 2, .5, 3, 1 / 3)))
            rows.append(row)
    write_report(rows, report_path)
    json.dump(rows, open(os.path.splitext(report_path)[0] + ".json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)


def share(rows, key):
    return sum(1 for r in rows if r[key]) / len(rows) if rows else float("nan")


def mean(rows, key):
    return statistics.mean(r[key] for r in rows) if rows else float("nan")


def write_report(rows, path):
    lines = ["# Automix stage 2 on the test set", "",
             "Reference: Beat This! final0 (no DBN). Each song's first 30 s and last 45 s are scored on their own.", "",
             "| Group | Windows | Tempo Acc1 | Tempo Acc2 | Beat F | Downbeat F | Riff sure of the beat | Sure and right (Acc1, beat F >= 0.85) |",
             "|---|---|---|---|---|---|---|---|"]
    for group in ("steady", "hard"):
        g = [r for r in rows if r["group"] == group and not r["beat_free"]]
        sure = [r for r in g if r["sure"]]
        right = [r for r in sure if r["acc1"] and r["beat_f"] >= 0.85]
        lines.append(f"| {group} | {len(g)} | {share(g, 'acc1'):.0%} | {share(g, 'acc2'):.0%} | {mean(g, 'beat_f'):.2f} | "
                     f"{mean(g, 'down_f'):.2f} | {len(sure)} | {len(right)} of {len(sure)} |")
    free = [r for r in rows if r["beat_free"]]
    lines += ["", f"Windows with no beat in the reference: {len(free)}; Riff sure of a beat in {sum(r['sure'] for r in free)} of them.",
              f"Riff's time per window on this PC: median {statistics.median(r['ms'] for r in rows)} ms.", "",
              "Targets (report 6.1, steady-beat songs): Acc1 >= 85 %, Acc2 >= 95 %, beat F >= 0.85, downbeat F >= 0.70.", "",
              "| # | Part | Group | Genre | Ref BPM | Riff BPM | Sure | Acc1 | Acc2 | Beat F | Downbeat F | Song |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        if r["beat_free"]:
            lines.append(f"| {r['n']} | {r['part']} | {r['group']} | {r['genre']} | none | {fmt(r['bpm'])} | {yes(r['sure'])} | | | | | {r['file']} |")
        else:
            lines.append(f"| {r['n']} | {r['part']} | {r['group']} | {r['genre']} | {r['ref_bpm']:.1f} | {fmt(r['bpm'])} | {yes(r['sure'])} | "
                         f"{yes(r['acc1'])} | {yes(r['acc2'])} | {r['beat_f']:.2f} | {r['down_f']:.2f} | {r['file']} |")
    open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")


def fmt(bpm):
    return "-" if bpm is None else f"{bpm:.1f}"


def yes(flag):
    return "yes" if flag else "no"


if __name__ == "__main__":
    main(*sys.argv[1:5])
