package com.xayah.core.util.command

import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.SymbolUtil.shellQuote
import com.xayah.core.util.command.BaseUtil.execute
import com.xayah.core.util.model.ShellResult

object PreparationUtil {
    suspend fun listExternalStorage(): ShellResult = run {
        // mount | awk '$3 ~ "/mnt/media_rw/[^/]+$" {print $3}'
        execute(
            "mount",
            "|",
            "awk",
            "'${SymbolUtil.USD}3 ~ \"/mnt/media_rw/[^/]+${SymbolUtil.USD}\" {print ${SymbolUtil.USD}3}'",
        )
    }

    suspend fun getExternalStorageType(path: String): ShellResult = run {
        // mount | awk '$3 == "/mnt/media_rw/6EBF-FE14" {print $5}'
        // path 嵌入 awk 字符串字面量：反斜杠与双引号需 awk 转义；整个 awk 程序经 shellQuote
        // 单引号包裹，防止单引号逃逸 root shell。
        val awkProgram = "\$3 == \"${path.replace("\\", "\\\\").replace("\"", "\\\"")}\" {print \$5}"
        execute(
            "mount",
            "|",
            "awk",
            shellQuote(awkProgram),
        )
    }

    suspend fun getInputMethods(): ShellResult = run {
        // settings get secure default_input_method
        execute(
            "settings",
            "get",
            "secure",
            "default_input_method",
        )
    }

    suspend fun setInputMethods(inputMethods: String): ShellResult = run {
        var isSuccess: Boolean
        val out = mutableListOf<String>()

        // ime enable "$inputMethods"
        execute(
            "ime",
            "enable",
            shellQuote(inputMethods),
        ).also { result ->
            isSuccess = result.isSuccess
            out.addAll(result.out)
        }

        // ime set "$inputMethods"
        execute(
            "ime",
            "set",
            shellQuote(inputMethods),
        ).also { result ->
            isSuccess = isSuccess and result.isSuccess
            out.addAll(result.out)
        }

        // settings put secure default_input_method "$inputMethods"
        execute(
            "settings",
            "put",
            "secure",
            "default_input_method",
            shellQuote(inputMethods),
        ).also { result ->
            isSuccess = isSuccess and result.isSuccess
            out.addAll(result.out)
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun getAccessibilityServices(): ShellResult = run {
        // settings get secure enabled_accessibility_services
        execute(
            "settings",
            "get",
            "secure",
            "enabled_accessibility_services",
        )
    }

    suspend fun setAccessibilityServices(accessibilityServices: String): ShellResult = run {
        var isSuccess: Boolean
        val out = mutableListOf<String>()

        // settings put secure enabled_accessibility_services "$accessibilityServices"
        execute(
            "settings",
            "put",
            "secure",
            "enabled_accessibility_services",
            shellQuote(accessibilityServices),
        ).also { result ->
            isSuccess = result.isSuccess
            out.addAll(result.out)
        }

        // settings put secure accessibility_enabled 1
        execute(
            "settings",
            "put",
            "secure",
            "accessibility_enabled",
            "1",
        ).also { result ->
            isSuccess = isSuccess and result.isSuccess
            out.addAll(result.out)
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun setInstallEnv(): ShellResult = run {
        var isSuccess: Boolean
        val out = mutableListOf<String>()

        // settings put global verifier_verify_adb_installs 0
        execute(
            "settings",
            "put",
            "global",
            "verifier_verify_adb_installs",
            "0",
        ).also { result ->
            isSuccess = result.isSuccess
            out.addAll(result.out)
        }

        // settings put global package_verifier_enable 0
        execute(
            "settings",
            "put",
            "global",
            "package_verifier_enable",
            "0",
        ).also { result ->
            isSuccess = result.isSuccess
            out.addAll(result.out)
        }

        // settings get global package_verifier_user_consent
        val userConsent = execute(
            "settings",
            "get",
            "global",
            "package_verifier_user_consent",
        ).outString.trim()
        if (userConsent != "-1") {
            // settings put global package_verifier_user_consent -1
            execute(
                "settings",
                "put",
                "global",
                "package_verifier_user_consent",
                "-1",
            ).also { result ->
                isSuccess = result.isSuccess
                out.addAll(result.out)
            }

            // settings put global upload_apk_enable 0
            execute(
                "settings",
                "put",
                "global",
                "upload_apk_enable",
                "0",
            ).also { result ->
                isSuccess = result.isSuccess
                out.addAll(result.out)
            }
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }
}
