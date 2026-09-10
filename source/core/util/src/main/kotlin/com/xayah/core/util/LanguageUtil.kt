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
