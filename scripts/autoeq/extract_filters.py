"""AutoEq ParametricEQ results, read from a git clone, to a gzip table of rounded filters. See README.md."""
import subprocess, sys, re, gzip, io, collections
repo, out = sys.argv[1], sys.argv[2]
tree = subprocess.run(['git','-C',repo,'ls-tree','-r','HEAD','results'],capture_output=True,check=True).stdout.decode('utf-8')
items = []
for line in tree.splitlines():
    meta, path = line.split('\t',1)
    if path.endswith(' ParametricEQ.txt'): items.append((meta.split()[2], path))
p = subprocess.Popen(['git','-C',repo,'cat-file','--batch'],stdin=subprocess.PIPE,stdout=subprocess.PIPE)
num = r'[-+]?[0-9]*\.?[0-9]+'
rx = re.compile(r'^\s*Filter\s*\d*\s*:\s*ON\s+([A-Z]+)\s+Fc\s+('+num+r')\s*Hz\s+Gain\s+('+num+r')\s*dB\s+Q\s+('+num+r')', re.I)
code = {'PK':'P','LSC':'L','HSC':'H'}
sources = collections.OrderedDict(); rows = []; skipped = collections.Counter()
for sha, path in items:
    p.stdin.write((sha+'\n').encode()); p.stdin.flush()
    head = p.stdout.readline().split(); size = int(head[2]); body = p.stdout.read(size); p.stdout.read(1)
    parts = path.split('/')  # results/<source>/<rig>/<name>/<file>
    if len(parts) != 5: skipped['shape'] += 1; continue
    src, rig, name = parts[1], parts[2], parts[3]
    filters = []
    for l in body.decode('utf-8','replace').splitlines():
        m = rx.match(l)
        if not m: continue
        t = code.get(m.group(1).upper())
        if t is None: skipped['type'] += 1; continue
        f, g, q = float(m.group(2)), float(m.group(3)), float(m.group(4))
        filters.append(f"{t}{round(f)},{round(g*10)},{round(q*100)}")
    if not filters: skipped['empty'] += 1; continue
    key = src + '\t' + rig
    if key not in sources: sources[key] = len(sources)
    rows.append((name, sources[key], ';'.join(filters)))
rows.sort(key=lambda r: (r[0].lower(), r[1]))
text = io.StringIO()
text.write('# AutoEq results, jaakkopasanen/AutoEq at ' + subprocess.run(['git','-C',repo,'rev-parse','HEAD'],capture_output=True,check=True).stdout.decode().strip() + ' (MIT). Filters: type Fc,gain*10,Q*100.\n')
licence = subprocess.run(['git','-C',repo,'show','HEAD:LICENSE'],capture_output=True,check=True).stdout.decode('utf-8')
for line in licence.strip().splitlines(): text.write(('#L ' + line).rstrip() + '\n')
for k, i in sources.items(): text.write(f'#s\t{i}\t{k}\n')
for n, s, f in rows: text.write(f'{n}\t{s}\t{f}\n')
data = text.getvalue().encode('utf-8')
with gzip.GzipFile(out, 'wb', compresslevel=9, mtime=0) as g: g.write(data)
print('profiles', len(rows), 'sources', len(sources), 'raw', len(data), 'skipped', dict(skipped))
