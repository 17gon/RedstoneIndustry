package net.craftoriya.redstoneIndustry.outline

import net.craftoriya.adaptersLib.model.outline.OutlineStyle
import org.spongepowered.configurate.objectmapping.ConfigSerializable


@ConfigSerializable
data class OutlinesConfig(val styles: Map<String, OutlineStyle> = emptyMap())
