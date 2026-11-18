package io.github.kamakazedan.plexampweb.setup

import androidx.annotation.StringRes
import io.github.kamakazedan.plexampweb.R
import io.github.kamakazedan.plexampweb.gui.GuiRefs
import io.github.kamakazedan.plexampweb.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Whether an address is a plexamp-web player, following it where it's moved (to https://,
 * say), but never from https:// to plain http://, nor to anything but an http:// or
 * https:// address.
 */
object PlayerCheck {
    private const val MAX_REDIRECTS = 5

    sealed interface Result {
        /** A player, at `base` (where it ended up). */
        data class Found(val base: String) : Result

        /** It moves from https:// to `to`, a plain http:// address, which isn't followed. */
        data class Unencrypted(val to: String) : Result

        /** Not one, or it couldn't be reached: `message` says why, with `code` where it has a %d. */
        data class Problem(@param:StringRes val message: Int, val code: Int = 0) : Result
    }

    suspend fun check(address: String): Result = withContext(Dispatchers.IO) {
        var url = address
        for (hop in 0..MAX_REDIRECTS) {
            val res = try {
                Http.request(url, headers = mapOf("Accept" to "text/html,*/*"))
            } catch (e: IOException) {
                return@withContext problemFor(e)
            }
            if (res.status in 300..399) {
                val location = res.header("location") ?: return@withContext Result.Problem(R.string.problem_error, res.status)
                url = when (val next = PlayerAddress.redirect(url, location)) {
                    is PlayerAddress.Redirect.To -> next.url
                    is PlayerAddress.Redirect.Unencrypted -> return@withContext Result.Unencrypted(next.url)
                    PlayerAddress.Redirect.Nowhere -> return@withContext Result.Problem(R.string.problem_error, res.status)
                }
                continue
            }
            return@withContext when (res.status) {
                200 -> if (GuiRefs.looksLikePlexampWeb(res.text())) {
                    Result.Found(PlayerAddress.normalize(url.substringBefore('#').substringBefore('?')) ?: address)
                } else {
                    Result.Problem(R.string.problem_not_player)
                }
                401, 407 -> Result.Problem(R.string.problem_password)
                403 -> Result.Problem(R.string.problem_refused, 403)
                404, 410 -> Result.Problem(R.string.problem_not_found)
                else -> Result.Problem(R.string.problem_error, res.status)
            }
        }
        Result.Problem(R.string.problem_error, 310)
    }

    private fun problemFor(e: IOException): Result.Problem = when (e) {
        is UnknownHostException -> Result.Problem(R.string.problem_unknown_host)
        is SocketTimeoutException -> Result.Problem(R.string.problem_timeout)
        is SSLException -> Result.Problem(R.string.problem_certificate)
        else -> Result.Problem(R.string.problem_unreachable)
    }
}
