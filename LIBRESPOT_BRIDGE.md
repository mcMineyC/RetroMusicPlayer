# librespot.aar — bridge API and handoff contract

`app/libs/librespot.aar` is a gomobile binding of `go-librespot/mobilebind`
(source: `~/Developer/embedded-librespot/go-librespot`). It exposes one class,
`mobilebind.Librespot`, plus three callback interfaces (`EventCallback`,
`CredentialCallback`, `OggDumpCallback`). This file is the contract
between the bridge (Go side) and the app (Kotlin side). The app currently uses
it from `app/src/main/java/code/name/monkey/retromusic/spotify/`.

Built 2026-11-17 from the uncommitted working tree. `app/libs/librespot-sources.jar`
holds the generated Java with doc comments for every method.

---

## 1. Ground rules (read these first)

| Rule | Detail |
|---|---|
| **Threading** | Every method that talks to Spotify **blocks** (network timeout 2 min). Never call from the main thread. Playback commands are fast but still cross into Go; keep them off main too (`LibrespotEngine.call`/`launchCall` already does this). |
| **Callbacks run on Go threads** | `onEvent`, `onLog`, `onCredentialsPersist`, `onOggWritten`, `onOggDumpError` arrive on arbitrary background threads, possibly concurrently. Post to main before touching UI. Don't call back into the bridge synchronously from `onLog`. |
| **Errors** | Methods declared `throws Exception` report failure by throwing; the message is human-readable, not a stable code. Methods without `throws` never throw and return a sentinel instead (`"{}"`, `"[]"`, or no-op). |
| **JSON in, JSON out** | Lists of URIs are passed as a JSON array string: `["spotify:track:a","spotify:track:b"]`. Results are JSON strings. Parse leniently: fields may be added, absent optional fields are omitted (not `null`) unless stated. |
| **Integers** | Go `int` → Java `long`. `int32` → `int`. Volume is in **steps** (`0..volume_steps`), not percent. |
| **URIs** | Always `spotify:<type>:<base62>`. Never pass open.spotify.com links to the bridge (convert first). |
| **State comes from events** | Playback commands are fire-and-forget; the authoritative state is `getStatusJSON()` plus events. Don't assume a command succeeded until the matching event arrives. |
| **Single instance** | One `Librespot` per process. `startWithConfigJSON` throws "already started" if called twice; call `stop()` first. |

---

## 2. Lifecycle

```kotlin
val lib = Mobilebind.newLibrespot()
lib.setCredentialCallback(credCb)          // before start, so the first login is persisted
lib.startWithConfigJSON(configJson, eventCb)
// ... use it ...
lib.stop()                                 // blocks until shut down
```

| Method | Contract |
|---|---|
| `startWithConfigJSON(config, cb)` | Parses config, connects, starts the player. Returns once started; the player connects in the background and emits `online`. If player setup fails (e.g. Spotify 5xx on the client token), it logs `warn: player initialization skipped: …` via `onLog` and **does not retry** — every playback/library call then throws `not started`. **App should detect this and `stop()` + restart with backoff.** |
| `stop()` | Shuts down; safe to call when not started. |
| `setEventCallback(cb)` | Replace the event callback; also re-routes logs to it. |
| `setCredentialCallback(cb)` | See §3. |
| `onBackground()` | If playing, pauses and remembers that it did. If already paused/stopped, does nothing. |
| `onForeground()` | Resumes **only if `onBackground()` paused it**. An explicit `play()`/`pause()` in between (e.g. lock-screen controls) cancels the auto-resume. If you want playback to continue in the background (normal for a music app), simply don't call these. |
| `dumpStateJSON()` / `loadStateJSON(json)` | Opaque daemon state blob for the host to persist and restore. |

### Config JSON (`startWithConfigJSON`)

```json
{
  "device_id": "40 hex chars, stable per install (generated if omitted)",
  "device_name": "Pixel 8",
  "device_type": "smartphone",
  "volume_steps": 100,
  "initial_volume": 50,
  "bitrate": 160,
  "log_level": "info",
  "credentials": {
    "type": "interactive",
    "spotify_token": { "username": "<saved username>", "data": "<saved data>" }
  },
  "ogg_dump": { "enabled": true, "dir": "/data/user/0/<pkg>/files/spotify-ogg" }
}
```

