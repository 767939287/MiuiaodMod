package cn.darknight.miuiaodmod.ui.component.pref

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.darknight.miuiaodmod.prefs.OptionSpec
import cn.darknight.miuiaodmod.prefs.OptionType
import cn.darknight.miuiaodmod.ui.util.LocalPreferenceBackdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/** 根据 [OptionSpec.type] 渲染对应组件。 */
@Composable
fun HookOptionView(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
    onArrowClick: () -> Unit = {},
    backdrop: LayerBackdrop? = LocalPreferenceBackdrop.current,
) {
    when (spec.type) {
        OptionType.SWITCH -> HookSwitchCard(spec, modifier)
        OptionType.CHECKBOX -> HookCheckboxCard(spec, modifier)
        OptionType.ARROW -> HookArrowCard(spec, modifier, onArrowClick)
        OptionType.DROPDOWN -> HookDropdownCard(spec, modifier, backdrop)
        OptionType.RADIO -> HookRadioCard(spec, modifier)
        OptionType.SLIDER -> HookSliderCard(spec, modifier)
        OptionType.TEXT -> HookTextCard(spec, modifier)
        OptionType.PACKAGE_LIST -> HookPackageListCard(spec, modifier)
        OptionType.SPINNER -> HookDropdownCard(spec, modifier, backdrop)
    }
}
