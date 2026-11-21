package cn.darknight.miuiaodmod

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
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
 * ## 运行进程
 * `com.miui.aod` 的 `DozeService` 实现 `com.android.systemui.plugins.DozeServicePlugin`，
 * 由 SystemUI 以插件形式加载，所以 `com.miui.aod.AODView` 实际运行在
 * **`com.android.systemui`** 进程，且类由插件的独立 ClassLoader 持有。
 *
 * ## 安全策略（重要）
 * 本版本**不 hook 任何类加载热路径**（`ClassLoader.loadClass` 曾导致 SystemUI 崩溃）。
 * 改为定时探测：
 * 1. 直接尝试用当前进程 ClassLoader 取 `AODView`；
 * 2. 读取 MIUI 插件注册表 `PluginInstanceInjector.sClassLoaders` 里的每个 ClassLoader；
 * 3. 遍历线程上下文的 ClassLoader。
 * 只有真正取到 `AODView` 后，才对 `showAnimate` 挂钩一次，失败只记日志。
 */
class ModuleMain : XposedModule() {

    /** 目标方法是否已挂上 hook。 */
    private val installed = AtomicBoolean(false)

    /** 是否已启动探测循环（每个进程一次）。 */
    private val probing = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "onModuleLoaded: ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val pkg = param.packageName
        if (pkg != SYSTEMUI_PACKAGE && pkg != AOD_PACKAGE) return
        log(Log.INFO, TAG, "onPackageReady: $pkg")

        val handler = Handler(Looper.getMainLooper())

        // 先试直接取（少数 ROM 插件 dex 挂在主 ClassLoader 上）。
        probe(pkg, param.classLoader)

        if (!probing.compareAndSet(false, true)) return
        var round = 0
        val task = object : Runnable {
            override fun run() {
                if (installed.get() || round >= MAX_ROUNDS) {
                    log(
                        Log.INFO,
                        TAG,
                        "probe finished: installed=${installed.get()}, rounds=$round",
                    )
                    return
                }
                round++
                probePluginLoaders(pkg, param.classLoader)
                handler.postDelayed(this, PROBE_INTERVAL_MS)
            }
        }
        handler.postDelayed(task, PROBE_INTERVAL_MS)
    }

    /** 遍历可能的插件 ClassLoader 来源。 */
    private fun probePluginLoaders(pkg: String, ownLoader: ClassLoader) {
        // 1) MIUI 插件 ClassLoader 注册表（SystemUI 侧）。
        runCatching {
            val injector = Class.forName(PLUGIN_INJECTOR, false, ownLoader)
            val field = injector.getDeclaredField("sClassLoaders")
            field.isAccessible = true
            val map = field.get(null) as? Map<*, *> ?: return@runCatching
            log(Log.INFO, TAG, "plugin loaders: ${map.keys}")
            for (value in map.values) {
                (value as? ClassLoader)?.let { probe(pkg, it) }
            }
        }.onFailure {
            log(Log.INFO, TAG, "read $PLUGIN_INJECTOR failed: $it")
        }

        // 2) 线程上下文 ClassLoader。
        runCatching {
            for (thread in Thread.getAllStackTraces().keys) {
                thread.contextClassLoader?.let { probe(pkg, it) }
            }
        }
    }

    /** 尝试用指定 ClassLoader 取 `AODView`，取到就挂钩。 */
    private fun probe(pkg: String, loader: ClassLoader) {
        if (installed.get()) return
        val clazz = runCatching { Class.forName(AOD_VIEW, false, loader) }.getOrNull() ?: return
        log(Log.INFO, TAG, "found $AOD_VIEW in $pkg via $loader")
        install(clazz)
    }

    private fun install(clazz: Class<*>) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            val method = clazz.getDeclaredMethod(SHOW_ANIMATE, String::class.java)
            // 直接返回 null，跳过光效的创建与播放。
            hook(method).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept { null }
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
        private const val AOD_PACKAGE = "com.miui.aod"
        private const val AOD_VIEW = "com.miui.aod.AODView"
        private const val SHOW_ANIMATE = "showAnimate"
        private const val PLUGIN_INJECTOR = "com.miui.systemui.plugin.PluginInstanceInjector"
        private const val PROBE_INTERVAL_MS = 3_000L
        private const val MAX_ROUNDS = 40
    }
}
