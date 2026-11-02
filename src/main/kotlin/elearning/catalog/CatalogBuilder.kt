package elearning.catalog

import elearning.course.PrerequisiteKnowledge
import elearning.course.PrerequisiteKnowledgeGuard
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

// lo que se lee de Wasichai para armar el catálogo: los registros de los nueve objetos de model/model.json
data class CatalogRecords(
    val categories: List<RecordResponse>,
    val courses: List<RecordResponse>,
    val versions: List<RecordResponse>,
    val units: List<RecordResponse>,
    val lessons: List<RecordResponse>,
    val offerings: List<RecordResponse>,
    val profiles: List<RecordResponse>,
    val assignments: List<RecordResponse>,
    // matriculados por edición (id → número), de lms.ocupacion_edicion: solo para saber si está completa
    val occupancy: Map<String, Int> = emptyMap(),
    // el vocabulario conocimiento_previo: el nombre de cada clave que declara una versión (ADR-0028)
    val knowledge: List<RecordResponse> = emptyList(),
)

// un curso listo para la proyección: la ficha completa y las columnas por las que se filtra y ordena
data class PublishedCourse(
    val detail: CourseDetail,
    // inicios y fines de las ediciones vigentes (no canceladas ni finalizadas), alineados: «desde» y «próximos»
    val startDates: List<LocalDate>,
    val endDates: List<LocalDate> = emptyList(),
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
    // un curso que no se puede publicar (datos fuera de rango, slug que la ruta rechazaría) se deja fuera y se informa
    // por onReject: un registro malo no puede dejar sin refrescar el catálogo entero
    fun build(
        records: CatalogRecords,
        onReject: (course: String, problem: Throwable) -> Unit = { _, _ -> },
    ): List<PublishedCourse> {
        val categories = records.categories.associateBy { it.id }
        // solo los perfiles con consentimiento (#66): privado por defecto, como pide la especificación
        val profiles = records.profiles.filter { it.bool("perfil_publico") == true }.associateBy { it.id }
        val lessonsByUnit = records.lessons.groupBy { it.ref("unidad") }
        val unitsByVersion = records.units.groupBy { it.ref("version_curso") }
        val offeringsByVersion = records.offerings.groupBy { it.ref("version_curso") }
        val teachersByOffering = records.assignments.groupBy { it.ref("edicion_curso") }
        val knowledge = records.knowledge.mapNotNull { k -> k.text("clave")?.let { it to (k.text("nombre") ?: it) } }.toMap()
        val published =
            records.versions
                .filter { it.text("estado") == PUBLISHED }
                .groupBy { it.ref("curso") }
                .mapValues { (_, versions) -> versions.maxBy { it.int("numero") ?: 0 } }

        return records.courses
            .sortedBy { it.id }
            .mapNotNull { course ->
                val version = published[course.id] ?: return@mapNotNull null
                try {
                    course(
                        course,
                        version,
                        categories,
                        profiles,
                        lessonsByUnit,
                        unitsByVersion,
                        offeringsByVersion,
                        teachersByOffering,
                        records.occupancy,
                        knowledge,
                    )
                } catch (e: IllegalArgumentException) {
                    onReject(course.text("codigo") ?: course.id, e)
                    null
                } catch (e: IllegalStateException) {
                    onReject(course.text("codigo") ?: course.id, e)
                    null
                } catch (e: NullPointerException) {
                    onReject(course.text("codigo") ?: course.id, e)
                    null
                } catch (e: java.time.DateTimeException) {
                    onReject(course.text("codigo") ?: course.id, e)
                    null
                }
            }
    }

    private fun course(
        course: RecordResponse,
        version: RecordResponse,
        categories: Map<String, RecordResponse>,
        profiles: Map<String, RecordResponse>,
        lessonsByUnit: Map<String?, List<RecordResponse>>,
        unitsByVersion: Map<String?, List<RecordResponse>>,
        offeringsByVersion: Map<String?, List<RecordResponse>>,
        teachersByOffering: Map<String?, List<RecordResponse>>,
        occupancy: Map<String, Int>,
        knowledge: Map<String, String>,
    ): PublishedCourse {
        val slug = requireNotNull(course.text("slug")) { "course without slug" }
        require(PUBLIC_SLUG.matches(slug)) { "slug '$slug' is not a public slug" }
        val current =
            offeringsByVersion[version.id]
                .orEmpty()
                .filter { it.text("estado") in CURRENT_OFFERINGS }
                .map { it to offering(it, teachersByOffering[it.id].orEmpty(), profiles, occupancy[it.id] ?: 0) }
                .sortedWith(compareBy({ it.second.startDate }, { it.second.code }))
        val offerings = current.map { it.second }
        val units =
            unitsByVersion[version.id]
                .orEmpty()
                .sortedWith(compareBy({ it.int("orden") ?: 0 }, { it.id }))
                .map { unit(it, lessonsByUnit[it.id].orEmpty()) }
        val teachers =
            current
                .flatMap { (record, _) -> teachersByOffering[record.id].orEmpty() }
                .mapNotNull { profiles[it.ref("perfil_docente")] }
                .distinctBy { it.id }
                .map { TeacherView(it.text("nombre_publico") ?: "", it.text("titulo_profesional"), it.text("biografia"), it.text("especialidades")) }
                .sortedBy { it.name }
        val workload = workload(version)
        val category = course.ref("categoria")?.let { categories[it] }
        return PublishedCourse(
            detail =
                CourseDetail(
                    slug = slug,
                    code = requireNotNull(course.text("codigo")) { "course without code" },
                    title = requireNotNull(course.text("titulo")) { "course without title" },
                    summary = course.text("resumen"),
                    language = course.text("idioma") ?: "es",
                    level = course.text("nivel") ?: "INICIAL",
                    modality = requireNotNull(version.text("modalidad")) { "version without modality" },
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
                    // lo que declaró la versión publicada, que no cambia; una clave sin registro en el vocabulario lleva su clave
                    prerequisiteKnowledgeTags = PrerequisiteKnowledge.keys(version.text(KNOWLEDGE_KEYS)).map { KnowledgeTag(it, knowledge[it] ?: it) },
                ),
            startDates = offerings.map { it.startDate },
            endDates = offerings.map { it.endDate },
        )
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
            lessons.sortedWith(compareBy({ it.int("orden") ?: 0 }, { it.id })).map {
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
        taken: Int,
    ): OfferingView {
        val capacity = offering.int("capacidad")
        val zone = offering.text("zona_horaria")?.takeIf { runCatching { ZoneId.of(it) }.isSuccess } ?: DEFAULT_ZONE
        return OfferingView(
            code = requireNotNull(offering.text("codigo")) { "offering without code" },
            status = requireNotNull(offering.text("estado")) { "offering without state" },
            startDate = requireNotNull(offering.date("fecha_inicio")) { "offering without start" },
            endDate = requireNotNull(offering.date("fecha_fin")) { "offering without end" },
            timeZone = zone,
            enrollmentFrom = offering.date("inscripcion_desde"),
            enrollmentUntil = offering.date("inscripcion_hasta"),
            // depende del día: lo decide la lectura (CatalogClock), no la proyección
            enrollmentOpen = false,
            capacity = capacity,
            schedule = offering.text("calendario"),
            // sin capacidad no hay límite: nunca está completa
            full = capacity != null && taken >= capacity,
            teachers =
                assignments
                    .mapNotNull { a ->
                        profiles[a.ref("perfil_docente")]?.let {
                            OfferingTeacher(it.text("nombre_publico") ?: "", it.text("titulo_profesional"), a.text("rol_docente") ?: "TITULAR")
                        }
                    }.sortedWith(compareBy({ it.role }, { it.name })),
        )
    }

    companion object {
        const val PUBLISHED = "PUBLICADA"
        const val KNOWLEDGE_KEYS = PrerequisiteKnowledgeGuard.FIELD
        const val DEFAULT_ZONE = "America/Lima"

        // lo que acepta la ruta GET /courses/{slug}: un curso con otro slug se listaría pero su ficha daría 404
        val PUBLIC_SLUG = Regex("^[a-z0-9][a-z0-9-]{0,99}$")
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
