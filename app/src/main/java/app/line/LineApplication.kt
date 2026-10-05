package app.line

import android.app.Application
import app.line.push.PushRegistrar

class LineApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppNotifications.createChannels(this)
        PushRegistrar.refresh(this)
    }
}
