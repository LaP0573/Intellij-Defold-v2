package com.aridclown.intellij.defold.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "DefoldSettings", storages = [Storage("defold.xml")])
class DefoldSettings : PersistentStateComponent<DefoldSettings.State> {
    data class State(
        var installPath: String? = null,
        var iosProvisioningDebug: String? = null,
        var iosProvisioningRelease: String? = null,
        var iosIdentityDebug: String? = null,
        var iosIdentityRelease: String? = null,
        var androidKeystore: String? = null,
        var androidKeystorePass: String? = null,
        var androidKeystoreAlias: String? = null,
        var buildServer: String? = null,
        var privateDepEmail: String? = null,
        var privateDepAuth: String? = null
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun installPath(): String? = state.installPath?.takeNonBlank()

    fun setInstallPath(path: String) {
        state.installPath = path.trim()
    }

    fun clearInstallPath() {
        state.installPath = null
    }

    fun iosProvisioningDebug(): String? = state.iosProvisioningDebug?.takeNonBlank()
    fun iosProvisioningRelease(): String? = state.iosProvisioningRelease?.takeNonBlank()
    fun iosIdentityDebug(): String? = state.iosIdentityDebug?.takeNonBlank()
    fun iosIdentityRelease(): String? = state.iosIdentityRelease?.takeNonBlank()
    fun androidKeystore(): String? = state.androidKeystore?.takeNonBlank()
    fun androidKeystorePass(): String? = state.androidKeystorePass?.takeNonBlank()
    fun androidKeystoreAlias(): String? = state.androidKeystoreAlias?.takeNonBlank()
    fun buildServer(): String? = state.buildServer?.takeNonBlank()
    fun privateDepEmail(): String? = state.privateDepEmail?.takeNonBlank()
    fun privateDepAuth(): String? = state.privateDepAuth?.takeNonBlank()

    fun setIosProvisioningDebug(value: String?) {
        state.iosProvisioningDebug = value?.trim()
    }

    fun setIosProvisioningRelease(value: String?) {
        state.iosProvisioningRelease = value?.trim()
    }

    fun setIosIdentityDebug(value: String?) {
        state.iosIdentityDebug = value?.trim()
    }

    fun setIosIdentityRelease(value: String?) {
        state.iosIdentityRelease = value?.trim()
    }

    fun setAndroidKeystore(value: String?) {
        state.androidKeystore = value?.trim()
    }

    fun setAndroidKeystorePass(value: String?) {
        state.androidKeystorePass = value?.trim()
    }

    fun setAndroidKeystoreAlias(value: String?) {
        state.androidKeystoreAlias = value?.trim()
    }

    fun setBuildServer(value: String?) {
        state.buildServer = value?.trim()
    }

    fun setPrivateDepEmail(value: String?) {
        state.privateDepEmail = value?.trim()
    }

    fun setPrivateDepAuth(value: String?) {
        state.privateDepAuth = value?.trim()
    }

    companion object {
        private val fallbackInstance by lazy { DefoldSettings() }

        fun getInstance(): DefoldSettings {
            val application = ApplicationManager.getApplication()
            return application?.getService(DefoldSettings::class.java) ?: fallbackInstance
        }
    }
}

private fun String.takeNonBlank(): String? = takeIf { it.isNotBlank() }
