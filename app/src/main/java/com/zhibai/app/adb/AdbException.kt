// Copied from Shizuku (https://github.com/rikkaapps/Shizuku), GPLv3.
package com.zhibai.app.adb

@Suppress("NOTHING_TO_INLINE")
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

open class AdbException : Exception {

    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException(com.zhibai.app.i18n.LangManager.t("adb.err.badCode", "配对码错误"))

class AdbKeyException(cause: Throwable) : AdbException(cause)
