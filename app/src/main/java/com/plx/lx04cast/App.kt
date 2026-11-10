package com.plx.lx04cast

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.plx.lx04cast.lyrics.LyricsRepository

class App : Application() {

    private val main = Handler(Looper.getMainLooper())
    private var startedActivities = 0

    private val trimUiMemory = Runnable {
        if (startedActivities == 0) {
            LyricsRepository.trimMemory()
            UiMemory.trimAfterUiGone()
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                main.removeCallbacks(trimUiMemory)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    main.removeCallbacks(trimUiMemory)
                    // Give the window one second to disconnect its surface before asking
                    // the renderer to release its caches.
                    main.postDelayed(trimUiMemory, 1_000L)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Lyrics are a UI convenience; drop them as soon as only the receiver service lives.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            LyricsRepository.trimMemory()
        }
    }
}
