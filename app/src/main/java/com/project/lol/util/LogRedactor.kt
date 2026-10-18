package com.project.lol.util

/** Shared by live logs, exported diagnostics and standalone security tests. */
object LogRedactor {
    fun redact(input: String): String = com.project.lol.security.LogRedactor.redact(input)
}
