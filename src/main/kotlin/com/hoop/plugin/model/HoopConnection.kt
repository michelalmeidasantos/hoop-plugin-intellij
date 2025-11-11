package com.hoop.plugin.model

data class HoopConnection(
    val name: String,
    val command: String,
    val type: String,
    val agent: String,
    val status: String,
    var port: Int = 5432,
    var selected: Boolean = false
)