- `audio_backend` defaults to `oto`; the HTTP API server and zeroconf are forced off.
- `log_level`: `trace|debug|info|warn|error`, default `info`. Controls what reaches `onLog`.
- Keep `device_id` stable across launches, or Spotify sees a new device each time.
- `ogg_dump` (optional, bridge-only): turns on automatic Ogg saving at start (§5.6).
  `enabled: true` requires `dir`. When the key is present it overrides any
  `enableOggDump`/`disableOggDump` made before start; when absent, those are kept.
- Other keys: see `Config` in `daemoncore/app.go` (`disable_autoplay`, `normalisation_*`, …).

---

## 3. Authentication and credentials

1. First run (no saved credentials): start with empty `spotify_token`, then
   `val url = lib.beginInteractiveAuth()`, open it in a Custom Tab/WebView, and pass
   the redirect URL (or the raw code) to `lib.completeInteractiveAuth(redirectUrl)`.
2. On success the bridge calls `CredentialCallback.onCredentialsPersist(json)`:
   ```json
   {"username": "...", "data": "<opaque string>", "web_api_credentials": {...}}
   ```
   Store the whole string securely (EncryptedSharedPreferences/Keystore). The bridge
   writes nothing to disk itself.
3. Next launches: put `username` and `data` into `credentials.spotify_token` in the config.
4. Throwing from `onCredentialsPersist` is non-fatal (logged); login still completes.
5. After a fresh interactive login, restart the bridge with the saved credentials to
   start the player (this is what the desktop test host does).

---

## 4. Events (`EventCallback.onEvent(event, payloadJson)`)

| event | payload | meaning |
|---|---|---|
| `online` / `offline` | `{}`/`null` | Connection to Spotify (re)established / lost. |
| `active` / `inactive` | — | This device became / stopped being the active Connect device. |
| `will_play` | `{context_uri, uri, play_origin}` | A track started loading. Clear any cached "now playing" title here. |
| `metadata` | `{uri, name, artist_names[], album_name, album_cover_url, position, duration, release_date, track_number, disc_number}` | The loaded track's metadata (ms). |
| `playing` | `{context_uri, uri, resume, play_origin}` | Playback running. |
| `paused` | `{context_uri, uri, play_origin}` | Paused. |
| `not_playing` | `{context_uri, uri, play_origin}` | Track ended with nothing to play. |
| `stopped` | `{play_origin}` | Player stopped / unloaded. |
| `seek` | `{context_uri, uri, position, duration, play_origin}` | Position jumped (ms). |
| `volume` | `{value, max}` | Connect volume changed (steps). |
| `repeat_track` / `repeat_context` / `shuffle_context` | `{value: bool}` | Mode changed (setting repeat `track` also turns on `repeat_context`). |
| `queue_changed` | `{context_uri, uri, next_count}` | Context/current/upcoming changed → call `getQueueJSON()`. |
| `error` | `{source, message, uri?}` | A background operation failed (`advance`, `skip_next`, `load_context`, `connect_command`, …). |

`onLog(level, message)`: `level` is `trace|debug|info|warning|error|fatal`. All library
logs arrive here (structured fields appended as `key=value`); nothing goes to logcat
unless the app logs it.

---

## 5. Methods

`T` = throws on failure. Duplicate aliases exist for history (`api*` vs plain);
**prefer the plain names** listed first.

### 5.1 Playback

