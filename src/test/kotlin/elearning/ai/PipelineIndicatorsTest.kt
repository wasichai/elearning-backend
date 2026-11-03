package elearning.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// RF-IA-10: las piezas puras de los indicadores de proceso. La mediana es la de percentile_cont(0.5) y el cambio estructural
// se reconoce en la nota de una intervención EDITED de #288 (docs/ai §10 y §14)
class PipelineIndicatorsTest {
    @Test
    fun `the median interpolates an even sample and is null without one`() {
        assertNull(PipelineIndicators.median(emptyList()))
        assertEquals(3.0, PipelineIndicators.median(listOf(3.0)))
        assertEquals(2.0, PipelineIndicators.median(listOf(6.0, 1.0, 2.0, 1.0, 4.0)))
        assertEquals(2.5, PipelineIndicators.median(listOf(4.0, 1.0, 2.0, 3.0)))
    }

    @Test
    fun `a structural change is removing, reordering or moving a unit, lesson or evaluation`() {
        val id = "0b6c3f0e-1d2a-4c55-9a43-6f0f6d1c2e10"
        val structural =
            listOf(
                "unidad $id deleted",
                "leccion $id deleted",
                "evaluacion $id deleted",
                "unidad $id: orden",
                "leccion $id: orden, titulo",
                "leccion $id: resumen, unidad",
                "evaluacion $id: instrucciones, leccion, titulo",
                "unidad $id: titulo, version_curso",
            )
        structural.forEach { assertTrue(PipelineIndicatorStore.STRUCTURAL_CHANGE.containsMatchIn(it), it) }
        val notStructural =
            listOf(
                "unidad $id: titulo",
                "evaluacion $id: orden_aleatorio",
                "pregunta $id deleted",
                "opcion $id: orden",
                "version_curso $id: titulo",
                "leccion $id: duracion_min, unidades_extra",
                "recurso_leccion $id deleted",
            )
        notStructural.forEach { assertFalse(PipelineIndicatorStore.STRUCTURAL_CHANGE.containsMatchIn(it), it) }
    }
}
