package elearning.catalog

import elearning.common.LmsProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import wasichai.core.data.RecordService
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.TenantDirectory

data class RefreshResult(
    val published: Int,
    val written: Int,
    val deleted: Long,
)

// mantiene lms.catalogo_publicado (ADR-0011). corre en segundo plano porque RecordService.asPlatform y TenantDirectory
// lanzan dentro de una petición; con varias réplicas solo una refresca a la vez (ClusterLock). solo escribe lo que
// cambió (hash) y borra los cursos que dejaron de estar publicados. una matrícula llega a la ficha en el refresco
// siguiente, y solo cuando agota el cupo (full cambia una vez): el resto de altas no cambia el hash ni escribe
@Component
class CatalogProjection(
    private val properties: LmsProperties,
    private val tenants: TenantDirectory,
    private val records: RecordService,
    private val source: CatalogSource,
    private val repository: CatalogRepository,
    private val clusterLock: ClusterLock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val builder = CatalogBuilder()

    @Scheduled(fixedDelayString = "\${lms.catalog.refresh:30s}", initialDelayString = "\${lms.catalog.initial-delay:5s}")
    suspend fun scheduled() {
        if (!properties.catalog.scheduling) return
        try {
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("catalog projection refresh failed", e)
        }
    }

    // null: otra réplica (u otra llamada) está refrescando
    suspend fun refresh(): RefreshResult? = clusterLock.tryLock(LOCK)?.use { sync() }

    // espera a que el refresco en curso termine y hace uno propio: para pruebas y operación manual
    suspend fun refreshNow(): RefreshResult {
        repeat(ATTEMPTS) {
            refresh()?.let { return it }
            delay(WAIT_MS)
        }
        error("catalog projection lock still busy after ${ATTEMPTS * WAIT_MS} ms")
    }

    private suspend fun sync(): RefreshResult {
        val slug = properties.public.organization
        val organization = slug.takeIf { it.isNotBlank() }?.let { s -> tenants.organizationsWithObject(VERSION_OBJECT).firstOrNull { it.slug == s } }
        if (organization == null) {
            if (slug.isNotBlank()) log.warn("public catalog organization '{}' not found or without the LMS model: catalog emptied", slug)
            // sin organización pública no hay catálogo: nada de seguir sirviendo lo que se proyectó antes
            return RefreshResult(0, 0, repository.deleteAll())
        }
        val loaded = records.asPlatform(organization.id, SOURCE) { source.load() }
        val courses =
            builder.build(loaded.copy(occupancy = repository.occupancy(organization.id))) { course, problem ->
                log.warn("course {} left out of the public catalog: {}", course, problem.message)
            }
        val stored = repository.stored(slug).associate { it.slug to it.hash }
        val changed = courses.filter { stored[it.detail.slug] != it.hash }
        changed.forEach { repository.upsert(slug, it) }
        val gone = stored.keys - courses.map { it.detail.slug }.toSet()
        val deleted = repository.delete(slug, gone) + repository.deleteOtherOrganizations(slug)
        if (changed.isNotEmpty() || deleted > 0) log.info("public catalog: {} courses, {} written, {} removed", courses.size, changed.size, deleted)
        return RefreshResult(courses.size, changed.size, deleted)
    }

    companion object {
        const val LOCK = "elearning:catalog-projection"
        const val SOURCE = "job:catalogo-publico"
        const val VERSION_OBJECT = "version_curso"
        private const val ATTEMPTS = 60
        private const val WAIT_MS = 500L
    }
}
