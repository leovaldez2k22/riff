#!/usr/bin/env python3
"""Writes the provider contract fixtures under app/src/test/resources/quality/provider.

Every file is built here from invented names and identifiers. Nothing is copied from a recorded
response: the shapes are the renderers the app's parsers read, reduced to the fields they use plus a
few they must ignore. No cookie, token, signed address, account identifier or personal value exists
in any of them, because none was ever available to this script.

Run from the repository root:  python scripts/quality/generate_provider_fixtures.py
Running it again must leave the files unchanged; ProviderFixtureTest fails on a file that was edited by hand.
"""
import hashlib
import json
import pathlib

OUT = pathlib.Path(__file__).resolve().parents[2] / "app" / "src" / "test" / "resources" / "quality" / "provider"

ARTIST = "UCfixtureArtist00000001"
ARTIST_B = "UCfixtureArtist00000002"
ALBUM = "MPREb_fixtureAlbum01"
OWNED = "PLfixtureOwned0000000001"
PUBLIC = "PLfixturePublic000000001"
SHOW = "MPSPPLfixtureShow0000001"
LYRICS = "MPLYt_fixtureLyrics01"


def vid(n):
    return "fxsong%05d" % n


def thumb(name, size=120):
    return {"thumbnails": [{"url": "https://lh3.googleusercontent.com/fixture-%s=w%d-h%d" % (name, size, size), "width": size, "height": size}]}


def browse(browse_id, page=None, params=None):
    endpoint = {"browseId": browse_id}
    if params:
        endpoint["params"] = params
    if page:
        endpoint["browseEndpointContextSupportedConfigs"] = {"browseEndpointContextMusicConfig": {"pageType": page}}
    return {"browseEndpoint": endpoint}


def watch(video, kind="MUSIC_VIDEO_TYPE_ATV"):
    return {"watchEndpoint": {"videoId": video, "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": kind}}}}


def run(text, endpoint=None):
    return {"text": text, "navigationEndpoint": endpoint} if endpoint else {"text": text}


