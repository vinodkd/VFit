package com.vfit.app

import android.app.Application
import com.vfit.app.data.Repository
import com.vfit.app.data.VFitDb

class VFitApp : Application() {
    val repository: Repository by lazy { Repository(VFitDb.open(this)) }
}
