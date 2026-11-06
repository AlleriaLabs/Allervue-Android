package com.allerix.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local-only diagnostics for playback startup and fatal app crashes. */
object DiagnosticLog {
    private const val FILE_NAME = "allervue-diagnostics.log"
    private const val MAX_BYTES = 512 * 1024
    @Volatile private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(app, "FATAL uncaught exception on thread " + thread.name, throwable)
            previous?.uncaughtException(thread, throwable)
        }
        record(app, "Crash handler installed; device=" + Build.MANUFACTURER + " " + Build.MODEL + "; Android=" + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
    }

    @JvmStatic
    @Synchronized
    fun record(context: Context, message: String, error: Throwable? = null) {
        runCatching {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val stack = if (error == null) "" else {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                "\n" + writer.toString()
            }
            file.appendText("[" + stamp + "] " + message + stack + "\n", Charsets.UTF_8)
            if (file.length() > MAX_BYTES) {
                val tail = file.readText(Charsets.UTF_8).takeLast(MAX_BYTES / 2)
                file.writeText(tail, Charsets.UTF_8)
            }
        }
    }

    fun read(context: Context): String = runCatching {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }.getOrDefault("Unable to read diagnostic log.")
}
