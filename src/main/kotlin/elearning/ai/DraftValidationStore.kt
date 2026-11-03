package elearning.ai

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.util.UUID

// lo que la validación (RF-IA-05) lee de lms.ejecucion_agente, siempre en la organización. No escribe: eso es de
// AgentExecutionLog. Las consultas usan los índices de V23
@Repository
class DraftValidationStore(
    private val db: DatabaseClient,
) {
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
