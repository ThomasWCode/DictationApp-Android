package com.thomaswcode.dictationapp.core

/** Minimal logging seam so the core stays free of android.* and can run in plain JVM tests. */
interface Logger {
    fun debug(message: String)

    fun info(message: String)

    fun warn(message: String, error: Throwable? = null)

    fun error(message: String, error: Throwable? = null)
}

object NoLogger : Logger {
    override fun debug(message: String) = Unit

    override fun info(message: String) = Unit

    override fun warn(message: String, error: Throwable?) = Unit

    override fun error(message: String, error: Throwable?) = Unit
}
