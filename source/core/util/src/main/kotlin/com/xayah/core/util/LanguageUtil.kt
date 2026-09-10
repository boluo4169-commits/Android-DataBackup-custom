package com.xayah.core.util

import android.content.Context
import androidx.core.app.LocaleManagerCompat
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.datastore.readLanguage
import com.xayah.core.util.LanguageUtil.toLocale
import kotlinx.coroutines.flow.map
import java.util.Locale

fun Context.readMappedLanguage() = readLanguage().map { it.toLocale(this) }

object LanguageUtil {
    fun getSystemLocale(context: Context) = LocaleManagerCompat.getSystemLocales(context).get(0)!!

    fun String.toLocale(context: Context): Locale = when (this) {
        ConstantUtil.LANGUAGE_SYSTEM -> getSystemLocale(context)
        // 「中文（香港）」已与「繁體中文」合并（app 的 values-zh-rHK 已移除，语言列表由资源目录自动生成）。
        // 曾选过 zh-HK 的用户继续走繁体，而不是因找不到资源而回落到英文。
        LANGUAGE_ZH_HK -> Locale.forLanguageTag(LANGUAGE_ZH_TW)
        else -> Locale.forLanguageTag(this)
    }
}

/** 与 app 模块语言列表对应：繁体中文统一到 zh-TW */
private const val LANGUAGE_ZH_HK = "zh-HK"
private const val LANGUAGE_ZH_TW = "zh-TW"

/** 系统显示名不易分辨简繁的语言覆盖表 */
private val localeDisplayOverrides = mapOf(
    "zh-CN" to "中文简体",
    "zh-TW" to "中文繁体",
)

/**
 * 语言显示名：对中文做覆盖（zh-CN →「中文简体」、zh-TW →「中文繁体」），
 * 其余语言走系统 getDisplayName（「中文（中国）/中文（台灣）」这类带地区后缀的名字不易分辨简繁）。
 * 顶层扩展函数（放 LanguageUtil 内会变成成员扩展，外部 import 不到）。
 * 语言选择页与设置页「语言」摘要行共用，保证两处一致。
 */
fun Locale.displayLanguageName(): String = localeDisplayOverrides[toLanguageTag()] ?: getDisplayName(this)
