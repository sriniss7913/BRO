package com.example.bro.search

data class WebSearchResult(
    val title: String,
    val snippet: String,
    val url: String = ""
)
