package elearning.credits

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.common.NotFoundException
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// lo que la separación de funciones necesita leer sin depender de los permisos de quien aprueba: una autoridad académica
// sin READ sobre docente_edicion no deja por eso de ser docente del curso. subconsulta sobre las tablas de Wasichai, como
// los alcances de lectura (ADR-0025): organización ligada en cada nivel, columnas de los metadatos, todo valor ligado
@Repository
class CreditDuties(
    private val db: DatabaseClient,
    private val metadata: MetadataService,
    private val schemas: WasichaiSchemas,
) {
    // ¿tiene esta persona una asignación docente en alguna edición de alguna versión del curso? sin el modelo para
    // saberlo, sí (falla cerrada: no aprueba)
    suspend fun teachesCourse(
        organization: UUID,
        user: UUID,
        course: UUID,
    ): Boolean {
        val assignment = definition(organization, ASSIGNMENT) ?: return true
        val offering = definition(organization, OFFERING) ?: return true
        val version = definition(organization, VERSION) ?: return true
        val teacher = column(assignment, TEACHER) ?: return true
        val assignedTo = column(assignment, OFFERING) ?: return true
        val ofVersion = column(offering, VERSION) ?: return true
        val ofCourse = column(version, COURSE) ?: return true
        return db
            .sql(
                """
                SELECT EXISTS (
                    SELECT 1 FROM ${table(assignment)} a
                    JOIN ${table(offering)} o ON o."id" = a.$assignedTo AND o.organization_id = :org
                    JOIN ${table(version)} v ON v."id" = o.$ofVersion AND v.organization_id = :org
                    WHERE a.organization_id = :org AND a.$teacher = :user AND v.$ofCourse = :course
                ) AS teaches
                """.trimIndent(),
            ).bind("org", organization)
            .bind("user", user)
            .bind("course", course)
            .map { row, _ -> row.get("teaches", Boolean::class.javaObjectType) == true }
            .one()
            .awaitSingle()
    }

    // una aprobación o revocación a la vez por versión, hasta el commit: dos autoridades a la vez no aprueban ambas
    suspend fun lock(version: UUID) {
        db
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .bind("key", "lms.aprobacion_creditos:$version")
            .fetch()
            .first()
            .awaitSingleOrNull()
    }

    private suspend fun definition(
        organization: UUID,
        name: String,
    ): ObjectDefinition? =
        try {
            metadata.loadDefinition(organization, name)
        } catch (_: NotFoundException) {
            null
        }

    private fun table(definition: ObjectDefinition): String = schemas.dataTable(definition.obj.physicalTable)

    private fun column(
        definition: ObjectDefinition,
        field: String,
    ): String? = definition.fields.firstOrNull { it.name == field }?.let { SqlIdentifier.quote(it.columnName) }

    companion object {
        const val ASSIGNMENT = "docente_edicion"
        const val OFFERING = "edicion_curso"
        const val VERSION = "version_curso"
        const val COURSE = "curso"
        const val TEACHER = "usuario_id"
    }
}
