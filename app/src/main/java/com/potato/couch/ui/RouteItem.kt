package com.potato.couch.ui

import com.potato.couch.data.RoutePoint

data class RouteItem(
    val id: Long,
    val name: String,
    val points: List<RoutePoint>,
    val createdAt: Long
)
