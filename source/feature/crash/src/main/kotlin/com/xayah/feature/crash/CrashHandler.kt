package com.xayah.feature.crash

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.common.util.toLineString
import com.xayah.core.util.DateUtil
import com.xayah.core.util.LogUtil
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class CrashHandler(private val mContext: Context) : Thread.UncaughtExceptionHandler {
    private var mDefaultHandler: Thread.UncaughtExceptionHandler? = null
    private var crashInfo = ""

    fun initialize() {
        runCatching {
            if (mContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
                mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler(this)
            }
        }
    }

    @ExperimentalMaterial3Api
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (!handleException(throwable) && mDefaultHandler != null) {
            mDefaultHandler!!.uncaughtException(thread, throwable)
        } else {
            val intent = Intent(mContext, MainActivity::class.java).apply {
                putExtra("crashInfo", crashInfo)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            mContext.startActivity(intent)
            exitProcess(0)
        }
    }

    private fun handleException(throwable: Throwable?): Boolean {
        if (throwable == null) return false
        getCrashInfo(throwable)
        return true
    }

    /**
     * 抓取本进程最近的 logcat（无需 root，logcat 读自身进程缓冲不受限）。
     * 崩溃前的系统警告、Binder 错误、ANR 线索常在这里。失败返回空串不阻塞崩溃处理。
     */
    private fun dumpRecentLogcat(): String = runCatching {
        val pid = android.os.Process.myPid()
        val process = ProcessBuilder("logcat", "-d", "-t", "300", "--pid", pid.toString())
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text
    }.getOrDefault("")

    private fun getCrashInfo(throwable: Throwable) {
        val stringWriter = StringWriter()
        PrintWriter(stringWriter).apply {
            throwable.printStackTrace(this)
            var cause = throwable.cause
            while (cause != null) {
                cause.printStackTrace(this)
                cause = cause.cause
            }
            flush()
            close()
        }

        runCatching {
            val infoList = mutableListOf(
                "================================",
                "Date:     ${DateUtil.formatTimestamp(DateUtil.getTimestamp())}",
                "Version:  ${BuildConfigUtil.VERSION_NAME} (${BuildConfigUtil.VERSION_CODE}) ${BuildConfigUtil.FLAVOR_feature}/${BuildConfigUtil.FLAVOR_abi}",
                "System:   ${Build.DISPLAY} (SDK ${Build.VERSION.SDK_INT})",
                "Device:   ${Build.MANUFACTURER} ${Build.MODEL}",
                "ABIs:     ${Build.SUPPORTED_ABIS.joinToString(separator = ", ")}",
                "================================",
                stringWriter.toString(),
            )
            // 附加崩溃前的本进程 logcat，便于还原崩溃上下文
            val logcat = dumpRecentLogcat()
            if (logcat.isNotBlank()) {
                infoList.add("-------- Recent logcat (this pid) --------")
                infoList.add(logcat)
            }
            crashInfo = infoList.toLineString().trim()
            // 写入日志文件，便于用户通过「导出日志」提交崩溃堆栈。
            LogUtil.logCrash(crashInfo)
        }
    }
}
