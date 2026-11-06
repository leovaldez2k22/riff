package com.musicglass.desktop
import androidx.compose.ui.graphics.Color

/** Built-in fixture library for desktop preview and M2 ergonomics. */
internal object FixtureTracks {
    private val defaultMesh = deckMesh(IntArray(36) { 0xFF181A22.toInt() })

    val cinnamonGirl = SongItem(
        id = "track-1",
        title = "Cinnamon Girl",
        artist = "Lana Del Rey",
        album = "Norman Fucking Rockwell!",
        durationMs = 218_000L,
        dominantColor = Color(0xFFC75D78),
        deckMesh = defaultMesh,
        lyrics = listOf(
            LyricLine(0, 3200, "Cinnamon girl in the sunset light", listOf(
                LyricPart(0, 800, 0, 8),
                LyricPart(800, 1400, 9, 13),
                LyricPart(1400, 2000, 14, 16),
                LyricPart(2000, 2400, 17, 20),
                LyricPart(2400, 3200, 21, 33)
            )),
            LyricLine(3200, 6800, "You try to push me down, but I take off", listOf(
                LyricPart(3200, 3800, 0, 3),
                LyricPart(3800, 4400, 4, 7),
                LyricPart(4400, 4900, 8, 10),
                LyricPart(4900, 5500, 11, 15),
                LyricPart(5500, 6000, 16, 18),
                LyricPart(6000, 6800, 24, 38)
            )),
            LyricLine(6800, 11000, "If you hold me without hurting me", listOf(
                LyricPart(6800, 7400, 0, 2),
                LyricPart(7400, 8000, 3, 6),
                LyricPart(8000, 8800, 7, 11),
                LyricPart(8800, 9400, 12, 14),
                LyricPart(9400, 10200, 15, 22),
                LyricPart(10200, 11000, 23, 31)
            )),
            LyricLine(11000, 16000, "You'll be the first who ever did", listOf(
                LyricPart(11000, 12000, 0, 7),
                LyricPart(12000, 12800, 8, 10),
                LyricPart(12800, 13600, 11, 14),
                LyricPart(13600, 14400, 15, 20),
                LyricPart(14400, 15200, 21, 25),
                LyricPart(15200, 16000, 26, 29)
            ))
        )
    )

    val sangeet = SongItem(
        id = "track-2",
        title = "संगीत बहता है",
        artist = "अमित त्रिवेदी",
        album = "ध्वनि यात्रा",
        durationMs = 245_000L,
        dominantColor = Color(0xFFE28743),
        deckMesh = defaultMesh,
        lyrics = listOf(
            LyricLine(0, 4000, "संगीत बहता है हवाओं में कहीं", listOf(
                LyricPart(0, 1000, 0, 5),
                LyricPart(1000, 2000, 6, 10),
                LyricPart(2000, 2500, 11, 13),
                LyricPart(2500, 3400, 14, 20),
                LyricPart(3400, 4000, 21, 25)
            )),
            LyricLine(4000, 8500, "एक नई सुबह की रोशनी लिए", listOf(
                LyricPart(4000, 5000, 0, 3),
                LyricPart(5000, 6000, 4, 6),
                LyricPart(6000, 7000, 7, 11),
                LyricPart(7000, 7800, 12, 14),
                LyricPart(7800, 8500, 15, 25)
            ))
        )
    )

    val alMusiqa = SongItem(
        id = "track-3",
        title = "الموسيقى تضيء الليل",
        artist = "فيروز",
        album = "سهرة الشتاء",
        durationMs = 192_000L,
        dominantColor = Color(0xFF347B98),
        deckMesh = defaultMesh,
        lyrics = listOf(
            LyricLine(0, 4200, "الموسيقى تضيء أرجاء المساء", listOf(
                LyricPart(0, 1200, 0, 8),
                LyricPart(1200, 2200, 9, 13),
                LyricPart(2200, 3200, 14, 19),
                LyricPart(3200, 4200, 20, 26)
            )),
            LyricLine(4200, 8000, "وتهمس بالجمال في كل مكان", listOf(
                LyricPart(4200, 5500, 0, 5),
                LyricPart(5500, 6800, 6, 13),
                LyricPart(6800, 8000, 14, 25)
            ))
        )
    )

    val instrumentalChill = SongItem(
        id = "track-4",
        title = "Midnight Horizon (Instrumental)",
        artist = "Riff Soundscapes",
        album = "Ambient Dimensions",
        durationMs = 310_000L,
        dominantColor = Color(0xFF6B4E87),
        deckMesh = defaultMesh,
        lyrics = listOf(
            LyricLine(0, 8000, "Instrumental — ambient flow", listOf(
                LyricPart(0, 4000, 0, 12),
                LyricPart(4000, 8000, 13, 27)
            )),
            LyricLine(8000, 16000, "Breathe with the frequency pulse", listOf(
                LyricPart(8000, 11000, 0, 7),
                LyricPart(11000, 16000, 8, 32)
            ))
        )
    )

