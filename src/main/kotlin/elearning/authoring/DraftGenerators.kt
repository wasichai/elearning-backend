package elearning.authoring

import java.util.UUID

// de dónde salió cada versión (RF-IA-08, #288): el generador (MODEL o DETERMINISTIC) de la ejecución de IA que la creó
// (docs/ai §13); una versión escrita a mano no está en el mapa. Con DETERMINISTIC el estudio y la bandeja la marcan
// «generado sin IA, ilustrativo». Lo implementa elearning.ai, que authoring no puede usar (ArchitectureTest)
fun interface DraftGenerators {
    suspend fun of(
        organization: UUID,
        versions: Collection<UUID>,
    ): Map<UUID, String>
}
