package com.xayah.feature.main.cloud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 局域网访问权限（本地网络保护 LNP 按系统版本分流）：
 * - Android 17（API 37，targetSdk 37）起强制：应用访问局域网（私有 IP 段，如 192.168.x.x）必须
 *   授予运行时权限 ACCESS_LOCAL_NETWORK（属于「附近设备」NEARBY_DEVICES 权限组），否则出站 LAN
 *   TCP/UDP 直接失败（实测报 failed to connect ... after 5000ms）。targetSdk<=36 时隐式用 INTERNET 放行。
 * - Android 16（API 36）及更早的 opt-in 阶段：LNP 未强制，局域网访问用 NEARBY_WIFI_DEVICES 管控
 *   （澎湃 OS / MIUI 实测需要；一加 ColorOS 上 ACCESS_LOCAL_NETWORK 不是运行时权限，申请无效不弹窗）。
 * - Android 13~15（API 33~35）：同样用 NEARBY_WIFI_DEVICES。
 * - Android 12 及以下不受管控，视为已授予。
 * 两者都声明在 Manifest 中，这里按系统版本选择检查/申请哪一个。
 */
fun isLocalNetworkGranted(context: Context): Boolean = when {
    // API 37+（Android 17）：本地网络保护强制，需 ACCESS_LOCAL_NETWORK
    Build.VERSION.SDK_INT >= 37 ->
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    // API 33~36：NEARBY_WIFI_DEVICES（Android 17 之前 LNP 未强制，且一加上 ACCESS_LOCAL_NETWORK 无运行时申请）
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED

    else -> true
}

/**
 * 返回一个「检查并申请局域网访问权限」的函数。未授予时触发系统权限弹窗（API 37+ 申请
 * ACCESS_LOCAL_NETWORK，API 33~36 申请 NEARBY_WIFI_DEVICES）；用户拒绝后回调 [onDenied]。
 */
@Composable
fun rememberLocalNetworkPermissionRequester(onDenied: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted.not()) onDenied()
    }
    return {
        if (isLocalNetworkGranted(context).not()) {
            launcher.launch(
                if (Build.VERSION.SDK_INT >= 37) {
                    Manifest.permission.ACCESS_LOCAL_NETWORK
                } else {
                    Manifest.permission.NEARBY_WIFI_DEVICES
                }
            )
        }
    }
}
