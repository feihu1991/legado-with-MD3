package io.legado.app.ui.book.read.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.CharacterVoice
import io.legado.app.help.mimo.MiMoTtsApi
import io.legado.app.model.ReadBook
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MiMo 音色管理界面
 */
@Composable
fun MiMoVoiceManageSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
) {
    if (!show) return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val book = ReadBook.book
    val bookId = book?.bookUrl?.hashCode()?.toLong() ?: 0L

    var voices by remember { mutableStateOf<List<CharacterVoice>>(emptyList()) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var analysisMessage by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingVoice by remember { mutableStateOf<CharacterVoice?>(null) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }

    // 加载音色列表
    LaunchedEffect(bookId) {
        voices = withContext(Dispatchers.IO) {
            appDb.characterVoiceDao.getByBookId(bookId)
        }
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🎭 MiMo 多角色朗读",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { showApiKeyDialog = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "设置API Key")
                }
            }

            Text(
                text = "为《${book?.name ?: ""}》配置角色音色",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MediumTonalButton(
                    onClick = {
                        scope.launch {
                            isAnalyzing = true
                            analysisMessage = "正在分析书籍角色..."
                            try {
                                val key = withContext(Dispatchers.IO) {
                                    context.getSharedPreferences("mimo_tts", 0)
                                        .getString("mimo_tts_api_key", "") ?: ""
                                }
                                if (key.isBlank()) {
                                    analysisMessage = "请先配置 API Key"
                                    isAnalyzing = false
                                    return@launch
                                }

                                // 取前3章内容进行分析
                                val bookText = withContext(Dispatchers.IO) {
                                    val chapters = appDb.bookChapterDao.getChapterList(book!!.bookUrl)
                                    val sb = StringBuilder()
                                    for (i in 0 until minOf(3, chapters.size)) {
                                        val content = io.legado.app.help.book.BookHelp.getContent(book, chapters[i])
                                        if (content != null) {
                                            sb.appendLine(content.take(2000))
                                            sb.appendLine("---")
                                        }
                                    }
                                    sb.toString()
                                }

                                if (bookText.isBlank()) {
                                    analysisMessage = "无法获取书籍内容"
                                    isAnalyzing = false
                                    return@launch
                                }

                                val baseUrl = withContext(Dispatchers.IO) {
                                    context.getSharedPreferences("mimo_tts", 0)
                                        .getString("mimo_tts_base_url", "https://api.xiaomimimo.com/v1")
                                        ?: "https://api.xiaomimimo.com/v1"
                                }

                                val characters = MiMoTtsApi.analyzeCharacters(bookText, key, baseUrl)

                                // 保存到数据库
                                withContext(Dispatchers.IO) {
                                    val voiceList = mutableListOf<CharacterVoice>()
                                    // 添加旁白
                                    voiceList.add(
                                        CharacterVoice(
                                            bookId = bookId,
                                            characterName = "旁白",
                                            presetVoiceId = "baihua",
                                            isNarrator = true,
                                            sampleText = "在那遥远的地方，有一片广袤的大地。"
                                        )
                                    )
                                    // 添加角色
                                    for (char in characters) {
                                        if (char.name.isNotBlank()) {
                                            voiceList.add(
                                                CharacterVoice(
                                                    bookId = bookId,
                                                    characterName = char.name,
                                                    presetVoiceId = char.suggestedVoice,
                                                    voiceDescription = "${char.gender}${char.age}，${char.personality}，${char.voiceHint}",
                                                    sampleText = "我是${char.name}。"
                                                )
                                            )
                                        }
                                    }
                                    appDb.characterVoiceDao.deleteByBookId(bookId)
                                    appDb.characterVoiceDao.insertAll(voiceList)
                                    voices = appDb.characterVoiceDao.getByBookId(bookId)
                                }
                                analysisMessage = "分析完成，识别到 ${characters.size} 个角色"
                            } catch (e: Exception) {
                                analysisMessage = "分析失败: ${e.localizedMessage}"
                            } finally {
                                isAnalyzing = false
                            }
                        }
                    },
                    icon = Icons.Default.AutoAwesome,
                    text = if (isAnalyzing) "分析中..." else "AI 分析角色",
                    enabled = !isAnalyzing
                )

                MediumTonalButton(
                    onClick = { showAddDialog = true },
                    icon = Icons.Default.Add,
                    text = "手动添加"
                )
            }

            if (analysisMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = analysisMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(16.dp))

            // 角色列表
            if (voices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "暂无角色配置\n点击「AI 分析角色」自动识别",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(voices) { voice ->
                        VoiceItem(
                            voice = voice,
                            onEdit = { editingVoice = voice },
                            onDelete = {
                                scope.launch(Dispatchers.IO) {
                                    appDb.characterVoiceDao.delete(voice)
                                    voices = appDb.characterVoiceDao.getByBookId(bookId)
                                }
                            },
                            onPreview = {
                                scope.launch {
                                    try {
                                        val key = withContext(Dispatchers.IO) {
                                            context.getSharedPreferences("mimo_tts", 0)
                                                .getString("mimo_tts_api_key", "") ?: ""
                                        }
                                        val baseUrl = withContext(Dispatchers.IO) {
                                            context.getSharedPreferences("mimo_tts", 0)
                                                .getString("mimo_tts_base_url", "https://api.xiaomimimo.com/v1")
                                                ?: "https://api.xiaomimimo.com/v1"
                                        }
                                        // TODO: 播放预览音频
                                        analysisMessage = "预览功能开发中..."
                                    } catch (e: Exception) {
                                        analysisMessage = "预览失败: ${e.localizedMessage}"
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 添加角色对话框
    if (showAddDialog) {
        AddCharacterDialog(
            bookId = bookId,
            onDismiss = { showAddDialog = false },
            onSaved = {
                showAddDialog = false
                scope.launch(Dispatchers.IO) {
                    voices = appDb.characterVoiceDao.getByBookId(bookId)
                }
            }
        )
    }

    // 编辑角色对话框
    editingVoice?.let { voice ->
        EditVoiceDialog(
            voice = voice,
            onDismiss = { editingVoice = null },
            onSaved = {
                editingVoice = null
                scope.launch(Dispatchers.IO) {
                    voices = appDb.characterVoiceDao.getByBookId(bookId)
                }
            }
        )
    }

    // API Key 配置对话框
    if (showApiKeyDialog) {
        var keyInput by remember { mutableStateOf("") }
        var baseUrlInput by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                keyInput = context.getSharedPreferences("mimo_tts", 0)
                    .getString("mimo_tts_api_key", "") ?: ""
                baseUrlInput = context.getSharedPreferences("mimo_tts", 0)
                    .getString("mimo_tts_base_url", "https://api.xiaomimimo.com/v1")
                    ?: "https://api.xiaomimimo.com/v1"
            }
        }

        androidx.compose.ui.window.Dialog(onDismissRequest = { showApiKeyDialog = false }) {
            Column(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.shapes.extraLarge
                    )
                    .padding(24.dp)
            ) {
                Text(
                    text = "MiMo API 配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrlInput,
                    onValueChange = { baseUrlInput = it },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showApiKeyDialog = false }) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            context.getSharedPreferences("mimo_tts", 0).edit()
                                .putString("mimo_tts_api_key", keyInput)
                                .putString("mimo_tts_base_url", baseUrlInput)
                                .apply()
                        }
                        showApiKeyDialog = false
                    }) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceItem(
    voice: CharacterVoice,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPreview: () -> Unit,
) {
    val voiceName = CharacterVoice.PRESET_VOICES
        .find { it.id == voice.presetVoiceId }?.name ?: voice.presetVoiceId

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 角色头像
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (voice.isNarrator) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (voice.isNarrator) "📖" else voice.characterName.take(1),
                style = MaterialTheme.typography.titleSmall
            )
        }

        Spacer(Modifier.width(12.dp))

        // 角色信息
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = voice.characterName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "音色: $voiceName${voice.voiceDescription?.let { " · $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 操作按钮
        IconButton(onClick = onPreview) {
            Icon(Icons.Default.PlayArrow, contentDescription = "预览")
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "编辑")
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun AddCharacterDialog(
    bookId: Long,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var selectedVoice by remember { mutableStateOf("mimo_default") }
    var description by remember { mutableStateOf("") }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.shapes.extraLarge
                )
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "添加角色",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("角色名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "选择音色",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))

            // 音色选择网格
            Column {
                CharacterVoice.PRESET_VOICES.chunked(3).forEach { rowVoices ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowVoices.forEach { voice ->
                            val isSelected = selectedVoice == voice.id
                            OutlinedButton(
                                onClick = { selectedVoice = voice.id },
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = voice.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = voice.suitableFor,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        // 填充空位
                        repeat(3 - rowVoices.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("音色描述 (可选)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("如：低沉磁性的中年男声") }
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                appDb.characterVoiceDao.insert(
                                    CharacterVoice(
                                        bookId = bookId,
                                        characterName = name,
                                        presetVoiceId = selectedVoice,
                                        voiceDescription = description.ifBlank { null },
                                        sampleText = "我是$name。"
                                    )
                                )
                                withContext(Dispatchers.Main) { onSaved() }
                            }
                        }
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
private fun EditVoiceDialog(
    voice: CharacterVoice,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedVoice by remember { mutableStateOf(voice.presetVoiceId) }
    var description by remember { mutableStateOf(voice.voiceDescription ?: "") }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.shapes.extraLarge
                )
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "编辑音色 - ${voice.characterName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = "选择音色",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))

            Column {
                CharacterVoice.PRESET_VOICES.chunked(3).forEach { rowVoices ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowVoices.forEach { pv ->
                            val isSelected = selectedVoice == pv.id
                            OutlinedButton(
                                onClick = { selectedVoice = pv.id },
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = pv.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = pv.suitableFor,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        repeat(3 - rowVoices.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("音色描述 (可选)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            appDb.characterVoiceDao.update(
                                voice.copy(
                                    presetVoiceId = selectedVoice,
                                    voiceDescription = description.ifBlank { null },
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            withContext(Dispatchers.Main) { onSaved() }
                        }
                    }
                ) {
                    Text("保存")
                }
            }
        }
    }
}
