package elearning.course.meeting

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// el enlace de una sesión: https público. la revisión de la Fase 3 encontró IPv4 privadas escritas de otra forma
class SafeLinkTest {
    @Test
    fun `a public https link is allowed`() {
        for (link in listOf("https://meet.example.org/abc", "https://meet.example.org./abc", "https://8.8.8.8/sala", "https://100.128.0.1/sala")) {
            assertTrue(SafeLink.isAllowed(link), link)
        }
    }

    @Test
    fun `a link to the own network is refused`() {
        val refused =
            listOf(
                "http://meet.example.org/abc",
                "https://user@meet.example.org/",
                "https://localhost/",
                "https://localhost./",
                "https://sala.local/",
                "https://[::1]/",
                "https://127.0.0.1/",
                "https://10.0.0.1/",
                "https://172.16.0.1/",
                "https://192.168.1.1/",
                "https://169.254.169.254/",
                "https://0.0.0.0/",
                "https://100.64.0.1/",
                "https://224.0.0.1/",
            )
        for (link in refused) assertFalse(SafeLink.isAllowed(link), link)
    }

    @Test
    fun `a private address written another way is refused too`() {
        // la misma 127.0.0.1: entera, en hexadecimal, en octal o abreviada
        for (link in listOf("https://2130706433/", "https://0x7f.1/", "https://0177.0.0.1/", "https://127.1/")) {
            assertFalse(SafeLink.isAllowed(link), link)
        }
    }
}
