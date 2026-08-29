package com.databackup.version

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xayah.core.ui.component.AnimatedNavHost
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.theme.DataBackupTheme
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.command.BaseUtil
import com.xayah.feature.main.cloud.PageCloud
import com.xayah.feature.main.cloud.add.PageCloudAddAccount
import com.xayah.feature.main.cloud.add.PageFTPSetup
import com.xayah.feature.main.cloud.add.PageSFTPSetup
import com.xayah.feature.main.cloud.add.PageSMBSetup
import com.xayah.feature.main.cloud.add.PageWebDAVSetup
import com.xayah.feature.main.configurations.PageConfigurations
import com.xayah.feature.main.dashboard.PageDashboard
import com.xayah.feature.main.dashboard.PageDataMigration
import com.xayah.feature.main.dashboard.PageDataMigrationExport
import com.xayah.feature.main.dashboard.PageDataMigrationImport
import com.xayah.feature.main.details.DetailsRoute
import com.xayah.feature.main.directory.PageDirectory
import com.xayah.feature.main.history.HistoryRoute
import com.xayah.feature.main.history.TaskDetailsRoute
import com.xayah.feature.main.list.ListRoute
import com.xayah.feature.main.processing.medium.backup.MediumBackupProcessingGraph
import com.xayah.feature.main.processing.medium.restore.MediumRestoreProcessingGraph
import com.xayah.feature.main.processing.packages.backup.PackagesBackupProcessingGraph
import com.xayah.feature.main.processing.packages.restore.PackagesRestoreProcessingGraph
import com.xayah.feature.main.restore.PageRestore
import com.xayah.feature.main.restore.reload.PageReload
import com.xayah.feature.main.settings.PageSettings
import com.xayah.feature.main.settings.about.PageAboutSettings
import com.xayah.feature.main.settings.about.PageTranslatorsSettings
import com.xayah.feature.main.settings.backup.PageBackupSettings
import com.xayah.feature.main.settings.blacklist.PageBlackList
import com.xayah.feature.main.settings.language.PageLanguageSelector
import com.xayah.feature.main.settings.restore.PageRestoreSettings
import com.xayah.feature.main.settings.schedules.PageSchedules
import com.xayah.core.data.repository.ScheduleRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.work.WorkManagerInitializer
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var scheduleRepo: ScheduleRepository
    @Inject lateinit var taskRepo: TaskRepository

    @ExperimentalCoroutinesApi
    @ExperimentalAnimationApi
    @ExperimentalFoundationApi
    @ExperimentalLayoutApi
    @ExperimentalMaterial3Api
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        runBlocking {
            runCatching {
                BaseUtil.initializeEnvironment(context = this@MainActivity)
            }
        }

        // 定时备份启动对账：force-stop 后 WorkManager 不会自行唤醒，冷启动时把启用中的计划重新入队；
        // 同时清理进程死亡残留的「处理中」尸体任务（否则串行检查永远误判为忙）。
        // 仅真正冷启动执行：旋转/重建 Activity 时可能有备份正在跑，误清会把活任务标成未处理。
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                runCatching {
                    taskRepo.clearStaleProcessing()
                    WorkManagerInitializer.reconcileSchedules(applicationContext, scheduleRepo.queryEnabled())
                }
            }
        }

        setContent {
            DataBackupTheme {
                val navController = rememberNavController()
                CompositionLocalProvider(
                    LocalNavController provides navController,
                    androidx.lifecycle.compose.LocalLifecycleOwner provides androidx.compose.ui.platform.LocalLifecycleOwner.current,
                ) {
                    AnimatedNavHost(
                        navController = navController,
                        startDestination = MainRoutes.Dashboard.route,
                    ) {
                        composable(MainRoutes.Dashboard.route) {
                            PageDashboard()
                        }
                        composable(MainRoutes.Cloud.route) {
                            PageCloud()
                        }
                        composable(MainRoutes.CloudAddAccount.route) {
                            PageCloudAddAccount()
                        }
                        composable(MainRoutes.FTPSetup.route) {
                            PageFTPSetup()
                        }
                        composable(MainRoutes.WebDAVSetup.route) {
                            PageWebDAVSetup()
                        }
                        composable(MainRoutes.SMBSetup.route) {
                            PageSMBSetup()
                        }
                        composable(MainRoutes.SFTPSetup.route) {
                            PageSFTPSetup()
                        }
                        composable(
                            MainRoutes.List.route,
                            arguments = listOf(
                                androidx.navigation.navArgument(MainRoutes.ARG_RETURN_TO_SETUP) {
                                    type = androidx.navigation.NavType.BoolType
                                    defaultValue = false
                                }
                            )
                        ) {
                            ListRoute()
                        }
                        composable(MainRoutes.Details.route) {
                            DetailsRoute()
                        }
                        composable(MainRoutes.History.route) {
                            HistoryRoute()
                        }
                        composable(MainRoutes.DataMigration.route) {
                            PageDataMigration()
                        }
                        composable(MainRoutes.DataMigrationExport.route) {
                            PageDataMigrationExport()
                        }
                        composable(MainRoutes.DataMigrationImport.route) {
                            PageDataMigrationImport()
                        }
                        composable(MainRoutes.TaskDetails.route) {
                            TaskDetailsRoute()
                        }
                        composable(
                            MainRoutes.PackagesBackupProcessingGraph.route,
                            arguments = listOf(
                                androidx.navigation.navArgument(MainRoutes.ARG_CHAIN_FILE_BACKUP) {
                                    type = androidx.navigation.NavType.BoolType
                                    defaultValue = false
                                }
                            )
                        ) {
                            PackagesBackupProcessingGraph()
                        }
                        composable(MainRoutes.PackagesRestoreProcessingGraph.route) {
                            PackagesRestoreProcessingGraph()
                        }
                        composable(
                            MainRoutes.MediumBackupProcessingGraph.route,
                            arguments = listOf(
                                androidx.navigation.navArgument(MainRoutes.ARG_SKIP_SETUP) {
                                    type = androidx.navigation.NavType.BoolType
                                    defaultValue = false
                                }
                            )
                        ) { entry ->
                            MediumBackupProcessingGraph(skipSetup = entry.arguments?.getBoolean(MainRoutes.ARG_SKIP_SETUP) ?: false)
                        }
                        composable(MainRoutes.MediumRestoreProcessingGraph.route) {
                            MediumRestoreProcessingGraph()
                        }
                        composable(MainRoutes.Settings.route) {
                            PageSettings()
                        }
                        composable(MainRoutes.Restore.route) {
                            PageRestore()
                        }
                        composable(MainRoutes.Reload.route) {
                            PageReload()
                        }
                        composable(MainRoutes.BackupSettings.route) {
                            PageBackupSettings()
                        }
                        composable(MainRoutes.Schedules.route) {
                            PageSchedules()
                        }
                        composable(MainRoutes.RestoreSettings.route) {
                            PageRestoreSettings()
                        }
                        composable(MainRoutes.LanguageSettings.route) {
                            PageLanguageSelector()
                        }
                        composable(MainRoutes.BlackList.route) {
                            PageBlackList()
                        }
                        composable(MainRoutes.Configurations.route) {
                            PageConfigurations()
                        }
                        composable(MainRoutes.About.route) {
                            PageAboutSettings()
                        }
                        composable(MainRoutes.Translators.route) {
                            PageTranslatorsSettings()
                        }
                        composable(route = MainRoutes.Directory.route) {
                            PageDirectory()
                        }
                    }
                }
            }
        }
    }
}
