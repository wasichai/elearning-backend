# ADR-0006: El catálogo público usa una cadena de seguridad acotada a `GET /api/lms/public/**`

**Estado**: aceptada · 2026-10-27

## Contexto

- El catálogo debe verse sin sesión: buscar, filtrar, ordenar y abrir la ficha de un curso publicado.
- La cadena de Wasichai es `@Order(0)` con `anyExchange().authenticated()` y `permitAll` solo para login, token y
  salud. Un módulo con cadena propia necesita `@Order` menor que cero para correr antes
  ([ADR-025 de Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/0025-extension-spis.md)).
- La API de registros de Wasichai siempre exige un usuario. Para leer sin uno existe
  `RecordService.asPlatform(organizationId) { … }`: dentro del bloque las consultas se limitan a esa organización y
  **no se aplica ningún permiso**
  ([ADR-039 de Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/0039-background-work-runs-as-the-platform-with-a-cluster-lock.md)).
- Un visitante no tiene organización: hay que decidir de qué tenant se lee.

## Decisión

- **Una `SecurityWebFilterChain` propia `@Order(-1)` con `securityMatcher` solo para `GET /api/lms/public/**`**, que
  permite el acceso anónimo. Cualquier otro método o ruta (incluido el resto de `/api/lms/**`) no coincide y cae en la
  cadena de Wasichai, que exige sesión.
- **El tenant es configuración**: `lms.public.organization`. El catálogo no acepta organización en la petición.
- **Lectura con `RecordService.asPlatform`** de ese tenant, filtrando en el servidor: solo versiones en estado
  `PUBLICADA`; un borrador responde `404` también por slug directo.
- **DTO propios**: la respuesta se construye campo a campo; nunca se serializa un `RecordResponse` ni campos internos
  (ids de usuario, estados de revisión, notas).
- **Límites**: tamaño de página máximo, longitud máxima del texto de búsqueda, orden solo por campos de una lista
  cerrada.

## Alternativas consideradas

- **Ampliar `permitAll` de la cadena de Wasichai.** Imposible sin bifurcar Wasichai; además mezclaría reglas.
- **Un usuario «anónimo» con rol de lectura.** Rechazado: un token compartido o emitido sin login es otra vía de
  autenticación, que la especificación prohíbe crear; y daría acceso a la API genérica de registros, no solo al catálogo.
- **Exportar el catálogo como sitio estático.** Rechazado ahora: el catálogo necesita filtros y fechas de edición vivas.
  Puede volver como caché en F6.

## Consecuencias

- Como `asPlatform` ignora permisos, **la única barrera es el código del paquete `catalog`**: el filtro por estado
  y el DTO. Las pruebas de integración lo cubren: borrador invisible por slug, otra organización invisible,
  `/api/lms/**` no público → 401, `POST` anónimo → 401/403.
- Riesgo de **enumeración**: los slugs son predecibles. Mitigación: un no publicado responde igual que uno inexistente
  (`404`), sin distinguir.
- Riesgo de **abuso** (búsquedas caras, raspado): límites de página y de consulta; sin limitación de tasa en F1.
  Planificado (F6): limitación de tasa en el proxy o en la aplicación, con mediciones.
- Un único tenant público por despliegue; varios catálogos públicos necesitarían otro ADR.
- La cadena propia es código de seguridad: todo cambio pasa por `/security-review`.
- A verificar en implementación: la firma exacta de `asPlatform` en 1.6.0 y que la cadena `@Order(-1)` no interfiere
  con CORS, cabeceras y manejo de errores `ProblemDetail` de Wasichai.
