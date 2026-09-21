package moe.ouom.neriplayer.ui.screen.tab.settings.component

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.library.LocalLibrarySync
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.core.logging.NPLogger

@Composable
internal fun SettingsLocalLibrarySyncSection(
    downloadDirectoryUri: String?,
    playlistRepository: LocalPlaylistRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSyncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var syncProgress by remember { mutableStateOf(0f) }
    var syncProgressText by remember { mutableStateOf<String?>(null) }

    MiuixSettingsSectionCard {
        MiuixSettingsSectionIntro(
            title = stringResource(R.string.settings_local_library_sync),
            description = stringResource(R.string.settings_local_library_sync_desc)
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiuixSettingsOutlinedButton(
                    onClick = {
                        if (downloadDirectoryUri.isNullOrBlank()) {
                            syncMessage = "请先在「存储与管理」中配置下载目录"
                            return@MiuixSettingsOutlinedButton
                        }
                        isSyncing = true
                        syncMessage = null
                        syncProgress = 0f
                        syncProgressText = "准备中..."
                        scope.launch {
                            try {
                                val uri = Uri.parse(downloadDirectoryUri)
                                val result = LocalLibrarySync.syncLibrary(
                                    context = context,
                                    libraryRootUri = uri,
                                    playlistRepository = playlistRepository,
                                    onProgress = LocalLibrarySync.OnProgressListener { current, total, message ->
                                        syncProgress = if (total > 0) current.toFloat() / total else 0f
                                        syncProgressText = message
                                    }
                                )

                                if (result.errors.isNotEmpty()) {
                                    syncMessage = "同步完成，但有 ${result.errors.size} 个错误"
                                } else if (result.syncedPlaylists == 0 && result.skippedPlaylists == 0) {
                                    syncMessage = result.debugInfo.ifBlank { "没有找到新的歌单" }
                                    if (result.errors.isNotEmpty()) {
                                        syncMessage = "错误: ${result.errors.first().take(100)}"
                                    }
                                } else {
                                    syncMessage = "同步完成: 新建 ${result.syncedPlaylists} 个歌单 (${result.totalSongs} 首), 跳过 ${result.skippedPlaylists} 个"
                                }
                            } catch (e: Exception) {
                                val msg = "同步失败: ${e.message}"
                                NPLogger.e("SettingsLocalLibrarySync", msg, e)
                                syncMessage = msg
                            } finally {
                                isSyncing = false
                                syncProgress = 0f
                                syncProgressText = null
                            }
                        }
                    },
                    enabled = !isSyncing
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_local_library_sync_button))
                }
            }

            if (isSyncing && syncProgressText != null) {
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(
                        text = syncProgressText!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { syncProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (syncMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = syncMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncMessage!!.contains("失败") || syncMessage!!.contains("failed")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}