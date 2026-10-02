package com.musicglass.personal
internal data class RadioChannel(val category:String,val name:String,val handle:String)
internal val radioChannels=listOf(
    RadioChannel("Lofi & chill","Lofi Girl","LofiGirl"),
    RadioChannel("Lofi & chill","Chillhop Music","ChillhopMusic"),
    RadioChannel("Lofi & chill","College Music","CollegeMusic"),
    RadioChannel("Lofi & chill","Chill with Taiki","ChillwithTaiki"),
    RadioChannel("Lofi & chill","The Jazz Hop Café","jazzhopcafe"),
    RadioChannel("Lofi & chill","the bootleg boy","thebootlegboy"),
    RadioChannel("Jazz & café","Cafe Music BGM","CafeMusicBGMchannel"),
    RadioChannel("Jazz & café","BGM channel","BGMchannel"),
    RadioChannel("Jazz & café","Relax Cafe Music","RelaxCafeMusic"),
    RadioChannel("Jazz & café","Jazz and Blues Experience","jazznbluesexperience"),
    RadioChannel("Jazz & café","Coffee Jazz Melody","CoffeeJazzMelodyOfficial"),
    RadioChannel("Electronic & house","ChillYourMind","ChillYourMind"),
    RadioChannel("Electronic & house","The Good Life Radio","TheGoodLifeRadio"),
    RadioChannel("Electronic & house","Chill Nation","ChillNation"),
    RadioChannel("Electronic & house","Monstercat","Monstercat"),
    RadioChannel("Electronic & house","Spinnin’ Records","SpinninRecords"),
    RadioChannel("Electronic & house","Armada Music TV","armadamusictv"),
    RadioChannel("Synthwave","The Prime Thanatos","ThePrimeThanatos"),
    RadioChannel("Synthwave","Synthwave Radio","SynthwaveRadio"),
    RadioChannel("Ambient & sleep","Yellow Brick Cinema","YellowBrickCinema"),
    RadioChannel("Ambient & sleep","Soothing Relaxation","SoothingRelaxation"),
    RadioChannel("Ambient & sleep","SleepTube","SleepTube"),
    RadioChannel("Ambient & sleep","Calmed By Nature","CalmedByNature"),
    RadioChannel("Ambient & sleep","Meditative Mind","MeditativeMind"),
    RadioChannel("Nature & soundscapes","Relaxing White Noise","RelaxingWhiteNoise"),
    RadioChannel("Nature & soundscapes","Explore Live Nature Cams","ExploreLiveNatureCams"),
    RadioChannel("Nature & soundscapes","EarthCam","EarthCam"),
    RadioChannel("Classical & piano","HALIDONMUSIC","HALIDONMUSIC"),
    RadioChannel("Classical & piano","Brilliant Classics","BrilliantClassics"),
    RadioChannel("Classical & piano","Classical Tunes","ClassicalTunes"),
    RadioChannel("World & lounge","Café De Anatolia","CafeDeAnatolia"),
    RadioChannel("World & lounge","Buddha’s Lounge","BuddhasLounge"),
    RadioChannel("Indian music","Saregama Music","SaregamaMusic"),
    RadioChannel("Indian music","T-Series","tseries"),
    RadioChannel("Indian music","Sony Music India","SonyMusicIndia"),
    RadioChannel("News & talk","DW News","DWNews"),
    RadioChannel("News & talk","Sky News","SkyNews"),
    RadioChannel("News & talk","FRANCE 24 English","France24_en"),
    RadioChannel("News & talk","Al Jazeera English","aljazeeraenglish"),
    RadioChannel("News & talk","euronews","euronews"),
    RadioChannel("News & talk","NDTV","NDTV"),
    RadioChannel("News & talk","WION","WION")
)
internal val radioCategories=listOf("Featured","All")+radioChannels.map {it.category}.distinct()
internal fun radioFeedKey(category:String)=if(category=="Featured") "radio" else "radio:$category"
internal fun channelsForRadio(category:String):List<RadioChannel> = when(category) {
    "All"->radioChannels
    "Featured"->radioChannels.groupBy {it.category}.values.map {it.first()}
    else->radioChannels.filter {it.category==category}
}
internal fun uniqueRadioShelves(shelves:List<ProviderHomeSection>):List<ProviderHomeSection> {
    val seen=mutableSetOf<String>()
    return shelves.map {s->s.copy(cards=s.cards.filter {it.kind==CatalogKind.Live && it.videoId!=null && seen.add(it.videoId)})}.filter {it.cards.isNotEmpty()}
}
