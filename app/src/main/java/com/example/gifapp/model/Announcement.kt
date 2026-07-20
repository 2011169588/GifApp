package com.example.gifapp.model

data class Announcement(
    val id: String,
    val title: String,
    val message: String,
    val date: String,
    val type: String = "info" // info, update, warning
)
