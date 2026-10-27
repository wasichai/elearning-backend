# ADR-0011: El catálogo público se lee de una proyección, no de los registros

**Estado**: aceptada · 2026-10-27 · reemplaza la parte de lectura de [ADR-0006](0006-catalogo-publico-con-cadena-de-seguridad-acotada.md)

## Contexto

ADR-0006 preveía que la ruta anónima `GET /api/lms/public/**` leyera los registros con `RecordService.asPlatform` del
tenant público. No funciona: `asPlatform` comprueba que no está sirviendo una petición y lanza `IllegalStateException`
si el contexto Reactor lleva el `SecurityContext`, que Spring Security agrega a **toda** petición que pasa por una cadena,
anónima incluida (`wasichai-core/src/main/kotlin/wasichai/core/platform/Background.kt`, ADR-039 de Wasichai). Es una
barrera deliberada de la plataforma, no un detalle a sortear.

Lo encontraron, por separado, el análisis de arquitectura (E1.3) y el de seguridad (E1.6), y se confirmó leyendo el
código.

## Decisión

**El catálogo público es una proyección de solo lectura en una tabla fija propia, `lms.catalogo_publicado`, que
mantiene un trabajo en segundo plano. La ruta pública solo lee esa tabla.**

- **Qué contiene.** Una fila por curso con una versión `PUBLICADA` en la organización pública configurada
  (`lms.public.organization`): columnas para filtrar y ordenar (`slug`, `titulo`, `categoria`, `nivel`, `modalidad`,
  `proxima_fecha_inicio`, `semanas`, horas), y la ficha completa ya armada (temario, ediciones abiertas o próximas,
  docentes con su nombre público, carga y créditos **propuestos**) en una columna `jsonb`, más un `hash` del contenido.
  Nada que no sea público entra: ni borradores, ni versiones en revisión, ni correos, ni `usuario_id`.
- **Quién la escribe.** Un trabajo `@Scheduled` (intervalo `lms.catalog.refresh`, 30 s por defecto, y una vez al
  arrancar) que toma un `ClusterLock`, lee con `RecordService.asPlatform(organizacionPublica)` —en segundo plano, donde
  está permitido—, arma las fichas con el dominio (`workload` calcula carga y créditos) y sincroniza la tabla: inserta o
  actualiza solo las filas cuyo `hash` cambió y borra las de cursos que dejaron de tener versión publicada. Una
  ejecución que no encuentra cambios no escribe.
- **Quién la lee.** El paquete `catalog` con `DatabaseClient` y valores ligados. La cadena `@Order(-1)` de ADR-0006 se
  mantiene igual: solo `GET`, sin sesión.
- **Migración.** `ModuleMigration("lms", "classpath:db/lms", 200)` con historial propio (`flyway_history_lms`); es la
  primera tabla fija del proyecto y queda dentro de lo que ADR-0004 permite (lo que la metadata no alcanza: una lectura
  anónima).

## Alternativas consideradas

- **Principal de catálogo** (la cadena pública fija como principal una cuenta de servicio con `READ` y un
  `RecordReadScope` limitado a lo publicado). Rechazada: exige construir un `Jwt` en proceso con piezas internas de
  Wasichai y convierte cada petición anónima en una petición autenticada de una cuenta real; un error de alcance
  expondría todo lo que esa cuenta puede leer.
- **Escribir la proyección al publicar, en la misma transacción.** Rechazada como único mecanismo: las ediciones,
  docentes y categorías cambian después de publicar, y los datos de demostración se cargan por la API genérica de
  registros, que no pasa por un comando de publicación. Puede añadirse más adelante un `RecordChangeListener` que
  adelante el refresco, si la latencia molesta.
- **Leer las tablas de datos de Wasichai con SQL directo.** Rechazada: depende del almacenamiento interno de la
  plataforma (una tabla física por Custom Object) y salta sus permisos y su auditoría.
- **Ejecutar la lectura en otro `CoroutineScope` para que no parezca petición.** Rechazada: es exactamente el escape que
  la barrera de Wasichai existe para impedir.

## Consecuencias

- **La barrera de seguridad es física**: un error en el endpoint público no puede filtrar un borrador, porque los
  borradores no están en la tabla. Las pruebas negativas (borrador → 404, otra organización → 404) siguen siendo
  obligatorias.
- **Latencia de publicación**: un cambio aparece en el catálogo dentro del intervalo de refresco (30 s por defecto).
  Se documenta en la ficha de operación; una prueba de integración fuerza el refresco en vez de esperar.
- Lectura barata y paginable con índices propios; la búsqueda `q` es `ILIKE` sobre título y resumen mientras el volumen
  lo permita (sin motor de búsqueda, como pide la especificación).
- Hay que mantener el trabajo y su tabla: con varias réplicas, solo una refresca a la vez (`ClusterLock`).
- La proyección se puede reconstruir entera en cualquier momento: no es fuente de verdad y no necesita copia de
  seguridad propia.
