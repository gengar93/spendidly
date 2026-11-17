package io.github.gengar93.spendidly

import android.app.Application
import io.github.gengar93.spendidly.backend.SpendidlyBackend

class SpendidlyApplication : Application() {
    val backend: SpendidlyBackend by lazy { SpendidlyBackend(this) }
}
