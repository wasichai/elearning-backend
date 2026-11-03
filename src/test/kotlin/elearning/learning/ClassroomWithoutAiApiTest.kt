package elearning.learning

import com.embabel.common.ai.autoconfig.ProviderInitialization
import elearning.ai.AiProviderGate
import elearning.ai.AiStatus
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import wasichai.core.data.RecordRequest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

// RF-MED-04 (#294): lo publicado no depende del proveedor. La aplicación entera sin ninguna clave, como NoAiProviderApiTest:
// AiProviderGate saca los proveedores y la plataforma de Embabel. Una versión publicada cuyo temario salió de una ejecución
// de agente (lms.ejecucion_agente, aceptada por una persona) se lee entera desde el aula y se completa: el aula lee
// registros publicados, nunca la IA. La ejecución se siembra por SQL, como quedaría tras el paso del borrador de lecciones
@TestPropertySource(
    properties = [
        "ANTHROPIC_API_KEY=",
        "OPENAI_API_KEY=",
        "embabel.agent.platform.models.anthropic.api-key=",
        "embabel.agent.platform.models.openai.api-key=",
    ],
)
class ClassroomWithoutAiApiTest : ClassroomApiTestSupport() {
    @Autowired
    private lateinit var context: ConfigurableApplicationContext

    @Autowired
    private lateinit var status: AiStatus

    @Autowired
    private lateinit var db: DatabaseClient

    private val versions = mutableListOf<String>()

    @AfterEach
    fun retireGenerated() =
        runBlocking {
            records.asPlatform(ORG) {
                versions.forEach { records.patch("version_curso", UUID.fromString(it), RecordRequest(mapOf("estado" to "RETIRADA")), null, null) }
            }
            versions.clear()
        }

    private class Generated(
        val offering: String,
        val version: String,
        val unit: String,
        val lessons: List<String>,
        val resource: String,
        val run: UUID,
    )

    // lo que «escribió» el paso LESSON_DRAFT: una unidad y dos lecturas con su resumen
    private val output =
        mapOf(
            "unit" to mapOf("title" to "Unidad generada", "description" to "Descripción propuesta por el agente"),
            "lessons" to
                listOf(
                    mapOf("title" to "Qué es el padrón", "summary" to "Resumen generado de la primera lectura"),
                    mapOf("title" to "Consultar el padrón", "summary" to "Resumen generado de la segunda lectura"),
                ),
        )

    // el temario se escribe como lo dejaría aceptar la propuesta: con el texto de la salida de la ejecución, mientras la versión
    // es borrador; luego se publica y se abre una edición
    private fun generatedCourse(): Generated {
        val resource = publishedHtml()
        val requester = UUID.fromString(userId(admin))
        return runBlocking {
            records.asPlatform(ORG) {
                val code = uniqueName("GEN-").uppercase()
                val course =
                    records.create(
                        "curso",
                        RecordRequest(
                            mapOf("codigo" to code, "slug" to code.lowercase(), "titulo" to "Curso generado", "idioma" to "es", "nivel" to "INICIAL"),
                        ),
                    )
                val version =
                    records.create(
                        "version_curso",
                        RecordRequest(
                            mapOf(
                                "curso" to course.id,
                                "numero" to 1,
                                "estado" to "BORRADOR",
                                "modalidad" to "ASINCRONA",
                                "semanas" to 1,
                                "sistema_credito" to "NINGUNO",
                                "contenido_ilustrativo" to true,
                            ),
                        ),
                    )
                val unitOutput = output.getValue("unit") as Map<*, *>
                val unit =
                    records.create(
                        "unidad",
                        RecordRequest(
                            mapOf(
                                "version_curso" to version.id,
                                "orden" to 1,
                                "titulo" to unitOutput["title"]!!,
                                "descripcion" to unitOutput["description"]!!,
                            ),
                        ),
                    )
                val lessons =
                    (output.getValue("lessons") as List<*>).mapIndexed { index, item ->
                        val lesson = item as Map<*, *>
                        records
                            .create(
                                "leccion",
                                RecordRequest(
                                    mapOf(
                                        "unidad" to unit.id,
                                        "orden" to index + 1,
                                        "titulo" to lesson["title"]!!,
                                        "tipo" to "LECTURA",
                                        "resumen" to lesson["summary"]!!,
                                    ),
                                ),
                            ).id
                    }
                records.create("recurso_leccion", RecordRequest(mapOf("leccion" to lessons.first(), "version_recurso" to resource, "orden" to 1)))
                val run = agentRun(UUID.fromString(course.id), UUID.fromString(unit.id), UUID.fromString(version.id), requester)
                records.patch("version_curso", UUID.fromString(version.id), RecordRequest(mapOf("estado" to "PUBLICADA")), null, null)
                versions += version.id
                val today = LocalDate.now(ZoneId.of("America/Lima"))
                records.create(
                    "edicion_curso",
                    RecordRequest(
                        mapOf(
                            "codigo" to code,
                            "version_curso" to version.id,
                            "estado" to "INSCRIPCION_ABIERTA",
                            "fecha_inicio" to today.toString(),
                            "fecha_fin" to today.plusDays(30).toString(),
                            "zona_horaria" to "America/Lima",
                            "inscripcion_desde" to today.minusDays(1).toString(),
                            "inscripcion_hasta" to today.plusDays(5).toString(),
                        ),
                    ),
                )
                Generated(code, version.id, unit.id, lessons, resource, run)
            }
        }
    }

