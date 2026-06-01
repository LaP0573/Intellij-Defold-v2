package com.aridclown.intellij.defold.settings

import com.aridclown.intellij.defold.BundleCredentials

fun DefoldSettings.toBundleCredentials(): BundleCredentials = BundleCredentials(
    iosProvisioningDebug = iosProvisioningDebug(),
    iosProvisioningRelease = iosProvisioningRelease(),
    iosIdentityDebug = iosIdentityDebug(),
    iosIdentityRelease = iosIdentityRelease(),
    androidKeystore = androidKeystore(),
    androidKeystorePass = androidKeystorePass(),
    androidKeystoreAlias = androidKeystoreAlias(),
    buildServer = buildServer(),
    privateDepEmail = privateDepEmail(),
    privateDepAuth = privateDepAuth()
)
