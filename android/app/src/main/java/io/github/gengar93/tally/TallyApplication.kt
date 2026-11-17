package io.github.gengar93.tally

import android.app.Application
import io.github.gengar93.tally.backend.TallyBackend

class TallyApplication : Application() {
    val backend: TallyBackend by lazy { TallyBackend(this) }
}
