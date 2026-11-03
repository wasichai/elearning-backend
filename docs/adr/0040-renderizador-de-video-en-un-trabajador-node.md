# ADR-0040: El vídeo se renderiza con Remotion en un trabajador Node que la cola llama por HTTP; el vídeo se guarda en `wasichai-files`

**Estado**: aceptada · 2026-11-03 · E37.1 ([#296](https://github.com/wasichai/elearning-backend/issues/296)); desarrolla
[ADR-0039](0039-remotion-como-motor-de-render-con-licencia-por-despliegue.md) y precisa
[ADR-0018](0018-fase-4-estudio-revision-e-importador.md) §4, [ADR-0022](0022-limites-y-ciclo-de-vida-de-los-trabajos.md)
y [ADR-0033](0033-fase-5-ia.md) §5

## Contexto

[ADR-0039](0039-remotion-como-motor-de-render-con-licencia-por-despliegue.md) eligió Remotion como motor de la Fase 5,
opcional, desactivado por defecto y en un programa Node separado, que solo recibe datos. Falta decidir cómo
([épica E37 #295](https://github.com/wasichai/elearning-backend/issues/295), RF-MED-01…04, [IA §12](../ai/README.md#12-remotion-sección-12)):
dónde vive el trabajador, qué recibe, cómo se entera de la cola, dónde queda el vídeo y cómo se valida.

Hechos comprobados el 2026-11-03:

- **La cola** `lms.trabajo` (ADR-0018 §4, ADR-0022, ADR-0033 §5) la reclama el backend con `FOR UPDATE SKIP LOCKED` y
  ejecuta cada `JobHandler` en un dispatcher acotado (`lms.jobs.parallelism`, 2 por réplica), nunca en el event loop. Da
  idempotencia por clave, tope por persona, recomprobación de permisos al ejecutar, fallo permanente (`PermanentJobFailure`),
  reintentos con espera, cancelación y retención. El tiempo límite es **uno para todos los tipos** (`lms.jobs.timeout`,
  10 min; `lease`, 15 min) y no hay límite de concurrencia por tipo al reclamar.
- **`wasichai-files`** (`wasichai/wasichai@d44787eb4fb5`, `wasichai-files/src/main/kotlin/wasichai/files/`):
  - `FileService.stage` recibe un `Flux<DataBuffer>` pero lo **junta entero en memoria** (`DataBufferUtils.join`) hasta el
    tope del campo y luego llama a `FileStore.put(key, ByteArray, contentType)`; el almacén S3 lo sube en un solo
    `PutObject` (`AsyncRequestBody.fromBytes`). No hay subida en streaming ni multiparte.
  - `wasichai.files.max-bytes` (10 MB por defecto; **25 MB** aquí) es a la vez el tope de un campo que no declara el suyo
    y **el máximo que cualquier campo puede declarar** (`FileFieldType.maxBytes = min(campo, techo)`). Todos los campos
    `FILE` de `model/model.json` declaran hoy su `maxBytes` (25 MB, o 1 MB la fuente AsciiDoc).
  - `ContentSniffer` reconoce PNG, JPEG, GIF, WebP, PDF, ZIP y texto; **un MP4 queda como `application/octet-stream`**.
  - Las descargas se transmiten en streaming y se comprueban contra el sha256 al vuelo (ADR-074 de Wasichai), pero
    `FileController` **no atiende `Range`**.
- `artefacto_generado` (ADR-0018 §7) ya guarda los HTML y PDF generados con su sha256, generador y `trabajo_id`, y el
  modelo de dominio prevé que F5 le añada MP4 y VTT.
- Un vídeo de diapositivas a 1280×720 y 30 fps con H.264 ocupa del orden de 1–2 Mbit/s: 15 minutos son ~110–225 MB.
  Los 25 MB de hoy dan para unos 2 minutos.

## Decisión

### 1. El trabajador: `render-worker/` en este repositorio, con su propio `package.json`

- Un **paquete Node aparte en `render-worker/`** de este repositorio, con su `package.json` (licencia LGPL-3.0-or-later),
  su `yarn.lock`, su `.node-version`, su `Dockerfile` y sus pruebas. Lo que lleva: las composiciones del proyecto, un
  servidor HTTP con `node:http` (sin framework) y el esquema del guion.
- **No es un workspace** del `package.json` raíz (que solo tiene herramientas de formato y commits): `yarn install` en la
  raíz y `./gradlew build` **no instalan Remotion**. CI construye y prueba el trabajador en un trabajo propio y comprueba
  que ni `yarn.lock` raíz ni el classpath del backend lo contienen (E37.2).
- Remotion y todos los `@remotion/*` fijados a la **misma versión exacta** (4.0.540 al escribir esto), como exige
  Remotion. Subir a 5.0 pasa por la revisión de licencia de ADR-0039 §8.
- **Imagen**: el `Dockerfile` la construye quien despliega; el proyecto **no publica** una imagen con Remotion
  (ADR-0039 §7). Lleva Chrome Headless Shell (lo descarga `@remotion/renderer`) y `ffprobe`.
- **Por qué aquí y no en otro repositorio o imagen de terceros**: el esquema del guion, las composiciones y el manejador
  del backend cambian juntos; en el mismo repositorio un PR los cambia y CI los prueba a la vez. Un repositorio aparte
  sería un segundo proceso de versiones sin un segundo usuario que lo pida.

### 2. La entrada: el guion como props JSON validados, nunca código

- La entrada es el **guion de RF-MED-01** con sus parámetros de salida, como un único documento JSON. Su esquema vive en
  `render-worker/schema/guion.schema.json` (JSON Schema 2020-12, `additionalProperties: false` en todos los objetos) y
  lo validan **los dos lados**: el backend al guardar el guion y al encolar, y el trabajador al recibirlo (con `zod`,
  que es lo que usa Remotion para las props de una composición). Unos mismos ejemplos válidos e inválidos en
  `render-worker/schema/ejemplos/` los leen las pruebas de ambos.
- Contenido:
  - `version` del esquema, `idioma`, `titulo`, `objetivos`;
  - `secciones`: cada una con `id`, `titulo`, `segundos` (entero, 1–600), su **diapositiva** (un tipo de una lista
    cerrada: portada, viñetas, imagen con pie, texto de código mostrado como texto, cierre), `narracion` (texto de la
    transcripción), `subtitulos` (`desde`, `hasta`, `texto`) y `trazas` a la fuente (`{ doc, ancla }`);
  - **salida**: `resolucion` ∈ {`1280x720`, `1920x1080`} (720p por defecto), `fps` ∈ {25, 30} (30 por defecto); la
    duración es la suma de las secciones y no pasa de `lms.render.max-seconds` (15 min);
  - **tipografía**: una clave de una lista cerrada de fuentes **OFL** que van dentro del paquete
    (`render-worker/fonts/`, con su `OFL.txt`); nunca una fuente descargada al renderizar;
  - **imágenes** de las diapositivas: el backend las resuelve a partir de archivos del curso (solo PNG, JPEG o WebP según
    la detección por bytes de Wasichai) y las manda **dentro de la petición** (`recursos: { <sha256>: <base64> }`, 20 MB
    en total como mucho); la diapositiva las nombra por sha256.
- **Nunca código**: ningún campo es un componente, una expresión, una ruta, una URL ni HTML. Los textos se pintan como
  nodos de texto de React (escapados), nunca con `dangerouslySetInnerHTML`; la composición se elige de una lista cerrada
  por nombre. Una petición que no cumple el esquema es `400` y, en el backend, `PermanentJobFailure`. Esto es lo que
  exige §RenderingService de Remotion (ADR-0039 §5) y lo que pide `docs/ai` §6: el texto que viene de la IA es dato.
- **Sin red al renderizar**: todo lo que la composición usa viene en la petición o en el paquete; el contenedor no tiene
  salida a Internet (política de red del despliegue).

### 3. La comunicación: el backend encola y ejecuta; el trabajador renderiza por HTTP interno

- El render es un tipo de trabajo más de `lms.trabajo` (`VIDEO_RENDER`), con su clave de idempotencia (hash del guion,
  de los recursos, de la salida y versión del trabajador), el tope por persona y la recomprobación de permisos de
  ADR-0022. **El trabajador no toca la base de datos ni el almacén de archivos** y no tiene sus credenciales.
- Su `JobHandler` en el backend corre en el dispatcher de la cola y llama al trabajador con el `WebClient` reactivo de
  Spring (sin bloquear hilos):
  1. `PUT /renders/{trabajo}` con el guion y los recursos. Responde cuando termina con un informe: sha256 y tamaño del
     MP4 y del VTT, la salida de `ffprobe` (§5), versión de Remotion y del trabajador. Es **idempotente por id de
     trabajo**: si ya lo hizo, devuelve el mismo informe sin renderizar otra vez; si lo está haciendo, espera a que
     acabe. `400` (esquema), `413` (demasiado grande) y `422` (render imposible) son permanentes; `503` (ocupado o
     arrancando) y los errores de red, pasajeros.
  2. `GET /renders/{trabajo}/video.mp4` y `/subtitulos.vtt`: los bytes, que el backend lee con el tope de §4 y cuyo
     sha256 compara con el del informe.
  3. `DELETE /renders/{trabajo}` cuando el backend ya los guardó; el trabajador borra además lo que lleve más de una hora
     sin recoger.
- **Autenticación**: el trabajador escucha solo en la red interna y exige `Authorization: Bearer` con un secreto
  compartido (`LMS_RENDER_WORKER_TOKEN`), que nunca va a registros ni a `lms.trabajo`.
- **Concurrencia**: el trabajador renderiza de uno en uno (configurable) y contesta `503` si está lleno. Para que lanzar
  más renders que el límite **los encole** sin gastar intentos (RF-MED-02), la cola gana un **límite de concurrencia por
  tipo al reclamar**: no reclama un `VIDEO_RENDER` mientras haya `lms.render.max-concurrent` (1) en `RUNNING`; los demás
  esperan en `QUEUED`.
- **Tiempo límite por tipo**: un render puede pasar de los 10 minutos de `lms.jobs.timeout`. Un `JobHandler` puede
  declarar su propio tiempo límite (el render, `lms.render.timeout`, 30 min) y `lms.jobs.lease` debe superar el mayor;
  la comprobación de arranque de `JobProperties` se extiende a eso. Un render ocupa una plaza de `lms.jobs.parallelism`
  mientras dura: la guía de operación lo dice para dimensionarla.
- **Cancelar** (ADR-0033 §5) marca el trabajo; el manejador lo mira antes de pedir el render y antes de guardar, y manda
  `DELETE` al trabajador. Un render ya empezado no se interrumpe en el trabajador en esta versión.

### 4. El almacenamiento: `wasichai-files` con un tope propio para el vídeo

- El vídeo **no sale de `wasichai-files`**: así conserva sha256, comprobación al descargar (ADR-074 de Wasichai),
  permisos y alcance del registro, limpieza de huérfanos y el almacén S3 del despliegue. Nada de escribir en el bucket por
  otra vía.
- **Archivos de más de 25 MB**: se sube el techo `wasichai.files.max-bytes` a **256 MB** (`WASICHAI_FILES_MAX_BYTES`) y
  **solo el campo del vídeo** declara ese tope; todos los demás campos `FILE` conservan su `maxBytes` explícito (25 MB o
  menos), así que subir el techo no cambia lo que puede subir una persona. `model/apply.py --validate-only` pasa a exigir
  `maxBytes` en todo campo `FILE`, para que ninguno herede el techo por descuido (E37.2).
- El campo del vídeo **solo lo escribe la plataforma** dentro del trabajo (ADR-068 de Wasichai, como hoy los artefactos);
  un `RecordWriteGuard` rechaza cualquier escritura de una persona. No declara lista de tipos, porque Wasichai detecta el
  MP4 como `application/octet-stream`; el tipo lo garantiza la validación de §5 antes de guardar.
- **Una sola copia de los bytes**: el MP4 se guarda una vez, en el registro que lee el aula (la versión del recurso de
  tipo `VIDEO`); su `artefacto_generado` (`formato` `MP4`) guarda sha256, parámetros, informe de `ffprobe`, generador,
  `trabajo_id` y `ejecucion_agente_id`, y apunta a esa versión, sin duplicar 256 MB. Los subtítulos (`VTT`, pequeños)
  siguen el camino de 25 MB de hoy. Los nombres exactos de los campos se fijan en `model/model.json` con E37.2.
- **Coste en memoria**: con el render activo, cada réplica puede tener en memoria un vídeo entero mientras lo guarda (el
  tope por campo y la concurrencia de render, 1, lo acotan). La guía de operación pide un heap de al menos 1 GB con el
  render activo. Una salida mayor que el tope es un fallo permanente: el trabajador ajusta la tasa de bits para que una
  duración dentro de `max-seconds` quepa, y el backend lee como mucho el tope.
- **A Wasichai, con este proyecto como segundo usuario concreto** (regla 1): subida en streaming (`FileStore.put` desde
  un flujo, multiparte en S3) para que el tamaño no dependa del heap; `Range` en las descargas para que el reproductor
  pueda saltar sin bajarse el vídeo entero; y detección de MP4 (`ftyp`) y WebM en `ContentSniffer`. Cuando lleguen, este
  apartado se revisa con otro ADR; mientras tanto, que el `<video>` del aula reproduzca un archivo servido como
  `application/octet-stream` y sin `Range` es **a verificar** en E37.2 con los navegadores de la matriz de pruebas.

### 5. La validación: `ffprobe` en el trabajador, comprobada en el backend (RF-MED-03)

- Tras renderizar, el trabajador ejecuta `ffprobe -v error -print_format json -show_format -show_streams` sobre el MP4 y
  devuelve su salida en el informe.
- El backend, con un validador propio y probado sin binarios, la compara con el guion pedido: contenedor MP4; una pista de
  vídeo H.264 con la resolución y los fps pedidos; duración igual a la suma de las secciones (tolerancia de un fotograma
  más 0,1 s); pista de audio solo si el guion la trae; tamaño dentro del tope; sha256 de los bytes recibidos igual al del
  informe. Los subtítulos VTT se analizan en el backend: bien formados, ordenados y dentro de la duración, y uno por
  subtítulo del guion.
- **Si algo no cuadra, el trabajo falla sin crear la versión**: un render fallido o incompleto nunca se ofrece para
  publicar. Sin validar no hay «vídeo terminado».
- `ffprobe` va en la imagen del trabajador, no en la del backend: la JVM no necesita ni binarios de ffmpeg ni archivos
  temporales. Qué compilación de ffmpeg trae y su licencia (LGPL; GPL si se usa una con `--enable-gpl`) se documenta con
  E37.2 en `docs/legal/licensing.md` (criterio de #267).

### 6. Previsualización y publicación

- La previsualización es **el propio MP4 renderizado**, en la versión en `BORRADOR`, reproducido en el estudio con el
  mismo reproductor que el aula. La interfaz **no usa el Player de Remotion**: el frontend no depende de Remotion
  (ADR-0039).
- La versión publicada referencia el vídeo por su archivo y sha256 y sigue funcionando sin el trabajador y sin el
  proveedor de IA (RF-MED-04). Lo publicado no cambia (regla 5): renderizar otra vez crea otra versión.

### 7. Opcionalidad: desactivado por defecto

- `lms.render.enabled` (`LMS_RENDER_ENABLED`) es `false` por defecto. Así **no existe** el `JobHandler` de render ni su
  cliente HTTP, y pedir un render responde `409` con un problema que dice que el render no está activo en este despliegue;
  la interfaz oculta el botón por cortesía. El resto del backend, el estudio y el aula funcionan igual y las versiones
  `VIDEO` subidas a mano siguen como hoy.
- Activado, **no arranca** si falta `LMS_RENDER_WORKER_URL` o `LMS_RENDER_WORKER_TOKEN`, ni si `wasichai.files.max-bytes`
  es menor que `lms.render.max-bytes`. No se comprueba al arrancar que el trabajador responda: si no está, los trabajos
  fallan como pasajeros y luego como fallidos, con un error que lo dice.
- Configuración prevista (nombres a confirmar al crear las propiedades en E37.2): `LMS_RENDER_ENABLED`,
  `LMS_RENDER_WORKER_URL`, `LMS_RENDER_WORKER_TOKEN`, `LMS_RENDER_TIMEOUT` (30 min), `LMS_RENDER_MAX_CONCURRENT` (1),
  `LMS_RENDER_MAX_SECONDS` (900), `LMS_RENDER_MAX_BYTES` (256 MB) y `WASICHAI_FILES_MAX_BYTES` (256 MB con el render).

## Alternativas consideradas

- **El trabajador lee y cierra `lms.trabajo` directamente** (Node con un cliente de PostgreSQL). Rechazada: duplicaría en
  otro lenguaje la idempotencia, la recomprobación de permisos con `UserDirectory` y `RoleQueries` y los cierres de
  ADR-0022, y el trabajador necesitaría credenciales de la base.
- **El backend lanza el render como subproceso** (`npx remotion render`, como Asciidoctor en ADR-0018 §7). Rechazada:
  metería Node, Chrome y Remotion en la imagen del backend, que dejaría de funcionar sin licencia de Remotion en cada
  despliegue (ADR-0039 §3) y mezclaría el programa LGPL con Remotion.
- **El trabajador sube el vídeo al bucket** y el backend solo registra la clave. Rechazada: es reimplementar
  `wasichai-files` (regla 1) sin su sha256 registrado, su comprobación al descargar ni su limpieza, y daría al
  trabajador credenciales del almacén.
- **Un `FileStore` propio con subida en streaming.** Rechazada por ahora: cambiaría el almacén de todos los archivos para
  resolver lo que es una mejora de Wasichai; se pide allí (§4).
- **Subir el techo y no declarar topes por campo.** Rechazada: cualquier campo sin tope dejaría a una persona subir
  256 MB a memoria.
- **Mantener 25 MB y limitar los vídeos a ~2 minutos.** Rechazada: un vídeo de una lección suele durar más.
- **Copiar el MP4 en el artefacto y en la versión**, como los HTML y PDF de hoy. Rechazada para el vídeo: duplica
  hasta 256 MB en memoria y en el almacén por cada render.
- **Cola propia para el render** o mensajería (Redis, RabbitMQ). Rechazada: `lms.trabajo` ya es la cola (ADR-0033 §5) y
  basta un límite por tipo.
- **Validar con `ffprobe` en el backend.** Rechazada: necesita el binario en la imagen del backend y escribir el vídeo a
  un temporal; con el sha256 cruzado, el informe del trabajador basta.
- **El Player de Remotion para previsualizar.** Rechazada: pondría Remotion en el frontend (ADR-0039).

## Consecuencias

- E37.2 ([#297](https://github.com/wasichai/elearning-backend/issues/297)) implementa el trabajador, el esquema con sus
  ejemplos compartidos, la comprobación en CI de que la build por defecto no instala Remotion, el informe de licencias del
  trabajador y la prueba de que una petición con código se rechaza. Los cambios en la cola (límite por tipo al reclamar,
  tiempo límite por manejador) van con su prueba en el paso que los use.
- `model/model.json` gana el campo del vídeo con tope de 256 MB y `formato_artefacto` gana `MP4` y `VTT`; `apply.py`
  exige `maxBytes` en todo campo `FILE`.
- Con el render activo, un despliegue necesita: el contenedor del trabajador en la red interna sin salida a Internet, el
  secreto compartido, el techo de archivos a 256 MB, más heap en el backend y la licencia de Remotion que le corresponda
  ([operación](../operations/README.md#render-de-vídeo-opcional)).
- Se abren en `wasichai/wasichai` las peticiones de subida en streaming, `Range` y detección de MP4/WebM.
- `docs/ai/README.md` §12 y `docs/legal/licensing.md` enlazan este ADR.
