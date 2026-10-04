package net.craftoriya.redstoneIndustry.common

object Trace {
    @Volatile var sink: ((String) -> Unit)? = null
    inline fun log(msg: () -> String) { sink?.invoke(msg()) }
}