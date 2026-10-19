package com.indiotube.app.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Local-only "accounts". IndioTube never signs in to Google — each account is
// an on-device profile that carries its own subscriptions, history and
// playlists, so switching accounts feels like YouTube without a login.
class AccountsStore(
    private val file: File,
    private val scope: CoroutineScope,
    private val onSwitch: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _activeId = MutableStateFlow("")
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    private val _active = MutableStateFlow<Account?>(null)
    val active: StateFlow<Account?> = _active.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    init {
        load()
        if (_accounts.value.isEmpty()) {
            val first = Account(id = UUID.randomUUID().toString(), name = "You", createdAt = System.currentTimeMillis())
            _accounts.value = listOf(first)
            _activeId.value = first.id
            save()
        }
        refreshActive(notify = false)
    }

    fun load() {
        try {
            if (file.exists()) {
                val data = SyncJson.decodeFromString(AccountsFile.serializer(), file.readText())
                _accounts.value = data.accounts
                _activeId.value = data.activeId.ifEmpty { data.accounts.firstOrNull()?.id ?: "" }
            }
        } catch (_: Exception) {
            // corrupted file — start fresh rather than crash
        }
    }

    private fun save() {
        val data = AccountsFile(_accounts.value, _activeId.value)
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(SyncJson.encodeToString(AccountsFile.serializer(), data))
                    if (!tmp.renameTo(file)) {
                        file.writeText(tmp.readText())
                        tmp.delete()
                    }
                } catch (_: Exception) { /* next mutation retries */ }
            }
        }
    }

    private fun refreshActive(notify: Boolean) {
        _active.value = _accounts.value.firstOrNull { it.id == _activeId.value }
            ?: _accounts.value.firstOrNull()
        _subscriptions.value = _active.value?.subscriptions ?: emptyList()
        if (notify) _active.value?.let { onSwitch(it.id) }
    }

    fun account(id: String): Account? = _accounts.value.firstOrNull { it.id == id }

    fun switchTo(id: String) {
        if (id == _activeId.value) return
        if (_accounts.value.none { it.id == id }) return
        _activeId.value = id
        save()
        refreshActive(notify = true)
    }

    fun addAccount(name: String): Account {
        val clean = name.trim().ifEmpty { "New account" }
        val acc = Account(id = UUID.randomUUID().toString(), name = clean, createdAt = System.currentTimeMillis())
        _accounts.value = _accounts.value + acc
        save()
        return acc
    }

    fun renameAccount(id: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        _accounts.value = _accounts.value.map { if (it.id == id) it.copy(name = clean) else it }
        save()
        if (id == _activeId.value) refreshActive(notify = false)
    }

    fun removeAccount(id: String) {
        val next = _accounts.value.filterNot { it.id == id }
        if (next.isEmpty()) return
        _accounts.value = next
        if (_activeId.value == id) {
            _activeId.value = next.first().id
            save()
            refreshActive(notify = true)
        } else {
            save()
        }
    }

    // ---- subscriptions of the active account --------------------------------

    fun isSubscribed(channelId: String?): Boolean {
        if (channelId.isNullOrBlank()) return false
        return _active.value?.subscriptions?.any { it.channelId == channelId } == true
    }

    fun subscribe(sub: Subscription) {
        val acc = _active.value ?: return
        if (acc.subscriptions.any { it.channelId == sub.channelId }) return
        updateActive(acc) { it.copy(subscriptions = (listOf(sub.copy(at = System.currentTimeMillis())) + it.subscriptions)) }
    }

    fun unsubscribe(channelId: String) {
        val acc = _active.value ?: return
        updateActive(acc) { it.copy(subscriptions = it.subscriptions.filterNot { s -> s.channelId == channelId }) }
    }

    fun toggleSubscription(channel: ChannelItem) {
        if (isSubscribed(channel.channelId)) {
            unsubscribe(channel.channelId)
        } else {
            subscribe(Subscription(channel.channelId, channel.name, channel.avatar))
        }
    }

    private fun updateActive(acc: Account, transform: (Account) -> Account) {
        _accounts.value = _accounts.value.map { if (it.id == acc.id) transform(it) else it }
        _active.value = _accounts.value.firstOrNull { it.id == acc.id }
        _subscriptions.value = _active.value?.subscriptions ?: emptyList()
        save()
    }
}
