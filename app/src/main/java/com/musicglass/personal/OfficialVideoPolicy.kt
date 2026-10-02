package com.musicglass.personal

/** A provider pairing/official-video type is evidence; a title alone is not. */
internal fun officialVideoMatches(track: ProviderTrack, title: String, owner: String,
    ownerId: String? = null, officialEvidence: Boolean): Boolean {
    if (!officialEvidence || owner.contains("- Topic", true)) return false
    val unwanted = Regex("\\b(lyrics?|lyric video|audio|visuali[sz]er|karaoke|cover|reaction|fan made|fanmade|sped up|slowed|instrumental)\\b", RegexOption.IGNORE_CASE)
    if (unwanted.containsMatchIn(title)) return false
    if (!track.title.contains("live", true) && Regex("\\b(live|concert|performance)\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)) return false
    val artist = playerArtistLabel(track.artist).removeSuffix(" - Topic")
        .substringBefore(",").substringBefore(" feat.").substringBefore(" & ")
    val descriptors = Regex("\\b(official (?:music )?video|music video|4k upgrade|4k|1080p|hd|hq)\\b", RegexOption.IGNORE_CASE)
    val normalized = lyricsNormalize(descriptors.replace(title, ""))
    val wanted = lyricsNormalize(lyricsQueryTitle(track.title))
    val artistName = lyricsNormalize(artist)
    // Match the whole recording title, allowing the artist prefix/suffix commonly used on YouTube.
    // A substring match incorrectly accepts medleys, remixes and longer song names.
    if (wanted.isBlank() || normalized !in setOf(wanted, artistName + wanted, wanted + artistName)) return false
    fun channel(value: String) = lyricsNormalize(value.replace(Regex("vevo$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\b(official|vevo|music|channel)\\b", RegexOption.IGNORE_CASE), ""))
    return ownerId != null && ownerId == track.artistId ||
        channel(owner).isNotEmpty() && channel(owner) == channel(artist)
}