    val neonEchoes = SongItem(
        id = "track-5",
        title = "Neon Glass Echoes",
        artist = "The Synth Collective",
        album = "Refraction Wave",
        durationMs = 204_000L,
        dominantColor = Color(0xFFFF5EAC),
        deckMesh = defaultMesh,
        lyrics = listOf(
            LyricLine(0, 3500, "Walking through corridors of glass", listOf(
                LyricPart(0, 1000, 0, 7),
                LyricPart(1000, 1800, 8, 15),
                LyricPart(1800, 2600, 16, 26),
                LyricPart(2600, 3000, 27, 29),
                LyricPart(3000, 3500, 30, 35)
            )),
            LyricLine(3500, 7200, "Colors refracting in the dark", listOf(
                LyricPart(3500, 4500, 0, 6),
                LyricPart(4500, 5800, 7, 17),
                LyricPart(5800, 6200, 18, 20),
                LyricPart(6200, 6600, 21, 24),
                LyricPart(6600, 7200, 25, 29)
            ))
        )
    )

    val all: List<SongItem> = listOf(cinnamonGirl, sangeet, alMusiqa, instrumentalChill, neonEchoes)

    val albums: List<AlbumItem> = listOf(
        AlbumItem(
            id = "alb-1",
            title = "Norman Fucking Rockwell!",
            artist = "Lana Del Rey",
            year = 2019,
            dominantColor = Color(0xFFC75D78),
            tracks = listOf(cinnamonGirl)
        ),
        AlbumItem(
            id = "alb-2",
            title = "ध्वनि यात्रा",
            artist = "अमित त्रिवेदी",
            year = 2022,
            dominantColor = Color(0xFFE28743),
            tracks = listOf(sangeet)
        ),
        AlbumItem(
            id = "alb-3",
            title = "سهرة الشتاء",
            artist = "فيروز",
            year = 1985,
            dominantColor = Color(0xFF347B98),
            tracks = listOf(alMusiqa)
        ),
        AlbumItem(
            id = "alb-4",
            title = "Ambient Dimensions",
            artist = "Riff Soundscapes",
            year = 2025,
            dominantColor = Color(0xFF6B4E87),
            tracks = listOf(instrumentalChill)
        ),
        AlbumItem(
            id = "alb-5",
            title = "Refraction Wave",
            artist = "The Synth Collective",
            year = 2026,
            dominantColor = Color(0xFFFF5EAC),
            tracks = listOf(neonEchoes)
        )
    )

    val artists: List<ArtistItem> = listOf(
        ArtistItem(
            id = "art-1",
            name = "Lana Del Rey",
            monthlyListeners = "48,290,120 monthly listeners",
            dominantColor = Color(0xFFC75D78),
            topTracks = listOf(cinnamonGirl),
            albums = listOf("Norman Fucking Rockwell!", "Born to Die", "Ultraviolence")
        ),
        ArtistItem(
            id = "art-2",
            name = "अमित त्रिवेदी",
            monthlyListeners = "16,450,800 monthly listeners",
            dominantColor = Color(0xFFE28743),
            topTracks = listOf(sangeet),
            albums = listOf("ध्वनि यात्रा", "Dev.D", "Lootera")
        ),
        ArtistItem(
            id = "art-3",
            name = "فيروز",
            monthlyListeners = "9,820,300 monthly listeners",
            dominantColor = Color(0xFF347B98),
            topTracks = listOf(alMusiqa),
            albums = listOf("سهرة الشتاء", "كيفك انت")
        )
    )

    val playlists: List<PlaylistItem> = listOf(
        PlaylistItem(
            id = "pl-1",
            name = "Fluid Glass Focus",
            description = "Refractive ambient tones and instrumental soundscapes for deep flow.",
            dominantColor = Color(0xFF6B4E87),
            tracks = listOf(instrumentalChill, neonEchoes)
        ),
        PlaylistItem(
            id = "pl-2",
            name = "Global Harmony",
            description = "Multilingual melodies spanning acoustic traditions and modern rhythms.",
            dominantColor = Color(0xFFE28743),
            tracks = listOf(sangeet, alMusiqa, cinnamonGirl)
        ),
        PlaylistItem(
            id = "pl-3",
            name = "Synthwave Glaze",
            description = "High-energy analog synthesisers and neon glass resonances.",
            dominantColor = Color(0xFFFF5EAC),
            tracks = listOf(neonEchoes, instrumentalChill)
        )
    )

    val podcasts: List<PodcastItem> = listOf(
        PodcastItem(
            id = "pod-1",
            title = "Glass Audio Architecture",
            publisher = "Riff Engineering Labs",
            description = "Deep dives into DSP, Skia runtime shaders, and native audio pipelines.",
            episodeCount = 24,
            dominantColor = Color(0xFF00E5FF)
        ),
        PodcastItem(
            id = "pod-2",
            title = "Acoustic Spaces",
            publisher = "Sound & Structure",
            description = "Exploring room acoustics, reverberation models, and binaural spatialization.",
            episodeCount = 18,
            dominantColor = Color(0xFF6200EE)
        )
    )

    val radioStations: List<RadioStationItem> = listOf(
        RadioStationItem(
            id = "rad-1",
            name = "Lana Del Rey Radio",
            genre = "Dream Pop / Cinematic",
            dominantColor = Color(0xFFC75D78),
            seedTrack = cinnamonGirl
        ),
        RadioStationItem(
            id = "rad-2",
            name = "Ambient Soundscapes Radio",
            genre = "Electronic / Ambient",
            dominantColor = Color(0xFF6B4E87),
            seedTrack = instrumentalChill
        ),
        RadioStationItem(
            id = "rad-3",
            name = "World Acoustic Waves",
            genre = "Global Folk / Acoustic",
            dominantColor = Color(0xFFE28743),
            seedTrack = sangeet
        )
    )
}
