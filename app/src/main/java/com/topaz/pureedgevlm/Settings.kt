package com.topaz.pureedgevlm

import android.content.Context
import android.content.SharedPreferences

// 全局设置（持久化到 SharedPreferences，关机重启后仍生效）
// 两个开关：
//   kv_reuse         —— 多轮对话是否复用 KV 缓存（默认开，第二轮起首字更快）
//   pipeline_parallel —— 视觉三模型（检测/场景/OCR）是否并行推理（默认开，阶段五实测总耗时更短）
object Settings {
    private const val NAME = "pureedge_settings"
    private const val KV_REUSE = "kv_reuse"
    private const val PIPELINE_PARALLEL = "pipeline_parallel"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun kvReuse(ctx: Context): Boolean = prefs(ctx).getBoolean(KV_REUSE, true)
    fun setKvReuse(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KV_REUSE, v).apply()

    // 视觉三模型并行（true=并行，false=串行）
    fun pipelineParallel(ctx: Context): Boolean = prefs(ctx).getBoolean(PIPELINE_PARALLEL, true)
    fun setPipelineParallel(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(PIPELINE_PARALLEL, v).apply()
}
