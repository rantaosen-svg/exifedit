package com.photoedit.app.domain

object CopyNaming {
    fun next(originalName: String, takenNames: Set<String>): String {
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val ext = if (dot > 0) originalName.substring(dot) else ".jpg"
        var i = 1
        while (true) {
            val candidate = if (i == 1) "${base}_副本$ext" else "${base}_副本$i$ext"
            if (candidate !in takenNames) return candidate
            i++
        }
    }
}
