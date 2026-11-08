"""A copy of Riff+'s fitAutoEq (AutoEq.kt) and sectionGainDb (EqualizerCurve.kt), for fitting the bundled list off the phone. PhaseFiveTest checks the two agree."""
import math
PROBES = [20.0 * 1000.0 ** (i / 63.0) for i in range(64)]
MANUAL_F = [60.0, 150.0, 400.0, 1000.0, 2500.0, 6000.0, 14000.0]
JUDGED = [i for i, f in enumerate(PROBES) if f <= 10000.0]

def gain_db(shape, fc, q, g, f, sr):
    if g == 0: return 0.0
    ny = sr / 2.0; fc = min(fc, ny * .99); f = min(f, ny * .999)
    w = math.tan(math.pi * f / sr) / math.tan(math.pi * fc / sr)
    a = 10 ** (g / 40.0); w2 = w * w
    if shape == 'P': nr, ni, dr, di = 1 - w2, w * a / q, 1 - w2, w / (a * q)
    elif shape == 'L': nr, ni, dr, di = a * (a - w2), a * w * math.sqrt(a) / q, 1 - a * w2, w * math.sqrt(a) / q
    else: nr, ni, dr, di = a * (1 - a * w2), a * w * math.sqrt(a) / q, a - w2, w * math.sqrt(a) / q
    return 20 * math.log10(math.hypot(nr, ni) / math.hypot(dr, di))

def curve(sections, sr=48000):
    return [sum(gain_db(s, fc, q, g, f, sr) for s, fc, q, g in sections) for f in PROBES]

def manual(gains, sr=48000):
    secs = [('L' if i == 0 else 'H' if i == 6 else 'P', MANUAL_F[i], 1.0, max(-12.0, min(12.0, gains[i]))) for i in range(7)]
    return curve(secs, sr)

def error(c, t):
    off = sum(t[i] - c[i] for i in JUDGED) / len(JUDGED)
    return math.sqrt(sum((c[i] + off - t[i]) ** 2 for i in JUDGED) / len(JUDGED))

def solve(a, b):
    n = len(b); m = [a[i][:] + [b[i]] for i in range(n)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(m[r][c]))
        if abs(m[p][c]) < 1e-12: return None
        m[c], m[p] = m[p], m[c]
        for r in range(c + 1, n):
            f = m[r][c] / m[c][c]
            for k in range(c, n + 1): m[r][k] -= f * m[c][k]
    x = [0.0] * n
    for r in range(n - 1, -1, -1): x[r] = (m[r][n] - sum(m[r][k] * x[k] for k in range(r + 1, n))) / m[r][r]
    return x

def fit(target, sr=48000):
    mean = sum(target[i] for i in JUDGED) / len(JUDGED)
    goal = [target[i] - mean for i in JUDGED]
    g = [0.0] * 7
    for _ in range(12):
        now = manual(g, sr); nm = sum(now[i] for i in JUDGED) / len(JUDGED)
        res = [goal[k] - (now[i] - nm) for k, i in enumerate(JUDGED)]
        jac = []
        for b in range(7):
            s = g[:]; s[b] += .5; c = manual(s, sr); cm = sum(c[i] for i in JUDGED) / len(JUDGED)
            jac.append([((c[i] - cm) - (now[i] - nm)) / .5 for i in JUDGED])
        a = [[sum(jac[i][k] * jac[j][k] for k in range(len(JUDGED))) + (1e-3 if i == j else 0) for j in range(7)] for i in range(7)]
        r = [sum(jac[i][k] * res[k] for k in range(len(JUDGED))) for i in range(7)]
        d = solve(a, r)
        if d is None: continue
        g = [max(-12.0, min(12.0, g[b] + d[b])) for b in range(7)]
    rounded = [round(x * 10) / 10 for x in g]
    return rounded, error(manual(rounded, sr), target)
