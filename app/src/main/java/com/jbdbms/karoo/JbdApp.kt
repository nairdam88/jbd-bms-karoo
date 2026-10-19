package com.jbdbms.karoo

import android.app.Application
import com.jbdbms.karoo.data.BmsRepository

class JbdApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BmsRepository.init(this)
    }
}
