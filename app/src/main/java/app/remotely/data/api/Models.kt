package app.remotely.data.api

import kotlinx.serialization.Serializable

@Serializable
data class Channel(
    val code: String,
    val name: String,
    /** Number shown to the user (portal "usermixno"); also the id used by the subscription list. */
    val number: Int,
    val mixno: String,
    val columnCode: String,
    val definition: String,
    val logoUrl: String?,
    val hasArchive: Boolean,
    /** Snapshot of the programme on air when the list was fetched. */
    val nowTitle: String = "",
    val nowStartMs: Long = 0,
    val nowEndMs: Long = 0,
)

/** Portal channel category ("column" under 08), e.g. «Кино», «Спорт». */
/** A daily break in a channel's broadcast set by Zala, in UTC seconds of the day; [endSec] is its last second. */
@Serializable
data class BlackoutWindow(val beginSec: Int, val endSec: Int)

data class ChannelColumn(val code: String, val name: String, val sortNum: Int)

@Serializable
data class Programme(
    val code: String,
    val channelCode: String,
    val title: String,
    val description: String,
    val genre: String,
    val startMs: Long,
    val endMs: Long,
    /** Portal marks the programme as recorded ("istimeshift"). */
    val recorded: Boolean,
)

data class Session(
    val epgBase: String,
    val frameBase: String,
    val userToken: String,
    val expiresAtMs: Long,
)

/** Step 1 of login. */
data class LoginEntry(
    val epgBase: String,
    val encryToken: String?,
    val iemg: String,
    val authStr: String,
    val emgInfo: String,
)

data class UserToken(val token: String, val expiresAtMs: Long?)

open class ZalaException(message: String) : Exception(message)

/** Wrong login/password or unknown user: never retried automatically. */
class BadCredentialsException(val code: String) : ZalaException("bad credentials ($code)")

/** Server forgot our session (redirect to rebuildsession/login, HTML instead of JSON). */
class SessionLostException(detail: String) : ZalaException("session lost: $detail")

class ZalaServerException(val code: String, message: String) : ZalaException("server error $code: $message")

class ArchiveUnavailableException(val code: String) : ZalaException("archive unavailable ($code)")
