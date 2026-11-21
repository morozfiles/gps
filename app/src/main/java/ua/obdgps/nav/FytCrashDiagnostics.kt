package ua.obdgps.nav

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

/**
 * One-shot, opt-in settings-screen diagnostic. Never uploads logs or records
 * location, Bluetooth IDs, API keys, addresses or OBD payloads.
 *
 * State is committed synchronously so the last stage is retained even when
 * Android terminates the process before the uncaught-exception handler runs.
 */
internal object FytCrashDiagnostics {
    private const val PREFS="fyt_settings_crash_diag_v1"
    private const val LOG="fyt_settings_diagnostic.txt"
    private const val MAX_TEXT=24_000

    private fun prefs(context:Context)=context.applicationContext
        .getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    private fun reportFile(context:Context)=File(context.applicationContext.filesDir,LOG)

    fun hasRun(context:Context)=prefs(context).getBoolean("used",false)
    fun isArmed(context:Context)=prefs(context).getBoolean("armed",false)
    fun result(context:Context)=prefs(context).getString("result","").orEmpty()

    fun begin(context:Context):Boolean {
        if(hasRun(context))return false
        val saved=prefs(context).edit()
            .putBoolean("used",true)
            .putBoolean("armed",true)
            .putString("result","running")
            .putString("phase","Нажата кнопка диагностики")
            .putBoolean("read",false).commit()
        if(!saved)return false
        runCatching {
            reportFile(context).writeText(
                "FYT 7870 · разовая диагностика открытия настроек\n"+
                "Время (UTC): \${Instant.now()}\n"+
                "Версия: \${BuildConfig.VERSION_NAME} (\${BuildConfig.VERSION_CODE})\n"+
                "Пакет: \${context.packageName}\n"+
                "Logcat не требуется; при падении Java записывается stack trace.\n\n"+
                "[\${Instant.now()}] Нажата кнопка диагностики\n")
        }
        return true
    }

    fun mark(context:Context,phase:String) {
        if(!isArmed(context))return
        val state=prefs(context)
        if(state.getString("phase",null)==phase)return
        state.edit().putString("phase",phase.take(180)).commit()
        runCatching {
            if(reportFile(context).length()<MAX_TEXT)
                reportFile(context).appendText("[\${Instant.now()}] \${phase.take(180)}\n")
        }
    }

    /** The default Android crash handler still runs and terminates the app. */
    fun recordCrash(context:Context,thread:Thread,error:Throwable) {
        if(!isArmed(context))return
        try {
            val last=prefs(context).getString("phase","Неизвестный этап")
            val trace=Log.getStackTraceString(error).take(MAX_TEXT)
            reportFile(context).appendText(
                "\n[\${Instant.now()}] СБОЙ В ПРИЛОЖЕНИИ\n"+
                "Последний этап: $last\n"+
                "Поток: \${thread.name.take(80)}\n"+
                "Ошибка:\n$trace\n")
            prefs(context).edit()
                .putString("result","crashed")
                .putBoolean("armed",false)
                .commit()
        }catch(_:Throwable){
            // Never replace the original crash with a diagnostics failure.
        }
    }

    fun recordLaunchFailure(context:Context,error:Throwable) {
        if(!isArmed(context))return
        runCatching {
            reportFile(context).appendText(
                "\n[\${Instant.now()}] Не удалось открыть экран:\n"+
                Log.getStackTraceString(error).take(MAX_TEXT)+"\n")
        }
        prefs(context).edit()
            .putString("result","launch_failed")
            .putBoolean("armed",false)
            .commit()
    }

    fun finishOnReturn(context:Context){
        if(!isArmed(context))return
        mark(context,"Возврат из настроек на карту")
        prefs(context).edit().putString("result","returned")
            .putBoolean("armed",false).commit()
        runCatching {
            reportFile(context).appendText(
                "[\${Instant.now()}] Экран вернулся без Java-исключения.\n")
        }
    }

    /** Also returns the last persisted stage after an abrupt process kill. */
    fun report(context:Context):String {
        val result=result(context)
        val header=when {
            result=="crashed" -> "Обнаружено падение Java/Kotlin.\n\n"
            result=="launch_failed" -> "Ошибка запуска экрана.\n\n"
            isArmed(context) -> "Проверка не завершилась; возможно, процесс был остановлен.\n"+
                "Последний этап: \${prefs(context).getString("phase","неизвестен")}\n\n"
            result=="returned" -> "После открытия настроек выполнен возврат на карту.\n"+
                "Если ошибка повторилась, потребуется журнал Android logcat.\n\n"
            else -> "Диагностика ещё не запускалась.\n\n"
        }
        val body=runCatching{reportFile(context).readText()}.getOrElse{
            "Не удалось прочитать локальный файл диагностики: \${it.javaClass.simpleName}"
        }
        return (header+body).take(MAX_TEXT)
    }
}
