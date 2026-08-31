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
 * 局域网访问权限（Android 16+ 本地网络保护 LNP）：
 * - Android 17（API 36+ 的 SDK 37 target）起强制：应用访问局域网（私有 IP 段，如 192.168.x.x）必须
 *   授予运行时权限 ACCESS_LOCAL_NETWORK（属于「附近设备」NEARBY_DEVICES 权限组），否则出站 LAN
 *   TCP/UDP 直接失败（实测报 failed to connect ... after 5000ms）。targetSdk<=36 时隐式用 INTERNET 放行。
 * - Android 13~15（API 33~35）：澎湃 OS / MIUI 对局域网直连有「附近设备」权限管控，需 NEARBY_WIFI_DEVICES。
 * - Android 12 及以下不受管控，视为已授予。
 * 两者都声明在 Manifest 中，这里按系统版本选择检查/申请哪一个。
 */
fun isLocalNetworkGranted(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED

    else -> true
}

/**
 * 返回一个「检查并申请局域网访问权限」的函数。未授予时触发系统权限弹窗（API 36+ 申请
 * ACCESS_LOCAL_NETWORK，API 33~35 申请 NEARBY_WIFI_DEVICES）；用户拒绝后回调 [onDenied]。
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    Manifest.permission.ACCESS_LOCAL_NETWORK
                } else {
                    Manifest.permission.NEARBY_WIFI_DEVICES
                }
            )
        }
    }
}
