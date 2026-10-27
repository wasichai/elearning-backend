package elearning.catalog

import elearning.workload.CreditPolicy
import elearning.workload.EctsPolicy
import elearning.workload.SemesterCreditHourPolicy
import elearning.workload.WeeklyHours
import elearning.workload.Workload
import tools.jackson.databind.json.JsonMapper
import wasichai.core.data.RecordResponse
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// lo que se lee de Wasichai para armar el catálogo: los registros de los ocho objetos de model/model.json
data class CatalogRecords(
    val categories: List<RecordResponse>,
    val courses: List<RecordResponse>,
    val versions: List<RecordResponse>,
    val units: List<RecordResponse>,
    val lessons: List<RecordResponse>,
    val offerings: List<RecordResponse>,
    val profiles: List<RecordResponse>,
    val assignments: List<RecordResponse>,
)

// un curso listo para la proyección: la ficha completa y las columnas por las que se filtra y ordena
data class PublishedCourse(
    val detail: CourseDetail,
    // inicios de las ediciones vigentes (no canceladas ni finalizadas), para «desde» y «próximos»
    val startDates: List<LocalDate>,
) {
    fun json(): String = MAPPER.writeValueAsString(detail)

    val hash: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(json().toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        internal val MAPPER: JsonMapper = JsonMapper.builder().findAndAddModules().build()
    }
}

// arma las fichas públicas a partir de los registros. puro: sin base de datos ni sesión, por eso se prueba solo.
// entra únicamente la última versión PUBLICADA de cada curso; un curso sin versión publicada no existe para el catálogo
class CatalogBuilder(
    private val ectsPolicy: CreditPolicy = EctsPolicy(),
    private val schPolicy: CreditPolicy = SemesterCreditHourPolicy(),
) {
    fun build(records: CatalogRecords): List<PublishedCourse> {
        val categories = records.categories.associateBy { it.id }
        val profiles = records.profiles.associateBy { it.id }
        val lessonsByUnit = records.lessons.groupBy { it.ref("unidad") }
        val unitsByVersion = records.units.groupBy { it.ref("version_curso") }
        val offeringsByVersion = records.offerings.groupBy { it.ref("version_curso") }
        val teachersByOffering = records.assignments.groupBy { it.ref("edicion_curso") }
        val published =
            records.versions
                .filter { it.text("estado") == PUBLISHED }
                .groupBy { it.ref("curso") }
                .mapValues { (_, versions) -> versions.maxBy { it.int("numero") ?: 0 } }

        return records.courses.mapNotNull { course ->
            val version = published[course.id] ?: return@mapNotNull null
            val offerings =
                offeringsByVersion[version.id]
                    .orEmpty()
                    .filter { it.text("estado") in CURRENT_OFFERINGS }
                    .map { offering(it, teachersByOffering[it.id].orEmpty(), profiles) }
                    .sortedWith(compareBy({ it.startDate }, { it.code }))
            val units =
                unitsByVersion[version.id]
                    .orEmpty()
                    .sortedBy { it.int("orden") ?: 0 }
                    .map { unit(it, lessonsByUnit[it.id].orEmpty()) }
            val teachers =
                offerings
                    .flatMap { o -> teachersByOffering[records.offerings.first { it.text("codigo") == o.code }.id].orEmpty() }
                    .mapNotNull { profiles[it.ref("perfil_docente")] }
                    .distinctBy { it.id }
                    .map { TeacherView(it.text("nombre_publico") ?: "", it.text("titulo_profesional"), it.text("biografia"), it.text("especialidades")) }
            val workload = workload(version)
            val category = course.ref("categoria")?.let { categories[it] }
            PublishedCourse(
                detail =
                    CourseDetail(
                        slug = course.text("slug")!!,
                        code = course.text("codigo")!!,
                        title = course.text("titulo")!!,
                        summary = course.text("resumen"),
                        language = course.text("idioma") ?: "es",
                        level = course.text("nivel") ?: "INICIAL",
                        modality = version.text("modalidad")!!,
                        category = category?.let { CategoryRef(it.text("slug")!!, it.text("nombre")!!) },
                        weeks = workload.weeks,
                        illustrative = version.bool("contenido_ilustrativo") ?: true,
                        prerequisiteKnowledge = course.text("conocimientos_previos"),
                        version = VersionView(version.int("numero") ?: 0, version.instant("publicada_en")),
                        objectives = version.lines("objetivos"),
                        learningOutcomes = version.lines("resultados_aprendizaje"),
                        requirements = version.text("requisitos"),
                        assessment = version.text("sistema_evaluacion"),
                        certification = version.text("certificacion"),
                        sources = version.lines("fuentes"),
                        workload = workloadView(workload),
                        credits = credits(version, workload),
                        units = units,
                        offerings = offerings,
                        teachers = teachers,
                    ),
                startDates = offerings.map { it.startDate },
            )
        }
    }

    private fun workload(version: RecordResponse) =
        Workload(
            weeks = version.int("semanas") ?: 1,
            weekly =
                WeeklyHours(
                    theory = version.decimal("horas_teoria_semana") ?: BigDecimal.ZERO,
                    practice = version.decimal("horas_practica_semana") ?: BigDecimal.ZERO,
                    supervised = version.decimal("horas_supervisadas_semana") ?: BigDecimal.ZERO,
                    autonomous = version.decimal("horas_autonomas_semana") ?: BigDecimal.ZERO,
                ),
        )

    private fun workloadView(w: Workload) =
        WorkloadView(
            weeks = w.weeks,
            weekly = WeeklyHoursView(w.weekly.theory.plain(), w.weekly.practice.plain(), w.weekly.supervised.plain(), w.weekly.autonomous.plain()),
            theoryHours = w.theoryHours.plain(),
            practiceHours = w.practiceHours.plain(),
            supervisedHours = w.supervisedHours.plain(),
            autonomousHours = w.autonomousHours.plain(),
            contactHours = w.contactHours.plain(),
            directedHours = w.directedHours.plain(),
            totalHours = w.totalHours.plain(),
        )

    // un solo sistema por versión: no se mezclan ECTS y SCH ni se convierte uno en otro (ADR-0007)
    private fun credits(
        version: RecordResponse,
        workload: Workload,
    ): CreditsView {
        val policy =
            when (version.text("sistema_credito")) {
                "ECTS" -> ectsPolicy
                "SCH" -> schPolicy
                else -> return CreditsView(system = null, approved = null, proposal = null)
            }
        val proposal = policy.propose(workload)
        return CreditsView(
            system = policy.system.name,
            approved = version.decimal("creditos_aprobados")?.plain(),
            proposal =
                CreditProposalView(
                    system = proposal.system.name,
                    policyId = proposal.policyId,
                    policyVersion = proposal.policyVersion,
                    method = proposal.method,
                    parameters = proposal.parameters,
                    source = proposal.source,
                    totalHours = proposal.totalHours.plain(),
                    calculated = proposal.calculated.plain(),
                    proposed = proposal.proposed.plain(),
                    warnings = proposal.warnings,
                    status = proposal.status.name,
                ),
        )
    }

    private fun unit(
        unit: RecordResponse,
        lessons: List<RecordResponse>,
    ) = UnitView(
        order = unit.int("orden") ?: 0,
        title = unit.text("titulo") ?: "",
        description = unit.text("descripcion"),
        objectives = unit.lines("objetivos"),
        estimatedHours = unit.decimal("horas_estimadas")?.plain(),
        lessons =
            lessons.sortedBy { it.int("orden") ?: 0 }.map {
                LessonView(
                    order = it.int("orden") ?: 0,
                    title = it.text("titulo") ?: "",
                    type = it.text("tipo") ?: "LECTURA",
                    durationMinutes = it.int("duracion_minutos"),
                    summary = it.text("resumen"),
                    source = it.text("referencia_fuente"),
                )
            },
    )

    private fun offering(
        offering: RecordResponse,
        assignments: List<RecordResponse>,
        profiles: Map<String, RecordResponse>,
    ): OfferingView {
        val zone = offering.text("zona_horaria")?.takeIf { runCatching { ZoneId.of(it) }.isSuccess } ?: DEFAULT_ZONE
        return OfferingView(
            code = offering.text("codigo")!!,
            status = offering.text("estado")!!,
            startDate = offering.date("fecha_inicio")!!,
            endDate = offering.date("fecha_fin")!!,
            timeZone = zone,
            enrollmentFrom = offering.date("inscripcion_desde"),
            enrollmentUntil = offering.date("inscripcion_hasta"),
            // depende del día: lo decide la lectura (CatalogClock), no la proyección
            enrollmentOpen = false,
            capacity = offering.int("capacidad"),
            schedule = offering.text("calendario"),
            teachers =
                assignments
                    .sortedBy { it.text("rol_docente") }
                    .mapNotNull { a ->
                        profiles[a.ref("perfil_docente")]?.let {
                            OfferingTeacher(it.text("nombre_publico") ?: "", it.text("titulo_profesional"), a.text("rol_docente") ?: "TITULAR")
                        }
                    },
        )
    }

    companion object {
        const val PUBLISHED = "PUBLICADA"
        const val DEFAULT_ZONE = "America/Lima"
        val CURRENT_OFFERINGS = setOf("PROGRAMADA", "INSCRIPCION_ABIERTA", "EN_CURSO")
    }
}

