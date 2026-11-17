package com.cherret.zaprett.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.util.writeBytes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cherret.zaprett.data.ItemType
import com.cherret.zaprett.data.RepoItemFull
import com.cherret.zaprett.data.ServiceType
import com.cherret.zaprett.data.StorageData
import com.cherret.zaprett.utils.NetworkUtils
import com.cherret.zaprett.utils.enableStrategy
import com.cherret.zaprett.utils.getActiveByeDPIStrategy
import com.cherret.zaprett.utils.getAllByeDPIStrategies
import com.cherret.zaprett.utils.getValidManifests
import com.cherret.zaprett.utils.setServiceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

class TvViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val strategyDir = application.filesDir.resolve("tv-strategies")
    private val manifestsDir = strategyDir.resolve("manifests")

    var installed by mutableStateOf<List<StorageData>>(emptyList())
        private set
    var available by mutableStateOf<List<RepoItemFull>>(emptyList())
        private set
    var activeStrategy by mutableStateOf<StorageData?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var catalogFailed by mutableStateOf(false)
        private set
    var downloadingId by mutableStateOf<String?>(null)
        private set
    var downloadFailed by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            activeStrategy = withContext(Dispatchers.IO) {
                getActiveByeDPIStrategy(prefs).getOrNull()?.takeIf { File(it.file).canRead() }
            }
            refreshInstalled()
        }
        refreshCatalog()
    }

    private suspend fun refreshInstalled() {
        installed = withContext(Dispatchers.IO) {
            (getValidManifests(manifestsDir).asList() + getAllByeDPIStrategies())
                .distinctBy { it.id }
                .sortedBy { it.name }
        }
    }

    fun refreshCatalog() {
        if (loading) return
        viewModelScope.launch {
            loading = true
            catalogFailed = false
            try {
                val url = prefs.getString("repo_url", null) ?: NetworkUtils.DEFAULT_REPO_URL
                val (_, catalog) = NetworkUtils.getRepo(url) { it.type == ItemType.byedpi }.first()
                available = catalog.filter { it.manifest.dependencies.isEmpty() }
                    .distinctBy { it.manifest.id }
                    .sortedBy { it.manifest.name }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w("ZaprettTV", "Couldn't load strategies", exception)
                catalogFailed = true
            } finally {
                loading = false
            }
        }
    }

    fun prepareServiceMode() {
        setServiceType(prefs, ServiceType.byedpi)
    }

    fun selectStrategy(strategy: StorageData) {
        prepareServiceMode()
        enableStrategy(strategy.manifestPath, prefs)
        activeStrategy = strategy
        downloadFailed = false
    }

    fun downloadStrategy(item: RepoItemFull) {
        if (downloadingId != null) return
        viewModelScope.launch {
            downloadingId = item.manifest.id
            downloadFailed = false
            try {
                val strategy = withContext(Dispatchers.IO) { saveStrategy(item) }
                selectStrategy(strategy)
                refreshInstalled()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w("ZaprettTV", "Couldn't download strategy", exception)
                downloadFailed = true
            } finally {
                downloadingId = null
            }
        }
    }

    private suspend fun saveStrategy(item: RepoItemFull): StorageData {
        val manifest = item.manifest
        val content = NetworkUtils.downloadArtifact(manifest.artifact.url)
        val checksum = sha256(content)
        check(checksum.equals(manifest.artifact.sha256, ignoreCase = true)) { "Strategy checksum mismatch" }

        manifestsDir.mkdirs()
        val artifact = strategyDir.resolve("$checksum.txt")
        val manifestFile = manifestsDir.resolve("${sha256(manifest.id.encodeToByteArray())}.json")
        val strategy = StorageData(
            manifest.schema, manifest.id, manifest.name, manifest.version, manifest.author,
            manifest.description, file = artifact.path
        ).apply { manifestPath = manifestFile.path }
        AtomicFile(artifact).writeBytes(content)
        AtomicFile(manifestFile).writeBytes(Json.encodeToString(strategy).encodeToByteArray())
        return strategy
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
