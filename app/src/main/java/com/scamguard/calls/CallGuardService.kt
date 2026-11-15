package com.scamguard.calls

import android.app.Service
import android.content.Intent
import android.os.IBinder

class CallGuardService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
