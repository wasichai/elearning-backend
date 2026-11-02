# ADR-0029: Sistema de diseño Quipu aplicado: composición y piezas compartidas del frontend

**Estado**: aceptada · 2026-11-03 · parte de la propuesta de ADR-0026
([#246](https://github.com/wasichai/elearning-backend/pull/246), aún sin fusionar) y mantiene [ADR-0005](0005-lms-en-el-registro-de-wasichai-ui.md)

## Contexto

- El tema Quipu ya estaba en la interfaz: tokens, hoja de controles y modo lectura (ADR-0026, propuesta). Faltaba decidir cómo
  se componen las pantallas. La auditoría de
  [`UX-UI-AUDIT.md`](https://github.com/wasichai/elearning-frontend/blob/ddafd75/docs/design-system/UX-UI-AUDIT.md) encontró
  jerarquía poco clara, estados solo con color, objetivos táctiles de 24–36 px y acciones principales lejos de la vista.
- La épica [elearning-frontend#126](https://github.com/wasichai/elearning-frontend/issues/126) rediseña las pantallas por
  prioridades (aprendizaje, docencia, gestión editorial, descubrimiento), sin tocar el backend ni los contratos y sin otra
  librería visual. Dirección y patrones:
  [`UX-UI-IMPLEMENTATION.md`](https://github.com/wasichai/elearning-frontend/blob/ddafd75/docs/design-system/UX-UI-IMPLEMENTATION.md).
- `@wasichai/core` y `@wasichai/ui` no cubren toda esa composición. Lo que se afirma de ellos aquí está comprobado en
  [v1.6.0](https://github.com/wasichai/wasichai-ui/releases/tag/v1.6.0). La interfaz sigue en 1.5.0 hasta
  [elearning-frontend#140](https://github.com/wasichai/elearning-frontend/issues/140).
- El menú lateral mezcla los grupos del LMS con «Datos» y «Administración» de core, y a un estudiante le salen entradas que no le
  sirven (W7 de la auditoría).

## Decisión

1. **Tema de app sobre el contrato de `@wasichai/ui`, sin otra librería.** `Button`, `Card`, `Dialog`, `Tabs`, `Select`, `Table`,
   `Badge` y `Alert` son los de `@wasichai/ui`, y `EmptyState` y `LoadingState` los de `@wasichai/core`. No se crean equivalentes ni
   se cambian colores, temas o radios: este ADR decide la composición.
2. **Las piezas compartidas de composición viven en `src/shared` de la interfaz**, sobre esos componentes:

   | Pieza                        | Para qué                                                                     |
   | ---------------------------- | ---------------------------------------------------------------------------- |
   | `ScreenHeader`, `ScreenBody` | Cabecera y cuerpo con el mismo contenedor (`reading`, `content`, `wide`)     |
   | `Breadcrumbs`                | Ruta cuando hay más de un nivel                                              |
   | `SectionTabs`                | Secciones de un recurso, por URL, con `aria-current`                         |
   | `Section`                    | Título de sección, descripción y acción                                      |
   | `Stat`, `StatList`           | Cifra con etiqueta, como lista de definiciones                               |
   | `Skeleton`                   | Carga con la geometría real, respetando el movimiento reducido               |
   | `Notice`                     | `Alert` con icono y fondo; conserva su `role`                                |
   | `StatusBadge`                | Estados con icono y palabra                                                  |
   | `CordBanner`, `QuipuMark`    | La banda de cordones de un curso, generada de su slug, y la marca            |
   | `ProblemState`               | Error con icono, qué hacer y su acción                                       |
   | `VersionDiffSection`         | Comparación de versiones, compartida por el estudio y la bandeja de revisión |
   | `PublicLayout`               | Marco público, con la entrada a la cuenta                                    |

   Lo que solo usa un módulo se queda en ese módulo.

3. **`lucide-react` como dependencia directa, en la misma versión que core** (1.61.0; `@wasichai/ui` la fija y `@wasichai/core`
   pide `^1.61.0`). No añade otra familia: es la que ya llega con core, declarada para no depender de que el gestor de paquetes
   la deje accesible. A 16 px en metadatos y 20 px en acciones, con `aria-hidden` cuando acompaña a una palabra, y sin emojis.
4. **Principios que se aplican a cada pantalla:**
   - **Solo datos reales.** Lo que el contrato no da no se muestra ni se inventa: rachas, puntos, valoraciones, precios, fotos u
     «horas restantes». Un dato derivado se deriva de forma exacta y se puede explicar.
   - **Una sola primaria.** Cada pantalla responde arriba a «¿qué hago ahora?». Si una zona ya tiene su acción, el pie no compite
     con ella.
   - **El estado nunca es solo un color.** `StatusBadge`, nudos y barras llevan siempre su palabra o su cifra, y los avisos, su
     icono.
   - **Una jerarquía, no una caja por dato.** Secciones con título y filas separadas por `border`; tarjeta solo para lo que es un
     objeto. El aula es mobile-first y sosegada; docencia y edición aprovechan el ancho.
5. **Una sola `WasichaiApp` también para la sección de administración, manteniendo ADR-0005.** La sección es un estado del menú,
   resuelto con `shellNav`, y «Inicio» depende de `homeRoute`, los dos de `@wasichai/core` 1.6.0
   ([wasichai-ui#119](https://github.com/wasichai/wasichai-ui/issues/119) y
   [#120](https://github.com/wasichai/wasichai-ui/issues/120)). Deciden con `GET /auth/me/permissions`, nunca con un nombre de rol,
   y son cortesía: el servidor sigue decidiendo el acceso. No cambia ninguna URL. Está decidido y pendiente de implementar
   ([elearning-frontend#140](https://github.com/wasichai/elearning-frontend/issues/140),
   [#141](https://github.com/wasichai/elearning-frontend/issues/141) y
   [#142](https://github.com/wasichai/elearning-frontend/issues/142)).
6. **Lo que Wasichai no ofrece se pide y no se parchea.** Mientras llega, se usa lo que hay o una pieza propia que se retira
   después. Pedidos abiertos en wasichai-ui:
   [#122](https://github.com/wasichai/wasichai-ui/issues/122) (`data-slot` de los botones de cerrar),
   [#123](https://github.com/wasichai/wasichai-ui/issues/123) (`shellChrome`),
   [#124](https://github.com/wasichai/wasichai-ui/issues/124) (`PageHeader`),
   [#125](https://github.com/wasichai/wasichai-ui/issues/125) (`EmptyState`),
   [#126](https://github.com/wasichai/wasichai-ui/issues/126) (logotipo),
   [#127](https://github.com/wasichai/wasichai-ui/issues/127) (`cn()`),
   [#128](https://github.com/wasichai/wasichai-ui/issues/128) (router de datos, para avisar antes de salir),
   [#129](https://github.com/wasichai/wasichai-ui/issues/129) (`ReasonDialog`) y
   [#130](https://github.com/wasichai/wasichai-ui/issues/130) (icono en `Alert`).

## Alternativas consideradas

- **Una librería de componentes más, o traer la hoja de las vistas previas de Quipu.** Rechazada: dos sistemas visuales en paralelo
  a `@wasichai/ui`, en contra de ADR-0005 y de «Wasichai primero».
- **Parchear o copiar `AppShell` y `PageHeader`.** Rechazada: «Wasichai primero» y cada subida de versión sería un conflicto. Las
  piezas de `src/shared` componen sobre ellos, no los sustituyen.
- **Dos apps y dos routers para separar la administración, como `srtm-ui`.** Rechazada: ADR-0005 ya descartó ese patrón, las rutas
  de core ya empiezan por `admin/` (con `basename: '/admin'` quedarían en `/admin/admin/…`), y el estudio enlaza a `/data/objects/…`
  para que un creador edite lecciones.
- **Un paquete propio de componentes de diseño.** Rechazada: no hay un segundo usuario (regla 8 de `CLAUDE.md`).
- **Importar `lucide-react` sin declararlo, por llegar con core.** Rechazada: una dependencia transitiva puede dejar de estar
  accesible sin aviso. Declararla no añade nada al paquete resultante.
- **Hacer a la vez los cambios de backend que el rediseño echa en falta.** Rechazada: la épica no toca contratos, y cada uno pide
  su propio análisis. Se listan abajo.

## Consecuencias

- `ScreenHeader`, `Notice` y las dos guardas de salida de la interfaz son deuda con fecha de salida: se retiran cuando Wasichai
  atienda #124, #130 y #128. Hasta entonces, «atrás» del navegador pierde lo escrito sin avisar en corrección, asistencia y revisión,
  y en docencia también el menú del shell.
- `tailwind-merge` toma las escalas de texto de la app por colores, así que esas utilidades solo van en elementos propios hasta #127.
- Subir `@wasichai/*` obliga a subir `lucide-react` con ellos para mantener una sola versión.
- El rediseño no cambió ninguna API, así que este repositorio solo registra la decisión.

### Lo que el rediseño pide al backend y no se hizo

Nada de esto se implementa con este ADR. Cada punto pide su issue y, si cambia el modelo o el contrato, su decisión.

- **Enlace de repaso de una pregunta a su lección.** Una pregunta no dice qué lección la explica, así que el resultado de la
  evaluación no enlaza al repaso que pide la guía `PantallaResultadoEvaluacion`.
- **Nombres de autor y revisor.** `authorId`, `reviewerId` y los `authorId` de decisiones y notas son identificadores: estudio y
  revisión no dicen quién, y el historial de decisiones no muestra a quien decidió.
- **Historial de estados de una versión.** `StudioVersion` trae `status` y `updatedAt`, y `decisions` no cubre cada transición. La
  línea de estados se deriva del estado actual, sin quién ni cuándo, y una versión retirada no dice por dónde pasó.
- **Endpoint agregado para los paneles de estudiante y docente.** Hoy hay una lectura por matrícula o por edición: el aula y las
  sesiones del estudiante, la cola y las sesiones del docente. Son hasta dos peticiones por elemento, y el panel docente se limita a
  8 ediciones (`MAX_PANEL_OFFERINGS`) y avisa cuando aplica el tope.
- **Gravedad en los problemas de importación.** `problems` trae `kind`, `origin`, `line` y `detail`. La interfaz deriva la gravedad
  de `kind` con una tabla propia (`SEVERITY` en `ImportPage.tsx`), que puede quedar desfasada respecto del analizador.
- **Listado de trabajos de importación.** Solo existe `GET /api/lms/studio/import/{id}` y la interfaz guarda el id en memoria: al
  recargar la página se pierde de vista el trabajo en curso.
- **Crear un borrador vacío en un curso sin versiones.** `POST /api/lms/studio/courses/{courseId}/versions` responde `400` («this
  course has no versions», `NewVersionService`). El curso no aparece en «Mis versiones», y la primera versión llega importando o
  desde los registros de `version_curso`
  ([elearning-frontend#144](https://github.com/wasichai/elearning-frontend/pull/144) lo anota para
  [#141](https://github.com/wasichai/elearning-frontend/issues/141)).
