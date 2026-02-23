package com.potato.couch.data

import org.json.JSONArray
import org.json.JSONObject

object RouteJson {
    fun toJson(points: List<RoutePoint>): String {
        val array = JSONArray()
        points.forEach { point ->
            val obj = JSONObject()
            obj.put("lat", point.latitude)
            obj.put("lng", point.longitude)
            array.put(obj)
        }
        return array.toString()
    }

    fun fromJson(json: String): List<RoutePoint> {
        if (json.isBlank()) return emptyList()
        val array = JSONArray(json)
        val points = ArrayList<RoutePoint>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            points.add(RoutePoint(obj.getDouble("lat"), obj.getDouble("lng")))
        }
        return points
    }
}
