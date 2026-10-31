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
    // como el navegador (WHATWG): si la última etiqueta es un número, decimal o 0x…, el host entero es una IPv4
    private val NUMERIC = Regex("^(0x[0-9a-f]*|\\d+)$")

    fun isAllowed(link: String): Boolean {
        val uri = runCatching { URI(link) }.getOrNull() ?: return false
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.userInfo != null) return false
        // el punto final no cambia el destino: «localhost.» es localhost
        val host =
            uri.host
                ?.lowercase()
                ?.removeSurrounding("[", "]")
                ?.trimEnd('.')
                ?.takeIf { it.isNotEmpty() } ?: return false
        if (host.contains(':')) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return false
        val labels = host.split('.')
        return if (NUMERIC.matches(labels.last())) publicIpv4(labels) else true
    }

    // solo la forma canónica a.b.c.d: 2130706433, 0x7f.1 o 0177.0.0.1 son la misma IP privada disfrazada (revisión de la
    // Fase 3). fuera: privadas, bucle local, enlace local, CGNAT (100.64/10), «esta red» y multidifusión o reservadas
    private fun publicIpv4(labels: List<String>): Boolean {
        if (labels.size != 4) return false
        val parts = labels.map { label -> label.toIntOrNull()?.takeIf { it in 0..255 && label == it.toString() } ?: return false }
        val (a, b) = parts
        return !(
            a == 0 ||
                a == 10 ||
                a == 127 ||
                a >= 224 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168)
        )
    }
}
