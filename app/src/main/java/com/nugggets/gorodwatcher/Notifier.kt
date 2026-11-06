package com.nugggets.gorodwatcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Notifier {
    const val CH_NEW = "new"
    const val CH_SVC = "svc"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_NEW, "Новые объявления", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SVC, "Работа в фоне", NotificationManager.IMPORTANCE_LOW))
    }

    fun openAppIntent(ctx: Context, req: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, req,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun mapUrl(r: Row): String? =
        if (r.lat != null && r.lon != null) "https://yandex.ru/maps/?pt=${r.lon},${r.lat}&z=18&l=map" else null

    fun messageText(r: Row): String {
        val lines = ArrayList<String?>()
        lines.add(r.themeName?.let { "🚗 $it" })
        lines.add(r.place?.let { "📍 $it" })
        lines.add(r.address)
        lines.add("🕒 ${fmtTs(r.published ?: r.created)}  (${r.statusName ?: "?"})")
        if (r.text.isNotBlank()) lines.add(r.text.take(400))
        lines.add(mapUrl(r))
        if (r.photos.isNotEmpty()) lines.add("Фото: ${r.photos[0]}")
        lines.add("№ ${r.id}")
        return lines.filter { !it.isNullOrEmpty() }.joinToString("\n")
    }

    fun androidNotify(ctx: Context, title: String, short: String, full: String, id: Int) {
        val n = Notification.Builder(ctx, CH_NEW)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(short)
            .setStyle(Notification.BigTextStyle().bigText(full))
            .setContentIntent(openAppIntent(ctx, id))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun notify(ctx: Context, r: Row, p: Prefs) {
        Bus.log("новое: № ${r.id} ${r.address ?: ""}")
        if (p.androidNotify) {
            androidNotify(
                ctx, "Брошенное авто", r.address ?: r.place ?: "новое объявление",
                messageText(r), 100 + (r.id % 1_000_000_000L).toInt()
            )
        }
        val err = telegram(p, messageText(r))
        if (err != null && err != "не настроен") Bus.log("Telegram: $err")
    }

    fun alert(ctx: Context, text: String) {
        Bus.log(text)
        Prefs(ctx).let { p ->
            if (p.androidNotify) androidNotify(ctx, "GorodWatcher", text, text, 99)
            telegram(p, text)
        }
    }

    /** null — отправлено; иначе текст ошибки ("не настроен" — токен/чат пусты). */
    fun telegram(p: Prefs, text: String): String? {
        val tok = p.tgToken.trim()
        val chat = p.tgChat.trim()
        if (tok.isEmpty() || chat.isEmpty()) return "не настроен"
        return try {
            val c = URL("https://api.telegram.org/bot$tok/sendMessage").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 30000
            c.readTimeout = 30000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = "chat_id=${URLEncoder.encode(chat, "UTF-8")}&text=${URLEncoder.encode(text, "UTF-8")}"
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code in 200..299) c.inputStream.use { it.readBytes() }
            c.disconnect()
            if (code in 200..299) null else "HTTP $code"
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    fun test(ctx: Context): String {
        val p = Prefs(ctx)
        val now = System.currentTimeMillis() / 1000
        val r = Row(0, null, "Тест", now, now, "affirmed", "Проблема в работе", "Проверка уведомлений",
            "Тестовое место", "Тестовый адрес", null, null, null, emptyList())
        val parts = ArrayList<String>()
        if (p.androidNotify) {
            androidNotify(ctx, "GorodWatcher", "Тестовое уведомление", messageText(r), 98)
            parts.add("Android: отправлено")
        }
        val err = telegram(p, messageText(r))
        parts.add("Telegram: " + (if (err == null) "отправлено" else err))
        return parts.joinToString("; ")
    }
}
