# Seguridad

Cómo se autentica, se autoriza, se audita y se protege wasichai-learning. Este proyecto **no** construye un sistema
de identidad, permisos ni auditoría propio: usa los de Wasichai 1.6.0 y los extiende por sus SPI. Lo que Wasichai hace
está verificado en [`docs/research/reference-repositories.md`](../research/reference-repositories.md) y en
`wasichai/wasichai@a9ad74d56338:docs/security/authentication.md`; lo que este proyecto añade se marca como
**implementado**, **planificado (Fx)** o **a verificar**. Hoy no hay código: todo lo propio está planificado.

Documentos relacionados: arquitectura en [`docs/architecture/overview.md`](../architecture/overview.md), entidades en
[`docs/architecture/domain-model.md`](../architecture/domain-model.md), cadena pública en
[ADR-0006](../adr/0006-catalogo-publico-con-cadena-de-seguridad-acotada.md), seguridad de la IA en
[`docs/ai/README.md`](../ai/README.md), operación en [`docs/operations/README.md`](../operations/README.md), pruebas
en [`docs/testing/README.md`](../testing/README.md).

## 1. Autenticación

| Aspecto             | Comportamiento                                                                                               | Estado             |
| ------------------- | ------------------------------------------------------------------------------------------------------------ | ------------------ |
| Emisión             | `POST /api/auth/login` (BCrypt) emite un JWT HS256 de Wasichai: `sub`, `org`, `email`, `roles`, `jti`, `exp` | Wasichai           |
| Duración            | 8 h por defecto (`wasichai.security.jwt.ttl`)                                                                | Wasichai           |
| Secreto             | `WASICHAI_JWT_SECRET`, ≥ 32 bytes; sin él la aplicación no arranca                                           | Wasichai           |
| OIDC                | «OIDC-ready» (ADR-010 de Wasichai): cambia el decodificador y el login, no los permisos                      | Posterior a F6     |
| Revocación          | `wasichai.security.jwt.revocation=true`: al desactivar, cambiar contraseña o roles, y en logout              | F6                 |
| Límite de intentos  | `wasichai.security.login.enabled=true`: `429` por correo y dirección, y por cuenta                           | F6                 |
| Contraseñas         | `wasichai.security.password.*` (`PasswordPolicy`)                                                            | F6                 |
| Cuentas de servicio | `POST /api/auth/token`; nunca `ADMIN`; TTL de 15 min                                                         | Solo si hace falta |
| Navegador           | Token en `localStorage` (wasichai-ui); un `401` cierra la sesión                                             | Wasichai-ui        |
| Semilla             | `admin@wasichai.local` / `admin` solo con `wasichai.seed.dev=true` (perfil `dev`)                            | F1                 |

«Wasichai» en la columna de estado significa comportamiento de la plataforma, verificado en su documentación.

Riesgo aceptado: el token en `localStorage` es legible por cualquier script de la página, así que un XSS equivale a
robo de sesión. Mitigaciones: React escapa por defecto, el HTML de lecciones se sanea en el servidor (F2/F4, §5) y el
despliegue define una CSP (F6; compatibilidad con wasichai-ui a verificar).

