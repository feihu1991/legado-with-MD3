package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * 角色音色配置 - 用于 MiMo 多角色朗读
 */
@Entity(
    tableName = "character_voices",
    primaryKeys = ["bookId", "characterName"],
    indices = [Index(value = ["bookId"])]
)
data class CharacterVoice(
    val bookId: Long,
    val characterName: String,
    val voiceType: String = VOICE_TYPE_PRESET,
    val presetVoiceId: String = "mimo_default",
    val voiceDescription: String? = null,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val emotion: String? = null,
    val style: String? = null,
    val sampleText: String = "",
    val previewAudioPath: String? = null,
    val isNarrator: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val VOICE_TYPE_PRESET = "preset"
        const val VOICE_TYPE_DESIGNED = "designed"

        // MiMo 预置音色
        val PRESET_VOICES = listOf(
            PresetVoice("mimo_default", "默认", "默认音色", "通用"),
            PresetVoice("bingtang", "冰糖", "温柔甜美女声", "青年女性"),
            PresetVoice("moli", "茉莉", "活泼清脆女声", "少女"),
            PresetVoice("suda", "苏打", "阳光清爽男声", "青年男性"),
            PresetVoice("baihua", "白桦", "沉稳磁性男声", "成熟男性"),
            PresetVoice("Mia", "Mia", "英文女声", "英文女性"),
            PresetVoice("Chloe", "Chloe", "知性英文女声", "英文知性"),
            PresetVoice("Milo", "Milo", "英文男声", "英文男性"),
            PresetVoice("Dean", "Dean", "磁性英文男声", "英文磁性")
        )
    }

    data class PresetVoice(
        val id: String,
        val name: String,
        val description: String,
        val suitableFor: String
    )
}