    // una ejecución terminada del doble determinista (sin proveedor ni modelo) con la salida y la aceptación de una persona
    private suspend fun agentRun(
        course: UUID,
        unit: UUID,
        version: UUID,
        requester: UUID,
    ): UUID {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val accepted = json.writeValueAsString(listOf(mapOf("userId" to requester.toString(), "kind" to "ACCEPTED", "at" to now.toString())))
        return db
            .sql(
                """
                INSERT INTO lms.ejecucion_agente (organizacion_id, trabajo_id, paso, solicitante_id, curso_id, unidad_id, version_curso_id, objetivo,
                    entradas, generador, version_instrucciones, version_plantilla, clave, intento, iniciada_en, estado, salida, terminada_en,
                    intervenciones)
                VALUES (:org, :job, 'LESSON_DRAFT', :requester, :course, :unit, :version, 'Borrador de las lecciones de la unidad', CAST('{}' AS jsonb),
                    'DETERMINISTIC', 'lesson-draft@1', 'lesson@1', :key, 1, :startedAt, 'SUCCEEDED', CAST(:output AS jsonb), :finishedAt,
                    CAST(:interventions AS jsonb))
                RETURNING id
                """.trimIndent(),
            ).bind("org", ORG)
            .bind("job", UUID.randomUUID())
            .bind("requester", requester)
            .bind("course", course)
            .bind("unit", unit)
            .bind("version", version)
            .bind("key", "LESSON_DRAFT:${UUID.randomUUID()}")
            .bind("startedAt", now.minusMinutes(1))
            .bind("output", json.writeValueAsString(output))
            .bind("finishedAt", now)
            .bind("interventions", accepted)
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .one()
            .awaitSingle()
    }

    private fun isApplication(name: String) = packageOf(name)?.let { it == "elearning" || it.startsWith("elearning.") } == true

    // el paquete de la clase de un bean, o null si no se sabe
    private fun packageOf(name: String): String? =
        runCatching { context.beanFactory.getType(name) }
            .getOrNull()
            ?.packageName

