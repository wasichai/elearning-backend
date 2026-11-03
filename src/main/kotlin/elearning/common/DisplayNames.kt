package elearning.common

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// el nombre para mostrar de las personas de la organización, en una sentencia para todas (#258, ADR-0031). nunca el correo
// ni otro dato. Wasichai 1.8.0 no lo ofrece en UserDirectory, así que se lee su tabla de personas con las mismas condiciones
// que UserDirectory.existing: de la organización y habilitada (una cuenta de servicio no lo está). quien está deshabilitado,
// fue borrado o no es de la organización no aparece en el mapa. provisional hasta que UserDirectory lo dé
@Component
class DisplayNames(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
) {
    // los ids que no son UUID se ignoran: un id guardado en un campo de texto no se fía
    suspend fun of(
        organization: UUID,
        ids: Collection<String?>,
    ): Map<String, String> {
        val wanted = ids.mapNotNull { id -> id?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() } }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        return db
            .sql("SELECT id, display_name FROM ${schemas.metadata}.users WHERE organization_id = :org AND enabled AND id = ANY(:ids)")
            .bind("org", organization)
            .bind("ids", wanted.toTypedArray())
            .map { row, _ -> row.get("id", UUID::class.java).toString() to row.get("display_name", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
            .toMap()
    }
}
