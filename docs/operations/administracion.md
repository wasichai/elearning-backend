# Administración de usuarios, roles y categorías

Recorrido de [RF-ADM-02](../product/requirements.md): usuarios, roles y categorías se administran con las pantallas de
Wasichai (`@wasichai/core` 1.3.0). El LMS no tiene pantallas paralelas: sus módulos de interfaz (`catalog`, `classroom`)
solo registran rutas `cursos`, `certificados` y `mis-cursos`.

Ejecutado el 2026-10-31, de principio a fin, con Playwright contra la interfaz compilada y un backend de perfil `dev` con
`python3 model/apply.py --demo` aplicado. Los pasos llevan los textos reales de la interfaz en español.

## Antes de empezar

Inicia sesión con una cuenta `ADMIN`. En desarrollo es `admin@wasichai.local` / `admin` (ver
[la puesta en marcha](../development/getting-started.md)). El menú de la izquierda tiene los grupos «Datos» y
«Administración».

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

## Auditoría

**Administración → Auditoría** (`/admin/audit`) lista quién cambió qué y cuándo, con filtros por objeto y operación. Tras
este recorrido aparecen, a nombre del administrador:

| Operación    | Objeto       | Qué fue                                     |
| ------------ | ------------ | ------------------------------------------- |
| Creación     | `admin:user` | El alta del usuario                         |
| Modificación | `admin:user` | La edición y el cambio de roles (dos filas) |
| Creación     | `categoria`  | El alta de la categoría                     |

## Limitación conocida

La navegación todavía no se filtra por permisos ([RNF-UX-06](../product/requirements.md)). Una cuenta sin
`MANAGE_ORGANIZATION` ve las entradas de «Administración» en el menú. Si abre `/admin/users`, ve el formulario y «Todavía
no hay usuarios», aunque la lista no le ha llegado porque el servidor la rechaza. No hay fuga ni escalada: la API responde
`403`, pero el mensaje induce a error. Lo decide el servidor (CLAUDE.md, regla 4).
