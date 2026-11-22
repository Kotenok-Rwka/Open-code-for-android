package com.pocketcode.session

import kotlinx.serialization.Serializable

@Serializable
data class QuestionOptionData(val label: String, val description: String = "")

@Serializable
data class QuestionData(
    val question: String,
    val tag: String = "",
    val options: List<QuestionOptionData>,
)

@Serializable
data class QuestionAnswer(val question: String, val answer: String)
