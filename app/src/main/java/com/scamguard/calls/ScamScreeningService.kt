package com.scamguard.calls

import android.telecom.Call
import android.telecom.CallScreeningService

class ScamScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        respondToCall(callDetails, CallResponse.Builder().build())
    }
}
