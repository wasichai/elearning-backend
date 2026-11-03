package elearning.reputation

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.ObjectProvider
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.common.NotFoundException
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

// lo que la guarda, el alcance y el comando de valorar necesitan saber sin depender de quien llama (ADR-0038 §25): el
// estudiante no lee las asignaciones ni sabe quién es el autor de un recurso, y la plataforma no corre dentro de una
// petición. subconsultas sobre las tablas de Wasichai, como CreditDuties y los alcances de ADR-0025: organización ligada en
// cada nivel, columnas de los metadatos, todo valor ligado. sin el modelo para responder, null o vacío (falla cerrada)
@Repository
class RatingFacts(
    private val db: DatabaseClient,
    // MetadataService recibe los alcances, y el de las valoraciones usa esto: se pide perezosamente para no formar un ciclo
    private val metadata: ObjectProvider<MetadataService>,
    private val schemas: WasichaiSchemas,
) {
    data class Enrollment(
        val id: UUID,
        val student: UUID?,
        val offering: UUID?,
        val version: UUID?,
        val state: String?,
    )

    data class Offering(
        val id: UUID,
        val end: LocalDate?,
        val zone: String?,
    )

    // una asignación docente de la edición: su cuenta y el nombre público de su perfil
    data class Assignment(
        val id: UUID,
        val user: UUID?,
        val name: String?,
    )

    // una versión de recurso enlazada en una lección de la versión del curso
    data class LinkedResource(
        val resourceVersion: UUID,
        val lesson: UUID,
        val author: UUID?,
        val title: String?,
    )

    data class Dimension(
        val id: UUID,
        val key: String,
        val name: String,
        val target: String?,
        val active: Boolean,
        val order: Int,
    )

    data class Rating(
        val id: UUID,
        val student: UUID?,
        val enrollment: UUID?,
        val offering: UUID?,
        val target: String?,
        val assignment: UUID?,
        val resourceVersion: UUID?,
        val state: String? = null,
    )

    // una tabla de datos de Wasichai con sus columnas por nombre de campo
    class Table(
        val name: String,
        private val columns: Map<String, String>,
    ) {
        fun column(field: String): String = columns[field] ?: throw MissingModel(field)
    }

    // el modelo no tiene lo que la pregunta necesita: quien pregunta responde que no (falla cerrada)
    class MissingModel(
        what: String,
    ) : RuntimeException("missing in the model: $what")

    suspend fun table(
        organization: UUID,
        objectName: String,
    ): Table {
        val definition = definition(organization, objectName) ?: throw MissingModel(objectName)
        return Table(schemas.dataTable(definition.obj.physicalTable), definition.fields.associate { it.name to SqlIdentifier.quote(it.columnName) })
    }

    suspend fun enrollment(
        organization: UUID,
        id: UUID,
    ): Enrollment? {
        val m = table(organization, ENROLLMENT)
        return db
            .sql(
                "SELECT m.\"id\", m.${m.column(STUDENT)} AS student, m.${m.column(OFFERING_FIELD)} AS offering, " +
                    "m.${m.column(VERSION)} AS version, m.${m.column(STATE)} AS state " +
                    "FROM ${m.name} m WHERE m.organization_id = :org AND m.\"id\" = :id",
            ).bind("org", organization)
            .bind("id", id)
            .map { row, _ ->
                Enrollment(
                    row.get("id", UUID::class.java)!!,
                    row.get("student", UUID::class.java),
                    row.get("offering", UUID::class.java),
                    row.get("version", UUID::class.java),
                    row.get("state", String::class.java),
                )
            }.one()
            .awaitSingleOrNull()
    }

    suspend fun offering(
        organization: UUID,
        id: UUID,
    ): Offering? {
        val o = table(organization, OFFERING)
        return db
            .sql(
                "SELECT o.\"id\", o.${o.column(END)} AS fin, o.${o.column(ZONE)} AS zona " +
                    "FROM ${o.name} o WHERE o.organization_id = :org AND o.\"id\" = :id",
            ).bind("org", organization)
            .bind("id", id)
            .map { row, _ -> Offering(row.get("id", UUID::class.java)!!, row.get("fin", LocalDate::class.java), row.get("zona", String::class.java)) }
            .one()
            .awaitSingleOrNull()
    }

    // las asignaciones docentes de una edición, con el nombre público de su perfil
    suspend fun assignments(
        organization: UUID,
        offering: UUID,
    ): List<Assignment> {
        val a = table(organization, ASSIGNMENT)
        val p = table(organization, PROFILE)
        return db
            .sql(
                "SELECT a.\"id\", a.${a.column(TEACHER)} AS teacher, p.${p.column(PUBLIC_NAME)} AS name FROM ${a.name} a " +
                    "LEFT JOIN ${p.name} p ON p.\"id\" = a.${a.column(PROFILE)} AND p.organization_id = :org " +
                    "WHERE a.organization_id = :org AND a.${a.column(OFFERING)} = :offering ORDER BY a.\"id\"",
            ).bind("org", organization)
            .bind("offering", offering)
            .map { row, _ -> Assignment(row.get("id", UUID::class.java)!!, row.get("teacher", UUID::class.java), row.get("name", String::class.java)) }
            .all()
            .collectList()
            .awaitSingle()
    }

    // las versiones de recurso enlazadas en las lecciones de una versión del curso, con su autor y el título del recurso
    suspend fun linkedResources(
        organization: UUID,
        version: UUID,
    ): List<LinkedResource> {
        val rl = table(organization, RESOURCE_LINK)
        val l = table(organization, LESSON)
        val u = table(organization, UNIT)
        val rv = table(organization, RESOURCE_VERSION)
        val r = table(organization, RESOURCE)
        return db
            .sql(
                "SELECT rl.${rl.column(RESOURCE_VERSION)} AS rv, rl.${rl.column(LESSON)} AS lesson, rv.${rv.column(AUTHOR)} AS author, " +
                    "r.${r.column(TITLE)} AS title FROM ${rl.name} rl " +
                    "JOIN ${l.name} l ON l.\"id\" = rl.${rl.column(LESSON)} AND l.organization_id = :org " +
                    "JOIN ${u.name} u ON u.\"id\" = l.${l.column(UNIT)} AND u.organization_id = :org " +
                    "JOIN ${rv.name} rv ON rv.\"id\" = rl.${rl.column(RESOURCE_VERSION)} AND rv.organization_id = :org " +
                    "LEFT JOIN ${r.name} r ON r.\"id\" = rv.${rv.column(RESOURCE)} AND r.organization_id = :org " +
                    "WHERE rl.organization_id = :org AND u.${u.column(VERSION)} = :version",
            ).bind("org", organization)
            .bind("version", version)
            .map { row, _ ->
                LinkedResource(
                    row.get("rv", UUID::class.java)!!,
                    row.get("lesson", UUID::class.java)!!,
                    row.get("author", UUID::class.java),
                    row.get("title", String::class.java),
                )
            }.all()
            .collectList()
            .awaitSingle()
    }

    // las lecciones completadas o aprobadas de una matrícula (el progreso guardado)
    suspend fun completedLessons(
        organization: UUID,
        enrollment: UUID,
    ): Set<UUID> {
        val p = table(organization, PROGRESS)
        return db
            .sql(
                "SELECT p.${p.column(LESSON)} AS lesson FROM ${p.name} p WHERE p.organization_id = :org " +
                    "AND p.${p.column(ENROLLMENT)} = :enrollment AND p.${p.column(STATE)} = ANY(:done) AND p.${p.column(LESSON)} IS NOT NULL",
            ).bind("org", organization)
            .bind("enrollment", enrollment)
            .bind("done", DONE)
            .map { row, _ -> row.get("lesson", UUID::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
            .toSet()
    }

    suspend fun dimensions(organization: UUID): List<Dimension> {
        val d = table(organization, DIMENSION)
        return db
            .sql(
                "SELECT d.\"id\", d.${d.column(KEY)} AS clave, d.${d.column(NAME)} AS nombre, d.${d.column(TARGET)} AS destino, " +
                    "d.${d.column(ACTIVE)} AS activa, d.${d.column(ORDER)} AS orden FROM ${d.name} d WHERE d.organization_id = :org",
            ).bind("org", organization)
            .map { row, _ ->
                Dimension(
                    row.get("id", UUID::class.java)!!,
                    row.get("clave", String::class.java).orEmpty(),
                    row.get("nombre", String::class.java).orEmpty(),
                    row.get("destino", String::class.java),
                    row.get("activa", Boolean::class.javaObjectType) == true,
                    row.get("orden", Number::class.java)?.toInt() ?: 0,
                )
            }.all()
            .collectList()
            .awaitSingle()
            .sortedWith(compareBy({ it.order }, { it.key }))
    }

    // por su id o, con byToken, por su ficha opaca (la que ve el valorado)
    suspend fun rating(
        organization: UUID,
        id: UUID,
        byToken: Boolean = false,
    ): Rating? {
        val v = table(organization, RATING)
        return db
            .sql(
                "SELECT v.\"id\", v.${v.column(STUDENT)} AS student, v.${v.column(ENROLLMENT)} AS enrollment, " +
                    "v.${v.column(OFFERING_FIELD)} AS offering, v.${v.column(TARGET)} AS target, v.${v.column(ASSIGNMENT)} AS assignment, " +
                    "v.${v.column(RESOURCE_VERSION)} AS rv, v.${v.column(MODERATION)} AS state FROM ${v.name} v " +
                    "WHERE v.organization_id = :org AND v.${if (byToken) v.column(TOKEN) else "\"id\""} = :id",
            ).bind("org", organization)
            .bind("id", id)
            .map { row, _ ->
                Rating(
                    row.get("id", UUID::class.java)!!,
                    row.get("student", UUID::class.java),
                    row.get("enrollment", UUID::class.java),
                    row.get("offering", UUID::class.java),
                    row.get("target", String::class.java),
                    row.get("assignment", UUID::class.java),
                    row.get("rv", UUID::class.java),
                    row.get("state", String::class.java),
                )
            }.one()
            .awaitSingleOrNull()
    }

    // una edición por su código
    suspend fun offeringByCode(
        organization: UUID,
        code: String,
    ): Offering? {
        val o = table(organization, OFFERING)
        return db
            .sql(
                "SELECT o.\"id\", o.${o.column(END)} AS fin, o.${o.column(ZONE)} AS zona " +
                    "FROM ${o.name} o WHERE o.organization_id = :org AND o.${o.column(CODE)} = :code",
            ).bind("org", organization)
            .bind("code", code)
            .map { row, _ -> Offering(row.get("id", UUID::class.java)!!, row.get("fin", LocalDate::class.java), row.get("zona", String::class.java)) }
            .one()
            .awaitSingleOrNull()
    }

    // las matrículas no retiradas de la edición que aún no tienen resultado
    suspend fun pendingResults(
        organization: UUID,
        offering: UUID,
    ): Long {
        val m = table(organization, ENROLLMENT)
        return db
            .sql(
                "SELECT count(*) AS pending FROM ${m.name} m WHERE m.organization_id = :org AND m.${m.column(OFFERING_FIELD)} = :offering " +
                    "AND m.${m.column(STATE)} IS DISTINCT FROM :withdrawn AND (m.${m.column(RESULT)} IS NULL OR m.${m.column(RESULT)} = :none)",
            ).bind("org", organization)
            .bind("offering", offering)
            .bind("withdrawn", WITHDRAWN)
            .bind("none", NO_RESULT)
            .map { row, _ -> row.get("pending", Number::class.java)!!.toLong() }
            .one()
            .awaitSingle()
    }

    data class AuthoredVersion(
        val id: UUID,
        val title: String?,
        val number: Int?,
    )

    // las versiones de recurso de las que la persona es autora, con el título del recurso
    suspend fun authoredResourceVersions(
        organization: UUID,
        user: UUID,
    ): List<AuthoredVersion> {
        val rv = table(organization, RESOURCE_VERSION)
        val r = table(organization, RESOURCE)
        return db
            .sql(
                "SELECT rv.\"id\", r.${r.column(TITLE)} AS title, rv.${rv.column(NUMBER)} AS number FROM ${rv.name} rv " +
                    "LEFT JOIN ${r.name} r ON r.\"id\" = rv.${rv.column(RESOURCE)} AND r.organization_id = :org " +
                    "WHERE rv.organization_id = :org AND rv.${rv.column(AUTHOR)} = :user ORDER BY r.${r.column(TITLE)}, rv.${rv.column(NUMBER)}",
            ).bind("org", organization)
            .bind("user", user)
            .map {
                row,
                _,
                ->
                AuthoredVersion(row.get("id", UUID::class.java)!!, row.get("title", String::class.java), row.get("number", Number::class.java)?.toInt())
            }.all()
            .collectList()
            .awaitSingle()
    }

    // las ediciones con alguna valoración de docente
    suspend fun offeringsWithTeacherRatings(organization: UUID): List<UUID> {
        val v = table(organization, RATING)
        return db
            .sql(
                "SELECT DISTINCT v.${v.column(OFFERING_FIELD)} AS offering FROM ${v.name} v WHERE v.organization_id = :org " +
                    "AND v.${v.column(TARGET)} = :target AND v.${v.column(OFFERING_FIELD)} IS NOT NULL",
            ).bind("org", organization)
            .bind("target", RatingWriteGuard.TEACHER)
            .map { row, _ -> row.get("offering", UUID::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
    }

    // la cuenta valorada: la del docente de la asignación o la del autor de la versión de recurso
    suspend fun ratedUser(
        organization: UUID,
        rating: Rating,
    ): UUID? {
        val (objectName, field, id) =
            when {
                rating.assignment != null -> Triple(ASSIGNMENT, TEACHER, rating.assignment)
                rating.resourceVersion != null -> Triple(RESOURCE_VERSION, AUTHOR, rating.resourceVersion)
                else -> return null
            }
        val t = table(organization, objectName)
        return db
            .sql("SELECT t.${t.column(field)} AS rated FROM ${t.name} t WHERE t.organization_id = :org AND t.\"id\" = :id")
            .bind("org", organization)
            .bind("id", id)
            .map { row, _ -> Optional.ofNullable(row.get("rated", UUID::class.java)) }
            .one()
            .awaitSingleOrNull()
            ?.orElse(null)
    }

    // el id del objeto valoracion, para preguntar por sus acciones
    suspend fun ratingObject(organization: UUID): UUID = (definition(organization, RATING) ?: throw MissingModel(RATING)).obj.id

    private suspend fun definition(
        organization: UUID,
        name: String,
    ): ObjectDefinition? =
        try {
            metadata.getObject().loadDefinition(organization, name)
        } catch (_: NotFoundException) {
            null
        }

    companion object {
        const val RATING = "valoracion"
        const val SCORE = "puntaje_valoracion"
        const val DIMENSION = "dimension_valoracion"
        const val ENROLLMENT = "matricula"
        const val OFFERING = "edicion_curso"
        const val OFFERING_FIELD = "edicion"
        const val VERSION = "version_curso"
        const val ASSIGNMENT = "docente_edicion"
        const val PROFILE = "perfil_docente"
        const val PUBLIC_NAME = "nombre_publico"
        const val TEACHER = "usuario_id"
        const val RESOURCE = "recurso"
        const val RESOURCE_VERSION = "version_recurso"
        const val RESOURCE_LINK = "recurso_leccion"
        const val LESSON = "leccion"
        const val UNIT = "unidad"
        const val PROGRESS = "progreso_leccion"
        const val AUTHOR = "autor_id"
        const val TITLE = "titulo"
        const val STUDENT = "estudiante_id"
        const val STATE = "estado"
        const val END = "fecha_fin"
        const val ZONE = "zona_horaria"
        const val KEY = "clave"
        const val NAME = "nombre"
        const val TARGET = "destino"
        const val ACTIVE = "activa"
        const val ORDER = "orden"
        const val CODE = "codigo"
        const val MODERATION = "estado_moderacion"
        const val TOKEN = "ficha"
        const val RESULT = "resultado"
        const val NO_RESULT = "SIN_RESULTADO"
        const val WITHDRAWN = "RETIRADA"
        const val NUMBER = "numero"
        val DONE = arrayOf("COMPLETADA", "APROBADA")
    }
}
