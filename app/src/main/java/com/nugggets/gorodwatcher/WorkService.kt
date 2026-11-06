package com.nugggets.gorodwatcher

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

class WorkService : Service() {

    companion object {
        const val A_WATCH = "gw.watch"
        const val A_SYNC = "gw.sync"
        const val A_STOP_WATCH = "gw.stop_watch"
        const val A_STOP_SYNC = "gw.stop_sync"
        const val EXTRA_SINCE = "since"

        fun start(ctx: Context, action: String, since: String? = null) {
            val i = Intent(ctx, WorkService::class.java).setAction(action)
            if (since != null) i.putExtra(EXTRA_SINCE, since)
            ctx.startForegroundService(i)
        }
    }

    private val lock = Any()
    private var wl: PowerManager.WakeLock? = null
    private var watchStop: AtomicBoolean? = null
    private var syncStop: AtomicBoolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun note(): Notification {
        val parts = ArrayList<String>()
        if (Bus.watching) parts.add("мониторинг: каждые ${Prefs(this).intervalMin} мин")
        if (Bus.syncing) parts.add("загрузка истории")
        return Notification.Builder(this, Notifier.CH_SVC)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("GorodWatcher")
            .setContentText(if (parts.isEmpty()) "запуск…" else parts.joinToString(", "))
            .setOngoing(true)
            .setContentIntent(Notifier.openAppIntent(this, 1))
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        startForeground(1, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        var action = intent?.action
        if (action == null && Prefs(this).watching) action = A_WATCH  // перезапуск системой
        when (action) {
            A_WATCH -> startWatch()
            A_SYNC -> startSync(intent?.getStringExtra(EXTRA_SINCE) ?: "2025-01-01")
            A_STOP_WATCH -> { watchStop?.set(true); Prefs(this).watching = false }
            A_STOP_SYNC -> syncStop?.set(true)
        }
        finishIfIdle()
        Bus.onChange?.run()
        return START_STICKY
    }

    private fun acquireWl() {
        if (wl == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gorodwatcher:work")
        }
        if (wl?.isHeld == false) wl?.acquire()
    }

    private fun finishIfIdle() {
        synchronized(lock) {
            if (!Bus.watching && !Bus.syncing) {
                if (wl?.isHeld == true) wl?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                getSystemService(NotificationManager::class.java).notify(1, note())
            }
        }
    }

    private fun startWatch() {
        synchronized(lock) {
            if (Bus.watching) return
            val stop = AtomicBoolean(false)
            watchStop = stop
            Bus.watching = true
            Prefs(this).watching = true
            acquireWl()
            Thread { runWatch(stop) }.start()
        }
    }

    private fun startSync(since: String) {
        synchronized(lock) {
            if (Bus.syncing) return
            val stop = AtomicBoolean(false)
            syncStop = stop
            Bus.syncing = true
            acquireWl()
            Thread { runSync(stop, since) }.start()
        }
    }

    private fun runWatch(stop: AtomicBoolean) {
        val p = Prefs(this)
        val db = Db(this)
        try {
            val api = Api(1000L) { stop.get() }
            val nodes = Engine.discover(api)
            Bus.log("мониторинг тем: " + nodes.joinToString { "${it.theme} ${it.title.take(30)}" })
            var fails = 0
            while (!stop.get()) {
                try {
                    Engine.watchCycle(this, api, db, nodes, p.days, p.notifyFirst)
                    fails = 0
                } catch (e: Cancelled) {
                    break
                } catch (e: Exception) {
                    fails++
                    Bus.log("ошибка цикла ($fails): ${e.message}")
                    if (fails == 5) Notifier.alert(this, "⚠️ GorodWatcher: 5 ошибок подряд. Последняя: ${e.message}")
                }
                var left = p.intervalMin.coerceAtLeast(1) * 60
                while (left > 0 && !stop.get()) {
                    Thread.sleep(1000)
                    left--
                }
            }
        } catch (e: Cancelled) {
        } catch (e: Exception) {
            Bus.log("мониторинг остановлен с ошибкой: ${e.message}")
        } finally {
            Bus.watching = false
            Bus.log("мониторинг остановлен")
            finishIfIdle()
        }
    }

    private fun runSync(stop: AtomicBoolean, since: String) {
        val p = Prefs(this)
        val db = Db(this)
        try {
            val api = Api(1000L) { stop.get() }
            Engine.sync(api, db, since, p.chunkDays)
        } catch (e: Cancelled) {
            Bus.log("загрузка истории остановлена")
        } catch (e: Exception) {
            Bus.log("ошибка загрузки: ${e.message}")
        } finally {
            Bus.syncing = false
            finishIfIdle()
        }
    }

    override fun onDestroy() {
        watchStop?.set(true)
        syncStop?.set(true)
        if (wl?.isHeld == true) wl?.release()
        super.onDestroy()
    }
}
