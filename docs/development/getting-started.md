# Puesta en marcha

Cómo levantar wasichai-learning en local, backend y frontend, con los datos de demostración. Cada paso de esta guía se
ejecutó tal cual durante el primer incremento (2026-10-28); si algo no funciona igual, es un error de la guía.

## 1. Requisitos

| Herramienta | Versión         | Nota                                                                                                                |
| ----------- | --------------- | ------------------------------------------------------------------------------------------------------------------- |
| JDK         | 25              | `jvmToolchain(25)`; Gradle lo encuentra solo (p. ej. instalado con sdkman: `sdk install java 25.0.4-tem`)           |
| Docker      | con Compose v2  | PostgreSQL 18 en contenedor; Testcontainers para las pruebas                                                        |
| Python      | 3.12 o superior | solo biblioteca estándar, para `model/apply.py`                                                                     |
| Node        | 26              | el frontend lo exige (`engines`); con [fnm](https://github.com/Schniz/fnm): `fnm install 26` y `fnm use` en el repo |
| yarn        | 1.22            | como wasichai-ui                                                                                                    |
| GitHub CLI  | `gh`            | para obtener el token de paquetes                                                                                   |

No hace falta instalar Gradle: el repositorio trae el wrapper (`./gradlew`, Gradle 9.8.2).

## 2. Acceso a los paquetes de Wasichai

Wasichai (`wasichai:*` 1.6.0 en Maven y `@wasichai/*` 1.3.0 en npm) **solo se publica en GitHub Packages**, que pide un
token con el permiso `read:packages` incluso para leer paquetes públicos ([ADR-0002](../adr/0002-wasichai-desde-github-packages.md)).

```bash
gh auth refresh -h github.com -s read:packages    # añade el permiso al token de gh (una vez)
gh auth status | grep scopes                       # debe listar 'read:packages'
```

El token **nunca va a un archivo del repositorio**:

- **Gradle** lo toma del entorno en cada ejecución:
  ```bash
  export GITHUB_ACTOR=$(gh api user -q .login) GITHUB_TOKEN=$(gh auth token)
  ```
  (o `gpr.user`/`gpr.key` en `~/.gradle/gradle.properties`, fuera del repo).
- **npm/yarn** lo leen de `~/.npmrc` (tu carpeta personal); el `.npmrc` del frontend solo dice qué registro usar:
  ```bash
  echo "//npm.pkg.github.com/:_authToken=$(gh auth token)" >> ~/.npmrc && chmod 600 ~/.npmrc
  ```

Comprobación: `npm view @wasichai/core@1.3.0 version --registry=https://npm.pkg.github.com` responde `1.3.0`, y
`./gradlew build` descarga `wasichai-bom-1.6.0`. Un `401`/`403` significa que falta `read:packages`.

## 3. Backend

```bash
cd elearning-backend
cp .env.example .env
sed -i "s|^WASICHAI_JWT_SECRET=.*|WASICHAI_JWT_SECRET=$(openssl rand -base64 48)|" .env   # sin secreto no arranca
docker compose up -d --wait                                                             # PostgreSQL 18 en 127.0.0.1:5436
export GITHUB_ACTOR=$(gh api user -q .login) GITHUB_TOKEN=$(gh auth token)
./gradlew bootRun --args='--spring.profiles.active=dev'                                 # http://localhost:8090
```

`bootRun` lee `.env` como variables de entorno. El perfil `dev` activa la semilla de Wasichai: organización `demo` y
administrador `admin@wasichai.local` / `admin` (**solo desarrollo**). Comprobación: `curl localhost:8090/api/health` →
`{"status":"UP",…}`.

## 4. Modelo y datos de demostración

En otra terminal, con el backend arriba:

```bash
export WASICHAI_EMAIL=admin@wasichai.local WASICHAI_PASSWORD=admin
export WASICHAI_DEMO_PASSWORD='elige-una-contraseña-de-desarrollo'   # la de los usuarios demo; nunca se guarda
python3 model/apply.py --demo
```

La primera ejecución crea el modelo (13 objetos, relaciones, acciones), los 4 roles, 5 usuarios y 89 registros; una
segunda responde «Sin cambios». Sin `--demo` aplica solo el modelo y los roles. `--validate-only` valida sin servidor.

Lo que deja la demo para el aula (F2):

- **Fechas relativas** al día en que se aplica (`"hoy+12"` en `model/demo/data.json`): la edición `SRTM-ANU-101-A`
  tiene la inscripción abierta hoy y empieza en 12 días; las demás, más adelante. No envejecen.
- **Recursos con archivo** (`model/demo/archivos/`, todos propios o sintéticos): una lectura HTML en la primera lección
  de SRTM, una guía PDF en «Cómo se deduce el estado», un vídeo de 4 s (patrón de prueba de FFmpeg) en la lección de
  vídeo y una presentación de tres diapositivas en PDF en «Consultar el padrón con filtros». Se suben al borrador y se publican antes de que la lección los use, como lo haría una persona.
- **`estudiante@…` matriculado** en `SRTM-ANU-101-A` por el comando de matrícula, iniciando sesión como él: entra y
  encuentra el curso en «Mis cursos».

Si aplicaste una demo anterior a estos cambios, el temario de SRTM ya está publicado y no admite la lección ni los
recursos nuevos: recrea la base (`docker compose down -v && docker compose up -d`) y vuelve a aplicar.

Usuarios de demostración (contraseña: la de `WASICHAI_DEMO_PASSWORD`):

| Correo                                        | Roles             |
| --------------------------------------------- | ----------------- |
| `estudiante@demo.wasichai-learning.test`      | ESTUDIANTE        |
| `docente@demo.wasichai-learning.test`         | DOCENTE           |
| `creador@demo.wasichai-learning.test`         | CREADOR           |
| `revisor@demo.wasichai-learning.test`         | REVISOR           |
| `docente.revisor@demo.wasichai-learning.test` | DOCENTE y REVISOR |

El catálogo público es el de la organización `LMS_PUBLIC_ORGANIZATION` (`demo` en `.env.example`) y se refresca en
segundo plano cada 30 s ([ADR-0011](../adr/0011-catalogo-publico-leido-de-una-proyeccion.md)):
`curl localhost:8090/api/lms/public/catalog/courses` devuelve los tres cursos publicados y no el borrador.

## 5. Frontend

```bash
cd elearning-frontend
fnm use                     # Node 26 (.node-version)
yarn install --frozen-lockfile
yarn dev                    # http://localhost:5180 — /api va al backend en 8090 (WASICHAI_API_URL para cambiarlo)
```

- Catálogo público, sin sesión: <http://localhost:5180/cursos>.
- Administración de Wasichai (modelo, registros, usuarios, roles): <http://localhost:5180/> con `admin@wasichai.local`.

## 6. Pruebas

| Qué                                                                | Comando                                                  |
| ------------------------------------------------------------------ | -------------------------------------------------------- |
| Backend: formato, unitarias, arquitectura                          | `./gradlew build`                                        |
| Backend: integración (Testcontainers `postgres:18`; nunca el 5432) | `./gradlew integrationTest`                              |
| Script del modelo                                                  | `cd model && python3 -m unittest`                        |
| Frontend                                                           | `yarn lint && yarn typecheck && yarn test && yarn build` |
| End-to-end con API simulada                                        | `yarn e2e`                                               |
| Smoke contra el backend real (pasos 3–4 hechos)                    | `E2E_REAL=1 yarn e2e:real`                               |

Estrategia y criterios: [docs/testing/README.md](../testing/README.md).

## 7. Problemas frecuentes

| Síntoma                                                  | Causa y arreglo                                                                               |
| -------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `401`/`403` al descargar `wasichai-*` o `@wasichai/*`    | El token no tiene `read:packages`: paso 2                                                     |
| `wasichai.security.jwt.secret must be at least 32 bytes` | Falta `WASICHAI_JWT_SECRET` en `.env`                                                         |
| `The engine "node" is incompatible … Expected ">=26"`    | Shell sin Node 26: `fnm use` (también lo necesita el hook de commit)                          |
| El backend intenta conectarse al 5432                    | `bootRun` no encontró `.env`: ejecútalo desde la raíz del repo                                |
| El catálogo sale vacío                                   | `LMS_PUBLIC_ORGANIZATION` vacío, modelo sin aplicar (paso 4) o aún no pasó el refresco (30 s) |
| Gradle 4.x al escribir `gradle`                          | Usa siempre `./gradlew`                                                                       |
