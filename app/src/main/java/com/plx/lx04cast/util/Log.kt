package com.plx.lx04cast.util

import android.util.Log as AndroidLog

/** Thin wrapper kept compatible with the copied CastBay modules (GPL-3.0). */
object Log {
    fun d(tag: String, message: String) = AndroidLog.d(tag, message)
    fun d(tag: String, message: String, error: Throwable?) = AndroidLog.d(tag, message, error)
    fun i(tag: String, message: String) = AndroidLog.i(tag, message)
    fun i(tag: String, message: String, error: Throwable?) = AndroidLog.i(tag, message, error)
    fun w(tag: String, message: String) = AndroidLog.w(tag, message)
    fun w(tag: String, message: String, error: Throwable?) = AndroidLog.w(tag, message, error)
    fun e(tag: String, message: String) = AndroidLog.e(tag, message)
    fun e(tag: String, message: String, error: Throwable?) = AndroidLog.e(tag, message, error)

    /** CastBay used this to mark personal data in reports; no reports here. */
    fun personal(value: Any?): String = value?.toString() ?: ""
}