| Method | Contract |
|---|---|
| `playUri(uri, skipToUri, paused)` T | Play a context: track, episode, album, playlist, artist, show, `spotify:collection`, station. `skipToUri` = track inside it to start at (or `""`). |
| `playTracks(urisJson, startIndex, positionMs, paused)` T | Play an explicit list as one context (`spotify:internal:tracks`). Throws if `startIndex` out of range. |
| `play()` T | Resume. If the last load failed (network), **retries the load**; throws if it fails again. |
| `pause()` T | Pause. No-op if already paused. |
| `next()` T / `previous()` T | `previous` restarts the track if past ~3 s. |
| `skipToNextTrack(uri)` T / `skipToPrevTrack(uri)` T | Jump to a URI present in upcoming / history. |
| `seek(positionMs)` T | Lands on the nearest audio page: expect the position to read **1–3 s before** the target. |
| `setRepeat("off"\|"context"\|"track")` T | Invalid mode throws. |
| `setShuffle(bool)` T | Reorders upcoming; turning off restores order. |
| `setVolume(steps)` T | Clamped to `0..volume_steps` silently — read back with `getVolumeJSON()`. |
| `getVolumeJSON()` | `{"value": 8, "max": 16}`; `"{}"` if not started. |
| `setLocalGain(0..1)` | Local-only output scale (ducking/fades), not sent to Connect. |
| `getStatusJSON()` | `{active, playing, paused, buffering, stopped, context_uri, track_uri, track_uid, position_ms, timestamp_ms, duration_ms, shuffle, repeat_context, repeat_track, repeat_mode, volume, volume_max, local_gain}`. While `playing`, current position = `position_ms + (now - timestamp_ms)`. `duration_ms` is 0 when unknown (e.g. load failed). `"{}"` if not started. |

**Failed load contract:** if a track can't load, the player stays on that track,
`paused=true`, `buffering=false`, `duration_ms=0`; an `error` event may fire. `play()` retries.

### 5.2 Queue

| Method | Contract |
|---|---|
| `getQueueJSON()` | `{context_uri, current, prev[], next[]}`; each entry `{uri, uid, provider, name, duration_ms, isrc, explicit, has_lyrics, album{uri,name,image_url,thumbnail_url}, artists[{uri,name}]}`. `provider` = `context` \| `queue` (user-queued) \| `autoplay`. `prev` is oldest-first. `current` null when idle. |
| `addToQueue(uri)` T | Append to user queue (plays before the rest of the context). |
| `insertIntoQueue(uri, index)` T | Insert into user queue; 0 = next. Clamped. |
| `removeFromQueue(uri)` T | Remove first matching upcoming entry; throws if not found. |
| `setQueueJSON(urisJson)` T | Replace **everything after the current track** with this order. Turns shuffle off. User-queued items become context items (lose `provider: "queue"`), so `clearQueue()` won't remove them afterwards. |
| `clearQueue()` | Remove user-queued items only; the context's own upcoming tracks stay. |
| `contextTracksJSON(n)` | `{prev[], next[]}` of `{uri, name}`, up to n each side. Lightweight. |
| `apiGetQueue()` | Returns `QueueInfo` with only `current` usable (lists don't bind). Prefer `getQueueJSON`. |

### 5.3 Catalog / metadata

| Method | Contract |
|---|---|
| `getTracksMetadataJSON(urisJson)` T | **Batch** track/episode metadata: one request per kind for hundreds of URIs, cached in memory. Returns array in input order; duplicates and unresolvable URIs omitted. Entry: `{uri, name, duration_ms, isrc, explicit, track_number, disc_number, popularity, has_lyrics, album{uri,name,image_url,thumbnail_url,release_date,artists[]}, artists[{uri,name}]}`; episodes add `is_episode: true` and describe the show in `album`. **Use this for library-scale resolution.** |
| `getAlbumJSON(uri)` T | `{uri, name, type, image_url, thumbnail_url, release_date, year, artists[], total_tracks, label, copyrights[], tracks[<track details>]}`. |
| `getArtistJSON(uri)` T | `{uri, name, image_url, thumbnail_url, biography, top_tracks[], albums[], singles[], compilations[], appears_on[], related[{uri,name}]}`; album summary = `{uri,name,type,image_url,thumbnail_url,release_date,year,artists,total_tracks}`. |
| `apiGetMetadataForURI(uri)` T | Raw Spotify metadata proto as JSON (track/album/artist/playlist), protobuf field names. Low level — prefer the typed calls above. **Was always 404 before this build** (ID-conversion bug, now fixed). |
| `getLyricsJSON(uri)` T | **Currently always throws** `lyrics are not available to this client (Spotify returned 403)` — Spotify refuses lyrics for this client's token. Hide lyrics UI or handle the error. Shape if it ever works: `{uri, sync_type, language, provider, lines[{start_ms,end_ms,words}]}`. |
| `search(query, limit)` / `apiSearch` T | `[{spotify_id, title, description, kind, image_url?, duration_ms?}]`. `kind` lowercase (`track`, `album`, `artist`, `playlist`, `show`, `episode`). `spotify_id` is a full URI. Descriptions are plain text (HTML stripped) but can be long (episodes). `search` returns `"[]"` on error. |
| `searchSuggestions(query, limit)` / `apiSearchSuggestions` T | `[{suggestion, spotify_id, description}]`; `spotify_id` may be a `spotify:search:` URI. |
| `recentSearches(limit)` / `apiRecentSearches` T | `[{title, spotify_id, description, kind}]`, `kind` lowercase. |

### 5.4 Library

| Method | Contract |
|---|---|
| `apiGetRootLibraryItems()` T / `getRootLibraryItemsJSON()` | User's sidebar in order: `[{uri, name, description, image_url, kind, revision, length, folder}]`. **See §6 — folders changed.** `kind`: `playlist`, `folder`, `album`, `artist`, `show`, `collection`, `unknown`. `image_url` may be a `spotify:image:` URI (convert as the app already does). `revision` changes whenever the playlist changes. |
| `getPlaylistJSON(uri, knownRevision)` T | `{uri, name, description, image_url, owner, collaborative, revision, length, unchanged, tracks[{uri, added_at, added_by, item_id}]}`. Pass the last synced revision: if unchanged → `unchanged: true`, empty `tracks`, one tiny request. **`added_at` is unix milliseconds here.** |
| `getCollectionTracksJSON()` T | Liked Songs newest-first: `[{uri, added_at}]`, **`added_at` unix seconds**. Resolve with `getTracksMetadataJSON`. |
| `getSavedAlbumsJSON()` T | `[{uri, name, type, image_url, thumbnail_url, release_date, year, artists, total_tracks, added_at}]`, `added_at` seconds. |
| `isLikedJSON(urisJson)` T | `{"<uri>": true/false, ...}`. **Was always 400 before this build; fixed.** |
| `like(urisJson)` T / `unlike(urisJson)` T | Tracks → Liked Songs, albums → saved, artists/shows → follow, episodes → Your Episodes. |
| `createPlaylist(name)` T | Returns URI; adds it to the top of the library and names it. (**Before this build new playlists came out unnamed; fixed.**) |
| `renamePlaylist(uri, name)` T | Returns new revision. |
| `setPlaylistDescription(uri, text)` T | Returns new revision. |
| `addToPlaylist(uri, urisJson, position)` T | Negative position appends. Returns new revision. |
| `removeFromPlaylist(uri, index, expectedUri)` T | 0-based. Pass `expectedUri` to fail safely if the playlist changed underneath you; remove multiple entries from highest index down. Returns new revision. |
| `deletePlaylist(uri)` T | Removes from the user's library (Spotify's "delete" for owned playlists). Irreversible from the app's point of view — confirm with the user. |
| `apiGetRootPlaylist()` T / `getRootPlaylistJSON()` | Raw rootlist proto JSON. Low level; prefer root library items. |

