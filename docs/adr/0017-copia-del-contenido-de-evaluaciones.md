# ADR-0017: Los intentos leen una copia del contenido de las evaluaciones

**Estado**: aceptada · 2026-10-31 · precisa el punto 2 de [ADR-0016](0016-fase-3-evaluaciones-docente-sesiones-certificados.md)

## Contexto

Empezar un intento copia la evaluación en su instantánea, y eso ocurre en una petición del estudiante. Pero el estudiante
no tiene `READ` sobre `evaluacion`, `pregunta` ni `opcion` (llevan las soluciones), y `RecordService.asPlatform` lanza
dentro de una petición (el mismo límite que motivó ADR-0011).

## Decisión

Un `RecordChangeListener` mantiene `lms.contenido_evaluacion`, una copia de esos cuatro objetos, en la misma llamada que
escribe el registro. `AssessmentContent` compone la instantánea desde esa copia. Solo la leen los intentos; ninguna API la
expone.

## Alternativas consideradas

- **Dar `READ` al estudiante y ocultar campos.** Rechazada en ADR-0016: abre la API genérica a las preguntas.
- **Proyección con un trabajo en segundo plano, como el catálogo.** Rechazada: añade un retraso sin necesidad, porque aquí
  toda escritura pasa por un punto que ya conocemos.
- **Ejecutar la lectura fuera del contexto de la petición para eludir `asPlatform`.** Rechazada: salta una protección de
  Wasichai.

## Consecuencias

- Una evaluación escrita antes de instalar el listener no estaría en la copia. No hay ninguna: nace con él.
- La copia sigue a sus registros incluso en borrador, pero solo una lección de una versión en la que se está matriculado
  llega a usarla, y esa ya está congelada por la guarda editorial.
