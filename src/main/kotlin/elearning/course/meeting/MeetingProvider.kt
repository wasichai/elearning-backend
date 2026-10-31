package elearning.course.meeting

import org.springframework.stereotype.Component
import java.net.URI

// el acceso a la reunión de una sesión (RF-SES-04): un puerto para que el núcleo no dependa de ningún proveedor de
// videoconferencia. el único adaptador es el enlace que escribe el docente
fun interface MeetingProvider {
    // el enlace que verán los matriculados; IllegalArgumentException si no se admite
    fun joinLink(requested: String?): String?
}

@Component
class TeacherLinkMeetingProvider : MeetingProvider {
    override fun joinLink(requested: String?): String? {
        val link = requested?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(SafeLink.isAllowed(link)) { "must be a public https link" }
        return link
    }
}

// un enlace externo que la interfaz puede ofrecer: https y sin apuntar a la propia red. no se resuelve DNS ni se visita:
// el servidor nunca lo abre, solo lo muestra
object SafeLink {
    private val PRIVATE =
        listOf(
            Regex("^127\\."),
            Regex("^10\\."),
            Regex("^192\\.168\\."),
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\."),
            Regex("^169\\.254\\."),
            Regex("^0\\."),
        )

    fun isAllowed(link: String): Boolean {
        val uri = runCatching { URI(link) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: return false
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.userInfo != null) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return false
        if (host.contains(':')) return false
        return PRIVATE.none { it.containsMatchIn(host) }
    }
}
