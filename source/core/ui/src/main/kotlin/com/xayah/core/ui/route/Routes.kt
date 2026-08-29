package com.xayah.core.ui.route

import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.util.encodedURLWithSpace

sealed class MainRoutes(val route: String) {
    companion object {
        const val ARG_PACKAGE_NAME = "pkgName"
        const val ARG_MEDIA_NAME = "mediaName"
        const val ARG_USER_ID = "userId"
        const val ARG_PRESERVE_ID = "preserveId"
        const val ARG_ACCOUNT_NAME = "accountName"
        const val ARG_ACCOUNT_REMOTE = "accountRemote"
        const val ARG_TARGET = "target"
        const val ARG_OP_TYPE = "opType"
        const val ARG_ID = "id"

        /** 列表页从备份引导页进入时置 true：FAB「继续」= 返回引导页（重新扫描），而不是进入对应处理流程 */
        const val ARG_RETURN_TO_SETUP = "returnToSetup"

        /** 一键备份：应用备份处理完成后自动接续文件备份 */
        const val ARG_CHAIN_FILE_BACKUP = "chainFileBackup"

        /** 文件备份图直接进处理页（跳过引导页），一键备份接续场景用 */
        const val ARG_SKIP_SETUP = "skipSetup"
    }

    data object Dashboard : MainRoutes(route = "main_dashboard")
    data object Cloud : MainRoutes(route = "main_cloud")
    data object CloudAddAccount : MainRoutes(route = "main_cloud_add_account")
    data object FTPSetup : MainRoutes(route = "main_ftp_setup/{$ARG_ACCOUNT_NAME}") {
        fun getRoute(name: String) = "main_ftp_setup/$name"
    }

    data object SFTPSetup : MainRoutes(route = "main_sftp_setup/{$ARG_ACCOUNT_NAME}") {
        fun getRoute(name: String) = "main_sftp_setup/$name"
    }

    data object WebDAVSetup : MainRoutes(route = "main_webdav_setup/{$ARG_ACCOUNT_NAME}") {
        fun getRoute(name: String) = "main_webdav_setup/$name"
    }

    data object SMBSetup : MainRoutes(route = "main_smb_setup/{$ARG_ACCOUNT_NAME}") {
        fun getRoute(name: String) = "main_smb_setup/$name"
    }
    data object Settings : MainRoutes(route = "main_settings")
    data object Restore : MainRoutes(route = "main_restore")
    data object Reload : MainRoutes(route = "main_reload/{$ARG_ACCOUNT_NAME}/{$ARG_ACCOUNT_REMOTE}") {
        fun getRoute(name: String, remote: String) = "main_reload/${name}/${remote}"
    }
    data object BackupSettings : MainRoutes(route = "main_backup_settings")
    data object RestoreSettings : MainRoutes(route = "main_restore_settings")
    data object Schedules : MainRoutes(route = "main_schedules")
    data object LanguageSettings : MainRoutes(route = "main_language_settings")
    data object BlackList : MainRoutes(route = "main_blacklist")
    data object Configurations : MainRoutes(route = "main_configurations")
    data object About : MainRoutes(route = "main_about")
    data object Translators : MainRoutes(route = "main_translators")

    data object List : MainRoutes(route = "main_list/{$ARG_TARGET}/{$ARG_OP_TYPE}/{$ARG_ACCOUNT_NAME}/{$ARG_ACCOUNT_REMOTE}?$ARG_RETURN_TO_SETUP={$ARG_RETURN_TO_SETUP}") {
        fun getRoute(
            target: Target,
            opType: OpType,
            cloudName: String = encodedURLWithSpace,
            backupDir: String = encodedURLWithSpace,
            returnToSetup: Boolean = false,
        ) = "main_list/${target}/${opType}/${cloudName}/${backupDir}" + if (returnToSetup) "?${ARG_RETURN_TO_SETUP}=true" else ""
    }

    data object Details : MainRoutes(route = "main_details/{$ARG_TARGET}/{$ARG_OP_TYPE}/{$ARG_ID}") {
        fun getRoute(target: Target, opType: OpType, id: Long) = "main_details/${target}/${opType}/${id}"
    }

    data object History : MainRoutes(route = "main_history")
    data object DataMigration : MainRoutes(route = "main_data_migration")
    data object DataMigrationExport : MainRoutes(route = "main_data_migration_export")
    data object DataMigrationImport : MainRoutes(route = "main_data_migration_import")
    data object TaskDetails : MainRoutes(route = "main_task_details/{$ARG_ID}") {
        fun getRoute(id: Long) = "main_task_details/${id}"
    }

    data object Directory : MainRoutes(route = "main_directory")

    data object PackagesBackupProcessing : MainRoutes(route = "main_packages_backup_processing")
    data object PackagesBackupProcessingSetup : MainRoutes(route = "main_packages_backup_processing_setup")
    data object PackagesBackupProcessingGraph : MainRoutes(route = "main_packages_backup_processing_graph?$ARG_CHAIN_FILE_BACKUP={$ARG_CHAIN_FILE_BACKUP}") {
        fun getRoute(chainFileBackup: Boolean = false) =
            "main_packages_backup_processing_graph" + if (chainFileBackup) "?$ARG_CHAIN_FILE_BACKUP=true" else ""
    }

    data object PackagesRestoreProcessing : MainRoutes(route = "main_packages_restore_processing")
    data object PackagesRestoreProcessingSetup : MainRoutes(route = "main_packages_restore_processing_setup")
    data object PackagesRestoreProcessingGraph : MainRoutes(route = "main_packages_restore_processing_graph/{$ARG_ACCOUNT_NAME}/{$ARG_ACCOUNT_REMOTE}") {
        fun getRoute(cloudName: String = encodedURLWithSpace, backupDir: String = encodedURLWithSpace) = "main_packages_restore_processing_graph/${cloudName}/${backupDir}"
    }

    data object MediumBackupProcessing : MainRoutes(route = "main_medium_backup_processing")
    data object MediumBackupProcessingSetup : MainRoutes(route = "main_medium_backup_processing_setup")
    data object MediumBackupProcessingGraph : MainRoutes(route = "main_medium_backup_processing_graph?$ARG_SKIP_SETUP={$ARG_SKIP_SETUP}") {
        fun getRoute(skipSetup: Boolean = false) =
            "main_medium_backup_processing_graph" + if (skipSetup) "?$ARG_SKIP_SETUP=true" else ""
    }

    data object MediumRestoreProcessing : MainRoutes(route = "main_medium_restore_processing")
    data object MediumRestoreProcessingSetup : MainRoutes(route = "main_medium_restore_processing_setup")
    data object MediumRestoreProcessingGraph : MainRoutes(route = "main_medium_restore_processing_graph/{$ARG_ACCOUNT_NAME}/{$ARG_ACCOUNT_REMOTE}") {
        fun getRoute(cloudName: String = encodedURLWithSpace, backupDir: String = encodedURLWithSpace) = "main_medium_restore_processing_graph/${cloudName}/${backupDir}"
    }
}
