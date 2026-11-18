package code.name.monkey.retromusic.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE LyricsEntity")
        database.execSQL("DROP TABLE BlackListStoreEntity")
    }
}

/** Adds the Spotify catalogue cache. Must match the entities in SpotifyEntities.kt. */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `spotify_track` (`uri` TEXT NOT NULL, `local_id` INTEGER NOT NULL, " +
                    "`name` TEXT NOT NULL, `album_uri` TEXT, `duration_ms` INTEGER NOT NULL, " +
                    "`track_number` INTEGER NOT NULL, `disc_number` INTEGER NOT NULL, `isrc` TEXT, " +
                    "`artist_uris` TEXT NOT NULL, `artist_names` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`uri`))"
        )
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_spotify_track_local_id` ON `spotify_track` (`local_id`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_spotify_track_album_uri` ON `spotify_track` (`album_uri`)")

        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `spotify_album` (`uri` TEXT NOT NULL, `local_id` INTEGER NOT NULL, " +
                    "`name` TEXT NOT NULL, `image_url` TEXT, `year` INTEGER NOT NULL, " +
                    "`artist_uris` TEXT NOT NULL, `artist_names` TEXT NOT NULL, PRIMARY KEY(`uri`))"
        )
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_spotify_album_local_id` ON `spotify_album` (`local_id`)")

        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `spotify_artist` (`uri` TEXT NOT NULL, `local_id` INTEGER NOT NULL, " +
                    "`name` TEXT NOT NULL, `image_url` TEXT, PRIMARY KEY(`uri`))"
        )
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_spotify_artist_local_id` ON `spotify_artist` (`local_id`)")

        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `spotify_playlist` (`uri` TEXT NOT NULL, `local_id` INTEGER NOT NULL, " +
                    "`name` TEXT NOT NULL, `description` TEXT, `image_url` TEXT, `kind` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, `revision` TEXT, PRIMARY KEY(`uri`))"
        )
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_spotify_playlist_local_id` ON `spotify_playlist` (`local_id`)")

        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `spotify_playlist_track` (`playlist_uri` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, `track_uri` TEXT NOT NULL, PRIMARY KEY(`playlist_uri`, `position`))"
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_spotify_playlist_track_track_uri` ON `spotify_playlist_track` (`track_uri`)")
    }
}

/** Adds `spotify_playlist.editable`. Revisions are cleared so the next sync re-reads ownership. */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `spotify_playlist` ADD COLUMN `editable` INTEGER NOT NULL DEFAULT 0")
        database.execSQL("UPDATE `spotify_playlist` SET `revision` = NULL")
    }
}

/** Adds the index of saved Ogg dump tracks. Must match [SpotifySavedTrackEntity]. */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `spotify_saved_track` (`id` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                    "`size` INTEGER NOT NULL, `modified` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                    "`artists` TEXT NOT NULL, `album` TEXT NOT NULL, `album_artist` TEXT NOT NULL, " +
                    "`year` INTEGER NOT NULL, `track_number` INTEGER NOT NULL, `disc_number` INTEGER NOT NULL, " +
                    "`duration_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
    }
}
