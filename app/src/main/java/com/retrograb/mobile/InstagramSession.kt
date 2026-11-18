package com.retrograb.mobile

import android.content.Context
import android.util.AtomicFile
import android.webkit.CookieManager
import java.io.File

class InstagramSession(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "instagram-cookies.txt"))
    val loggedIn get() = synchronized(lock) { file.baseFile.isFile }
    private val host = "https://www.instagram.com/"

    fun capture(): Boolean = synchronized(lock) {
        val cookies = CookieManager.getInstance().getCookie(host).orEmpty()
            .split(';').mapNotNull { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isBlank() || value.isBlank() || (name + value).any { it.isISOControl() }) {
                    null
                } else name to value
            }
        if (cookies.none { it.first == "sessionid" }) return@synchronized false
        val output = file.startWrite()
        try {
            val body = cookies.joinToString("\n", "# Netscape HTTP Cookie File\n", "\n") { (name, value) ->
                ".instagram.com\tTRUE\t/\tTRUE\t0\t$name\t$value"
            }
            output.write(body.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        CookieManager.getInstance().flush()
        true
    }

    fun copyTo(directory: File): File? = synchronized(lock) {
        if (!loggedIn) return@synchronized null
        File(directory, "instagram-cookies.txt").apply { writeBytes(file.readFully()) }
    }

    fun logout(done: () -> Unit) {
        synchronized(lock) { file.delete() }
        CookieManager.getInstance().apply {
            removeAllCookies {
                flush()
                done()
            }
        }
    }

    companion object { private val lock = Any() }
}
