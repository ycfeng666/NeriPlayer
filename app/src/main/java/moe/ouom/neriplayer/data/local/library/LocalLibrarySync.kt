package moe.ouom.neriplayer.data.local.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import kotlin.math.absoluteValue

object LocalLibrarySync {
    private const val TAG = "LocalLibrarySync"

    data class SyncResult(
        val syncedPlaylists: Int,
        val skippedPlaylists: Int,
        val totalSongs: Int,
        val errors: List<String>,
        val debugInfo: String = ""
    )

    fun interface OnProgressListener {
        fun onProgress(current: Int, total: Int, message: String)
    }

    private val CHILD_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )

    suspend fun syncLibrary(
        context: Context,
        libraryRootUri: Uri,
        playlistRepository: LocalPlaylistRepository,
        onProgress: OnProgressListener? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        var syncedPlaylists = 0
        var skippedPlaylists = 0
        var totalSongs = 0
        val errors = mutableListOf<String>()
        var rootChildren = emptyList<ChildEntry>()
        var directories = emptyList<ChildEntry>()

        try {
            NPLogger.d(TAG, "===== 开始同步本地音乐库 =====")
            NPLogger.d(TAG, "下载目录URI: $libraryRootUri")
            NPLogger.d(TAG, "URI scheme: ${libraryRootUri.scheme}")
            NPLogger.d(TAG, "URI authority: ${libraryRootUri.authority}")

            var existingPlaylistNames = playlistRepository.playlists.first().map { it.name }.toSet()
            NPLogger.d(TAG, "已存在歌单 (${existingPlaylistNames.size}个): $existingPlaylistNames")

            rootChildren = queryChildren(context, libraryRootUri)
            NPLogger.d(TAG, "根目录子项数量: ${rootChildren.size}")
            rootChildren.forEach { child ->
                NPLogger.d(TAG, "  子项: name='${child.name}' isDirectory=${child.isDirectory} uri=${child.documentUri}")
            }

            directories = rootChildren.filter { it.isDirectory }
            val totalFolders = directories.size
            NPLogger.d(TAG, "根目录下分类文件夹数量: ${directories.size}")
            onProgress?.onProgress(0, totalFolders, "开始同步...")

            if (directories.isEmpty()) {
                NPLogger.w(TAG, "根目录下没有找到任何分类文件夹！请确认下载目录下是否包含分类子文件夹")
            }

            for (child in directories) {
                val categoryName = child.name
                val currentIndex = directories.indexOf(child) + 1
                NPLogger.d(TAG, "处理分类文件夹: $categoryName ($currentIndex/$totalFolders)")
                onProgress?.onProgress(currentIndex, totalFolders, "正在扫描: $categoryName")

                val normalizedCategoryName = categoryName.replace(Regex("[^\\w\\s\\u4e00-\\u9fff]"), "").trim()
                if (existingPlaylistNames.any { 
                    it.replace(Regex("[^\\w\\s\\u4e00-\\u9fff]"), "").trim()
                        .equals(normalizedCategoryName, ignoreCase = true) 
                }) {
                    skippedPlaylists++
                    NPLogger.d(TAG, "跳过已存在的歌单: $categoryName")
                    continue
                }

                try {
                    val songs = scanCategoryFolder(context, child.documentUri)
                    NPLogger.d(TAG, "分类文件夹 '$categoryName' 扫描到歌曲数量: ${songs.size}")
                    if (songs.isEmpty()) {
                        NPLogger.d(TAG, "分类文件夹无歌曲, 跳过: $categoryName")
                        continue
                    }

                    songs.forEachIndexed { idx, song ->
                        NPLogger.d(TAG, "  歌曲[$idx]: name='${song.name}' artist='${song.artist}' uri='${song.mediaUri?.take(100)}'")
                    }

                    playlistRepository.createPlaylistWithPreparedSongs(categoryName, songs)
                    syncedPlaylists++
                    totalSongs += songs.size
                    existingPlaylistNames = existingPlaylistNames + categoryName
                    NPLogger.d(TAG, "同步歌单成功: $categoryName (${songs.size} 首)")
                } catch (e: Exception) {
                    val msg = "同步歌单 '$categoryName' 失败: ${e.message}"
                    errors.add(msg)
                    NPLogger.e(TAG, msg, e)
                }
            }

            onProgress?.onProgress(totalFolders, totalFolders, "同步完成")
            NPLogger.d(TAG, "===== 同步完成: 成功=$syncedPlaylists, 跳过=$skippedPlaylists, 歌曲=$totalSongs, 错误=${errors.size} =====")
        } catch (e: Exception) {
            val msg = "同步失败: ${e.message}"
            errors.add(msg)
            NPLogger.e(TAG, "同步音乐库失败", e)
        }

        val debugInfo = buildString {
            appendLine("根目录子项数量: ${rootChildren.size}")
            rootChildren.forEach { appendLine("  子项: ${it.name} (${if (it.isDirectory) "目录" else "文件"})") }
            appendLine("分类文件夹数: ${directories.size}")
            directories.forEach { appendLine("  ${it.name}") }
        }
        NPLogger.d(TAG, "调试信息:\n$debugInfo")
        SyncResult(syncedPlaylists, skippedPlaylists, totalSongs, errors, debugInfo)
    }

    private fun queryChildren(context: Context, parentUri: Uri): List<ChildEntry> {
        NPLogger.d(TAG, "queryChildren: parentUri=$parentUri")

        val parentFile = DocumentFile.fromTreeUri(context, parentUri)
        if (parentFile != null) {
            NPLogger.d(TAG, "queryChildren: DocumentFile name=${parentFile.name} exists=${parentFile.exists()}")
            val files = parentFile.listFiles()
            NPLogger.d(TAG, "queryChildren: DocumentFile.listFiles 返回 ${files.size} 个文件")
            if (files.isNotEmpty()) {
                return files.mapNotNull { file ->
                    val name = file.name ?: return@mapNotNull null
                    NPLogger.d(TAG, "queryChildren: 找到子项 name=$name isDirectory=${file.isDirectory} uri=${file.uri}")
                    ChildEntry(
                        name = name,
                        documentUri = file.uri,
                        isDirectory = file.isDirectory
                    )
                }
            }
            NPLogger.w(TAG, "queryChildren: DocumentFile.listFiles 为空，尝试 DocumentsContract 方式")
        } else {
            NPLogger.w(TAG, "queryChildren: DocumentFile.fromTreeUri 返回 null，尝试 DocumentsContract 方式")
        }

        return queryChildrenWithDocumentsContract(context, parentUri)
    }

    private fun queryChildrenWithDocumentsContract(context: Context, parentUri: Uri): List<ChildEntry> {
        NPLogger.d(TAG, "queryChildrenWithDocumentsContract: parentUri=$parentUri")
        val documentId = runCatching { DocumentsContract.getDocumentId(parentUri) }.getOrNull()
        NPLogger.d(TAG, "queryChildrenWithDocumentsContract: documentId=$documentId")
        if (documentId == null) {
            NPLogger.w(TAG, "queryChildrenWithDocumentsContract: 无法获取documentId")
            return emptyList()
        }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, documentId)
        NPLogger.d(TAG, "queryChildrenWithDocumentsContract: childrenUri=$childrenUri")
        val cursor = try {
            context.contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
        } catch (e: Exception) {
            NPLogger.e(TAG, "查询子项失败: ${e.message}", e)
            return emptyList()
        } ?: return emptyList()

        return cursor.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            NPLogger.d(TAG, "queryChildrenWithDocumentsContract: column indices id=$idIdx name=$nameIdx mime=$mimeIdx")
            if (idIdx < 0 || nameIdx < 0) {
                NPLogger.w(TAG, "queryChildrenWithDocumentsContract: 无法找到所需的列")
                return@use emptyList()
            }

            buildList {
                while (c.moveToNext()) {
                    val childId = c.getString(idIdx) ?: continue
                    val childName = c.getString(nameIdx) ?: continue
                    val childMimeType = c.getString(mimeIdx).orEmpty()
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, childId)
                    NPLogger.d(TAG, "queryChildrenWithDocumentsContract: 查到子项 id=$childId name=$childName mime=$childMimeType")
                    add(
                        ChildEntry(
                            name = childName,
                            documentUri = childUri,
                            isDirectory = childMimeType == DocumentsContract.Document.MIME_TYPE_DIR
                        )
                    )
                }
            }.also {
                NPLogger.d(TAG, "queryChildrenWithDocumentsContract: 共查到 ${it.size} 个子项")
            }
        }
    }

    private data class ChildEntry(
        val name: String,
        val documentUri: Uri,
        val isDirectory: Boolean
    )

    private suspend fun scanCategoryFolder(
        context: Context,
        categoryUri: Uri
    ): List<SongItem> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<SongItem>()

        NPLogger.d(TAG, "scanCategoryFolder: categoryUri=$categoryUri")
        val children = queryChildren(context, categoryUri)
        NPLogger.d(TAG, "scanCategoryFolder: 子项数量: ${children.size}")

        for (child in children) {
            if (child.isDirectory) {
                NPLogger.d(TAG, "scanCategoryFolder: 递归扫描子目录: ${child.name}")
                val subSongs = scanCategoryFolder(context, child.documentUri)
                songs.addAll(subSongs)
            } else if (child.name.startsWith(".")) {
                NPLogger.d(TAG, "scanCategoryFolder: 跳过隐藏文件: ${child.name}")
                continue
            } else if (isAudioFile(child.name)) {
                NPLogger.d(TAG, "scanCategoryFolder: 找到音频文件: ${child.name}")
                val songItem = createSongItem(child)
                if (songItem != null) {
                    songs.add(songItem)
                }
            } else {
                NPLogger.d(TAG, "scanCategoryFolder: 跳过非音频文件: ${child.name}")
            }
        }

        NPLogger.d(TAG, "scanCategoryFolder: 共找到 ${songs.size} 首歌曲")
        songs
    }

    private fun isAudioFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in listOf("flac", "mp3", "wav", "ogg", "m4a", "aac", "wma")
    }

    private fun createSongItem(child: ChildEntry): SongItem? {
        try {
            val title = extractTitle(child.name)
            val artist = extractArtist(child.name)

            val uriString = child.documentUri.toString()
            val songId = uriString.hashCode().toLong().let { h ->
                if (h == 0L) 1L else h.absoluteValue
            }
            NPLogger.d(TAG, "createSongItem: name=${child.name} title=$title artist=$artist id=$songId")
            return SongItem(
                id = songId,
                name = title,
                artist = artist,
                album = "",
                albumId = 0L,
                durationMs = 0L,
                coverUrl = null,
                mediaUri = uriString,
                localFilePath = null
            )
        } catch (e: Exception) {
            NPLogger.e(TAG, "创建歌曲项失败: name=${child.name} uri=${child.documentUri}", e)
            return null
        }
    }

    private fun extractTitle(fileName: String): String {
        val nameWithoutExt = fileName.substringBeforeLast('.')
        val parts = nameWithoutExt.split(" - ")
        return if (parts.size >= 2) parts[1].trim() else nameWithoutExt
    }

    private fun extractArtist(fileName: String): String {
        val nameWithoutExt = fileName.substringBeforeLast('.')
        val parts = nameWithoutExt.split(" - ")
        return if (parts.size >= 2) parts[0].trim() else "未知艺术家"
    }
}