package net.craftoriya.redstoneIndustry.outline

import net.craftoriya.adaptersLib.model.outline.OutlineShape
import net.craftoriya.adaptersLib.model.outline.OutlineStyle
import net.craftoriya.redstoneIndustry.common.Rgb

class OutlineStyles(config: OutlinesConfig) {
    private val map = DEFAULTS + config.styles

    operator fun get(name: String): OutlineStyle = map[name] ?: DEFAULTS.getValue(BOUND)

    companion object {
        const val DEMOLISH = "demolish"
        const val SAFE = "safe"
        const val BOUND = "bound"
        const val WORKSTATION = "workstation"
        const val INPUT = "input"
        const val OUTPUT = "output"
        const val IO = "io"

        private fun area(rgb: Int) = OutlineStyle(shape = OutlineShape.WIREFRAME, rgb = rgb, thickness = 0.06f)
        private fun block(rgb: Int) = OutlineStyle(shape = OutlineShape.WIREFRAME, rgb = rgb, thickness = 0.04f)

        val DEFAULTS: Map<String, OutlineStyle> = linkedMapOf(
            DEMOLISH to area(0xFF4040),
            SAFE to area(0x40FF40),
            BOUND to block(Rgb.BOUND),
            WORKSTATION to block(Rgb.WORKSTATION),
            INPUT to block(Rgb.INPUT),
            OUTPUT to block(Rgb.OUTPUT),
            IO to block(Rgb.IO)
        )

        val DEFAULT_CONFIG = OutlinesConfig(DEFAULTS)
    }
}