### 5.5 Devices

| Method | Contract |
|---|---|
| `listDevicesJSON()` | `[{id, name, type, is_active, volume, supports_volume}]`, active first then by name. `volume` is **percent** (0–100), unlike `setVolume` steps. Falls back to just this device before the cluster arrives. |
| `transferPlayback(deviceId, play)` T | Move playback to another Connect device; `play=false` transfers paused. |

### 5.6 Automatic Ogg dump (save tracks as they play, play from the saved file)

While enabled, playback goes through a folder of tagged Ogg Vorbis files:

- **Track already in the folder** → it plays from disk. Only metadata is fetched
  (title, normalisation); the audio is not downloaded.
- **Track not in the folder** → the whole track is downloaded straight into its file,
  and playback decodes from that file while it fills (it starts as soon as the
  first bytes arrive, as with streaming). `onOggWritten` fires once the file is complete.

The bridge does all the file work; the app only picks the folder and listens for the
callback. While disabled, every track streams from Spotify, even ones already saved.

| Method | Contract |
|---|---|
| `setOggDumpCallback(cb)` | Register the `OggDumpCallback` (below). Call any time, before or after start; `null` removes it. Kept across `stop()`/start. |
| `enableOggDump(dir)` T | Play through `dir` (created if missing). `""` reuses the last folder (from config or a previous call); throws if there is none, or if the folder can't be created. Callable before start (applied at start). Affects tracks that **start loading after** the call; the current track is not back-filled or switched. |
| `disableOggDump()` | New tracks stream from Spotify again. Downloads in progress still finish and still fire the callback. Keeps the folder, so `enableOggDump("")` resumes. |
| `isOggDumpEnabled()` | Current on/off state. |
| `getOggDumpDir()` | The folder, even while disabled; `""` if never set. |

