package com.rimuru.twobytwo

import android.app.Application
import com.facebook.spectrum.SpectrumSoLoader

class RimuruApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SpectrumSoLoader.init(this)
    }
}