// lectura tolerante de los atributos: Wasichai entrega números, fechas y textos según el tipo del campo
private fun RecordResponse.text(name: String): String? = attributes[name]?.toString()?.takeIf { it.isNotBlank() }

private fun RecordResponse.ref(name: String): String? = text(name)

private fun RecordResponse.int(name: String): Int? =
    when (val v = attributes[name]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

private fun RecordResponse.decimal(name: String): BigDecimal? =
    when (val v = attributes[name]) {
        is BigDecimal -> v
        is Number -> BigDecimal(v.toString())
        is String -> v.toBigDecimalOrNull()
        else -> null
    }

private fun RecordResponse.bool(name: String): Boolean? =
    when (val v = attributes[name]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull()
        else -> null
    }

private fun RecordResponse.date(name: String): LocalDate? =
    when (val v = attributes[name]) {
        is LocalDate -> v
        null -> null
        else -> LocalDate.parse(v.toString().take(10))
    }

private fun RecordResponse.instant(name: String): Instant? =
    when (val v = attributes[name]) {
        is Instant -> v
        null -> null
        else -> runCatching { Instant.parse(v.toString()) }.getOrNull()
    }

// un texto largo de «uno por línea» como lista, sin líneas vacías
private fun RecordResponse.lines(name: String): List<String> =
    text(name)
        ?.lines()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

// 72.00 se publica como 72, nunca como 7.2E+1
internal fun BigDecimal.plain(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