`OggDumpCallback` (both methods on a Go background thread, possibly concurrently for
different tracks):

| Method | When |
|---|---|
| `onOggWritten(uri, path, infoJson)` | The file at `path` is complete and in place. `infoJson`: `{uri, media_uri?, path, title, artists[], album, duration_ms, size}` (`size` = file bytes). Episodes: `album` = show name, `artists` = publisher. |
| `onOggDumpError(uri, message)` | The track could not be written (disk full, network, …). No partial file is left. Also logged via `onLog` as `warn`. |

File contract:

- **Path:** `<dir>/<base62 id>.ogg`, where the id is from `uri`, the URI playback
  requested (what `getQueueJSON` / the library show). Map a URI to its file with
  `uri.substringAfterLast(':') + ".ogg"`.
- **Relinking:** if Spotify substituted an equivalent track for this market,
  `media_uri` is set; the file holds that track's audio, and its `SPOTIFY_URI` tag
  names `media_uri`, not `uri`.
- **Existing files are played, not rewritten**, and fire no callback. To re-download,
  delete the file. The bridge trusts any `<id>.ogg` it finds: if the file can't be
  opened it streams instead, but if it opens and isn't valid Ogg Vorbis, loading the
  track fails (`error` event). Don't put other files under those names.
- **Atomic:** the bridge writes `.<id>.ogg.<random>.part` (hidden) and renames it into
  place, so any `*.ogg` in the folder is complete. Leftover `.part` files (process
  killed mid-write) can be deleted.
- **Content:** standard Ogg Vorbis (44.1 kHz stereo, the configured `bitrate`) with
  the track's metadata, ReplayGain and cover art embedded as Vorbis comments (see
  *How metadata is stored* below). Mode `0644`.
- **Full download, once:** a new track is downloaded completely at network speed
  (usually a few seconds), even if the user skips it after a moment; skipping does not
  cancel the download. Replays, repeat and seeks never download it again.
- **Seeking:** in a saved file a seek lands within one Ogg page (about 0.1–0.3 s),
  more accurate than streaming (1–3 s early, §7). While the file is still downloading,
  a seek past the downloaded part waits until the download reaches it.
- **Download failure:** if the network fails mid-track, playback of that track fails
  like a streaming failure (`error` event, `play()` retries), `onOggDumpError` fires
  and the partial file is removed.
- **Preloads count:** the next track is preloaded shortly before the current one ends,
  so it is saved even if the user then skips it.
- **Bitrate:** a saved file keeps the bitrate it was downloaded at; changing `bitrate`
  later doesn't replace it.
- **Folder choice:** use app-specific storage (`context.filesDir` or
  `getExternalFilesDir(...)`); the bridge needs plain filesystem write access and
  can't use SAF/`content://` URIs. To expose files in `MediaStore`, copy or scan them
  from `onOggWritten`.

#### How metadata is stored

Everything lives inside the `.ogg` itself: there are no sidecar files, database or
JSON next to it. The file is self-describing, so any tag reader can index it without
the bridge.

**Stream layout.** Spotify's files start with a proprietary page holding a seek table
and ReplayGain values, and most players reject it. The bridge rewrites the stream as
it downloads:

1. Spotify's proprietary first page: **removed** (its ReplayGain values become tags).
2. Vorbis identification header: copied unchanged.
3. Vorbis comment header: **rebuilt**. The encoder vendor string is kept; any comments
   Spotify put there are dropped and replaced by the tags below.
4. Vorbis setup header: copied unchanged (re-paged together with the new comment
   header).
5. Audio pages: copied byte for byte, except that page sequence numbers are renumbered
   and CRCs recomputed. The audio is never re-encoded, so the file is the same quality
   as the stream.

