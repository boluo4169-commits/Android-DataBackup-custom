package com.xayah.core.database.util

import androidx.room.TypeConverter
import com.google.gson.reflect.TypeToken
import com.xayah.core.model.database.PackagePermission
import com.xayah.core.util.GsonUtil

class StringListConverters {
    /**
     * Room 的 converter 是**逐行逐列**调用的（实测 permissions 列平均 1385 B/行），
     * 原实现每次调用都新建一个 GsonUtil（内含 GsonBuilder().create()）并重建 TypeToken，
     * 属于纯重复开销。这里改为本实例复用——Gson 与 TypeToken 都是线程安全的，
     * Room 保证 converter 实例本身在其事务线程上复用。
     */
    private val gsonUtil = GsonUtil()
    private val stringListType = object : TypeToken<List<String>>() {}.type
    private val permissionListType = object : TypeToken<List<PackagePermission>>() {}.type

    @TypeConverter
    fun fromStringListJson(json: String): List<String> =
        gsonUtil.fromJson(json, stringListType)

    @TypeConverter
    fun toStringListJson(list: List<String>): String =
        gsonUtil.toJson(list)

    @TypeConverter
    fun fromPermissionListJson(json: String): List<PackagePermission> =
        gsonUtil.fromJson(json, permissionListType)

    @TypeConverter
    fun toPermissionListJson(list: List<PackagePermission>): String =
        gsonUtil.toJson(list)
}
