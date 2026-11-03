package elearning.course

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.ObjectProvider
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.common.NotFoundException
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// las versiones de recurso que enlaza el temario de una versión de curso (#366, ADR-0041), leídas sin el alcance de quien
// escribe: la guarda editorial decide sobre lo que hay, no sobre lo que esa persona ve. una sentencia por consulta, con
// independencia del tamaño del temario (las pruebas de coste cuentan sentencias). subconsultas sobre las tablas de
// Wasichai como CreditDuties (ADR-0025): organización ligada en cada nivel, columnas de los metadatos, todo valor ligado.
// sin el modelo para saberlo, falla cerrada
@Repository
class CurriculumResources(
    private val db: DatabaseClient,
    // MetadataService recibe la lista de alcances y RecordService la de guardas: se pide perezosamente para no formar un ciclo
    private val metadata: ObjectProvider<MetadataService>,
    private val schemas: WasichaiSchemas,
) {
    // un enlace de una lección a una versión de recurso que no está PUBLICADA. state es null si la versión ya no existe
    data class Unpublished(
        val lesson: String,
        val resourceVersion: String,
        val state: String?,
    )

    // los enlaces del temario de la versión a versiones de recurso que no están PUBLICADAS, en el orden del temario
    suspend fun unpublished(
        organization: UUID,
        version: UUID,
    ): List<Unpublished> {
        val t = tables(organization) ?: return listOf(Unpublished("?", "?", null))
        return db
            .sql(
                """
                SELECT l.${t.lessonTitle} AS lesson, rl.${t.linkResource} AS resource_version, rv.${t.state} AS state
                FROM ${t.link} rl
                JOIN ${t.lesson} l ON l."id" = rl.${t.linkLesson} AND l.organization_id = :org
                JOIN ${t.unit} u ON u."id" = l.${t.lessonUnit} AND u.organization_id = :org
                LEFT JOIN ${t.resourceVersion} rv ON rv."id" = rl.${t.linkResource} AND rv.organization_id = :org
                WHERE rl.organization_id = :org AND u.${t.unitVersion} = :version AND rv.${t.state} IS DISTINCT FROM :published
                ORDER BY u.${t.unitOrder}, l.${t.lessonOrder}, rl."id"
                """.trimIndent(),
            ).bind("org", organization)
            .bind("version", version)
            .bind("published", EditorialWorkflowGuard.PUBLISHED)
            .map { row, _ ->
                Unpublished(
                    lesson = row.get("lesson", String::class.java).orEmpty(),
                    resourceVersion = row.get("resource_version", UUID::class.java)?.toString().orEmpty(),
                    state = row.get("state", String::class.java),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    // ¿la enlaza alguna lección de una versión de curso que ya salió de borrador? entonces lo que ve quien revisa, o el
    // estudiante, no cambia aunque la versión de recurso siga en borrador (datos de antes de #366 o escritos por la plataforma)
    suspend fun linkedFromFrozenVersion(
        organization: UUID,
        resourceVersion: UUID,
    ): Boolean {
        val t = tables(organization) ?: return true
        return db
            .sql(
                """
                SELECT EXISTS (
                    SELECT 1 FROM ${t.link} rl
                    JOIN ${t.lesson} l ON l."id" = rl.${t.linkLesson} AND l.organization_id = :org
                    JOIN ${t.unit} u ON u."id" = l.${t.lessonUnit} AND u.organization_id = :org
                    JOIN ${t.version} v ON v."id" = u.${t.unitVersion} AND v.organization_id = :org
                    WHERE rl.organization_id = :org AND rl.${t.linkResource} = :rv AND COALESCE(v.${t.versionState}, :draft) <> :draft
                ) AS frozen
                """.trimIndent(),
            ).bind("org", organization)
            .bind("rv", resourceVersion)
            .bind("draft", EditorialWorkflowGuard.DRAFT)
            .map { row, _ -> row.get("frozen", Boolean::class.javaObjectType) != false }
            .one()
            .awaitSingle()
    }

    private class Tables(
        val version: String,
        val versionState: String,
        val unit: String,
        val unitVersion: String,
        val unitOrder: String,
        val lesson: String,
        val lessonUnit: String,
        val lessonOrder: String,
        val lessonTitle: String,
        val link: String,
        val linkLesson: String,
        val linkResource: String,
        val resourceVersion: String,
        val state: String,
    )

    private suspend fun tables(organization: UUID): Tables? {
        val version = definition(organization, EditorialWorkflowGuard.VERSION) ?: return null
        val unit = definition(organization, EditorialWorkflowGuard.UNIT) ?: return null
        val lesson = definition(organization, EditorialWorkflowGuard.LESSON) ?: return null
        val link = definition(organization, EditorialWorkflowGuard.RESOURCE_LINK) ?: return null
        val resourceVersion = definition(organization, EditorialWorkflowGuard.RESOURCE_VERSION) ?: return null
        return Tables(
            version = table(version),
            versionState = column(version, EditorialWorkflowGuard.STATE) ?: return null,
            unit = table(unit),
            unitVersion = column(unit, EditorialWorkflowGuard.VERSION) ?: return null,
            unitOrder = column(unit, ORDER) ?: return null,
            lesson = table(lesson),
            lessonUnit = column(lesson, EditorialWorkflowGuard.UNIT) ?: return null,
            lessonOrder = column(lesson, ORDER) ?: return null,
            lessonTitle = column(lesson, TITLE) ?: return null,
            link = table(link),
            linkLesson = column(link, EditorialWorkflowGuard.LESSON) ?: return null,
            linkResource = column(link, EditorialWorkflowGuard.RESOURCE_VERSION) ?: return null,
            resourceVersion = table(resourceVersion),
            state = column(resourceVersion, EditorialWorkflowGuard.STATE) ?: return null,
        )
    }

    private suspend fun definition(
        organization: UUID,
        name: String,
    ): ObjectDefinition? =
        try {
            metadata.getObject().loadDefinition(organization, name)
        } catch (_: NotFoundException) {
            null
        }

    private fun table(definition: ObjectDefinition): String = schemas.dataTable(definition.obj.physicalTable)

    private fun column(
        definition: ObjectDefinition,
        field: String,
    ): String? = definition.fields.firstOrNull { it.name == field }?.let { SqlIdentifier.quote(it.columnName) }

    companion object {
        const val ORDER = "orden"
        const val TITLE = "titulo"
    }
}
