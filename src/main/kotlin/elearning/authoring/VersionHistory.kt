package elearning.authoring

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordChangeListener
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

// un cambio de estado de una versión: from nulo es su creación, by nulo la plataforma (ADR-0032)
data class VersionTransition(
    val from: String?,
    val to: String,
    val by: String?,
    val byName: String?,
    val at: Instant,
)

// lms.transicion_version (ADR-0032): solo inserción, siempre acotada a la organización y ligada a su versión
@Repository
class VersionHistory(
    private val db: DatabaseClient,
) {
    suspend fun insert(
        organization: UUID,
        version: UUID,
        from: String?,
        to: String,
        by: UUID?,
    ) {
        val spec =
            db
                .sql(
                    """
                    INSERT INTO lms.transicion_version (organizacion_id, version_curso_id, desde, hacia, autor_id)
                    VALUES (:org, :version, :from, :to, :by)
                    """.trimIndent(),
                ).bind("org", organization)
                .bind("version", version)
                .bind("to", to)
        val withFrom = from?.let { spec.bind("from", it) } ?: spec.bindNull("from", String::class.java)
        val withBy = by?.let { withFrom.bind("by", it) } ?: withFrom.bindNull("by", UUID::class.java)
        withBy.fetch().rowsUpdated().awaitSingle()
    }

    // en el orden en que se escribieron; el nombre lo pone quien la muestra (StudioReader)
    suspend fun ofVersion(
        organization: UUID,
        version: UUID,
    ): List<VersionTransition> =
        db
            .sql(
                """
                SELECT desde, hacia, autor_id, ocurrida_en FROM lms.transicion_version
                WHERE organizacion_id = :org AND version_curso_id = :version ORDER BY id
                """.trimIndent(),
            ).bind("org", organization)
            .bind("version", version)
            .map { row, _ ->
                VersionTransition(
                    from = row.get("desde", String::class.java),
                    to = row.get("hacia", String::class.java)!!,
                    by = row.get("autor_id", UUID::class.java)?.toString(),
                    byName = null,
                    at = row.get("ocurrida_en", OffsetDateTime::class.java)!!.toInstant(),
                )
            }.all()
            .collectList()
            .awaitSingle()
}

// cada cambio de estado de una version_curso, sea por el estudio, la bandeja, la API de registros o la plataforma, deja su
// fila; la creación, la primera. Wasichai avisa a los listeners justo después de escribir y en la transacción de quien
// escribe (su ADR-038): la fila se confirma o se deshace con el cambio. la API de registros no abre ninguna, así que allí
// cada sentencia se confirma sola, como la fila del audit_log de Wasichai (ADR-0032). escribir el resto de campos sin
// tocar el estado no deja nada
@Component
class VersionTransitionListener(
    private val history: VersionHistory,
) : RecordChangeListener {
    override suspend fun recordChanged(change: RecordChange) {
        if (change.objectName != VERSION || change.kind == RecordChangeKind.DELETED) return
        val to = state(change.after) ?: return
        val from = if (change.kind == RecordChangeKind.CREATED) null else state(change.before)
        if (from == to) return
        history.insert(change.organizationId, change.recordId, from, to, change.userId)
    }

    private fun state(values: Map<String, Any?>?): String? = values?.get(STATE)?.toString()?.takeIf { it.isNotBlank() }

    private companion object {
        const val VERSION = "version_curso"
        const val STATE = "estado"
    }
}
