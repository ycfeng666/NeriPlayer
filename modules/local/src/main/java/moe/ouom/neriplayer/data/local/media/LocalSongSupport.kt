package moe.ouom.neriplayer.data.local.media

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.local.media/LocalSongSupport
 * Updated: 2026/3/23
 */

import android.content.Context
import androidx.core.net.toUri
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import java.io.File
import java.util.Locale

object LocalSongSupport {
    const val LOCAL_ALBUM_IDENTITY = "__local_files__"

    fun isLocalSong(song: SongItem, context: Context? = null): Boolean {
        return !song.localFilePath.isNullOrBlank() ||
            isLocalMediaUri(song.mediaUri) ||
            isLikelyLegacyLocalSong(song, context)
    }

    fun isLocalSong(
        album: String?,
        mediaUri: String?,
        albumId: Long? = null,
        context: Context? = null
    ): Boolean {
        return isLocalMediaUri(mediaUri) ||
            (
                mediaUri.isNullOrBlank() &&
                    albumId == 0L &&
                    isLocalAlbumPlaceholder(album, context)
            )
    }

    internal fun isLocalSong(
        album: String?,
        mediaUri: String?,
        albumId: Long?,
        localAlbumNames: Set<String>
    ): Boolean = isLocalMediaUri(mediaUri) ||
        (mediaUri.isNullOrBlank() && albumId == 0L && !album.isNullOrBlank() &&
            (album == LOCAL_ALBUM_IDENTITY || localAlbumNames.any { it.equals(album, ignoreCase = true) }))

    fun isLocalMediaUri(mediaUri: String?): Boolean =
        moe.ouom.neriplayer.data.sync.policy.isLocalMediaUri(mediaUri)

    fun sanitizeMediaUriForSync(mediaUri: String?): String? {
        return mediaUri?.takeUnless { isLocalMediaUri(it) }
    }

    fun identityMediaReference(song: SongItem): String? {
        val preferred = preferredLocalMediaReference(
            localFilePath = song.localFilePath,
            mediaUri = song.mediaUri
        )
        return normalizedLocalReference(preferred)
            ?: normalizedLocalReference(song.localFilePath)
            ?: normalizedLocalReference(song.mediaUri)
            ?: preferred
            ?: song.localFilePath
            ?: song.mediaUri
    }

    fun localDuplicateKeys(
        song: SongItem,
        includeMetadataFallback: Boolean = false
    ): Set<String> {
        if (!isLocalSong(song, null)) {
            return emptySet()
        }

        return buildSet {
            normalizedLocalReference(song.localFilePath)?.let { add("ref:$it") }
            normalizedLocalReference(song.mediaUri)?.let { add("ref:$it") }

            val localAudioId = song.audioId
                ?.trim()
                ?.takeIf { it.isNotBlank() && song.channelId.equals("local", ignoreCase = true) }
            localAudioId?.let { add("audio:$it") }
            song.sourceStableKey
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { add("source:$it") }

            // 元信息只能识别历史上丢失来源引用的条目, 不能把两个真实文件合并
            if (includeMetadataFallback && isEmpty()) {
                addMetadataFallbackKeys(song)
            }
        }
    }

    fun hasSameLocalSource(
        first: SongItem,
        second: SongItem,
        includeMetadataFallback: Boolean = false
    ): Boolean {
        val firstKeys = localDuplicateKeys(first, includeMetadataFallback)
        if (firstKeys.isEmpty()) {
            return false
        }
        return localDuplicateKeys(second, includeMetadataFallback).any(firstKeys::contains)
    }

    private fun isLikelyLegacyLocalSong(song: SongItem, context: Context?): Boolean {
        return song.mediaUri.isNullOrBlank() &&
            song.albumId == 0L &&
            isLocalAlbumPlaceholder(song.album, context)
    }

    private fun isLocalAlbumPlaceholder(album: String?, context: Context?): Boolean {
        if (album.isNullOrBlank()) return false
        return album == LOCAL_ALBUM_IDENTITY || LocalFilesPlaylist.matches(album, context)
    }

