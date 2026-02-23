package com.sofawander.app.ui

import com.sofawander.app.data.RoutePoint

data class RouteItem(
    val id: Long,
    val name: String,
    val points: List<RoutePoint>,
    val createdAt: Long
)
