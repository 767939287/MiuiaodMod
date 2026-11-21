package cn.darknight.miuiaodmod.hook.miuiaod

import android.os.Handler
import android.os.Looper
import cn.darknight.miuiaodmod.hook.base.BaseHook
import cn.darknight.miuiaodmod.hook.base.BaseLoad
import cn.darknight.miuiaodmod.hook.base.PackageTarget
import cn.darknight.miuiaodmod.hook.prefs.HookPrefs
import cn.darknight.miuiaodmod.hook.xposed.BeforeHook
import cn.darknight.miuiaodmod.hook.xposed.HookHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 息屏显示（`com.miui.aod`）相关的 hook 入口。
 *
 * 注意：AOD 的显示逻辑并不运行在 `com.miui.aod` 自己的进程里——`DozeService`
 * 实现的是 `com.android.systemui.plugins.DozeServicePlugin`，以 **SystemUI 插件**
 * 的形式加载（`PluginApplication` / `DozeServicePluginImpl`）。
 * 因此真正的运行进程是 `com.android.systemui`，`com.miui.aod.*` 的类由插件独立
 * ClassLoader 加载，SystemUI 自带的 ClassLoader 取不到。
 */
class MiuiaodLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(SYSTEMUI_PACKAGE, AOD_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            MiuiaodNotificationEffectHook(),
            // 说明：App 侧暂无该配置项 UI，默认值取 true 才会生效；
            // 如后续接入设置页，把默认值改回 false。
            HookPrefs.getBoolean(MiuiaodNotificationEffectHook.KEY, true),
        )
    }

    companion object {
        const val SYSTEMUI_PACKAGE = "com.android.systemui"
        const val AOD_PACKAGE = "com.miui.aod"
    }
}

/**
 * 关闭 AOD「通知到来时的光效动画」。
 *
 * 调用链（反编译 `classes.dex` 证据）：
 * ```
 * 通知到达 → AODView.scheduleNotificationAnimation()
 *          → AODView.showAnimate(pkg)      // private void
 *          → NotificationStyleManager.createAnimationDrawer(...)
 *          → NotificationAnimationView.startAnimation()
 * ```
 * 替换 `showAnimate` 直接返回，光效既不创建也不播放；
 * 通知图标的更新走 `handleUpdateIcons()`，不受影响。
 *
 * 失败时只记录日志，不改动目标原有行为。
 */
class MiuiaodNotificationEffectHook : BaseHook() {

    override val key: String = KEY

    /** 插件 ClassLoader 是否已捕获（防止 `loadClass` 重复命中时重复 post）。 */
    private val captured = AtomicBoolean(false)

    /** 目标方法是否已挂上 hook。 */
    private val installed = AtomicBoolean(false)

    override fun init() {
        // 路径一：插件 dex 已注入当前 classpath（部分 ROM 的加载顺序如此）。
        val direct = runCatching { Class.forName(AOD_VIEW, false, target.classLoader) }.getOrNull()
        if (direct != null) {
            installOnAodView(direct)
            return
        }

        // 路径二：拦截插件 ClassLoader 的类加载。
        // 这里只能做一次字符串比较，回调里绝不能同步调用 loadClass（递归）。
        // 同时挂 `loadClass(String)` 与 `loadClass(String, boolean)`：
        // 前者内部会调用后者，两条都挂可覆盖不同调用方（含 Class.forName 的同进程分支）。
        val onLoadClass: BeforeHook = { param ->
            val name = param.args.getOrNull(0) as? String
            if (name == AOD_VIEW) captureLoader(param.thisObject as? ClassLoader)
        }
        HookHelper.findAndHookMethod(
            ClassLoader::class.java,
            "loadClass",
            String::class.java,
            callback = onLoadClass,
        )
        HookHelper.findAndHookMethod(
            ClassLoader::class.java,
            "loadClass",
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
            callback = onLoadClass,
        )
        HookHelper.log("$tag: waiting for $AOD_VIEW via ClassLoader hook")
    }

    private fun captureLoader(loader: ClassLoader?) {
        if (loader == null) return
        if (!captured.compareAndSet(false, true)) return
        // 等目标类被真正加载完成后再取类安装，避免在 loadClass 内部递归。
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { Class.forName(AOD_VIEW, false, loader) }
                .onSuccess { installOnAodView(it) }
                .onFailure {
                    // 取不到就放开标志，等下一次加载再试（插件可能被卸载重载）。
                    captured.set(false)
                    HookHelper.log("$tag: resolve $AOD_VIEW failed", it)
                }
        }, RESOLVE_DELAY_MS)
    }

    private fun installOnAodView(clazz: Class<*>) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            HookHelper.findAndHookMethodReplace(clazz, SHOW_ANIMATE, String::class.java) {
                // 直接返回，跳过光效的创建与播放。
                null
            }
        }.onSuccess {
            HookHelper.log("$tag: hook $AOD_VIEW#$SHOW_ANIMATE success")
        }.onFailure {
            HookHelper.log("$tag: hook $AOD_VIEW#$SHOW_ANIMATE failed", it)
        }
    }

    companion object {
        /** 配置键（App 侧需加 `prefs_key_` 前缀，由 [HookPrefs] 统一处理）。 */
        const val KEY = "miuiaod_notification_effect_off"

        private const val AOD_VIEW = "com.miui.aod.AODView"
        private const val SHOW_ANIMATE = "showAnimate"
        private const val RESOLVE_DELAY_MS = 100L
    }
}
