package com.pocketcode.llm

import kotlinx.serialization.Serializable

/** Результат проверки профиля: нужен для бейджей «работает / ошибка». */
@Serializable
data class ProviderHealth(
    val profileId: String,
    val label: String,
    val status: String,
    val checkedAt: Long = System.currentTimeMillis(),
    val models: List<String> = emptyList(),
    val supportsTools: Boolean? = null,
    val supportsVision: Boolean? = null,
    val supportsReasoning: Boolean? = null,
)
