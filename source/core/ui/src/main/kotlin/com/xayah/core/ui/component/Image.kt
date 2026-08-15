package com.xayah.core.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.LruCache
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xayah.core.ui.R
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.theme.withState
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.BaseUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

object PackageIconLoader {
    // 应用图标在运行期间不变，做全局内存缓存，避免列表滚动时反复 getApplicationIcon + 转 Bitmap。
    private val cache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun load(context: Context, packageName: String, inCircleShape: Boolean, sizePx: Int): Pair<Bitmap?, Bitmap?> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var background: Bitmap? = null
        var foreground: Bitmap? = null
        val iconDrawable = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        if (iconDrawable != null) {
            if (inCircleShape && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && iconDrawable is AdaptiveIconDrawable) {
                background = cached("$packageName:bg:$sizePx") { iconDrawable.background?.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888) }
                foreground = cached("$packageName:fg:$sizePx") { iconDrawable.foreground?.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888) }
            } else {
                foreground = cached("$packageName:fg:$sizePx") { iconDrawable.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888) }
            }
        } else {
            val adaptive = BaseUtil.readIcon(context, PathUtil.getPackageIconPath(context, packageName, true))
            if (adaptive != null) {
                background = cached("$packageName:bg:$sizePx") { adaptive.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888) }
            } else {
                val normal = BaseUtil.readIcon(context, PathUtil.getPackageIconPath(context, packageName, false))
                foreground = cached("$packageName:fg:$sizePx") { normal?.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888) }
            }
        }
        if (foreground == null && background == null) {
            foreground = cached("$packageName:default:$sizePx") {
                AppCompatResources.getDrawable(context, android.R.drawable.sym_def_app_icon)?.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            }
        }
        val cost = System.currentTimeMillis() - start
        if (cost > 100) {
            LogUtil.log("Slow icon load: $packageName took ${cost}ms")
        }
        background to foreground
    }

    private fun cached(key: String, loader: () -> Bitmap?): Bitmap? = cache.get(key) ?: loader()?.also { cache.put(key, it) }
}

@ExperimentalFoundationApi
@Composable
fun PackageIconImage(icon: ImageVector? = null, packageName: String, shape: Shape? = null, inCircleShape: Boolean = false, size: Dp = SizeTokens.Level32) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizeForeground = if (inCircleShape) size.div(sqrt(2.2F)) else size
    val sizeForegroundPx = with(density) { sizeForeground.roundToPx() }

    var foreground by remember(packageName, icon, sizeForegroundPx) { mutableStateOf<Bitmap?>(null) }
    var background by remember(packageName, icon, sizeForegroundPx) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(packageName, icon, inCircleShape, sizeForegroundPx) {
        if (icon == null) {
            val (bg, fg) = PackageIconLoader.load(context, packageName, inCircleShape, sizeForegroundPx)
            background = bg
            foreground = fg
        }
    }

    Box(modifier = if (shape != null) Modifier.clip(shape) else Modifier, contentAlignment = Alignment.Center) {
        background?.let { bg ->
            Image(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .scale(1.4f)
                    .background(ThemedColorSchemeKeyTokens.PrimaryContainer.value),
                bitmap = bg.asImageBitmap(),
                contentDescription = null,
            )
        } ?: Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(ThemedColorSchemeKeyTokens.PrimaryContainer.value)
        )
        if (icon != null) {
            Icon(
                imageVector = icon,
                modifier = Modifier.size(sizeForeground),
                contentDescription = null,
                tint = ThemedColorSchemeKeyTokens.Primary.value
            )
        } else {
            foreground?.let { fg ->
                Image(
                    modifier = Modifier.size(sizeForeground),
                    bitmap = fg.asImageBitmap(),
                    contentDescription = null,
                )
            }
        }
    }
}

@ExperimentalFoundationApi
@Composable
fun MediaIconImage(enabled: Boolean = true, name: String, textStyle: TextStyle = MaterialTheme.typography.labelMedium, size: Dp = SizeTokens.Level32) {
    Surface(modifier = Modifier.size(size), indication = null, shape = CircleShape, color = ThemedColorSchemeKeyTokens.PrimaryContainer.value.withState(), enabled = enabled) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name,
                style = textStyle,
                color = ThemedColorSchemeKeyTokens.OnPrimaryContainer.value.withState(enabled),
            )
        }
    }
}

@Composable
fun AppIcon(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(SizeTokens.Level128)
            .clip(CircleShape)
            .background(colorResource(id = R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            modifier = Modifier.size(SizeTokens.Level100),
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_launcher_foreground_tonal),
            contentDescription = null
        )
    }
}