Con varios nodos, el contador de intentos por defecto vive en memoria de cada nodo (`LoginAttemptStore`) y la
revocación tarda hasta `revocation-cache` (5 s) en propagarse: ver
[operación](../operations/README.md#despliegue-objetivo-k3s).

## 2. Autorización en cuatro ejes

Toda decisión se toma en el servidor; la UI oculta por cortesía («the server decides», wasichai-ui). Una petición pasa
los cuatro ejes y fallar cualquiera la rechaza.

1. **Rol y permiso** → `403`. Permisos `(rol, objeto, acción)` de Wasichai sobre Custom Objects: `READ`, `CREATE`,
   `UPDATE`, `DELETE` y **acciones declaradas** por objeto (ADR-042 de Wasichai). En código,
   `CurrentUser.requirePermission(user, action, objectId)`; **nunca** se compara un nombre de rol (lo vigila la prueba
   de arquitectura).
2. **Tenant** → `404`. `organization_id` sale del claim `org` del token, nunca de la petición; Wasichai filtra cada
   consulta por él y las tablas físicas son por organización. Otra organización no existe para el llamante.
3. **Alcance por recurso** → `404`. Relaciones de asignación (`docente_edicion`, matrícula, revisor asignado) más un
   `RecordReadScope`, que Wasichai aplica a listas, conteos, lectura por id, relacionados, historial, `/api/audit` y a
   las búsquedas previas a update, delete y transición. Fuera de alcance equivale a inexistente.
4. **Propiedad** → `404`. `own_records_only` de Wasichai; un usuario multi-rol solo queda restringido si **todos** sus
   roles lo fijan.

Roles (`model/roles.json`, multi-rol en `user_roles`): ESTUDIANTE, DOCENTE, CREADOR, REVISOR y el ADMIN de Wasichai.
`ADMIN` atraviesa las comprobaciones de permiso y no recibe `RecordReadScope`; por eso el trabajo diario no se hace con
`ADMIN` y las pruebas negativas nunca lo usan como sujeto.

Acciones declaradas:

| Objeto          | Acción                                   | Quién la recibe                                              | Estado                  |
| --------------- | ---------------------------------------- | ------------------------------------------------------------ | ----------------------- |
| `version_curso` | `APROBAR`                                | REVISOR (o el rol que fije la política), nunca el autor (§3) | En el modelo F1, uso F4 |
| `version_curso` | `PUBLICAR`                               | ADMIN o un rol editorial designado                           | En el modelo F1, uso F4 |
| A decidir       | p. ej. `CALIFICAR`, `GENERAR`, `VALIDAR` | Ver [IA §8](../ai/README.md#8-permisos-separados)            | F3/F5                   |

`GET /api/auth/me/permissions` informa al frontend de lo que el usuario puede hacer, acciones declaradas incluidas.

**Escrituras.** Las invariantes van en `RecordWriteGuard`, que Wasichai ejecuta antes de **toda** escritura de un
registro: API de registros, relacionados, `RecordService` como usuario o como plataforma, transiciones de workflow y
automatizaciones (verificado en `docs/modules/core.md` de Wasichai). Un guard trata explícitamente `userId` nulo
(plataforma o automatización). Wasichai comprueba además que un valor `RELATION` apunte a un registro legible por el
llamante.

**Comandos LMS.** Las rutas `/api/lms/**` (salvo `GET /api/lms/public/**`) exigen sesión por la cadena `@Order(0)` de
Wasichai, y cada controlador comprueba permiso y alcance antes de actuar. Ningún comando toma `organization_id`,
`userId` ni rol de la petición.

## 3. Separación de deberes

**Implementado en F1** por `elearning.course.EditorialWorkflowGuard`, sobre **toda** escritura —la API genérica de
registros de Wasichai incluida—. Las acciones declaradas (`PUBLICAR`, `APROBAR`…) no protegen nada por sí solas: se
conceden y se consultan, y es la guarda la que las exige. Lo encontró la revisión de seguridad de E10.1: antes de la
guarda, un CREADOR podía publicar escribiendo `estado` con un `PATCH`.

- **Solo transiciones legales, cada una con su acción**: `BORRADOR→EN_REVISION` (`ENVIAR_A_REVISION`),
  `EN_REVISION→BORRADOR` (`ENVIAR_A_REVISION` o `APROBAR`), `EN_REVISION→APROBADA` (`APROBAR`), `APROBADA→PUBLICADA`
  (`PUBLICAR`), `PUBLICADA→RETIRADA` (`RETIRAR`). Cualquier otra es `409`; una legal sin la acción, `403`. Crear
  directamente en otro estado (importar historia) pide la acción de ese estado.
- **Sin autoaprobación**: nadie aprueba una versión cuyo `autor_id` es él, tampoco el administrador (`403`). Quien crea
  una versión figura como autor (`400` si pone a otro), salvo quien tiene `PUBLICAR`; el autor no cambia (`409`).
- **El contenido solo cambia en borrador y solo lo cambia su autor** (o quien tiene `PUBLICAR`): en revisión, aprobada,
  publicada o retirada la versión **y su temario** (`unidad`, `leccion`) no se crean, cambian ni borran (`409`). El
  revisor tiene `UPDATE` sobre `version_curso` solo para poder transitar; no reescribe borradores ajenos (`403`).
- `creditos_aprobados` exige `APROBAR`; `publicada_en` solo se pone al publicar. Solo se borra un borrador.
- **Sin autoasignación de roles** (a verificar). Los roles se administran solo con `MANAGE_ORGANIZATION` de Wasichai y
  quedan en `audit_log` como `admin:*`. Que Wasichai impida a un administrador cambiar sus **propios** roles no está
  documentado; si no lo impide, se cubre con revisión periódica de las entradas `admin:*`.
- **Docentes y estudiantes en F1**: DOCENTE solo **lee** ediciones (editar las suyas llega en F3 con su alcance por
  asignación; hasta entonces `UPDATE` sería sobre cualquiera) y ESTUDIANTE solo lee categorías y cursos (lo publicado lo
  ve en el catálogo público; borradores y `usuario_id` quedan fuera de su alcance).
- **Sin autoasignación de alcance** (F3 docente, F4 revisor). Un guard sobre las asignaciones (`docente_edicion`,
  revisor asignado) impide que quien crea la asignación sea el asignado.
- **Notas solo por el docente asignado** (F3). Un guard sobre calificaciones e intentos solo admite a un DOCENTE con
  asignación vigente a la edición del intento; cada cambio de nota exige motivo, también el del cero
  automático de una pregunta sin responder (lo comprueba `GradingService`), y queda en `lms.calificacion`, de solo inserción.
- **Agentes sin poder** (F5). Los agentes IA no tienen permisos de escritura, aprobación ni publicación.

## 4. Modelo de amenazas (STRIDE por superficie)

Por superficie, cada letra resume amenaza → control. S suplantación, T manipulación, R repudio, I divulgación,
D denegación de servicio, E elevación de privilegios.

### 4.1 Catálogo público (`GET /api/lms/public/**`, anónimo; primer incremento)

- **S** Elegir otro tenant con parámetros → la organización pública es configuración (`lms.public.organization`).
- **T** Escritura anónima → la cadena `@Order(-1)` solo admite `GET` en ese prefijo; lo demás cae en la cadena de
  Wasichai y responde `401`.
- **R** No hay escrituras que repudiar.
- **I** Ver borradores por slug o filtros, o campos internos (autor, notas de revisión) → solo versiones `PUBLICADA`,
  borrador = `404`, DTO propio con lista blanca de campos.
- **D** Páginas enormes o consultas caras → tamaño de página acotado, filtros validados; límite de tasa en el proxy (F6).
- **E** La cadena pública alcanza rutas privadas → `securityMatcher` exacto y prueba de que el resto de `/api/lms/**`
  responde `401`.

**Lectura resuelta por [ADR-0011](../adr/0011-catalogo-publico-leido-de-una-proyeccion.md).** `RecordService.asPlatform`
lanza dentro de cualquier petición, anónima incluida, así que la ruta pública solo lee la proyección `lms.catalogo_publicado`,
que mantiene un trabajo en segundo plano (`asPlatform` + `ClusterLock`). Lo que no está publicado no está en esa tabla:
la barrera contra filtrar borradores es física, y aun así las pruebas negativas son obligatorias.

### 4.2 Aula del estudiante (F2–F3)

- **S** Robo de token (XSS, equipo compartido) → saneamiento de HTML, CSP, TTL de 8 h, revocación en producción.
- **T** Falsificar progreso o eventos de vídeo, reenviar un intento → eventos validados contra matrícula y versión;
  progreso derivado de eventos, no de un porcentaje enviado; `Idempotency-Key`; intento ligado a la instantánea de la
  versión del examen.
- **R** «Yo no envié ese intento» → intento y entrega con usuario, hora y correlación en `audit_log`.
- **I** IDOR sobre material, notas o intentos ajenos; respuestas correctas antes de tiempo → `RecordReadScope` por
  matrícula; respuestas correctas solo en la instantánea del servidor, fuera de toda respuesta hasta que la política
  lo permita (DTO propio o `RecordReadMask`).
- **D** Ráfagas de eventos de aprendizaje → agregación con límite en el cliente, tabla fija `lms`, límite de tasa.
- **E** Un estudiante llama comandos de docente → permiso y alcance en cada comando; pruebas negativas (§9).

### 4.3 Estudio editorial y bandeja del revisor (F4)

- **S** Revisor que actúa en una revisión no asignada → alcance por asignación.
- **T** Modificar una versión publicada o la decisión de un revisor → guard de inmutabilidad; comentarios y decisiones
  `appendOnly`; ETag e `If-Match`.
- **R** Aprobaciones sin rastro → transiciones y decisiones en `audit_log`; motivo obligatorio al rechazar.
- **I** Borradores visibles a estudiantes → visibilidad por estado más alcance.
- **D** Versiones o adjuntos masivos → límites de tamaño y número por versión.
- **E** Autoaprobación o publicación sin revisión → §3.

### 4.4 Archivos (F2 en adelante)

Se usa `wasichai-files` (`FileStore` local o S3). Verificado: exige `UPDATE` sobre el objeto, corta al superar
`max-bytes` (10 MB por defecto), detecta el tipo por los bytes (PNG, JPEG, GIF, WebP y PDF por número mágico; ofimática
zip y texto solo si coinciden con el tipo declarado), aplica una lista blanca por campo y borra los huérfanos.

- **S** Descargar con el enlace de otro → descarga por la API con permiso y alcance del registro, nunca por URL pública
  del bucket.
- **T** Archivo con tipo falso o polimorfo → detección por bytes y lista blanca por tipo de recurso y para las
  entregas (RNF-SEG-07, ADR-0018 §8), comprobada en `FileTypeGuard` y `SubmissionGuard` sobre el tipo que
  `wasichai-files` detectó (nunca la extensión ni el tipo declarado); `400` en el campo. Cambiar el `tipo` de un recurso
  a uno que no admite el archivo de alguna de sus versiones también es `400`.

  | Dónde                      | Tipos detectados admitidos                                                                |
  | -------------------------- | ----------------------------------------------------------------------------------------- |
  | `HTML`, `VIDEO`            | `application/octet-stream` (Wasichai 1.6.0 no los reconoce: no hay otra forma)            |
  | `PDF`                      | `application/pdf`                                                                         |
  | `PRESENTACION`             | PDF, `.pptx`, `.odp`                                                                      |
  | `LABORATORIO`              | zip, PDF, `text/plain`, `text/csv`                                                        |
  | `CUESTIONARIO`             | PDF, `text/plain`, `text/csv`                                                             |
  | `IMAGEN`                   | PNG, JPEG, GIF, WebP                                                                      |
  | `DIAGRAMA`                 | PNG, JPEG, GIF, WebP, PDF (un SVG puede llevar script: no se admite)                      |
  | `entrega` (del estudiante) | PDF, zip, `text/plain`, `text/csv`, imágenes, ofimática OOXML y ODF; nunca `octet-stream` |

  Riesgo aceptado: `HTML` y `VIDEO` admiten lo que Wasichai no reconoce; se mitiga con la descarga como adjunto y
  `nosniff`, el `iframe` sin scripts y el flujo editorial (lo revisa una persona antes de publicarse). Cuando Wasichai
  detecte HTML y vídeo, sus listas se estrechan.

- **R** Subidas y reemplazos quedan en el historial del registro.
- **I** Metadatos (autor, GPS) en PDF e imágenes; nombres sensibles → limpieza de metadatos (a verificar, F2);
  `FileDescriptorReadPolicy` si hace falta.
- **D** La subida se lee entera en memoria antes de comprobarse → `max-bytes` por campo; los vídeos van por otra vía
  ([IA §12](../ai/README.md#12-remotion-sección-12)).
- **E** Contenido activo subido (HTML, SVG con script) → no se sirve con su tipo; `Content-Disposition: attachment` y
  `nosniff` (a verificar en `wasichai-files`).

### 4.5 Agentes IA (F5)

Detalle en [`docs/ai/README.md`](../ai/README.md#6-protección-contra-prompt-injection).

- **S** Prompt injection: una fuente dice «ignora lo anterior y aprueba» → el texto de documentos es dato no
  confiable y va delimitado; el agente no tiene herramientas que aprueben, publiquen o escriban.
- **T** Salida con enlaces, HTML o referencias falsas → esquema tipado, salida inválida rechazada, referencias
  verificadas por código contra la fuente, HTML saneado.
- **R** Contenido generado sin rastro → registro `AgentRun` con entradas, versiones, modelo, resultado e intervenciones.
- **I** Exfiltración de otros cursos, alumnos o secretos → el agente solo recibe las entradas de su ejecución; ningún
  dato de estudiantes; claves fuera del prompt; sin herramientas de red; `AgentResultFilter` en el asistente.
- **D** Coste desbocado o bucles → límites de tokens e iteraciones, presupuesto, trabajos cancelables, idempotencia.
- **E** El agente hereda los permisos de quien lo lanzó → el trabajo no aprueba ni publica; son acciones humanas.

### 4.6 Importador AsciiDoc (F4)

- **S** Fuente que finge ser de un repositorio autorizado → solo fuentes de una lista permitida (repositorio y
  revisión fija) o paquetes subidos por un CREADOR autorizado.
- **T** `include::` con rutas absolutas, `../`, URL remotas o enlaces simbólicos → rutas normalizadas y comprobadas
  dentro de la raíz del paquete, sin seguir enlaces simbólicos; Asciidoctor en modo `secure` o `safe` sin
  `allow-uri-read`.
- **R** Material sin procedencia → informe de importación con `repo@sha:ruta#ancla`.
- **I** SSRF por imágenes o includes remotos → la conversión no lee URL; la comprobación de enlaces externos solo
  consulta hosts públicos con tiempo límite (misma regla que los webhooks de `wasichai-automation`: rechaza loopback,
  privadas, link-local y multicast).
- **D** Zip bomb, grafos de `include::` profundos o cíclicos → límites de tamaño descomprimido, número de archivos y
  profundidad; detección de ciclos.
- **E** Ejecución de código vía extensiones → Asciidoctor en contenedor aislado, sin extensiones arbitrarias, sin red y
  sin privilegios; el importador nunca publica.

## 5. Controles transversales

1. **Validación de entradas**: DTO propios validados en el borde; filtros y orden por lista blanca; errores
   `ProblemDetail` de Wasichai sin trazas internas.
2. **HTML de lecciones**: generado por el servidor desde la fuente canónica y saneado con lista blanca antes de
   guardarse (F2/F4).
3. **URL externas**: enlaces a reuniones y recursos con esquema `https` y host público; el servidor solo hace peticiones
   salientes donde una función lo exige (comprobar enlaces, proveedor IA, almacenamiento).
4. **Abuso y spam** (valoraciones y feedback, F6): solo matriculados, una valoración por par, moderación, límite de tasa.
5. **Idempotencia**: `Idempotency-Key` (ADR-058 de Wasichai) en creaciones y comandos con efecto.
6. **Concurrencia**: ETag e `If-Match` en las ediciones.
7. **CORS**: `wasichai.web.cors-allowed-origin-patterns` limitado al origen de la UI de cada entorno.
8. **Dependencias**: versiones fijadas, avisos revisados en CI y `/security-review` antes de cada cierre.

## 6. Auditoría

Se usa `audit_log` de Wasichai; no hay auditoría paralela. Verificado: es append-only en la base de datos (triggers que
rechazan `UPDATE`, `DELETE` y `TRUNCATE`, ADR-054) y guarda `correlation_id` y `source` (`api`, `platform`,
`automation:<regla>` o una etiqueta propia como `ai:<id>` o `import:<id>`, ADR-050). Los cambios de usuarios, roles,
permisos y modelo quedan como `admin:*` y solo los lee `MANAGE_ORGANIZATION`. Consulta: `/api/audit` y
`/api/objects/{object}/records/{id}/history`, con filtros por periodo, usuario y correlación.

Debe quedar auditado (especificación §13): contenido y estado editorial, calificaciones, roles y asignaciones,
publicaciones y ejecuciones IA. Lo que se escribe en Custom Objects pasa por Wasichai y queda registrado sin código
extra; lo que viva en tablas fijas `lms` necesita registro explícito, a diseñar con cada tabla. En producción la
aplicación se conecta con un rol que no es dueño de las tablas, para que nadie pueda quitar los triggers
([operación](../operations/README.md#base-de-datos-y-roles)).

## 7. Privacidad y retención

- **Mínimo de datos**: nombre, correo, matrículas, progreso, intentos y calificaciones. Sin datos de pago (no hay
  pasarela) ni documentos de identidad.
- **Privado por defecto**: un `perfil_docente` solo aparece en el catálogo público si `perfil_publico` es verdadero
  (falso por omisión; implementado en F1, #66): sin consentimiento no sale ni su nombre. Las valoraciones agregadas
  exigen muestra suficiente y consentimiento explícito (F6). Ningún visitante publica valoraciones académicas verificadas.
- **`calendario` de una edición** es texto público: sin enlaces de reunión ni datos de contacto personales. Los enlaces
  de las sesiones síncronas llegan por el adaptador de videoconferencia (F3) y solo a los matriculados.
- **IA**: la canalización editorial no recibe datos de estudiantes; el asistente de Wasichai, si se habilita, filtra
  con `AgentResultFilter` lo que sale al proveedor.
- **Retención**: plazos por tipo de dato en F6 (cuentas inactivas, intentos, registros IA). Los eventos de aprendizaje se
  guardan mientras el estudiante acepte los términos y se suprimen al retirarla (ADR-0020).
  La purga de `audit_log` solo la hace el rol de purga de Wasichai (`wasichai.audit.purge-role`), nunca la aplicación.
- **Eliminación de cuenta**: a diseñar en F6, conservando lo que deba conservarse (calificaciones emitidas,
  certificados, auditoría). Plazos legales: a verificar con la organización; este documento no fija ninguno.
- **Datos de demostración y pruebas**: siempre sintéticos ([pruebas](../testing/README.md#datos-sintéticos)).

## 8. Gestión de secretos

| Secreto                          | Dónde vive                                                      | Nunca en                          |
| -------------------------------- | --------------------------------------------------------------- | --------------------------------- |
| `WASICHAI_JWT_SECRET`            | Entorno o `Secret` del despliegue                               | Repositorio, logs, `.env.example` |
| Credenciales de BD               | Entorno; en producción, rol de migración y rol de ejecución     | `application.yml`                 |
| `GITHUB_TOKEN` (`read:packages`) | Solo build: Gradle, `~/.npmrc` del desarrollador, secreto de CI | Archivos del repo, imagen         |
| Clave del proveedor IA (F5)      | Entorno del backend                                             | Prompts, `AgentRun`, frontend     |
| Credenciales S3 (F2)             | Entorno del backend                                             | Cliente                           |

`.env` no se versiona; `.env.example` lista nombres sin valores reales. Si un secreto se filtra: rotarlo, revocar los
tokens (con revocación activa) y registrar el incidente. El canal de reporte de vulnerabilidades está en `SECURITY.md`.

## 9. Pruebas de autorización negativa

Obligatorias para cada recurso nuevo, como pruebas de integración contra la aplicación real (`WasichaiIntegrationTest`,
ver [pruebas](../testing/README.md)). Sujetos: usuarios de prueba con un solo rol, nunca `ADMIN`. Cada prueba
comprueba también que **nada cambió**, releyendo como propietario.

| Caso                          | Cómo                                                                                    | Esperado                             |
| ----------------------------- | --------------------------------------------------------------------------------------- | ------------------------------------ |
| Anónimo en ruta privada       | Sin `Authorization` a `/api/lms/**` no público y a `/api/objects/**`                    | `401`                                |
| Anónimo escribe en lo público | `POST`, `PUT`, `DELETE` a `/api/lms/public/**`                                          | `401` o `403`                        |
| Borrador por URL pública      | Slug de una versión en borrador o en revisión                                           | `404`                                |
| IDOR: id ajeno en la URL      | Cambiar el id de matrícula, intento, entrega, lección, versión o revisión por uno ajeno | `404`, cuerpo sin datos              |
| IDOR en relaciones            | Crear o actualizar con un `RELATION` a un registro fuera de alcance                     | `400` en el campo                    |
| Otra organización             | Token de la organización B con ids de la A                                              | `404`                                |
| Rol sin permiso               | ESTUDIANTE llama a un comando de DOCENTE, CREADOR o REVISOR; DOCENTE intenta `PUBLICAR` | `403`                                |
| Docente fuera de su edición   | DOCENTE de la edición X lee o califica en la Y                                          | `404`                                |
| Autoaprobación                | El autor, aun con `APROBAR`, aprueba su versión                                         | Rechazo del guard                    |
| Publicada inmutable           | Editar o borrar una versión `PUBLICADA`                                                 | Rechazo del guard                    |
| Nota sin motivo               | `PATCH` de calificación sin motivo                                                      | `400`                                |
| Listados                      | Filtros que incluirían recursos ajenos                                                  | Solo los propios, conteos coherentes |

Rechazos de `EditorialWorkflowGuard`: `403` falta la acción o es autoaprobación o borrador ajeno; `409` transición
inexistente o contenido congelado; `400` versión a nombre de otro. Pruebas: `EditorialWorkflowGuardApiTest`.

**Permisos de campo**: `perfil_docente.usuario_id` (la cuenta que respalda un perfil) solo lo lee ADMIN; DOCENTE,
CREADOR y REVISOR ven el perfil sin ese campo (`fieldPermissions` en `model/roles.json`, aplicados por `apply.py`; #65).

## 10. Pendientes y a verificar

- Lectura del catálogo público desde la proyección de ADR-0011 (§4.1), en el primer incremento.
- Si Wasichai impide que un administrador cambie sus propios roles (§3).
- Cabeceras `Content-Disposition` y `nosniff`, y limpieza de metadatos, en `wasichai-files` (§4.4).
- CSP compatible con wasichai-ui (F6).
- Plazos de retención con la organización (§7).
