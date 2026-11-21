package cn.darknight.miuiaodmod.hook.base

import cn.darknight.miuiaodmod.hook.demo.DemoLoad
import cn.darknight.miuiaodmod.hook.miuiaod.MiuiaodLoad

/**
 * 目标 Load 注册表。
 *
 * 新增目标包时，在这里登记对应的 [BaseLoad] 实现即可。
 */
object HookEntryRegistry {

    private val loads: List<BaseLoad> = listOf(
        DemoLoad(),
        MiuiaodLoad(),
    )

    fun loadsFor(packageName: String): List<BaseLoad> =
        loads.filter { it.targetPackages.contains(packageName) }

    fun all(): List<BaseLoad> = loads
}
