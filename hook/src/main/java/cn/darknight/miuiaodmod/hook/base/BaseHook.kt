package cn.darknight.miuiaodmod.hook.base

import cn.darknight.miuiaodmod.hook.device.DeviceContext
import cn.darknight.miuiaodmod.hook.device.DeviceType
import cn.darknight.miuiaodmod.hook.rule.HookSkippedException
import cn.darknight.miuiaodmod.hook.rule.HookVariant
import cn.darknight.miuiaodmod.hook.rule.HookVersionGate
import cn.darknight.miuiaodmod.hook.rule.VersionContext
import cn.darknight.miuiaodmod.hook.xposed.HookHelper

/**
 * 单条 hook 规则基类。
 *
 * 子类实现 [init]，在其中使用 [HookHelper] 完成挂载。
 *
 * 版本筛选：
 * - [versionGate]：不满足时整条规则跳过（不安装、不记录失败）；
 * - [variants]：按顺序匹配第一个满足的版本分支，实现「按 HyperOS / Android / 应用版本应用不同 hook 代码」。
 */
abstract class BaseHook {

    /** 日志 TAG。 */
    open val tag: String get() = javaClass.simpleName

    /** 绑定的配置键；用于 hook 状态上报与 UI 展示。 */
    open val key: String get() = javaClass.simpleName

    /** 版本门禁；为空表示不限制。 */
    open val versionGate: HookVersionGate? get() = null

    /** 设备形态白名单（手机 / 平板 / 折叠屏）；为空表示各设备通用。 */
    open val deviceScope: Set<DeviceType>? get() = null

    /** 版本分支；为空表示直接执行 [init]。 */
    open val variants: List<HookVariant> get() = emptyList()

    /** 当前目标包信息，由 [BaseLoad] 在安装前注入，可在 [init] 中读取（如 `target.classLoader`）。 */
    protected lateinit var target: PackageTarget

    /** 执行挂载（未声明 [variants] 时使用）。 */
    open fun init() {}

    /**
     * 执行设备 / 版本筛选并安装，返回命中的分支名（默认分支返回 null）。
     * 由 [BaseLoad] 调用，不应在子类中手动调用。
     */
    internal fun apply(target: PackageTarget): String? {
        this.target = target
        val device = DeviceContext.current
        deviceScope?.takeIf { it.isNotEmpty() }?.let {
            if (device.type !in it) {
                throw HookSkippedException("device scope not matched: $it | device=${device.type}")
            }
        }
        if (versionGate == null && variants.isEmpty()) {
            init()
            return null
        }
        val context = VersionContext.of(target.packageName, target.appVersionName, target.appVersionCode)
        versionGate?.let {
            if (!it.matches(context)) {
                throw HookSkippedException("version gate not matched: $it | $context")
            }
        }
        if (variants.isNotEmpty()) {
            val variant = variants.firstOrNull { it.matches(context, device.type) }
                ?: throw HookSkippedException("no variant matched: $context | device=${device.type}")
            variant.body()
            return variant.name
        }
        init()
        return null
    }

}