**Tags** (Vorbis comments, `KEY=value`, UTF-8). A tag is left out when Spotify has
no value for it. Tracks:

| Tag | Value | Source |
|---|---|---|
| `TITLE` | Track name | track |
| `ARTIST` | **One tag per artist**, in Spotify's order (not joined with `, ` or `;`) | track artists |
| `ALBUM` | Album name | album |
| `ALBUMARTIST` | One tag per album artist | album artists |
| `DATE` | `YYYY`, `YYYY-MM` or `YYYY-MM-DD`, as precise as Spotify knows | album release date |
| `LABEL` | Record label | album |
| `TRACKNUMBER` | Plain number, e.g. `3` (no `/total`) | track |
| `DISCNUMBER` | Plain number, e.g. `1` (no `/total`) | track |
| `ISRC` | Upper case | track external ids |
| `SPOTIFY_URI` | `spotify:track:<id>` of the **audio in the file**: `media_uri` when relinked, otherwise `uri` | |
| `SPOTIFY_ID` | Base62 id matching `SPOTIFY_URI` | |
| `REPLAYGAIN_TRACK_GAIN` | e.g. `-6.42 dB` | Spotify's first page |
| `REPLAYGAIN_TRACK_PEAK` | Linear, e.g. `0.987654` | 〃 |
| `REPLAYGAIN_ALBUM_GAIN` | e.g. `-7.10 dB` | 〃 |
| `REPLAYGAIN_ALBUM_PEAK` | Linear | 〃 |
| `METADATA_BLOCK_PICTURE` | Cover art (below) | album cover |

Episodes have `TITLE` = episode name, `ALBUM` = show name and `ARTIST` = publisher,
plus `SPOTIFY_URI`/`SPOTIFY_ID` (`spotify:episode:…`), ReplayGain when present and
the episode image. They have no track/disc numbers, date, label or ISRC.

**ReplayGain:** all four `REPLAYGAIN_*` tags are written, or none if Spotify's file
carries no gain values. They are Spotify's values passed through unchanged. The
bridge's own normalisation (on unless config `normalisation_disabled` is true) uses
separate loudness data from metadata (target −14 LUFS). If the app applies ReplayGain
from the tags itself, set `normalisation_disabled: true` to avoid adjusting twice.

**Cover art** is a standard `METADATA_BLOCK_PICTURE` comment: a base64-encoded FLAC
picture block with:

- picture type 3 (front cover)
- the MIME type (`image/jpeg`, occasionally `image/png`)
- the pixel width and height
- the raw image bytes

It is the largest size Spotify offers, typically a 640×640 JPEG, capped at 4 MiB. The
download runs in parallel with the audio key and has a 5 s timeout. If it fails or
times out, the file is written **without** a picture and is otherwise valid; the
bridge logs `warn` "writing ogg without cover art". The cover is never added to a file
later.

**Not stored:**

