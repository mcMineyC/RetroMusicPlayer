package code.name.monkey.retromusic.util

import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagField
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import java.io.File
import kotlin.math.pow

/**
 * Reads the tags Retro needs from audio files with jaudiotagger. Every call does disk I/O:
 * call off the main thread.
 */
object AudioTags {

    data class ReplayGain(
        val trackGainDb: Float?,
        val trackPeak: Float?,
        val albumGainDb: Float?,
        val albumPeak: Float?,
    ) {
        val isEmpty: Boolean get() = trackGainDb == null && albumGainDb == null

        /**
         * Linear volume for this file, at most 1 (players can't amplify through their volume),
         * so quiet tracks are not boosted. [preferAlbum] falls back to the track gain and vice
         * versa; the peak keeps the result from clipping.
         */
        fun volumeFactor(preferAlbum: Boolean): Float {
            val (gain, peak) = if (preferAlbum) {
                (albumGainDb ?: trackGainDb) to (if (albumGainDb != null) albumPeak else trackPeak)
            } else {
                (trackGainDb ?: albumGainDb) to (if (trackGainDb != null) trackPeak else albumPeak)
            }
            if (gain == null) return 1f
            var factor = 10.0.pow(gain / 20.0).toFloat()
            if (peak != null && peak > 0f) factor = minOf(factor, 1f / peak)
            return factor.coerceIn(0f, 1f)
        }
    }

    data class Info(
        val title: String,
        val artists: List<String>,
        val album: String,
        val albumArtist: String,
        val year: Int,
        val trackNumber: Int,
        val discNumber: Int,
        val durationMs: Long,
        val replayGain: ReplayGain,
    )

    /** Title, artists, album, numbering, duration and ReplayGain; null if unreadable. */
    fun read(file: File): Info? = try {
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tag
        Info(
            title = tag?.first(FieldKey.TITLE).orEmpty(),
            artists = tag?.all(FieldKey.ARTIST).orEmpty(),
            album = tag?.first(FieldKey.ALBUM).orEmpty(),
            albumArtist = tag?.first(FieldKey.ALBUM_ARTIST).orEmpty(),
            year = tag?.first(FieldKey.YEAR)?.take(4)?.toIntOrNull() ?: 0,
            trackNumber = tag?.first(FieldKey.TRACK).leadingInt(),
            discNumber = tag?.first(FieldKey.DISC_NO).leadingInt(),
            durationMs = (audioFile.audioHeader.preciseTrackLength * 1000).toLong(),
            replayGain = tag?.let(::replayGainOf) ?: ReplayGain(null, null, null, null),
        )
    } catch (e: Exception) {
        Log.w(TAG, "Could not read tags from ${file.path}: ${e.message}")
        null
    }

    /** Only the ReplayGain values; null if the file can't be read. */
    fun readReplayGain(file: File): ReplayGain? = try {
        AudioFileIO.read(file).tag?.let(::replayGainOf)
    } catch (e: Exception) {
        Log.w(TAG, "Could not read ReplayGain from ${file.path}: ${e.message}")
        null
    }

    /**
     * ReplayGain lives in format-specific places: Vorbis comments (Ogg/FLAC/Opus), ID3v2 TXXX
     * frames (MP3) and iTunes reverse-DNS atoms (MP4). Collect them by name, case-insensitively.
     */
    private fun replayGainOf(tag: Tag): ReplayGain {
        val values = HashMap<String, String>()
        fun add(name: String?, value: String?) {
            if (name.isNullOrEmpty() || value.isNullOrBlank()) return
            values.putIfAbsent(name.uppercase(), value)
        }

        val all = ArrayList<TagField>()
        tag.fields.forEach { all.add(it) }
        // ID3v2 keeps repeated TXXX frames together; ask for them directly as well.
        if (tag is AbstractID3v2Tag) all.addAll(tag.getFields("TXXX").orEmpty())
        for (field in all) {
            val frame = field as? AbstractID3v2Frame
            val txxx = frame?.body as? FrameBodyTXXX
            if (txxx != null) {
                add(txxx.description, txxx.firstTextValue)
            } else {
                add(field.id?.substringAfterLast(':'), (field as? TagTextField)?.content)
            }
        }
        return ReplayGain(
            trackGainDb = values["REPLAYGAIN_TRACK_GAIN"].parseNumber(),
            trackPeak = values["REPLAYGAIN_TRACK_PEAK"].parseNumber(),
            albumGainDb = values["REPLAYGAIN_ALBUM_GAIN"].parseNumber(),
            albumPeak = values["REPLAYGAIN_ALBUM_PEAK"].parseNumber(),
        )
    }

    private val NUMBER = Regex("[-+]?\\d+(?:[.,]\\d+)?")

    /** "-6.54 dB" → -6.54. */
    private fun String?.parseNumber(): Float? =
        this?.let { NUMBER.find(it)?.value?.replace(',', '.')?.toFloatOrNull() }

    /** "3/12" → 3. */
    private fun String?.leadingInt(): Int =
        this?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0

    private fun Tag.first(key: FieldKey): String? =
        try {
            getFirst(key)?.trim()?.ifEmpty { null }
        } catch (e: Exception) {
            null
        }

    private fun Tag.all(key: FieldKey): List<String> =
        try {
            getAll(key).map { it.trim() }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }

    private const val TAG = "AudioTags"
}
