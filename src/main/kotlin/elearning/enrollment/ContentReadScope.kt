package elearning.enrollment

import elearning.common.ScopeMemo
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import wasichai.core.common.Actions
import wasichai.core.common.NotFoundException
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordReadScope
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// lo que una persona sin VER_TODAS lee del contenido (ADR-0012, ADR-0013): las versiones PUBLICADAS o de sus matrículas
// no retiradas, y las unidades, lecciones y ediciones de esas versiones. un borrador se lee como inexistente. los recursos
// (E13.1) quedan solo para las versiones de sus matrículas. nunca por rol.
// con subconsultas SQL (M4 de #183, M1 de #104): antes listaba versiones, unidades y lecciones y las ligaba como arreglos de
// ids que crecían con el catálogo publicado; ahora la base de datos resuelve la cadena en la misma sentencia. cada nivel
// repite lo que leerlo como quien llama aplicaba: su organización, su VER_TODAS y, sin READ sobre él, nada (falla cerrada)
// own_records_only no se repite: ningún rol del LMS lo fija (model/roles.json)
@Component
class ContentReadScope(
    private val currentUser: CurrentUser,
    // MetadataService recibe la lista de alcances: se pide perezosamente para no formar un ciclo
    private val metadata: ObjectProvider<MetadataService>,
    private val schemas: WasichaiSchemas,
) : RecordReadScope {
    override suspend fun criterion(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
    ): RecordCriterion? = ScopeMemo.of("content", caller, definition.obj.name) { compute(caller, definition) }

    private suspend fun compute(
        caller: AuthenticatedUser,
        definition: ObjectDefinition,
    ): RecordCriterion? {
        val name = definition.obj.name
        if (name !in SCOPED || currentUser.hasPermission(caller, SEE_ALL, definition.obj.id)) return null
        val chain = Chain(caller)
        // ADR-0013: la versión se ve si está publicada o es de una matrícula activa; lo demás cuelga de las versiones que se ven
        if (name == VERSION) {
            val state = column(definition, STATE) ?: return nothing()
            val enrolled = chain.enrolledVersions() ?: return RecordCriterion { _, bind -> "$state = ${bind(PUBLISHED)}" }
            return RecordCriterion { _, bind -> "($state = ${bind(PUBLISHED)} OR $ID_COLUMN IN (${enrolled.render(bind)}))" }
        }
        val (column, ids) =
            when (name) {
                UNIT, OFFERING -> column(definition, VERSION) to chain.visibleVersions()
                LESSON -> column(definition, UNIT) to chain.visibleUnits()
                // los recursos, solo por las lecciones de las versiones en que está matriculado (ADR-0013)
                RESOURCE_LINK -> column(definition, LESSON) to chain.enrolledLessons()
                RESOURCE_VERSION -> ID_COLUMN to chain.linkedResourceVersions()
                else -> ID_COLUMN to chain.linkedResources()
            }
        if (column == null || ids == null) return nothing()
        return RecordCriterion { _, bind -> "$column IN (${ids.render(bind)})" }
    }

    private fun nothing() = RecordCriterion { _, _ -> NOTHING }

    // un trozo de SQL que liga sus valores con el bind del criterio
    private fun interface Sql {
        fun render(bind: (Any) -> String): String
    }

    // un objeto como nivel de una subconsulta: su tabla con un alias propio y sus columnas por nombre de campo
    private class Level(
        val table: String,
        val alias: String,
        val columns: Map<String, String>,
        val seeAll: Boolean,
    ) {
        val id = "$alias.$ID_COLUMN"

        fun column(field: String): String? = columns[field]?.let { "$alias.$it" }

        fun from(
            bind: (Any) -> String,
            organization: UUID,
        ) = "FROM $table $alias WHERE $alias.organization_id = ${bind(organization)}"
    }

    // las subconsultas de quien llama. cada una devuelve null cuando no hay nada que leer en ese nivel
    private inner class Chain(
        private val caller: AuthenticatedUser,
    ) {
        private val organization = caller.organizationId
        private val me = caller.userId

        private suspend fun level(
            objectName: String,
            alias: String,
        ): Level? {
            val definition =
                try {
                    metadata.getObject().loadDefinition(organization, objectName)
                } catch (_: NotFoundException) {
                    return null
                }
            if (!currentUser.hasPermission(caller, Actions.READ, definition.obj.id)) return null
            val columns = definition.fields.associate { it.name to SqlIdentifier.quote(it.columnName) }
            return Level(schemas.dataTable(definition.obj.physicalTable), alias, columns, currentUser.hasPermission(caller, SEE_ALL, definition.obj.id))
        }

        // las versiones de sus matrículas no retiradas
        suspend fun enrolledVersions(): Sql? {
            val m = level(ENROLLMENT, "lms_m") ?: return null
            val version = m.column(VERSION) ?: return null
            val student = m.column(STUDENT) ?: return null
            val state = m.column(STATE) ?: return null
            return Sql { bind -> "SELECT $version ${m.from(bind, organization)} AND $student = ${bind(me)} AND $state IS DISTINCT FROM ${bind(WITHDRAWN)}" }
        }

        // las versiones que lee: publicadas o de sus matrículas
        suspend fun visibleVersions(): Sql? {
            val v = level(VERSION, "lms_v") ?: return null
            if (v.seeAll) return Sql { bind -> "SELECT ${v.id} ${v.from(bind, organization)}" }
            val state = v.column(STATE) ?: return null
            val enrolled = enrolledVersions()
            return Sql { bind ->
                val mine = enrolled?.let { " OR ${v.id} IN (${it.render(bind)})" }.orEmpty()
                "SELECT ${v.id} ${v.from(bind, organization)} AND ($state = ${bind(PUBLISHED)}$mine)"
            }
        }

        // las unidades de las versiones que lee
        suspend fun visibleUnits(): Sql? {
            val u = level(UNIT, "lms_u") ?: return null
            if (u.seeAll) return Sql { bind -> "SELECT ${u.id} ${u.from(bind, organization)}" }
            val version = u.column(VERSION) ?: return null
            val versions = visibleVersions() ?: return null
            return Sql { bind -> "SELECT ${u.id} ${u.from(bind, organization)} AND $version IN (${versions.render(bind)})" }
        }

        // las lecciones de las versiones en que está matriculado
        suspend fun enrolledLessons(): Sql? {
            val l = level(LESSON, "lms_l") ?: return null
            val u = level(UNIT, "lms_eu") ?: return null
            val unit = l.column(UNIT) ?: return null
            val version = u.column(VERSION) ?: return null
            val versions = enrolledVersions() ?: return null
            return Sql { bind ->
                val units = "SELECT ${u.id} ${u.from(bind, organization)} AND $version IN (${versions.render(bind)})"
                "SELECT ${l.id} ${l.from(bind, organization)} AND $unit IN ($units)"
            }
        }

        // las versiones de recurso enlazadas desde esas lecciones
        suspend fun linkedResourceVersions(): Sql? {
            val rl = level(RESOURCE_LINK, "lms_rl") ?: return null
            val resourceVersion = rl.column(RESOURCE_VERSION) ?: return null
            if (rl.seeAll) return Sql { bind -> "SELECT $resourceVersion ${rl.from(bind, organization)}" }
            val lesson = rl.column(LESSON) ?: return null
            val lessons = enrolledLessons() ?: return null
            return Sql { bind -> "SELECT $resourceVersion ${rl.from(bind, organization)} AND $lesson IN (${lessons.render(bind)})" }
        }

        // los recursos de esas versiones
        suspend fun linkedResources(): Sql? {
            val rv = level(RESOURCE_VERSION, "lms_rv") ?: return null
            val resource = rv.column(RESOURCE) ?: return null
            if (rv.seeAll) return Sql { bind -> "SELECT $resource ${rv.from(bind, organization)}" }
            val versions = linkedResourceVersions() ?: return null
            return Sql { bind -> "SELECT $resource ${rv.from(bind, organization)} AND ${rv.id} IN (${versions.render(bind)})" }
        }
    }

    private fun column(
        definition: ObjectDefinition,
        field: String,
    ): String? = definition.fields.firstOrNull { it.name == field }?.let { SqlIdentifier.quote(it.columnName) }

    companion object {
        const val VERSION = "version_curso"
        const val UNIT = "unidad"
        const val LESSON = "leccion"
        const val OFFERING = "edicion_curso"
        const val RESOURCE = "recurso"
        const val RESOURCE_VERSION = "version_recurso"
        const val RESOURCE_LINK = "recurso_leccion"
        const val PUBLISHED = "PUBLICADA"
        const val ENROLLMENT = "matricula"
        const val STUDENT = "estudiante_id"
        const val STATE = "estado"
        const val WITHDRAWN = "RETIRADA"
        const val SEE_ALL = "VER_TODAS"
        const val NOTHING = "false"
        const val ID_COLUMN = "\"id\""
        val SCOPED = setOf(VERSION, UNIT, LESSON, OFFERING, RESOURCE, RESOURCE_VERSION, RESOURCE_LINK)
    }
}
