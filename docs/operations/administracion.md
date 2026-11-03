# Administración de usuarios, roles y categorías

Recorrido de [RF-ADM-02](../product/requirements.md): usuarios, roles y categorías se administran con las pantallas de
Wasichai (`@wasichai/core` 1.3.0). El LMS no tiene pantallas paralelas: sus módulos de interfaz (`catalog`, `classroom`)
solo registran rutas `cursos`, `certificados` y `mis-cursos`.

Ejecutado el 2026-10-31, de principio a fin, con Playwright contra la interfaz compilada y un backend de perfil `dev` con
`python3 model/apply.py --demo` aplicado. Los pasos llevan los textos reales de la interfaz en español.

## Antes de empezar

Inicia sesión con una cuenta `ADMIN`. En desarrollo es `admin@wasichai.local` / `admin` (ver
[la puesta en marcha](../development/getting-started.md)).

La administración es una sección del menú, sin cambiar ninguna URL: una sola aplicación, sin segundo router
([ADR-0005](../adr/0005-lms-en-el-registro-de-wasichai-ui.md)). La ofrece el menú a quien responde `admin: true` o la
capacidad `MANAGE_ORGANIZATION` en `GET /auth/me/permissions`:

- Fuera de las pantallas de Wasichai, el menú lleva **Plataforma → Administración**, que abre «Objetos» (`/data/objects`).
- Dentro de ellas, el menú lleva los grupos «Datos» y «Administración», sin los grupos del LMS. **Inicio** vuelve al LMS.

## Usuarios

1. **Administración → Usuarios** (`/admin/users`). La pantalla tiene el formulario «Nuevo usuario» y, debajo, la tabla.
2. Rellena «Correo», «Nombre» y «Contraseña», marca el rol «Estudiante» y pulsa «Crear». La fila aparece en la tabla con
   ese rol.
3. Pulsa el lápiz de la fila («Editar …»). Aparece «Editando a …»: marca también «Creador de contenido» y guarda. La fila
   muestra los dos roles; una cuenta tiene la unión de sus permisos (RF-ROL-03).
4. Con esas credenciales, en otro navegador, la persona entra sin problema.

Cambiar los roles revoca los tokens de la persona: tiene que volver a iniciar sesión. Nadie sin `MANAGE_ORGANIZATION`
(hoy, solo `ADMIN`) puede crear usuarios ni cambiar roles; lo prueba `RolesApiTest` (RF-ROL-05).

## Roles y permisos

- **Administración → Roles** (`/admin/roles`) lista los roles con su etiqueta y sus permisos.
- **Administración → Permisos** (`/admin/permissions`) muestra la matriz por objeto y los permisos de campo de cada rol.

Los roles del LMS se **declaran** en [`model/roles.json`](../../model/roles.json) y los aplica `model/apply.py`, que
reemplaza el conjunto de permisos de cada rol declarado. Estas pantallas sirven para consultarlos. Un cambio hecho aquí
sobre un rol declarado se pierde en la siguiente aplicación del modelo: el cambio permanente va a `model/roles.json`, con
su PR. En este recorrido se consultaron, no se editaron.

## Categorías

Una categoría es un registro del objeto `categoria`. El catálogo las muestra desde su proyección.

1. **Datos → Objetos** (`/data/objects`). En la fila «Categoría», pulsa «Ver registros». La entrada «Registros» del menú
   está desactivada: se llega por aquí.
2. En «Categorías» (`/data/objects/categoria/records`), pulsa «Nuevo registro».
3. Rellena «Nombre» e «Identificador en URL» (el _slug_, único), y opcionalmente «Descripción» y «Orden», y guarda. La
   categoría aparece en la lista.

## Conocimientos previos

El filtro «Conocimientos previos» del catálogo usa el vocabulario `conocimiento_previo`
([ADR-0028](../adr/0028-conocimientos-previos-como-claves-de-la-version.md)). Se da de alta igual que una categoría,
en «Conocimientos previos» (`/data/objects/conocimiento_previo/records`): «Clave» (minúsculas, dígitos y guiones;
`none` está reservada) y «Nombre». La clave no se cambia ni el registro se borra después: el nombre sí se corrige. El
creador escribe las claves, una por línea, en «Conocimientos previos: claves…» de su versión en borrador.

## Auditoría

**Administración → Auditoría** (`/admin/audit`) lista quién cambió qué y cuándo, con filtros por objeto y operación. Tras
este recorrido aparecen, a nombre del administrador:

| Operación    | Objeto       | Qué fue                                     |
| ------------ | ------------ | ------------------------------------------- |
| Creación     | `admin:user` | El alta del usuario                         |
| Modificación | `admin:user` | La edición y el cambio de roles (dos filas) |
| Creación     | `categoria`  | El alta de la categoría                     |

## Limitaciones conocidas

Sin ese permiso, «Datos» y «Administración» no salen en el menú, en ninguna ruta, y tampoco con los permisos cargando o
con error ([RNF-UX-06](../product/requirements.md)). Ocultarlos es cortesía: decide el servidor. Las URL siguen abiertas
porque un creador edita sus lecciones en `/data/objects/leccion/records/:id`, con su menú del LMS.

Una cuenta sin `MANAGE_ORGANIZATION` que escriba `/admin/users` a mano ve el formulario y «Todavía no hay usuarios», aunque
la lista no le ha llegado porque el servidor la rechaza. No hay fuga ni escalada: la API responde `403`, pero el mensaje
induce a error. La pantalla es de `@wasichai/core`.

En la interfaz, la primera versión de un curso nuevo solo llega importando. La API ya crea ese primer borrador vacío
(`POST /api/lms/studio/courses/{courseId}/versions`, #270), pero el estudio aún no lo ofrece
([front#161](https://github.com/wasichai/elearning-frontend/issues/161)). El formulario de `version_curso` no sirve para esto:
exige en «Autor» el UUID de quien crea, que ninguna pantalla le muestra.
