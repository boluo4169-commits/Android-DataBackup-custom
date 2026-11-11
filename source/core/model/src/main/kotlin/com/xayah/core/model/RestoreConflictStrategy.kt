package com.xayah.core.model

/**
 * 文件恢复遇到「本机已有同名文件」时的策略。
 *
 * 只作用于 `medium/restore`（把备份解压回本机），与备份侧的「替换 / 跳过 / 复制」
 * 是两套独立偏好，不共用。
 */
enum class RestoreConflictStrategy(val id: Int) {
    /** 覆盖同名文件；不传参数时 GNU tar 默认就是这个行为，显式写出仅为语义明确。 */
    OVERWRITE(0),

    /** 同名文件保持本机版本、静默跳过；备份里多出来的文件仍会补上。 */
    SKIP(1),

    /** 旧文件改名成 `xxx.~1~` 保留，恢复出来的用原名。 */
    KEEP_BOTH(2),

    /**
     * 每次恢复遇到同名冲突时**临时问我**，让我给不同目录选不同规则。
     * 这是「询问模式」而不是固定策略：实际 tar 参数由弹窗里选出的具体策略决定，
     * 见 [RestoreAskDecision]。弹窗带「本次全部按这个来」，避免目录多时反复问。
     */
    ASK(3),
    ;

    companion object {
        val DEFAULT = OVERWRITE

        fun fromId(id: Int): RestoreConflictStrategy = entries.find { it.id == id } ?: DEFAULT
    }
}

/**
 * 生成传给 `tar --totals <exclusion> <clear> -xpf …` 的冲突参数。
 *
 * 真机实测（GNU tar 1.35，2026-11-10）：
 * - `--overwrite` 与 `--overwrite-dir` **互斥**，同时传会 `exit 2` 且整包解压失败，故「替换」只用 `--overwrite`。
 * - `--skip-old-files` 静默跳过且不报错（`-k/--keep-old-files` 会报错退出 2），与 `-m` / `-xpf` / `--exclude` 叠加均正常。
 * - `--backup=numbered` 生成 `a.jpg.~1~`，旧文件权限一并保留。
 * - 「干净恢复」`--recursive-unlink` 会先清空目录层次，此时叠加任何策略都静默失效
 *   （实测 `clean+skip` / `clean+backup` 结果与纯 clean 完全一致），因此二者互斥、干净恢复优先。
 *
 * 返回的是固定常量，**不接受外部自由文本**，避免把用户输入拼进 root shell 命令。
 */
fun resolveRestoreConflictArgs(cleanRestoring: Boolean, strategy: RestoreConflictStrategy): String =
    if (cleanRestoring) "--recursive-unlink"
    else when (strategy) {
        RestoreConflictStrategy.OVERWRITE -> "--overwrite"
        RestoreConflictStrategy.SKIP -> "--skip-old-files"
        RestoreConflictStrategy.KEEP_BOTH -> "--backup=numbered"
        // ASK 是「询问模式」，正常路径下调用方会拿弹窗选出的具体策略重新生成本参数。
        // 万一弹窗没走到（预览失败 / 决策通道异常），必须保住本机旧文件：
        // 兜底用 --skip-old-files 而不是 --overwrite，宁可少覆盖也绝不静默覆盖用户数据。
        RestoreConflictStrategy.ASK -> "--skip-old-files"
    }
