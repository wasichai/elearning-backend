# Operación

Cómo se configura, se observa, se respalda y se diagnostica wasichai-learning. Hoy no hay código: lo que depende de
Wasichai está verificado (`wasichai/wasichai@a9ad74d56338`, ver
[`docs/research/reference-repositories.md`](../research/reference-repositories.md)); lo propio está **planificado**.
La instalación paso a paso está en [`docs/development/getting-started.md`](../development/getting-started.md); la
gestión de secretos, en [`docs/security/README.md`](../security/README.md#8-gestión-de-secretos).

## Puertos locales

| Servicio                       | Dirección                          | Notas                                                                               |
| ------------------------------ | ---------------------------------- | ----------------------------------------------------------------------------------- |
| Backend (Spring Boot)          | `http://localhost:8090`            | `server.port`; la misma que usa la guía de Wasichai                                 |
| UI (Vite, `yarn dev`)          | `http://localhost:5180`            | Proxy de `/api` al backend                                                          |
| PostgreSQL 18 (`compose.yaml`) | `127.0.0.1:5436`                   | Solo en loopback                                                                    |
| PostgreSQL de las pruebas      | Puerto aleatorio de Testcontainers | **Ninguna prueba usa 5432** ([pruebas](../testing/README.md#regla-del-puerto-5432)) |

MinIO (S3) y Mailpit se añaden a `compose.yaml` cuando un requisito los use (recursos y vídeo en F2/F5, avisos en F3),
también solo en loopback.

## Variables de entorno

Lista prevista; `.env.example` es la referencia ejecutable y nunca lleva valores reales. Wasichai lee
`wasichai.*` también como `WASICHAI_*` (enlace relajado de Spring).

| Variable                                                              | Uso                                                                                                                                            | Obligatoria                    | Fase |
| --------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------ | ---- |
| `WASICHAI_JWT_SECRET`                                                 | Firma de los JWT, ≥ 32 bytes; sin ella el backend no arranca                                                                                   | Sí                             | F1   |
| `WASICHAI_DB_HOST`, `WASICHAI_DB_PORT`                                | Servidor PostgreSQL. Wasichai pone por defecto `localhost:5432`: en local se fija `5436`                                                       | Sí en local                    | F1   |
| `WASICHAI_DB_NAME`, `WASICHAI_DB_USERNAME`, `WASICHAI_DB_PASSWORD`    | Base y credenciales (Wasichai: `wasichai` las tres)                                                                                            | Fuera de local                 | F1   |
| `SPRING_PROFILES_ACTIVE`                                              | `dev` en local (activa la semilla de Wasichai)                                                                                                 | No                             | F1   |
| `WASICHAI_SEED_DEV`                                                   | `true` solo en desarrollo: crea `admin@wasichai.local` / `admin`                                                                               | Nunca en producción            | F1   |
| `WASICHAI_WEB_CORS_ALLOWED_ORIGIN_PATTERNS`                           | Orígenes de la UI (por defecto de Wasichai `http://localhost:*`)                                                                               | En despliegue                  | F1   |
| `LMS_PUBLIC_ORGANIZATION`                                             | Organización cuyo catálogo es público (`lms.public.organization`)                                                                              | Sí                             | F1   |
| `WASICHAI_CORS_ALLOWED_ORIGINS`                                       | Orígenes de navegador permitidos, separados por comas. Vacío: ninguno ajeno (UI y API en el mismo dominio); `dev` permite `http://localhost:*` | Solo con la UI en otro dominio | F1   |
| `LMS_PRODUCT_NAME` / `VITE_APP_NAME`                                  | Nombre visible del producto (backend / build del frontend)                                                                                     | No                             | F1   |
| `WASICHAI_SECURITY_JWT_REVOCATION`, `…_LOGIN_ENABLED`, `…_PASSWORD_*` | Endurecimiento del login                                                                                                                       | Antes de producción            | F6   |
| `WASICHAI_FILES_*`                                                    | Almacén de archivos (`store`, `s3.*`, `max-bytes`)                                                                                             | Cuando haya archivos           | F2   |
| `ANTHROPIC_API_KEY`, `WASICHAI_AGENT_ENABLED`                         | IA; sin clave, [doble determinista](../ai/README.md#7-doble-determinista-sin-credenciales)                                                     | No                             | F5   |
| `GITHUB_ACTOR`, `GITHUB_TOKEN`                                        | **Solo build**: Wasichai desde GitHub Packages (`read:packages`), `$(gh auth token)`                                                           | Solo build                     | F1   |
| `WASICHAI_TEST_DB_*`                                                  | Base externa para pruebas de integración; el nombre debe terminar en `_test`                                                                   | No                             | F1   |

El frontend necesita además el token de `npm.pkg.github.com` en `~/.npmrc` del desarrollador o en un secreto de CI,
nunca en el `.npmrc` del repositorio. Nombres de variables `LMS_*`: a confirmar al crear las propiedades en F1.

## Salud

| Ruta                      | Qué dice                                                    | Acceso  |
| ------------------------- | ----------------------------------------------------------- | ------- |
| `GET /api/health`         | Salud de la aplicación (`healthController` de Wasichai)     | Público |
| `GET /actuator/health/**` | Actuator de Spring Boot, incluido en el starter de Wasichai | Público |

Grupos `liveness`/`readiness` para k3s y qué indicadores exponen detalle: a verificar al configurar actuator. Ningún
otro endpoint de actuator se expone sin autenticación.

## Registros

- Salida estándar del proceso; en contenedor, la recoge el orquestador.
- Wasichai propaga `X-Correlation-Id` (lo genera si falta o no cumple `^[A-Za-z0-9._-]{1,64}$`), lo devuelve en la
  respuesta y lo pone en el MDC como `correlationId`. Con ese id se cruzan registro, respuesta y
  `GET /api/audit?correlationId=…`.
- Formato estructurado (JSON) para producción: planificado (F6), a verificar con el soporte de Spring Boot 4.1.
- Nunca se registran tokens, contraseñas, claves de proveedor ni cuerpos con datos personales.

## Base de datos y roles

Esquemas: `wasichai` (metadata e identidad), `app_data` (una tabla por Custom Object) y, cuando exista, `lms`
(tablas fijas propias). Migraciones con Flyway al arrancar (`ModuleMigration` orden 200 para `lms`).

En local basta un rol. **Antes de producción**, dos roles como indica la guía de Wasichai («Two database roles»): un
rol dueño que migra y un rol de ejecución sin propiedad, con `REVOKE UPDATE, DELETE, TRUNCATE ON wasichai.audit_log`,
y `wasichai.database.migrate=false` en el servicio si las migraciones corren en un paso aparte. Mientras la aplicación
se conecte como dueña, Wasichai escribe un `WARN` al arrancar.

## Copias de seguridad y restauración

Herramientas: `pg_dump`/`pg_restore` de la misma versión mayor (18), ejecutadas en el contenedor porque no hay `psql`
local. Los nombres de servicio y base salen de `compose.yaml` y `.env` (a verificar cuando existan). Cubre los tres
esquemas; los archivos del almacén de objetos (F2) se respaldan aparte y en el mismo momento.

```bash
# copia (formato custom, comprimido)
docker compose exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "backups/lms-$(date +%Y%m%dT%H%M).dump"

# inventario de la copia
docker compose exec -T postgres pg_restore -l < backups/lms-AAAAMMDDTHHMM.dump | head
```

Restauración, siempre en una base **vacía**:

```bash
docker compose exec -T postgres createdb -U "$DB_USER" "${DB_NAME}_restore"
docker compose exec -T postgres pg_restore -U "$DB_USER" -d "${DB_NAME}_restore" --no-owner --exit-on-error \
  < backups/lms-AAAAMMDDTHHMM.dump
```

- Nunca `pg_restore --clean` sobre la base en uso: `audit_log` rechaza `DELETE` y `TRUNCATE` por diseño.
- `pg_dump` no copia roles; con dos roles, se crean antes con el script de roles del despliegue y se restaura con
  `--role` del dueño en lugar de `--no-owner`.
- Copia lógica diaria y retención: se fijan en F6 con la organización; aquí no se promete ningún RPO/RTO.

### Prueba de restauración

Una copia no cuenta hasta haberse restaurado. Procedimiento (manual ahora, automatizado en F6):

1. Restaurar la última copia en una base nueva, en un contenedor `postgres:18` aparte.
2. Arrancar el backend contra ella con `WASICHAI_DB_NAME=<base restaurada>` y comprobar que Flyway no tiene
   migraciones pendientes ni fallidas.
3. `GET /api/health` → `UP`; login con un usuario conocido; listar el catálogo público.
4. Contar registros de los objetos principales y comparar con la base de origen en el momento de la copia.
5. Comprobar que `audit_log` conserva sus últimas entradas y sus triggers.
6. Anotar fecha, copia, duración y resultado en el registro de operación del entorno.

## Diagnóstico básico

| Síntoma                                                                         | Comprobación                                                                                        |
| ------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| El backend no arranca: `wasichai.security.jwt.secret must be at least 32 bytes` | Definir `WASICHAI_JWT_SECRET` con ≥ 32 bytes                                                        |
| Gradle no resuelve `wasichai:*` (401/403)                                       | Token sin `read:packages`: `gh auth refresh -h github.com -s read:packages`; exportar `GITHUB_*`    |
| `yarn install` falla con 401 en `npm.pkg.github.com`                            | Token en `~/.npmrc` (`//npm.pkg.github.com/:_authToken=…`)                                          |
| Conexión rechazada al puerto 5436                                               | `docker compose ps`; el contenedor de PostgreSQL debe estar `healthy`                               |
| El backend intenta conectarse a 5432                                            | Falta `WASICHAI_DB_PORT=5436` (el valor por defecto de Wasichai es 5432)                            |
| `WARN` de Wasichai sobre el dueño de `audit_log`                                | Esperado en local; en producción, separar roles                                                     |
| La UI vuelve al login en cada llamada                                           | Token caducado (8 h) o secreto distinto entre reinicios                                             |
| Errores CORS en el navegador                                                    | `WASICHAI_WEB_CORS_ALLOWED_ORIGIN_PATTERNS` no incluye el origen de la UI                           |
| `./gradlew integrationTest` falla al iniciar el contenedor                      | Docker no accesible; o usar `WASICHAI_TEST_DB_*` con una base cuyo nombre termine en `_test`        |
| Seguir una petición concreta                                                    | Tomar `X-Correlation-Id` de la respuesta y buscarlo en el registro y en `/api/audit?correlationId=` |

## Despliegue objetivo (k3s)

Posterior (F6); nada de esto existe aún.

- Imagen del backend sin herramientas de build ni `GITHUB_TOKEN`; frontend estático servido aparte.
- Migraciones como `Job` con el rol dueño; el `Deployment` con el rol de ejecución y `wasichai.database.migrate=false`.
- Secretos como `Secret` de Kubernetes; nada en la imagen ni en el manifiesto.
- Sondas sobre los grupos de salud de actuator (a verificar).
- **Varias réplicas**: el contador de intentos de login de Wasichai es en memoria por nodo (`LoginAttemptStore` por
  defecto); con más de una réplica hay que declarar uno compartido (PostgreSQL) o aceptar límites por nodo. La
  revocación de tokens tarda hasta `revocation-cache` en llegar a los demás nodos.
- PostgreSQL gestionado o `StatefulSet` con volumen persistente y copias según la sección anterior.
- Almacenamiento de objetos S3 compatible (F2/F5).
