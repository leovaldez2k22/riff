"""Fits each profile of extract_filters.py's table to Riff's seven bands and writes the Riff+ asset. See README.md."""
import gzip, sys, io
sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parent))
from fit import fit, curve
src, out = sys.argv[1], sys.argv[2]
lines = gzip.open(src, 'rt', encoding='utf-8').read().splitlines()
header = [l for l in lines if l.startswith('#')]; rows = [l for l in lines if not l.startswith('#')]
text = io.StringIO()
snapshot = header[0].split(' at ')[1].split()[0][:7]
text.write('# AutoEq results (MIT, Copyright (c) 2018-2022 Jaakko Pasanen), jaakkopasanen/AutoEq at ' + snapshot + ', fitted by Riff to its seven bands: 60 150 400 1k 2.5k 6k 14k, gains in tenths of a dB, then the fit error in tenths.\n')
for h in header[1:]: text.write(h + '\n')
worst = 0
for n, row in enumerate(rows):
    name, source, filters = row.split('\t')
    secs = []
    for f in filters.split(';'):
        t = f[0]; fc, g, q = f[1:].split(',')
        secs.append((t, float(fc), int(q) / 100.0, int(g) / 10.0))
    gains, err = fit(curve(secs))
    text.write(f"{name}\t{source}\t{' '.join(str(round(x * 10)) for x in gains)}\t{round(err * 10)}\n")
    worst = max(worst, err)
    if n % 1000 == 0: print(n, flush=True)
data = text.getvalue().encode('utf-8')
open(out, 'wb').write(data)
print('rows', len(rows), 'raw', len(data), 'worst', worst)
