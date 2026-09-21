package app.krafted.candytriangle

import android.app.Application
import android.content.Context

/**
 * Application entry point; owns the app-scoped [AppContainer].
 *
 * The container is built here rather than lazily off a global so that its lifetime is exactly the
 * process lifetime and tests never see a half-initialised singleton. Construction itself is cheap:
 * every dependency inside the container is `lazy`, so no disk I/O happens on this thread.
 */
class CandyTriangleApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Reaches the [AppContainer] from any [Context] - Activities, Composables via `LocalContext`, and
 * `AndroidViewModel`. Uses `applicationContext` so holding the result can never leak an Activity.
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as CandyTriangleApp).container
