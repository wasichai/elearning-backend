package elearning.ai

import elearning.authoring.DraftGenerators
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.util.UUID

// lo que la validación (RF-IA-05) y las intervenciones (RF-IA-03, #288) leen de lms.ejecucion_agente, siempre en la
// organización. No escribe: eso es de AgentExecutionLog. Las consultas usan los índices de V23. Da al estudio y a la bandeja
// el generador de cada versión (DraftGenerators, de authoring)
@Repository
class DraftValidationStore(
    private val db: DatabaseClient,
) : DraftGenerators {
    // una versión es GENERADA si alguna ejecución de un paso que crea borradores (GENERATING_STEPS) la nombra: en
    // version_curso_id, si existía al empezar, o en salida.versionId, si la creó la ejecución (docs/ai §13). Esa tabla solo la
    // escribe la plataforma y no se edita, así que el autor no puede quitarle la marca a su borrador
    suspend fun isGenerated(
        organization: UUID,
        version: UUID,
    ): Boolean =
        db
            .sql(
                "SELECT EXISTS (SELECT 1 FROM lms.ejecucion_agente WHERE organizacion_id = :org AND paso IN ($STEPS) " +
                    "AND (version_curso_id = :version OR salida ->> 'versionId' = :versionText)) AS generada",
            ).bind("org", organization)
            .bind("version", version)
            .bind("versionText", version.toString())
            .map { row, _ -> row.get("generada", Boolean::class.javaObjectType)!! }
            .one()
            .awaitSingle()

    // la ejecución que generó la versión, a la que van las intervenciones de las personas: si varias la nombran, la más
    // reciente. null si se escribió a mano
    suspend fun generatingRun(
        organization: UUID,
        version: UUID,
    ): UUID? =
        db
            .sql(
                "SELECT id FROM lms.ejecucion_agente WHERE organizacion_id = :org AND paso IN ($STEPS) " +
                    "AND (version_curso_id = :version OR salida ->> 'versionId' = :versionText) ORDER BY iniciada_en DESC, id DESC LIMIT 1",
            ).bind("org", organization)
            .bind("version", version)
            .bind("versionText", version.toString())
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .one()
            .awaitSingleOrNull()

    // el generador de la ejecución más reciente que generó cada versión, en una lectura; las escritas a mano no están
    override suspend fun of(
        organization: UUID,
        versions: Collection<UUID>,
    ): Map<UUID, String> {
        if (versions.isEmpty()) return emptyMap()
        return db
            .sql(
                """
                SELECT DISTINCT ON (version) version, generador FROM (
                    SELECT version_curso_id AS version, generador, iniciada_en, id FROM lms.ejecucion_agente
                    WHERE organizacion_id = :org AND paso IN ($STEPS) AND version_curso_id = ANY(CAST(:versions AS uuid[]))
                    UNION ALL
                    SELECT CAST(salida ->> 'versionId' AS uuid), generador, iniciada_en, id FROM lms.ejecucion_agente
                    WHERE organizacion_id = :org AND paso IN ($STEPS) AND salida ->> 'versionId' = ANY(:versions)
                ) AS generadas
                ORDER BY version, iniciada_en DESC, id DESC
                """.trimIndent(),
            ).bind("org", organization)
            .bind("versions", versions.distinct().map { it.toString() }.toTypedArray())
            .map { row, _ -> row.get("version", UUID::class.java)!! to row.get("generador", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
            .toMap()
    }

    // la última validación terminada de la versión, o null
    suspend fun latestValidation(
        organization: UUID,
        version: UUID,
    ): UUID? =
        db
            .sql(
                "SELECT id FROM lms.ejecucion_agente WHERE organizacion_id = :org AND version_curso_id = :version AND paso = :step " +
                    "AND estado = 'SUCCEEDED' ORDER BY terminada_en DESC, iniciada_en DESC, id DESC LIMIT 1",
            ).bind("org", organization)
            .bind("version", version)
            .bind("step", AgentStep.VALIDATION.name)
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .one()
            .awaitSingleOrNull()

    companion object {
        // los pasos que dejan una versión en borrador; uno nuevo que lo haga entra aquí (y en docs/ai §13)
        val GENERATING_STEPS = setOf(AgentStep.LESSON_DRAFT)

        // nombres del enum, no datos de nadie: van en el SQL tal cual
        private val STEPS = GENERATING_STEPS.joinToString { "'${it.name}'" }
    }
}
