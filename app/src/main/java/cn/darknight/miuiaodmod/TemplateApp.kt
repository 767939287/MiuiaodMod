package cn.darknight.miuiaodmod

import android.app.Application
import cn.darknight.miuiaodmod.prefs.ConfigState
import cn.darknight.miuiaodmod.prefs.OptionRegistry
import cn.darknight.miuiaodmod.prefs.PrefsStore
import cn.darknight.miuiaodmod.ui.screen.features.featureSpecs
import cn.darknight.miuiaodmod.xposed.HookStatusStore
import cn.darknight.miuiaodmod.xposed.SafeModeReader
import cn.darknight.miuiaodmod.xposed.XposedServiceManager

class TemplateApp : Application() {

    override fun onCreate() {
        super.onCreate()
        PrefsStore.init(this)
        ConfigState.init(this)
        OptionRegistry.registerAll(featureSpecs())
        HookStatusStore.initialize(this)
        SafeModeReader.initialize(this)
        XposedServiceManager.init()
    }
}
