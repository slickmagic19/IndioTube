package com.indiotube.app

import android.app.Application
import android.content.Context
import com.indiotube.app.data.AccountsStore
import com.indiotube.app.data.CaptionPrefs
import com.indiotube.app.data.DownloadPrefs
import com.indiotube.app.data.Downloads
import com.indiotube.app.data.InnerTube
import com.indiotube.app.data.LibraryStore
import com.indiotube.app.data.Net
import com.indiotube.app.data.PlaybackPrefs
import com.indiotube.app.data.StreamResolver
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

class IndioTubeApplication : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }
}

// Single composition root — no DI framework, everything constructed once and
// shared. Lives for the whole process; nothing here holds an Activity.
class Container(context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    val downloadHttp: OkHttpClient = http.newBuilder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 8
        })
        .build()

    val net = Net(context)

    val library = LibraryStore(context.filesDir, appScope)

    val accounts = AccountsStore(File(context.filesDir, "accounts.json"), appScope) { accountId ->
        library.switchAccount(accountId)
    }.also { store ->
        library.switchAccount(store.activeId.value.ifEmpty { "default" })
    }

    val innerTube = InnerTube(http)
    val resolver = StreamResolver(http).also { StreamResolver.init(http) }
    val captions = CaptionPrefs(context)
    val playbackPrefs = PlaybackPrefs(context)
    val downloadPrefs = DownloadPrefs(context)
    val downloads = Downloads(
        context,
        resolver,
        downloadHttp,
        online = { net.online.value },
        unmetered = { net.unmetered.value },
        wifiOnly = { downloadPrefs.settings.value.wifiOnly },
    )

    // Home "For you" feed bookkeeping: when it was last built (so a return to
    // Home can auto-refresh stale data) and a rotation counter that lets each
    // refresh reshuffle the local ranking a little, like YouTube's feed.
    @Volatile var lastHomeRefreshAt: Long = 0L
    @Volatile var homeRotation: Int = 0
}
