package com.project.lol.service

internal class MediaServiceLifetime {
    private var taskRemoved = false

    fun markTaskRemoved() {
        taskRemoved = true
    }

    fun acceptStart(): Boolean = !taskRemoved
}
