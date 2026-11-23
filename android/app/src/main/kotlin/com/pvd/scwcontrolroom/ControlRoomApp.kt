package com.pvd.scwcontrolroom

import android.app.Application
import com.pvd.scwcontrolroom.model.AppModel
import com.pvd.scwcontrolroom.model.EncryptedKeyStore
import com.pvd.scwcontrolroom.model.FreeProStore
import com.pvd.scwcontrolroom.model.MockProStore
import com.pvd.scwcontrolroom.model.PlayProStore
import com.pvd.scwcontrolroom.model.ProStore

/** Holds the app's state for as long as the process lives. Tests swap in their own. */
class ControlRoomApp : Application() {
    lateinit var model: AppModel
    lateinit var pro: ProStore

    override fun onCreate() {
        super.onCreate()
        model = AppModel(EncryptedKeyStore(this))
        pro = when (BuildConfig.STORE) {
            "play" -> PlayProStore(this)
            "github" -> FreeProStore
            else -> MockProStore(this)
        }
    }
}