- duration (it comes from the stream itself; the callback's `duration_ms` has it too)
- the explicit flag, popularity, genres and lyrics
- the requested `uri` of a relinked track (only in the file name and the callback)
- the `bitrate` used

Get these from the catalog APIs (§5.3) by URI if needed.

**Reading it back on Android.** The tags are standard, so the app's existing tag code
reads them:

- **jaudiotagger** (already used by the tag editor):
  - `AudioFileIO.read(file).tag` is a `VorbisCommentTag`.
  - `getAll(FieldKey.ARTIST)` returns every artist.
  - `getFirst("SPOTIFY_URI")` reads the custom keys.
  - `firstArtwork` returns the decoded cover.
- **`MediaMetadataRetriever` / `MediaStore`:** read title, artist, album, album artist,
  date, track/disc numbers and the embedded picture. They may show only one of several
  `ARTIST` tags, and they ignore `SPOTIFY_*` and `REPLAYGAIN_*`.
- **No tag parsing:** the file name alone maps a file to Spotify (`<base62 id>.ogg` is
  the requested `uri`).

```kotlin
lib.setOggDumpCallback(object : OggDumpCallback {
    override fun onOggWritten(uri: String, path: String, infoJson: String) { /* index / scan path */ }
    override fun onOggDumpError(uri: String, message: String) { /* log */ }
})
lib.enableOggDump(File(context.filesDir, "spotify-ogg").path)
```

---

## 6. Changes in this build the app must handle

1. **Folders in root library items.** Previously folders leaked as two bogus rows per
   folder (`spotify:start-group:<id>:<name>` / `spotify:end-group:<id>`, kind `unknown`).
   Now each folder is one item `{uri: "spotify:folder:<id>", name, kind: "folder"}` and
   every item inside it carries `folder: "spotify:folder:<id>"` (nesting allowed; the
   field names the innermost folder). Folders can't be played.
   → `SpotifyLibrarySync.parseRootItems` ignores `folder` and stores folders as
   `SpotifyPlaylistEntity(kind="folder")`. Decide: skip them, or store parent/child.
   Delete any stored `start-group`/`end-group` rows from older syncs.
2. **Album sync path is now live.** `SpotifyLibrarySync` calls `engine.metadataJson(uri)`
   (`apiGetMetadataForURI`) for albums; that never succeeded before, so
   `SpotifyMetadataParser.parseAlbum` has never seen real data. Prefer switching to
   `getAlbumJSON(uri)` (typed, documented shape above); if keeping the raw call, verify
   the parser against real output.
3. **Playlists can now be synced cheaply.** The "skip playlists to avoid 404s" comment was
   caused by the same ID bug. Use `getPlaylistJSON(uri, storedRevision)` and skip when
   `unchanged`, or compare the root item's `revision` and skip without any call.
4. **`play()` / `pause()` now throw** on failure instead of silently succeeding
   (`LibrespotEngine.call` already catches and logs — fine).
5. **`onForeground` no longer resumes user-paused playback** (see §2).
6. **Logs now arrive via `onLog`** (previously most went to stderr and were lost).
7. **Descriptions are plain text** (HTML links/entities stripped) in library, playlist and
   search results.
8. **`isLikedJSON`, `apiGetMetadataForURI`, `clearQueue`, `createPlaylist` naming** fixed
   (see §5).
9. **New (additive): automatic Ogg dump** — `ogg_dump` config key, `enableOggDump`,
   `disableOggDump`, `isOggDumpEnabled`, `getOggDumpDir`, `setOggDumpCallback` and the
   `OggDumpCallback` interface (§5.6). While enabled, playback reads from the saved
   files (offline replay of saved tracks still needs a connection for metadata). Off
   unless enabled; nothing to change if unused.

## 7. Known limitations

- Lyrics: always throws (Spotify 403 for this client). Not fixable without a different token type.
- Seek lands 1–3 s early when streaming (not when playing a saved file, §5.6).
- Player setup failure at start is not retried by the bridge (§2).
- `added_at` units differ: playlists ms, collection/saved albums seconds.
- Liked Songs played via `playTracks` shows on other Connect devices as an anonymous
  list; `playUri("spotify:collection", skipToUri, false)` is documented as supported but
  untested in this build.
- Methods returning Go slices (`ApiListDevices`, `ContextTracks`) are not in the Java
  binding; use the `*JSON` variants.

## 8. Handoff contract between agents

- **Bridge side** (Go, `~/Developer/embedded-librespot/go-librespot`): owns everything in
  `mobilebind/`, `daemoncore/`, and this file's §1–§5 shapes. A change to a method name,
  parameter, JSON field or behaviour listed here is a **breaking change**: update this
  file and call it out explicitly.
- **App side** (this repo): consumes only what's documented here. If you need a field or
  method that isn't listed, request it rather than parsing undocumented output (e.g. the
  raw proto from `apiGetMetadataForURI`).
- **Additive changes** (new fields, new methods) are non-breaking; parse leniently.
- **Rebuild + install:** in `go-librespot`, run `./build-aar.sh`, then copy
  `librespot.aar` and `librespot-sources.jar` to `app/libs/`. Only `librespot.aar` is
  referenced by Gradle (`app/build.gradle.kts`); don't add a second copy of the binding
  (e.g. an old `mobilebind.aar`) — duplicate classes.
- **Testing the bridge without the app:** `go run ./examples/mobile_host` in `go-librespot`
  is an interactive shell over this same API (`help` lists commands). `oggdump on [dir]`
  / `oggdump off` / `oggdump` exercise §5.6 and print each callback.
