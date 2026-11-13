package com.xayah.core.util

import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 路由路径参数编码。
 *
 * `URLEncoder` 用 `+` 表示空格，但 Navigation 只对 `%XX` 做一次 decode、不处理 `+`，
 * 于是「空格」和「真实的 + 号」在 SavedStateHandle 里无法区分（账号名 `+cloud`
 * 会被 `decodeURL()` 变成 ` cloud` 而查不到账号）。统一改用 `%20` 表示空格。
 */
fun String.encodeURL(charset: Charset = StandardCharsets.UTF_8): String =
    URLEncoder.encode(this, charset.toString()).replace("+", "%20")

/**
 * SavedStateHandle 里的路径参数**已被 Navigation 解码过一次**，这里绝不能再解码第二遍：
 * - 账号名含孤立 `%` 时 `URLDecoder` 会抛 `IllegalArgumentException`（历史崩溃点）；
 * - 更隐蔽的是 `100%DCIM` 里的 `%DC` 是合法百分号编码，二次解码会把它变成乱码；
 * - `+` 在 `URLDecoder` 里代表空格，二次解码会把账号名 `+cloud` 变成 ` cloud`。
 *
 * 保留这个函数只是为了让现有调用点语义稳定；值一律原样返回，由 `encodeURL` +
 * Navigation 的一次解码负责还原。非路由来源（枚举名等）同样不含编码串，恒等是安全的。
 */
@Suppress("UNUSED_PARAMETER")
fun String.decodeURL(charset: Charset = StandardCharsets.UTF_8): String = this

/** 本地源的路径参数占位：编码后是 %20，Navigation 解码成空格，trim 后为空串。 */
val encodedURLWithSpace = " ".encodeURL()
fun String.ifEmptyEncodeURLWithSpace() = ifEmpty { " " }.encodeURL()