    // RF-MED-04 en el contexto de verdad: ningún bean del aula alcanza un bean de elearning.ai por sus dependencias, ni
    // siquiera por una interfaz de otra capacidad. ArchitectureTest lo vigila en el código; esto, en la inyección. Solo se
    // siguen los beans de la aplicación: los de Wasichai juntan las extensiones de todas las capacidades por sus SPI (el
    // RecordService lleva la guarda del brief, BriefGuard), y esa guarda solo actúa al escribir un brief
    @Test
    fun `no bean that serves the student reaches a bean of elearning ai through its dependencies`() {
        val factory = context.beanFactory
        val classroom = setOf("learning", "assessment", "enrollment", "catalog", "reputation").map { "elearning.$it" }
        val roots = factory.beanDefinitionNames.filter { name -> packageOf(name)?.let { p -> classroom.any { p == it || p.startsWith("$it.") } } == true }
        assertTrue(roots.isNotEmpty())
        val reached =
            roots.associateWith { root ->
                val seen = mutableSetOf<String>()
                val pending = ArrayDeque(factory.getDependenciesForBean(root).filter(::isApplication))
                while (pending.isNotEmpty()) {
                    val next = pending.removeFirst()
                    if (seen.add(next) && isApplication(next)) pending.addAll(factory.getDependenciesForBean(next).asList())
                }
                seen.filter { packageOf(it)?.let { p -> p == "elearning.ai" || p.startsWith("elearning.ai.") } == true }
            }
        assertEquals(emptyMap<String, List<String>>(), reached.filterValues { it.isNotEmpty() })
    }

    @Test
    fun `with every ai provider excluded, a published version generated by an agent is read whole from the classroom`() {
        // la IA de verdad está fuera: ni proveedor, ni modelos registrados, ni la plataforma de Embabel
        assertFalse(status.available)
        assertNull(status.provider)
        assertTrue(context.getBeansOfType(ProviderInitialization::class.java).isEmpty())
        val excluded =
            context.environment
                .getProperty("spring.autoconfigure.exclude")
                .orEmpty()
                .split(",")
        assertTrue(excluded.containsAll(AiProviderGate.PLATFORM), excluded.toString())

        val generated = generatedCourse()
        val stored =
            runBlocking {
                db
                    .sql("SELECT paso, estado, version_curso_id FROM lms.ejecucion_agente WHERE id = :id")
                    .bind("id", generated.run)
                    .map {
                        row,
                        _,
                        ->
                        listOf(row.get("paso", String::class.java), row.get("estado", String::class.java), row.get("version_curso_id", UUID::class.java))
                    }.one()
                    .awaitSingle()
            }
        assertEquals(listOf("LESSON_DRAFT", "SUCCEEDED", UUID.fromString(generated.version)), stored)

        val student = newStudent()
        val (enrollStatus, enrollment) = call(student, "/api/lms/enrollments", mapOf("offering" to generated.offering))
        assertTrue(enrollStatus.is2xxSuccessful, enrollment.toString())
        val id = enrollment!!["id"].asString()

        val classroom = ok(call(student, "/api/lms/classroom/enrollments/$id"))
        val unit = classroom["units"].single()
        assertEquals(generated.unit, unit["id"].asString())
        assertEquals("Unidad generada", unit["title"].asString())
        assertEquals("Descripción propuesta por el agente", unit["description"].asString())
        val lessons = unit["lessons"].values().toList()
        assertEquals(generated.lessons, lessons.map { it["id"].asString() })
        assertEquals(listOf("Qué es el padrón", "Consultar el padrón"), lessons.map { it["title"].asString() })
        assertEquals(
            listOf("Resumen generado de la primera lectura", "Resumen generado de la segunda lectura"),
            lessons.map { it["summary"].asString() },
        )
        val resource = lessons.first()["resources"].single()
        assertEquals(generated.resource, resource["id"].asString())
        val html =
            client
                .get()
                .uri(resource["file"]["url"].asString())
                .header("Authorization", student)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        assertTrue("Lectura" in html!!)

        // y se recorre hasta el final: el progreso tampoco pasa por la IA
        generated.lessons.forEach { ok(event(student, id, it, "MARKED_COMPLETE")) }
        val done = ok(call(student, "/api/lms/classroom/enrollments/$id"))
        assertEquals(2, done["progress"]["completedLessons"].asInt())
        assertEquals(100, done["units"].single()["progress"]["percent"].asInt())
    }
}
