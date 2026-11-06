package com.xayah.feature.main.processing.packages.restore

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xayah.core.model.OperationState
import com.xayah.core.ui.component.AnimatedNavHost
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.maybePopBackAndNavigateSingle
import com.xayah.feature.main.processing.PageProcessing
import com.xayah.feature.main.processing.R
import com.xayah.feature.main.settings.restore.PageRestoreSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
@ExperimentalAnimationApi
@ExperimentalLayoutApi
@ExperimentalFoundationApi
@ExperimentalMaterial3Api
@Composable
fun PackagesRestoreProcessingGraph() {
    val localNavController = rememberNavController()
    val viewModel = hiltViewModel<RestoreViewModelImpl>()
    val navController = LocalNavController.current!!

    AnimatedNavHost(
        navController = localNavController,
        startDestination = MainRoutes.PackagesRestoreProcessingSetup.route,
    ) {
        composable(MainRoutes.PackagesRestoreProcessing.route) {
            PageProcessing(
                topBarTitleId = { state ->
                    when (state) {
                        OperationState.PROCESSING -> R.string.processing
                        OperationState.DONE -> R.string.restore_completed
                        else -> R.string.restore
                    }
                },
                finishedTitleId = R.string.restore_completed,
                finishedSubtitleId = R.string.args_apps_restored,
                finishedWithErrorsSubtitleId = R.string.args_apps_restored_and_failed,
                viewModel = viewModel,
                onFinished = {
                    // 一键恢复：应用恢复完成后自动接续文件恢复。
                    // skipSetup = 文件勾选已确认，直达文件处理页；先弹掉应用恢复图。
                    if (viewModel.chainFileRestore) {
                        navController.maybePopBackAndNavigateSingle(
                            MainRoutes.MediumRestoreProcessingGraph.getRoute(
                                cloudName = viewModel.cloudNameArg,
                                backupDir = viewModel.backupDirArg,
                                skipSetup = true,
                            )
                        )
                    }
                },
            )
        }
        composable(MainRoutes.PackagesRestoreProcessingSetup.route) {
            PagePackagesRestoreProcessingSetup(localNavController = localNavController, viewModel = viewModel)
        }
        composable(MainRoutes.RestoreSettings.route) {
            PageRestoreSettings()
        }
    }
}