    internal fun identityAlbumKey(song: SongItem): String {
        return if (isLocalSong(song, null)) LOCAL_ALBUM_IDENTITY else song.album
    }

    /**
     * 本地曲在 hydrate（补时长/封面/歌词）之后、回写歌单之前，必须把本地身份钉死。
     *
     * 关键点是**清空 [SongItem.sourceStableKey]**：它常常是从 `.npmeta.json` sidecar
     * 继承来的远端（网易云）身份，而 [SongItem.identity] 会优先采信它
     * （见 `normalizedSourceStableIdentity`），把整首本地曲判成在线曲、
     * 把 `mediaUri` 归一化成 null；紧接着
     * `recoverNeteaseRemoteSourceFromStaleLocalCopy` 还会顺势把它彻底转成在线曲。
     * 外部表现就是「第一次点能播，之后退化为在线源」。
     *
     * 注意：一旦降级，`isLocalSong` 会返回 false，本函数会直接跳过，
     * 因此必须在**回写那一刻**就钉死，不能指望事后补救。
     *
     * 这里只覆盖身份与本地引用相关字段，保留 hydrate 带来的时长、封面和歌词。
     */
    fun preserveLocalIdentityOnHydration(original: SongItem, hydrated: SongItem): SongItem {
        if (!isLocalSong(original, null)) return hydrated
        if (hydrated == original) return hydrated
        return hydrated.copy(
            id = original.id,
            album = LOCAL_ALBUM_IDENTITY,
            albumId = original.albumId,
            mediaUri = original.mediaUri ?: hydrated.mediaUri,
            localFilePath = original.localFilePath ?: hydrated.localFilePath,
            localFileName = original.localFileName ?: hydrated.localFileName,
            channelId = original.channelId ?: hydrated.channelId,
            audioId = original.audioId ?: hydrated.audioId,
            subAudioId = original.subAudioId,
            // 本地曲不能携带远端来源身份，否则 identity() 会把它判成在线曲
            sourceStableKey = null
        )
    }

    private fun MutableSet<String>.addMetadataFallbackKeys(song: SongItem) {
        val durationMs = song.durationMs.takeIf { it > 0L } ?: return
        val fileName = localFileName(song)
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
        fileName?.let { add("file:$it|$durationMs") }

        val title = (song.originalName ?: song.name)
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() }
        val artist = (song.originalArtist ?: song.artist)
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() }
        if (title != null && artist != null) {
            add("meta:$title|$artist|$durationMs")
        }
    }

    private fun localFileName(song: SongItem): String? {
        song.localFileName?.takeIf { it.isNotBlank() }?.let { return it }
        song.localFilePath?.takeIf { it.isNotBlank() }?.let { return File(it).name }
        val mediaUri = song.mediaUri?.takeIf { it.isNotBlank() } ?: return null
        if (mediaUri.startsWith("/")) {
            return File(mediaUri).name
        }
        return runCatching { mediaUri.toUri().lastPathSegment }.getOrNull()
    }

    private fun normalizedLocalReference(reference: String?): String? {
        val raw = reference?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (raw.startsWith("/")) {
            return File(raw).absolutePath
        }
        if (raw.startsWith("file://", ignoreCase = true)) {
            runCatching {
                java.net.URI(raw).path?.takeIf { it.isNotBlank() }
            }.getOrNull()?.let { return File(it).absolutePath }
        }
        if (
            raw.startsWith("content://", ignoreCase = true) ||
            raw.startsWith("android.resource://", ignoreCase = true)
        ) {
            return raw
        }

        val uri = runCatching { raw.toUri() }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            null, "" -> uri.path?.takeIf { it.startsWith("/") }?.let { File(it).absolutePath }
            "file" -> uri.path?.takeIf { it.isNotBlank() }?.let { File(it).absolutePath }
            "content", "android.resource" -> uri.toString()
            else -> null
        }
    }
}