def column(*runs):
    return {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": list(runs)}}}


def sep():
    return run(" • ")


def artist_run(name="Fixture Artist", channel=ARTIST):
    return run(name, browse(channel, "MUSIC_PAGE_TYPE_ARTIST"))


def album_run(name="Fixture Album", album=ALBUM):
    return run(name, browse(album, "MUSIC_PAGE_TYPE_ALBUM"))


def song_row(n, title=None, kind="MUSIC_VIDEO_TYPE_ATV", set_id=None, label="Song", feedback=None):
    video = vid(n)
    item = {
        "thumbnail": {"musicThumbnailRenderer": {"thumbnail": thumb("song-%d" % n)}},
        "flexColumns": [
            column(run(title or "Fixture Song %d" % n, watch(video, kind))),
            column(run(label), sep(), artist_run(), sep(), album_run(), sep(), run("3:0%d" % (n % 10))),
        ],
        "playlistItemData": {"videoId": video},
    }
    if set_id:
        item["playlistItemData"]["playlistSetVideoId"] = set_id
    if feedback:
        item["menu"] = {"menuRenderer": {"items": [{"menuServiceItemRenderer": {
            "text": {"runs": [{"text": "Not interested"}]}, "icon": {"iconType": "NOT_INTERESTED"},
            "serviceEndpoint": {"feedbackEndpoint": {"feedbackToken": feedback}}}}]}}
    return {"musicResponsiveListItemRenderer": item}


def browse_row(title, subtitle, browse_id, page, art):
    return {"musicResponsiveListItemRenderer": {
        "thumbnail": {"musicThumbnailRenderer": {"thumbnail": thumb(art)}},
        "flexColumns": [column(run(title)), column(run(subtitle))],
        "navigationEndpoint": browse(browse_id, page),
    }}


def chip(label, endpoint):
    return {"chipCloudChipRenderer": {"text": {"runs": [{"text": label}]}, "navigationEndpoint": endpoint}}


def search(rows, shelf_title, next_token=None, chips=()):
    shelf = {"title": {"runs": [{"text": shelf_title}]}, "contents": rows}
    if next_token:
        shelf["continuations"] = [{"nextContinuationData": {"continuation": next_token}}]
    section = {"contents": [{"musicShelfRenderer": shelf}]}
    if chips:
        section["header"] = {"chipCloudRenderer": {"chips": list(chips)}}
    return {"responseContext": {}, "contents": {"tabbedSearchResultsRenderer": {"tabs": [{"tabRenderer": {"title": "YT Music", "content": {"sectionListRenderer": section}}}]}}}


def two_row(title, subtitle, endpoint, art):
    return {"musicTwoRowItemRenderer": {
        "title": {"runs": [run(title, endpoint)]}, "subtitle": {"runs": [{"text": subtitle}]},
        "thumbnailRenderer": {"musicThumbnailRenderer": {"thumbnail": thumb(art, 226)}}, "navigationEndpoint": endpoint}}


def sort_item(title, order=None, preference=None, selected=False, params=None, playlist=OWNED):
    action = {"action": "ACTION_SET_PLAYLIST_VIDEO_ORDER", "playlistVideoOrder": order} if order is not None else \
        {"action": "ACTION_SET_PLAYLIST_DYNAMIC_SORT_PREFERENCE", "playlistDynamicSortPreference": preference}
    edit = {"playlistId": playlist, "actions": [action]}
    if params:
        edit["params"] = params
    item = {"title": title, "serviceEndpoint": {"playlistEditEndpoint": edit}}
    if selected:
        item["selected"] = True
    return item


def playlist_header(title, owner, editable):
    header = {"musicResponsiveHeaderRenderer": {
        "title": {"runs": [{"text": title}]},
        "subtitle": {"runs": [{"text": "Playlist"}, {"text": " • "}, {"text": "2026"}]},
        "straplineText": {"runs": [{"text": owner}]},
        "thumbnail": {"musicThumbnailRenderer": {"thumbnail": thumb("playlist-" + title.lower().replace(" ", "-"), 544)}},
        "description": {"musicDescriptionShelfRenderer": {"description": {"runs": [{"text": "A playlist that exists only in this fixture."}]}}},
    }}
    if not editable:
        return header
    return {"musicEditablePlaylistDetailHeaderRenderer": {"header": header, "editHeader": {"musicPlaylistEditHeaderRenderer": {
        "title": {"runs": [{"text": title}]}, "playlistId": OWNED, "privacy": "PRIVATE"}}}}


def playlist_page(header, shelf):
    return {"responseContext": {}, "contents": {"twoColumnBrowseResultsRenderer": {
        "tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [header]}}}}],
        "secondaryContents": {"sectionListRenderer": {"contents": [{"musicPlaylistShelfRenderer": shelf}]}}}}}


def panel_video(n, title, kind="MUSIC_VIDEO_TYPE_ATV", like=None, feedback=None, video=None):
    video = video or vid(n)
    item = {"videoId": video, "title": {"runs": [{"text": title}]},
            "longBylineText": {"runs": [artist_run(), sep(), album_run(), sep(), run("2020")]},
            "thumbnail": thumb("panel-%d" % n), "navigationEndpoint": watch(video, kind)}
    menu = []
    if like:
        menu.append({"toggleMenuServiceItemRenderer": {"defaultIcon": {"iconType": "FAVORITE"}}})
    if feedback:
        menu.append({"menuServiceItemRenderer": {"text": {"runs": [{"text": "Not interested"}]}, "icon": {"iconType": "NOT_INTERESTED"},
                                                 "serviceEndpoint": {"feedbackEndpoint": {"feedbackToken": feedback}}}})
    if menu:
        item["menu"] = {"menuRenderer": {"items": menu}}
    if like:
        item["menu"]["menuRenderer"]["topLevelButtons"] = [{"likeButtonRenderer": {"likeStatus": like, "target": {"videoId": video}}}]
    return {"playlistPanelVideoRenderer": item}


def fixtures():
    out = {}

    # ---- search, one file for each kind the app filters by
    out["search-songs.json"] = search([song_row(1, feedback="fixture-feedback-song-1"), song_row(2)], "Songs", "fixture-search-songs-2")
    out["search-songs-page-2.json"] = {"responseContext": {}, "continuationContents": {"musicShelfContinuation": {"contents": [song_row(3)]}}}
    out["search-videos.json"] = search([song_row(11, "Fixture Song 1 (Official Video)", "MUSIC_VIDEO_TYPE_OMV", label="Video"),
                                        song_row(12, "Fixture fan upload", "MUSIC_VIDEO_TYPE_UGC", label="Video")], "Videos")
    out["search-albums.json"] = search([browse_row("Fixture Album", "Album • Fixture Artist • 2020", ALBUM, "MUSIC_PAGE_TYPE_ALBUM", "album-1")], "Albums")
    out["search-artists.json"] = search([browse_row("Fixture Artist", "Artist • 1.2M monthly audience", ARTIST, "MUSIC_PAGE_TYPE_ARTIST", "artist-1")], "Artists")
    out["search-playlists.json"] = search([browse_row("Fixture Public Playlist", "Playlist • Fixture Curator • 40 songs", "VL" + PUBLIC, "MUSIC_PAGE_TYPE_PLAYLIST", "playlist-1")], "Community playlists")
    episode = song_row(21, "Fixture Episode 1", "MUSIC_VIDEO_TYPE_PODCAST_EPISODE", label="Episode")
    out["search-podcasts.json"] = search([browse_row("Fixture Show", "Podcast • Fixture Host", SHOW, "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE", "show-1"), episode], "Podcasts")
    # The unfiltered answer: mixed kinds, and the chips that carry the provider's own filter parameters.
    out["search-mixed.json"] = search(
        [song_row(1), browse_row("Fixture Album", "Album • Fixture Artist • 2020", ALBUM, "MUSIC_PAGE_TYPE_ALBUM", "album-1"),
         browse_row("Fixture Artist", "Artist", ARTIST, "MUSIC_PAGE_TYPE_ARTIST", "artist-1")], "Top result",
        chips=[chip("Songs", {"searchEndpoint": {"query": "fixture", "params": "fixture-songs-filter"}}),
               chip("Albums", {"searchEndpoint": {"query": "fixture", "params": "fixture-albums-filter"}})])
    out["search-empty.json"] = {"responseContext": {}, "contents": {"tabbedSearchResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
        {"itemSectionRenderer": {"contents": [{"messageRenderer": {"text": {"runs": [{"text": "No results found"}]}}}]}}]}}}}]}}}

    # ---- Home
    out["home.json"] = {"responseContext": {}, "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {
        "contents": [
            {"musicCarouselShelfRenderer": {"header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "Quick picks"}]}}},
                                            "contents": [song_row(1), song_row(2)]}},
            {"musicCarouselShelfRenderer": {"header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "Albums for you"}]}}},
                                            "contents": [two_row("Fixture Album", "Album • Fixture Artist", browse(ALBUM, "MUSIC_PAGE_TYPE_ALBUM"), "album-1"),
                                                         two_row("Fixture Public Playlist", "Playlist • Fixture Curator", browse("VL" + PUBLIC, "MUSIC_PAGE_TYPE_PLAYLIST"), "playlist-1")]}},
            # A shelf with a header and nothing in it is not shown.
            {"musicCarouselShelfRenderer": {"header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "Empty shelf"}]}}}, "contents": []}},
        ],
        "continuations": [{"nextContinuationData": {"continuation": "fixture-home-2"}}],
        "header": {"chipCloudRenderer": {"chips": [chip("Relax", browse("FEmusic_home", params="fixture-mood-relax")),
                                                   chip("Podcasts", browse("FEmusic_non_music_audio", params="fixture-podcasts"))]}},
    }}}}]}}}
    out["home-page-2.json"] = {"responseContext": {}, "continuationContents": {"sectionListContinuation": {"contents": [
        {"musicCarouselShelfRenderer": {"header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "Forgotten favourites"}]}}},
                                        "contents": [song_row(3)]}}]}}}

    # ---- album
    def album_track(n):
        return {"musicResponsiveListItemRenderer": {
            "flexColumns": [column(run("Album Track %d" % n, watch(vid(30 + n)))), column(), column(run("1.%dM plays" % n))],
            "index": {"runs": [{"text": str(n)}]}, "playlistItemData": {"videoId": vid(30 + n)}}}
    out["album.json"] = {"responseContext": {}, "contents": {"twoColumnBrowseResultsRenderer": {
        "tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [{"musicResponsiveHeaderRenderer": {
            "title": {"runs": [{"text": "Fixture Album"}]},
            "subtitle": {"runs": [{"text": "Album"}, {"text": " • "}, {"text": "2020"}]},
            "straplineText": {"runs": [artist_run()]},
            "thumbnail": {"musicThumbnailRenderer": {"thumbnail": thumb("album-1", 544)}},
            "buttons": [{"musicPlayButtonRenderer": {"playNavigationEndpoint": {"watchPlaylistEndpoint": {"playlistId": "OLAK5uy_fixtureAlbum000000000001"}}}}],
        }}]}}}}],
        "secondaryContents": {"sectionListRenderer": {"contents": [{"musicShelfRenderer": {"contents": [album_track(1), album_track(2), album_track(3)]}}]}}}}}

    # ---- a playlist the account owns, in three pages, with the provider's own sort menu
    sort_menu = {"sortFilterSubMenuRenderer": {"title": "Sort", "subMenuItems": [
        sort_item("Manual ordering", order=0, selected=True),
        sort_item("Newest first", order=1, params="fixture-sort-newest"),
        sort_item("Oldest first", order=2),
        sort_item("Title", preference="TITLE"),
        # Not on the allowlist: a command that is not a sort preference is never kept, whatever it is called.
        {"title": "Artist", "serviceEndpoint": {"playlistEditEndpoint": {"playlistId": OWNED, "actions": [{"action": "ACTION_REMOVE_VIDEO", "setVideoId": "fixture-set-1"}]}}},
    ]}}
    owned_shelf = {"playlistId": OWNED, "contents": [song_row(1, set_id="fixture-set-1"), song_row(2, set_id="fixture-set-2")],
                   "continuations": [{"nextContinuationData": {"continuation": "fixture-owned-page-2"}}], "subHeaders": [sort_menu]}
    out["playlist-owned.json"] = playlist_page(playlist_header("Fixture Owned Playlist", "Fixture Listener", True), owned_shelf)
    out["playlist-owned-page-2.json"] = {"responseContext": {}, "continuationContents": {"musicPlaylistShelfContinuation": {
        "contents": [song_row(3, set_id="fixture-set-3")], "continuations": [{"nextContinuationData": {"continuation": "fixture-owned-page-3"}}]}}}
    # The other continuation form the provider uses: items appended by a command, with the next token as the last item.
    out["playlist-owned-page-3.json"] = {"responseContext": {}, "onResponseReceivedActions": [{"appendContinuationItemsAction": {"continuationItems": [
        song_row(4, set_id="fixture-set-4")]}}]}
    # The same song twice is two entries, told apart only by the entry's own identifier.
    out["playlist-owned-duplicates.json"] = playlist_page(playlist_header("Fixture Owned Playlist", "Fixture Listener", True),
        {"playlistId": OWNED, "contents": [song_row(1, set_id="fixture-set-1"), song_row(1, set_id="fixture-set-9")]})

    # ---- someone else's playlist: readable, not editable
    out["playlist-readonly.json"] = playlist_page(playlist_header("Fixture Public Playlist", "Fixture Curator", False),
        {"playlistId": PUBLIC, "contents": [song_row(1, set_id="fixture-public-set-1"), song_row(5, set_id="fixture-public-set-5")]})

    # ---- library
    out["library-playlists.json"] = {"responseContext": {}, "contents": {"singleColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
        {"gridRenderer": {"items": [
            two_row("Liked Music", "Auto playlist", browse("VLLM", "MUSIC_PAGE_TYPE_PLAYLIST"), "liked"),
            two_row("Fixture Owned Playlist", "Fixture Listener • 4 songs", browse("VL" + OWNED, "MUSIC_PAGE_TYPE_PLAYLIST"), "playlist-owned"),
            two_row("Fixture Public Playlist", "Fixture Curator • 40 songs", browse("VL" + PUBLIC, "MUSIC_PAGE_TYPE_PLAYLIST"), "playlist-1"),
        ]}}]}}}}]}}}

    # ---- account menu. The handle and the address are invented; neither may be taken for the name.
    out["account-menu.json"] = {"responseContext": {}, "actions": [{"openPopupAction": {"popup": {"multiPageMenuRenderer": {"header": {"activeAccountHeaderRenderer": {
        "accountName": {"runs": [{"text": "Fixture Listener"}]},
        "email": {"simpleText": "listener@example.invalid"},
        "channelHandle": {"runs": [{"text": "@fixture-listener"}]},
        "accountPhoto": {"thumbnails": [{"url": "https://yt3.ggpht.com/fixture-avatar=s108", "width": 108, "height": 108}]},
    }}}}}}]}
    out["account-menu-signed-out.json"] = {"error": {"code": 401, "message": "Request is missing required authentication credential.", "status": "UNAUTHENTICATED"}}

    # ---- watch queue: the song with its official video, its lyrics tab, a like state and a feedback token
    out["watch-next.json"] = {"responseContext": {}, "contents": {"singleColumnMusicWatchNextResultsRenderer": {"tabbedRenderer": {"watchNextTabbedResultsRenderer": {"tabs": [
        {"tabRenderer": {"title": "Up next", "content": {"musicQueueRenderer": {"content": {"playlistPanelRenderer": {
            "contents": [
                {"playlistPanelVideoWrapperRenderer": {
                    "primaryRenderer": panel_video(1, "Fixture Song 1", like="LIKE", feedback="fixture-feedback-song-1"),
                    "counterpart": [{"counterpartRenderer": panel_video(11, "Fixture Song 1 (Official Video)", "MUSIC_VIDEO_TYPE_OMV", video="fxvideo0001"), "segmentMap": {}}]}},
                panel_video(2, "Fixture Song 2"),
                panel_video(3, "Fixture Song 3"),
            ],
            "continuations": [{"nextRadioContinuationData": {"continuation": "fixture-radio-2"}}]}}}}}},
        {"tabRenderer": {"title": "Lyrics", "endpoint": browse(LYRICS, "MUSIC_PAGE_TYPE_TRACK_LYRICS")}},
        {"tabRenderer": {"title": "Related", "endpoint": browse("MPTRt_fixtureRelated01", "MUSIC_PAGE_TYPE_TRACK_RELATED")}},
    ]}}}}}
    # A fan upload under the song's name is not its official video.
    out["watch-next-unofficial-video.json"] = {"responseContext": {}, "contents": {"singleColumnMusicWatchNextResultsRenderer": {"tabbedRenderer": {"watchNextTabbedResultsRenderer": {"tabs": [
        {"tabRenderer": {"content": {"musicQueueRenderer": {"content": {"playlistPanelRenderer": {"contents": [
            {"playlistPanelVideoWrapperRenderer": {
                "primaryRenderer": panel_video(1, "Fixture Song 1"),
                "counterpart": [{"counterpartRenderer": panel_video(12, "Fixture Song 1 (Lyrics)", "MUSIC_VIDEO_TYPE_UGC", video="fxvideo0002"), "segmentMap": {}}]}}]}}}}}}]}}}}}

    # ---- lyrics
    out["lyrics-timed.json"] = {"responseContext": {}, "contents": {"elementRenderer": {"newElement": {"type": {"componentType": {"model": {"timedLyricsModel": {"lyricsData": {
        "timedLyricsData": [
            {"lyricLine": "First fixture line", "cueRange": {"startTimeMilliseconds": "0", "endTimeMilliseconds": "1500"}},
            {"lyricLine": "Second fixture line", "cueRange": {"startTimeMilliseconds": "1500", "endTimeMilliseconds": "3000"}},
            {"lyricLine": "Third fixture line", "cueRange": {"startTimeMilliseconds": "3000", "endTimeMilliseconds": "4500"}},
        ], "sourceMessage": "Source: Fixture"}}}}}}}}}
    out["lyrics-plain.json"] = {"responseContext": {}, "contents": {"sectionListRenderer": {"contents": [{"musicDescriptionShelfRenderer": {
        "description": {"runs": [{"text": "First fixture line\nSecond fixture line"}]}, "footer": {"runs": [{"text": "Source: Fixture"}]}}}]}}}

    # ---- answers to a change
    out["edit-succeeded.json"] = {"responseContext": {}, "status": "STATUS_SUCCEEDED", "actions": [{"addToToastAction": {"item": {"notificationTextRenderer": {"successResponseText": {"runs": [{"text": "Saved"}]}}}}}]}
    out["edit-failed.json"] = {"responseContext": {}, "status": "STATUS_FAILED"}
    out["like-acknowledged.json"] = {"responseContext": {"maxAgeSeconds": 0}, "actions": [{"addToToastAction": {"item": {"notificationTextRenderer": {"successResponseText": {"runs": [{"text": "Saved to liked music"}]}}}}}]}

    # ---- failures that arrive as HTTP 200
    out["error-envelope.json"] = {"error": {"code": 403, "message": "The caller does not have permission", "errors": [
        {"message": "The caller does not have permission", "domain": "global", "reason": "forbidden"}], "status": "PERMISSION_DENIED"}}
    out["error-not-found.json"] = {"error": {"code": 404, "message": "Requested entity was not found.", "status": "NOT_FOUND"}}

    # ---- a page from a provider that has moved on: renderers Riff has never seen, fields of the wrong type, one row it still understands
    out["unknown-renderers.json"] = {"responseContext": {}, "contents": {"twoColumnBrowseResultsRenderer": {
        "tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
            {"musicFutureHeroRenderer": {"headline": {"runs": [{"text": "Something new"}]}, "items": [{"musicFutureCardRenderer": {"title": "No endpoint here"}}]}},
            None, 7, "text", [],
        ]}}}}],
        "secondaryContents": {"sectionListRenderer": {"contents": [{"musicPlaylistShelfRenderer": {"playlistId": PUBLIC, "contents": [
            {"musicFutureRowRenderer": {"title": {"runs": [{"text": "Unknown row"}]}}},
            {"musicResponsiveListItemRenderer": {}},
            {"musicResponsiveListItemRenderer": {"flexColumns": "not a list", "navigationEndpoint": 7, "playlistItemData": []}},
            {"musicResponsiveListItemRenderer": {"flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": "not a list"}}}],
                                                 "playlistItemData": {"videoId": vid(41)}}},
            {"musicResponsiveListItemRenderer": {"flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [7, None, {"text": {"nested": True}}]}}}],
                                                 "playlistItemData": {"videoId": vid(42)}}},
            {"musicResponsiveListItemRenderer": {"flexColumns": [column(run("Title with an identifier of the wrong length", watch("short")))]}},
            {"musicResponsiveListItemRenderer": {"flexColumns": [column(run("Title with no identifier at all"))]}},
            {"musicResponsiveListItemRenderer": {"flexColumns": [column({"text": "Simple text of the wrong type", "navigationEndpoint": watch(vid(43))})],
                                                 "title": {"simpleText": {"not": "text"}}, "playlistItemData": {"videoId": {"not": "text"}, "playlistSetVideoId": 12}}},
            song_row(4, set_id="fixture-public-set-4"),
        ], "continuations": [{"nextContinuationData": {"continuation": {"not": "text"}}}, {"nextContinuationData": {}}, None]}}]}}}}}
    return out


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    files = fixtures()
    digests = {}
    for name in sorted(files):
        text = json.dumps(files[name], indent=1, ensure_ascii=True) + "\n"
        (OUT / name).write_bytes(text.encode("ascii"))
        digests[name] = hashlib.sha256(text.encode("ascii")).hexdigest()
    index = {"schemaVersion": 1,
             "provenance": "Written by scripts/quality/generate_provider_fixtures.py from invented values. Not recorded from YouTube Music.",
             "fixtures": digests}
    (OUT / "index.json").write_bytes((json.dumps(index, indent=1, sort_keys=True) + "\n").encode("ascii"))
    for stale in OUT.glob("*.json"):
        if stale.name != "index.json" and stale.name not in files:
            stale.unlink()
    print("%d fixtures written to %s" % (len(files), OUT))


if __name__ == "__main__":
    main()
