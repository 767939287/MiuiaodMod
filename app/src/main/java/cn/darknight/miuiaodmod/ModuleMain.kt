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
 * 关闭息屏显示（AOD）在通知到来时播放的波纹/粒子光效，通知图标与时钟照常显示。
 *
 * ## 运行进程
 * `com.miui.aod` 的 `DozeService` 实现 `com.android.systemui.plugins.DozeServicePlugin`，
 * 由 SystemUI 以插件形式加载，因此 `com.miui.aod.AODView` 实际运行在
 * **`com.android.systemui`** 进程，类由插件的独立 ClassLoader 持有。
 * 所以 `scope.list` 只需要 `com.android.systemui`。
 *
 * ## 获取插件 ClassLoader（主路径）
 * hook SystemUI 的 `com.android.systemui.shared.plugins.PluginInstance$PluginFactory`
 * 的 `createPluginContext()`：返回值是 `PluginContextWrapper`（ContextWrapper 子类），
 * 内部持有的就是插件 ClassLoader。该方法只在插件加载时调用，**不在任何热路径上**。
 *
 * ## 安全约束
 * **禁止 hook `ClassLoader.loadClass` / `Class.forName` 等类加载热路径**——此前版本
 * 因此导致 SystemUI 崩溃循环。所有 hook 使用 `ExceptionMode.PASSTHROUGH`，
 * 回调内部只记日志，不向外抛异常。
 *
 * ## 日志规范
 * 采用结构化日志：`event=<事件> result=<ok|fail|skip> [key=value...]`，
 * 异常路径附带 `LSM-*` 错误码，便于 `adb logcat | grep event=` 过滤。
 */
class ModuleMain : XposedModule() {

    /** 目标方法是否已挂上 hook。 */
    private val installed = AtomicBoolean(false)

    /** 是否已启动兜底探测循环（每个进程一次）。 */
    private val probing = AtomicBoolean(false)

