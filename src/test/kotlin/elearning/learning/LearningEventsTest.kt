package elearning.learning

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// eventos de aprendizaje (ADR-0012): solo se añaden, una vez por identificador del cliente, y la base no deja
// cambiarlos ni borrarlos
class LearningEventsTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var events: LearningEvents

    @Autowired
    private lateinit var db: DatabaseClient

    private fun event(clientId: UUID = UUID.randomUUID()) =
        LearningEvent(
            organizationId = UUID.randomUUID(),
            enrollmentId = UUID.randomUUID(),
            lessonId = UUID.randomUUID(),
            studentId = UUID.randomUUID(),
            type = "STARTED",
            clientEventId = clientId,
        )

    @Test
    fun `an event is recorded once per client id`() {
        val e = event()

        runBlocking {
            assertTrue(events.record(e))
            assertFalse(events.record(e), "el mismo evento repetido no se vuelve a guardar")
            assertEquals(1, events.count(e.organizationId, e.enrollmentId, e.lessonId))
            assertEquals(0, events.count(UUID.randomUUID(), e.enrollmentId, e.lessonId), "otra organización no los ve")
        }
    }

    @Test
    fun `the same client id from another student is another event`() {
        val a = event()
        val b = a.copy(studentId = UUID.randomUUID())

        runBlocking {
            assertTrue(events.record(a))
            assertTrue(events.record(b))
        }
    }

    @Test
    fun `events are never updated nor deleted, not even with plain sql`() {
        val e = event()
        runBlocking { events.record(e) }

        for (sql in listOf(
            "UPDATE lms.evento_aprendizaje SET tipo = 'X' WHERE matricula_id = :m",
            "DELETE FROM lms.evento_aprendizaje WHERE matricula_id = :m",
        )) {
            assertThrows<Exception>(sql) {
                runBlocking {
                    db
                        .sql(sql)
                        .bind("m", e.enrollmentId)
                        .fetch()
                        .rowsUpdated()
                        .awaitSingle()
                }
            }
        }
        runBlocking { assertEquals(1, events.count(e.organizationId, e.enrollmentId, e.lessonId)) }
    }
}
