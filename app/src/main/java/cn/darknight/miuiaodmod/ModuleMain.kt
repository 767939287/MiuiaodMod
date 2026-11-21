package cn.darknight.miuiaodmod

import android.content.ContextWrapper
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
 * 关闭息屏显示（AOD）在通知到来时播放的光效动画，通知图标照常显示。
 *
 * ## 运行进程
 * `com.miui.aod` 的 `DozeService` 实现 `com.android.systemui.plugins.DozeServicePlugin`，
 * 由 SystemUI 以插件形式加载，因此 `com.miui.aod.AODView` 实际运行在
 * **`com.android.systemui`** 进程，类由插件的独立 ClassLoader 持有。
 *
 * ## 获取插件 ClassLoader（主路径）
 * hook SystemUI 的 `com.android.systemui.shared.plugins.PluginInstance$PluginFactory`
 * 的 `createPluginContext()`：它的返回值是 `PluginContextWrapper`（ContextWrapper 子类），
 * 内部持有的就是插件 ClassLoader。该方法只在插件加载时调用，**不在任何热路径上**。
 *
 * 该思路参考自 HyperAodZen（AGPL/Apache-2.0 项目，MIUI/HyperOS 上验证过）。
 *
 * ## 安全约束
 * **禁止 hook `ClassLoader.loadClass` / `Class.forName` 等类加载热路径**——此前版本
 * 因此导致 SystemUI 反复崩溃。所有 hook 均设置 `ExceptionMode.PASSTHROUGH`，
 * 回调内部只做日志，不向外抛异常。
 */
class ModuleMain : XposedModule() {

    /** 目标方法是否已挂上 hook。 */
    private val installed = AtomicBoolean(false)

    /** 是否已启动兜底探测循环（每个进程一次）。 */
    private val probing = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "onModuleLoaded: ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SYSTEMUI_PACKAGE) return
        log(Log.INFO, TAG, "onPackageReady: ${param.packageName}")

        val handler = Handler(Looper.getMainLooper())

        // 主路径：插件加载时直接拿到插件 ClassLoader。
        hookPluginFactory(param.classLoader, handler)

        // 兜底一：插件 dex 若挂在主 ClassLoader 上，可直接取。
        probe(param.classLoader)

        // 兜底二：定时遍历插件注册表与线程上下文（只读，零风险）。
        startProbeLoop(param.classLoader, handler)
    }

    /** hook `PluginInstance$PluginFactory#createPluginContext()`，从返回值取插件 ClassLoader。 */
    private fun hookPluginFactory(sysLoader: ClassLoader, handler: Handler) {
        runCatching {
            val factory = Class.forName(PLUGIN_FACTORY, false, sysLoader)
            val method = factory.getDeclaredMethod(CREATE_PLUGIN_CONTEXT)
            hook(method).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept { chain ->
                val result = chain.proceed()
                try {
                    val loader = (result as? ContextWrapper)?.classLoader
                    if (loader != null) scheduleProbe(loader, handler)
                } catch (t: Throwable) {
                    log(Log.WARN, TAG, "resolve plugin classloader failed: $t")
                }
                result
            }
        }.onSuccess {
            log(Log.INFO, TAG, "hook $PLUGIN_FACTORY#$CREATE_PLUGIN_CONTEXT success")
        }.onFailure {
            log(Log.ERROR, TAG, "hook $PLUGIN_FACTORY#$CREATE_PLUGIN_CONTEXT failed: $it")
        }
    }

    /** 延迟后用插件 ClassLoader 取 `AODView`（避免在插件上下文创建途中加载类）。 */
    private fun scheduleProbe(loader: ClassLoader, handler: Handler) {
        if (installed.get()) return
        handler.postDelayed({
            if (installed.get()) return@postDelayed
            val clazz = runCatching { Class.forName(AOD_VIEW, false, loader) }.getOrNull()
            if (clazz == null) {
                log(Log.WARN, TAG, "plugin loader cannot resolve $AOD_VIEW")
                return@postDelayed
            }
            log(Log.INFO, TAG, "found $AOD_VIEW via plugin loader $loader")
            install(clazz)
        }, RESOLVE_DELAY_MS)
    }

    /** 兜底：定时遍历 MIUI 插件注册表与线程上下文 ClassLoader。 */
    private fun startProbeLoop(ownLoader: ClassLoader, handler: Handler) {
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
                runCatching {
                    val injector = Class.forName(PLUGIN_INJECTOR, false, ownLoader)
                    val field = injector.getDeclaredField("sClassLoaders")
                    field.isAccessible = true
                    val map = field.get(null) as? Map<*, *> ?: return@runCatching
                    if (round == 1) log(Log.INFO, TAG, "plugin loaders: ${map.keys}")
                    for (value in map.values) {
                        (value as? ClassLoader)?.let { probe(it) }
                    }
                }.onFailure {
                    if (round == 1) log(Log.INFO, TAG, "read $PLUGIN_INJECTOR failed: $it")
                }

                runCatching {
                    for (thread in Thread.getAllStackTraces().keys) {
                        thread.contextClassLoader?.let { probe(it) }
                    }
                }

                handler.postDelayed(this, PROBE_INTERVAL_MS)
            }
        }
        handler.postDelayed(task, PROBE_INTERVAL_MS)
    }

    /** 尝试用指定 ClassLoader 取 `AODView`，取到就挂钩。 */
    private fun probe(loader: ClassLoader) {
        if (installed.get()) return
        val clazz = runCatching { Class.forName(AOD_VIEW, false, loader) }.getOrNull() ?: return
        log(Log.INFO, TAG, "found $AOD_VIEW via $loader")
        install(clazz)
    }

    private fun install(clazz: Class<*>) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            val method = clazz.getDeclaredMethod(SHOW_ANIMATE, String::class.java)
            // 直接返回 null，跳过光效的创建与播放；通知图标由 handleUpdateIcons 负责，不受影响。
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
        private const val AOD_VIEW = "com.miui.aod.AODView"
        private const val SHOW_ANIMATE = "showAnimate"

        /** SystemUI 内部类，插件工厂。 */
        private const val PLUGIN_FACTORY =
            "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"
        private const val CREATE_PLUGIN_CONTEXT = "createPluginContext"

        /** MIUI 插件 ClassLoader 注册表（兜底探测用）。 */
        private const val PLUGIN_INJECTOR = "com.miui.systemui.plugin.PluginInstanceInjector"

        private const val RESOLVE_DELAY_MS = 100L
        private const val PROBE_INTERVAL_MS = 3_000L
        private const val MAX_ROUNDS = 40
    }
}
