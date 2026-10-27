# ADR-0003: Monolito modular: backend por capacidad, frontend por experiencia

**Estado**: aceptada · 2026-10-27

## Contexto

- La especificación pide un monolito modular por capacidades y «extraer procesos solo con razones operativas y
  métricas»; prohíbe microservicios por defecto.
- Wasichai es un monolito modular
  ([ADR-001 de Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/0001-modular-monolith.md)) y su
  `CLAUDE.md` dice «No overengineering: an abstraction needs a concrete second user».
- La especificación también pide experiencias distintas, no «una pantalla universal»: catálogo público, aula,
  área del profesor, estudio editorial, bandeja del revisor, administración.
- Se propuso separar repositorios o aplicaciones por audiencia: `elearning-student-*`, `elearning-teacher-*`,
  `elearning-content-*`.
- sgspe, la aplicación de referencia, es un único proyecto Gradle con paquetes por contexto.

## Decisión

**Backend:** un proyecto Gradle, un desplegable, paquete raíz `elearning` (nunca `wasichai.*`), paquetes por
**capacidad**: `common`, `catalog`, `course`, `workload`; en fases siguientes `enrollment`, `learning`, `assessment`,
`authoring`, `ai`, `media`. Las dependencias entre paquetes forman un DAG que una prueba de arquitectura comprueba
(escaneo de fuentes, como `CoreArchitectureTest` de Wasichai).

**Frontend:** una aplicación `<WasichaiApp>` con módulos por **experiencia**: `catalogModule` (F1), `classroomModule`,
`teachingModule`, `studioModule`, `reviewModule` (F2–F4). Cada módulo es un `WasichaiModule` con sus rutas, menú e i18n,
y no importa a otro ([ADR-0005](0005-lms-en-el-registro-de-wasichai-ui.md)).

**Respuesta a la propuesta `elearning-student-*`, `elearning-teacher-*`, `elearning-content-*`: no como repositorios
ni aplicaciones separadas.** Las tres audiencias comparten modelo (curso, versión, edición), identidad, permisos y
despliegue; separarlas hoy duplicaría configuración, CI, versiones de Wasichai, login y CORS, sin un equipo, una cadencia
ni una carga distinta que lo justifique. La separación por audiencia sí existe, como **módulos de UI** dentro de una
sola aplicación; un usuario con varios roles ve varios módulos sin cambiar de aplicación.

**Criterios para extraer un módulo o una aplicación más adelante** (se necesita al menos uno, medido, y un ADR nuevo):

| Criterio | Señal concreta |
|---|---|
| Equipos | un equipo distinto es dueño del módulo y las revisiones cruzadas lo frenan |
| Cadencia de despliegue | el módulo necesita desplegarse con otra frecuencia y el despliegue conjunto lo bloquea |
| Perímetro de seguridad | exige aislamiento propio (p. ej. ejecutar código no confiable de laboratorios, renderizado de vídeo) |
| Métricas | carga, latencia o consumo de recursos medidos que no se resuelven escalando el monolito |

## Alternativas consideradas

- **Repositorios y aplicaciones por audiencia** (`elearning-student-*`, `-teacher-*`, `-content-*`). Rechazado por
  sobreingeniería ahora: tres pipelines, tres despliegues y un contrato entre ellos para un dominio compartido.
- **Paquetes del backend por audiencia** (`student`, `teacher`). Rechazado: una edición de curso la usan estudiante y
  docente; las reglas quedarían repartidas y duplicadas. La audiencia es una cuestión de permisos y de UI.
- **Un módulo Gradle por capacidad.** Diferido, como en Wasichai: un paquete cuesta nada y se promueve a módulo Gradle
  cuando necesite su propio ciclo de vida.

## Consecuencias

- Una transacción puede abarcar varias capacidades sin coordinación distribuida.
- Las fronteras entre paquetes son una convención que solo protege la prueba de arquitectura; si no se mantiene, el
  monolito se acopla.
- Un fallo o un despliegue afecta a todas las experiencias a la vez.
- Extraer después significa mover un paquete y sustituir una interfaz por una llamada remota; se evita compartir tablas
  entre paquetes para que sea posible.
- Abierto: el procesamiento pesado (render Remotion, importación grande) puede necesitar un proceso aparte en F4–F5; se
  decide con los criterios de arriba.
