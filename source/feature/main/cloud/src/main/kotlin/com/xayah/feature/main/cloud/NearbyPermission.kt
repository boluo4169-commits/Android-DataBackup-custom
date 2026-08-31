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
 * 澎湃 OS / MIUI 对局域网直连（SMB/WebDAV/FTP/SFTP 连 IP:port）有「附近设备」权限管控：
 * 不授予 NEARBY_WIFI_DEVICES 时 TCP 连接直接超时失败（实测报 failed to connect ... after 5000ms）。
 * Android 13（API 33）起为运行时权限；低于 33 的版本不受管控，视为已授予。
 */
fun isNearbyDevicesGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED

/**
 * 返回一个「检查并申请附近设备权限」的函数。未授予时触发系统权限弹窗；
 * 用户拒绝后回调 [onDenied]（可用于提示文案）。
 */
@Composable
fun rememberNearbyDevicesPermissionRequester(onDenied: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted.not()) onDenied()
    }
    return {
        if (isNearbyDevicesGranted(context).not()) {
            launcher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }
}
