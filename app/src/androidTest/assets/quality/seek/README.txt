seek-source.webm: 200 s of a 330 Hz sine mixed with pink noise, Opus 128 kb/s stereo 48 kHz in WebM, generated with
ffmpeg (sine + anoisesrc, amix, libopus). Nothing sampled from a recording. SeekTimingDeviceTest repacks it with
AndroidAudioAssembler, as a download is stored, and times seeks in it (Q18-057).