    /** 主线程 Handler，用于延时复位 AOD 动画状态。 */
    private var mainHandler: Handler? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "event=module_loaded result=ok process=${param.processName} api=$apiVersion",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SYSTEMUI_PACKAGE) {
            log(
                Log.INFO,
                TAG,
                "LSM-SCOPE-001 event=route_skip result=skip reason=package_mismatch package=${param.packageName}",
            )
            return
        }
        log(
            Log.INFO,
            TAG,
            "event=package_ready result=ok package=${param.packageName} first=${param.isFirstPackage}",
        )

        val handler = Handler(Looper.getMainLooper())
        mainHandler = handler

        // 主路径：插件加载时直接拿到插件 ClassLoader。
        hookPluginFactory(param.classLoader, handler)

        // 兜底一：插件 dex 若挂在主 ClassLoader 上，可直接取。
        probe(param.classLoader)

        // 兜底二：定时遍历插件注册表与线程上下文（只读，零风险）。
        startProbeLoop(param.classLoader, handler)
    }

    /** hook `PluginInstance$PluginFactory#createPluginContext()`，从返回值取插件 ClassLoader。 */
    private fun hookPluginFactory(sysLoader: ClassLoader, handler: Handler) {
        val target = "$PLUGIN_FACTORY#$CREATE_PLUGIN_CONTEXT"
        runCatching {
            val factory = Class.forName(PLUGIN_FACTORY, false, sysLoader)
            val method = factory.getDeclaredMethod(CREATE_PLUGIN_CONTEXT)
            hook(method)
                .setId(HOOK_ID_PLUGIN_FACTORY)
                .setExceptionMode(ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val loader = (result as? ContextWrapper)?.classLoader
                        if (loader != null) scheduleProbe(loader, handler)
                    } catch (t: Throwable) {
                        log(
                            Log.WARN,
                            TAG,
                            "LSM-HOOK-005 event=hook_fallback result=ok recover=proceed reason=$t",
                        )
                    }
                    result
                }
            log(Log.INFO, TAG, "LSM-HOOK-001 event=install_hook target=$target result=ok hook_id=$HOOK_ID_PLUGIN_FACTORY")
        }.onFailure {
            log(Log.ERROR, TAG, "LSM-HOOK-003 event=install_hook target=$target result=fail reason=$it")
        }
    }

    /** 延迟后用插件 ClassLoader 取 `AODView`（避免在插件上下文创建途中加载类）。 */
    private fun scheduleProbe(loader: ClassLoader, handler: Handler) {
        if (installed.get()) return
        handler.postDelayed({
            if (installed.get()) return@postDelayed
            val clazz = runCatching { Class.forName(AOD_VIEW, false, loader) }.getOrNull()
            if (clazz == null) {
                log(Log.WARN, TAG, "LSM-HOOK-002 event=resolve_class target=$AOD_VIEW result=fail reason=not_found")
                return@postDelayed
            }
            log(Log.INFO, TAG, "event=resolve_class target=$AOD_VIEW result=ok via=$loader")
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
                    log(Log.INFO, TAG, "event=probe_finished result=ok installed=${installed.get()} rounds=$round")
                    return
                }
                round++
                runCatching {
                    val injector = Class.forName(PLUGIN_INJECTOR, false, ownLoader)
                    val field = injector.getDeclaredField("sClassLoaders")
                    field.isAccessible = true
                    val map = field.get(null) as? Map<*, *> ?: return@runCatching
                    if (round == 1) log(Log.INFO, TAG, "event=read_plugin_registry result=ok keys=${map.keys}")
                    for (value in map.values) {
                        (value as? ClassLoader)?.let { probe(it) }
                    }
                }.onFailure {
                    if (round == 1) {
                        log(Log.WARN, TAG, "LSM-HOOK-006 event=read_plugin_registry result=fail reason=$it")
                    }
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
        log(Log.INFO, TAG, "event=resolve_class target=$AOD_VIEW result=ok via=$loader")
        install(clazz)
    }

    private fun install(clazz: Class<*>) {
        if (!installed.compareAndSet(false, true)) return
        val target = "$AOD_VIEW#$SHOW_ANIMATE"
        runCatching {
            val method = clazz.getDeclaredMethod(SHOW_ANIMATE, String::class.java)
            hook(method)
                .setId(HOOK_ID_SHOW_ANIMATE)
                .setExceptionMode(ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    // 不创建 AnimationDrawer、不播放动画，但把原逻辑里的 AOD 显示状态补回去，
                    // 否则 SystemUI 认为没有通知动画 -> 来通知时 AOD 不亮。
                    holdAodState(chain.thisObject)
                    log(Log.INFO, TAG, "LSM-HOOK-004 event=hook_hit target=$target decision=skip")
                    null
                }
            log(Log.INFO, TAG, "LSM-HOOK-001 event=install_hook target=$target result=ok hook_id=$HOOK_ID_SHOW_ANIMATE")
        }.onFailure {
            installed.set(false)
            log(Log.ERROR, TAG, "LSM-HOOK-003 event=install_hook target=$target result=fail reason=$it")
        }
    }

    /**
     * 复刻 `AODView.showAnimate` 中 `if (drawer != null)` 分支里的状态调用：
     * `mHost.setNotificationAnimate(true)` + `fireAnimateState()`，延时后再复位为 false。
     * 这样 AOD 会正常亮起并显示通知图标，只是不播放波纹动画。
     */
    private fun holdAodState(aodView: Any?) {
        if (aodView == null) return
        runCatching {
            val hostField = findField(aodView.javaClass, "mHost") ?: run {
                log(Log.WARN, TAG, "LSM-HOOK-007 event=hold_state result=fail reason=no_mHost_field")
                return@runCatching
            }
            val host = hostField.get(aodView) ?: return@runCatching
            val setAnimate = host.javaClass.methods.firstOrNull {
                it.name == "setNotificationAnimate" && it.parameterTypes.size == 1
            } ?: return@runCatching
            val fireState = host.javaClass.methods.firstOrNull { it.name == "fireAnimateState" }

            setAnimate.invoke(host, true)
            fireState?.invoke(host)

            mainHandler?.postDelayed({
                runCatching {
                    setAnimate.invoke(host, false)
                    fireState?.invoke(host)
                }
            }, HOLD_MS)
        }.onFailure {
            log(Log.WARN, TAG, "LSM-HOOK-007 event=hold_state result=fail reason=$it")
        }
    }

    /** 沿类继承链查找字段（含 private）。 */
    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = cls
        while (current != null) {
            runCatching {
                return current!!.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    companion object {
        private const val TAG = "MiuiaodMod"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val AOD_VIEW = "com.miui.aod.AODView"
        private const val SHOW_ANIMATE = "showAnimate"

        /** SystemUI 内部插件工厂。 */
        private const val PLUGIN_FACTORY =
            "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"
        private const val CREATE_PLUGIN_CONTEXT = "createPluginContext"

        /** MIUI 插件 ClassLoader 注册表（兜底探测用）。 */
        private const val PLUGIN_INJECTOR = "com.miui.systemui.plugin.PluginInstanceInjector"

        private const val HOOK_ID_PLUGIN_FACTORY = "miuiaod_plugin_factory"
        private const val HOOK_ID_SHOW_ANIMATE = "miuiaod_show_animate"

        private const val RESOLVE_DELAY_MS = 100L

        /** 动画状态的保持时长，模拟原动画结束时机。 */
        private const val HOLD_MS = 1_200L

        private const val PROBE_INTERVAL_MS = 3_000L
        private const val MAX_ROUNDS = 40
    }
}
