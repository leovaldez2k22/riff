# Bundled AutoEq headphone profiles (Riff+)

`app/src/plus/assets/autoeq/profiles.tsv` is made from AutoEq's published results (MIT, Copyright (c) 2018-2022 Jaakko Pasanen; see NOTICE.md) at commit `7ae0f56d53074872b028649617a22bbb4232feb7`.

1. Clone AutoEq into a new, empty directory outside the repository (`git clone --filter=blob:none --no-checkout https://github.com/jaakkopasanen/AutoEq <dir>` then `git -C <dir> checkout 7ae0f56d53074872b028649617a22bbb4232feb7 -- LICENSE`). The results are read from git objects, so no long Windows paths are checked out.
2. `python -I scripts/autoeq/extract_filters.py <dir> <scratch>/autoeq-filters.tsv.gz`: every `ParametricEQ.txt` under `results/<source>/<rig>/<name>/`, filters rounded to whole hertz, tenths of a dB and hundredths of Q.
3. `python -I scripts/autoeq/fit_profiles.py <scratch>/autoeq-filters.tsv.gz app/src/plus/assets/autoeq/profiles.tsv`: each profile fitted to Riff's seven manual bands with `fit.py` (the same method as `fitAutoEq`). It takes about seven minutes.

The asset is plain UTF-8 text (the APK compresses it; AGP would unpack a `.gz` asset anyway): a comment, AutoEq's MIT licence as `#L` lines (so the notice travels with every copy of the list), `#s<TAB>id<TAB>source<TAB>rig` lines, then `name<TAB>source id<TAB>seven gains in tenths of a dB<TAB>fit error in tenths`. Precomputed gains (454,000 bytes, about 146 KB compressed in the APK) were chosen over shipping the filters (about 1.3 MB, 497 KB compressed) to keep Riff+ small; the cost is that the preview of a bundled profile shows the fitted curve but not AutoEq's own.
