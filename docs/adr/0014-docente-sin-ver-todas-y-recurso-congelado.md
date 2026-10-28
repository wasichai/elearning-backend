# ADR-0014: Sin «ver todas» del docente sobre matrículas y progreso; un recurso que salió del borrador no cambia

**Estado**: aceptada · 2026-10-28 · precisa el punto 2 de [ADR-0012](0012-aula-del-estudiante.md) y el punto 3 en lo que
toca a `recurso`

## Contexto

La revisión de seguridad del cierre de la Fase 2 (E16.3) encontró dos huecos en lo decidido:

- ADR-0012 daba la acción `VER_TODAS` al DOCENTE también sobre `matricula` (y E14 la extendió a `progreso_leccion`).
  Sin el alcance por asignación, que llega con el espacio docente en F3, eso significa que **cualquier docente lee las
  matrículas y el progreso de todos los estudiantes de la organización**, y la regla 4 de CLAUDE.md pide el alcance por
  asignación.
- La guarda editorial congelaba `version_recurso` al salir del borrador, pero no `recurso`. Un CREADOR podía cambiar el
  **título** o el **tipo** de un recurso que ya ven los estudiantes, y el tipo decide cómo se muestra el archivo (un HTML
  pasaría a descargarse; un PDF, al `iframe`). Eso contradice la regla 5 («lo publicado no cambia»).

## Decisión

1. Ningún rol tiene `VER_TODAS` sobre `matricula` ni `progreso_leccion` hasta que exista el alcance por asignación
   (docente de _su_ edición, F3). El administrador las sigue viendo (Wasichai no le aplica alcances); el docente lee solo
   las suyas como cualquier persona. Una prueba de modelo lo vigila.
2. `EditorialWorkflowGuard` cubre `recurso`: si alguna de sus versiones salió del borrador, no se cambian sus campos ni
   se borra. Para cambiar el título o el tipo de lo publicado se crea un recurso nuevo. Mientras todas sus versiones son
   borrador, se edita libremente.

## Consecuencias

- Hasta F3 ningún docente ve el avance de sus estudiantes por la API; su vista llega con `teachingModule` y su alcance.
- Lo que ven los estudiantes de un recurso (título, tipo, archivo) queda fijo como el resto de lo publicado.
- El resto de hallazgos de la revisión, de severidad media o baja, queda registrado en issues para su propio paso.
