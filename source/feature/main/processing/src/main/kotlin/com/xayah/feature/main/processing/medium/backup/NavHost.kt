package com.xayah.feature.main.processing.medium.backup

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
import com.xayah.feature.main.processing.PageProcessing
import com.xayah.feature.main.processing.R
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
@ExperimentalAnimationApi
@ExperimentalLayoutApi
@ExperimentalFoundationApi
@ExperimentalMaterial3Api
@Composable
fun MediumBackupProcessingGraph(skipSetup: Boolean = false) {
    val localNavController = rememberNavController()
    val viewModel = hiltViewModel<BackupViewModelImpl>()

    AnimatedNavHost(
        navController = localNavController,
        // 一键备份接续场景：应用备份已完成且文件勾选已确认，直接进处理页，不再重复引导
        startDestination = if (skipSetup) MainRoutes.MediumBackupProcessing.route else MainRoutes.MediumBackupProcessingSetup.route,
    ) {
        composable(MainRoutes.MediumBackupProcessing.route) {
            PageProcessing(
                topBarTitleId = { state ->
                    when (state) {
                        OperationState.PROCESSING -> R.string.processing
                        OperationState.DONE -> R.string.backup_completed
                        else -> R.string.backup
                    }
                },
                finishedTitleId = R.string.backup_completed,
                finishedSubtitleId = R.string.args_files_backed_up,
                finishedWithErrorsSubtitleId = R.string.args_files_backed_up_and_failed,
                viewModel = viewModel
            )
        }
        composable(MainRoutes.MediumBackupProcessingSetup.route) {
            PageMediumBackupProcessingSetup(localNavController = localNavController, viewModel = viewModel)
        }
    }
}
