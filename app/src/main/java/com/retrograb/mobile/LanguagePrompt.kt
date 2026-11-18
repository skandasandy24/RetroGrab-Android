package com.retrograb.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal const val EXTRA_LANGUAGE_QUESTION = "com.retrograb.mobile.LANGUAGE_QUESTION"

internal data class LanguageQuestion(
    val label: String,
    val languages: List<String>,
    val spotifyLanguage: String?,
    val multi: Boolean,
    val id: String = UUID.randomUUID().toString()
) {
    val defaultLanguage get() = spotifyLanguage?.takeIf { it in languages } ?: languages.first()
}

internal data class LanguageAnswer(val language: String, val useForRest: Boolean)

internal object LanguagePrompt {
    private const val CHANNEL = "questions"
    private const val NOTIFICATION_ID = 1
    private const val TIMEOUT_MS = 15 * 60 * 1000L
    private val mutex = Mutex()
    private val current = MutableStateFlow<LanguageQuestion?>(null)
    val question = current.asStateFlow()

    private data class Pending(val id: String, val answer: CompletableDeferred<LanguageAnswer?>)

    @Volatile
    private var pending: Pending? = null

    suspend fun ask(
        context: Context, question: LanguageQuestion, onTimeout: (String) -> Unit
    ): LanguageAnswer? = mutex.withLock {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val deferred = CompletableDeferred<LanguageAnswer?>()
        pending = Pending(question.id, deferred)
        current.value = question
        try {
            postNotification(context, notifications, question)
            if (withTimeoutOrNull(TIMEOUT_MS) { deferred.join(); true } == true) {
                deferred.await()
            } else {
                onTimeout(question.defaultLanguage)
                LanguageAnswer(question.defaultLanguage, useForRest = false)
            }
        } finally {
            pending = null
            current.value = null
            deferred.cancel()
            notifications.cancel(CHANNEL, NOTIFICATION_ID)
        }
    }

    fun respond(question: LanguageQuestion, answer: LanguageAnswer?) {
        if (answer != null && answer.language !in question.languages) return
        pending?.takeIf { it.id == question.id }?.answer?.complete(answer)
    }

    private fun postNotification(context: Context, notifications: NotificationManager, question: LanguageQuestion) {
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "Questions", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_LANGUAGE_QUESTION, question.id)
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val choices = question.languages.joinToString(", ", transform = SpotifyLanguages::displayName)
        val message = "${question.label} is available in $choices"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Choose a language")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .build()
        try {
            notifications.notify(CHANNEL, NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Log.w("LanguagePrompt", "Could not post language question notification", error)
        }
    }
}
