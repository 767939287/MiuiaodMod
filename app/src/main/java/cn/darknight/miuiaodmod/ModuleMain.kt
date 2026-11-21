package cn.darknight.miuiaodmod

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块入口（libxposed API 102，无 UI 纯 hook）。
 *
 * ## 目标
 * 关闭息屏显示（AOD）在通知到来时播放的光效动画。
 *
 * ## 为什么 hook 的是 SystemUI
 * `com.miui.aod` 的 `DozeService` 实现的是
 * `com.android.systemui.plugins.DozeServicePlugin`，以 **SystemUI 插件**的形式加载
 * （`PluginApplication` / `DozeServicePluginImpl`）。因此真正的运行进程是
 * `com.android.systemui`，`com.miui.aod.*` 的类由插件独立 ClassLoader 加载，
 * SystemUI 自带的 ClassLoader 取不到。
 *
 * ## 调用链（反编译 `classes.dex` 证据）
 * ```
 * 通知到达 → AODView.scheduleNotificationAnimation()
 *          → AODView.showAnimate(pkg)      // private void
 *          → NotificationStyleManager.createAnimationDrawer(...)
 *          → NotificationAnimationView.startAnimation()
 * ```
 * 替换 `showAnimate` 直接返回，光效既不创建也不播放；通知图标走
 * `handleUpdateIcons()`，不受影响。
 */
class ModuleMain : XposedModule() {

    /** 插件 ClassLoader 是否已捕获（防止 `loadClass` 重复命中时重复 post）。 */
    private val captured = AtomicBoolean(false)

    /** 目标方法是否已挂上 hook。 */
    private val installed = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "onModuleLoaded: ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SYSTEMUI_PACKAGE) return
        log(Log.INFO, TAG, "onPackageReady: ${param.packageName}")

        // 路径一：插件 dex 已注入当前 classpath（部分 ROM 的加载顺序如此）。
        val direct = runCatching { Class.forName(AOD_VIEW, false, param.classLoader) }.getOrNull()
        if (direct != null) {
            installOnAodView(direct)
            return
        }

        // 路径二：拦截插件 ClassLoader 的类加载。
        // 回调里只能做字符串比较，绝不能同步调用 loadClass（会递归）。
        // 同时挂 `loadClass(String)` 与 `loadClass(String, boolean)`：前者内部会调用
        // 后者，两条都挂可覆盖不同调用方（含 Class.forName 的同进程分支）。
        hookClassLoaderLoadClass()
        log(Log.INFO, TAG, "waiting for $AOD_VIEW via ClassLoader hook")
    }

    private fun hookClassLoaderLoadClass() {
        val loaderClass = ClassLoader::class.java
        runCatching {
            val oneArg = loaderClass.getDeclaredMethod("loadClass", String::class.java)
            hook(oneArg).intercept { chain ->
                val name = chain.args.getOrNull(0) as? String
                if (name == AOD_VIEW) captureLoader(chain.thisObject as? ClassLoader)
                chain.proceed()
            }
        }.onFailure { log(Log.ERROR, TAG, "hook loadClass(String) failed: $it") }

        runCatching {
            val twoArgs = loaderClass.getDeclaredMethod(
                "loadClass",
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
            )
            hook(twoArgs).intercept { chain ->
                val name = chain.args.getOrNull(0) as? String
                if (name == AOD_VIEW) captureLoader(chain.thisObject as? ClassLoader)
                chain.proceed()
            }
        }.onFailure { log(Log.ERROR, TAG, "hook loadClass(String, boolean) failed: $it") }
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
                    log(Log.WARN, TAG, "resolve $AOD_VIEW failed: $it")
                }
        }, RESOLVE_DELAY_MS)
    }

    private fun installOnAodView(clazz: Class<*>) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            val method = clazz.getDeclaredMethod(SHOW_ANIMATE, String::class.java)
            method.isAccessible = true
            // 直接返回 null，跳过光效的创建与播放。
            hook(method).intercept { null }
        }.onSuccess {
            log(Log.INFO, TAG, "hook $AOD_VIEW#$SHOW_ANIMATE success")
        }.onFailure {
            installed.set(false)
            log(Log.ERROR, TAG, "hook $AOD_VIEW#$SHOW_ANIMATE failed: $it")
        }
    }

    companion object {
        private const val TAG = "MiuiaodMod"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val AOD_VIEW = "com.miui.aod.AODView"
        private const val SHOW_ANIMATE = "showAnimate"
        private const val RESOLVE_DELAY_MS = 100L
    }
}
