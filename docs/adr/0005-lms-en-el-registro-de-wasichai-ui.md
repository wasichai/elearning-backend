# ADR-0005: Todo el LMS se registra como módulos de `<WasichaiApp>`

**Estado**: aceptada · 2026-10-27

## Contexto

- wasichai-ui arma la interfaz con un registro de módulos
  ([ADR-028 de Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/adr/0028-frontend-module-registry.md)):
  cada `WasichaiModule` aporta rutas, menú, i18n y *slots*; `createRegistry` valida la combinación al arrancar.
- Una ruta declara `chrome: 'shell' | 'bare' | 'public'` (`packages/core/src/registry/contract.ts`); las rutas
  `public` se montan fuera de la sesión (`packages/core/src/app/WasichaiRoutes.tsx`).
- Los enlaces se construyen con `useWasichaiLinks().to('<módulo>:<ruta>')`; cada paquete tiene `boundaries.test.ts`
  contra URLs literales e importaciones entre módulos.
- sgspe-ui usa otro patrón: el portal en `/` es una aplicación propia con su `createBrowserRouter`
  (`src/portal/PortalApp.tsx`) y `WasichaiApp` solo en `/admin` (ADR-0024 de sgspe, «portal en `/` y administración
  en `/admin`»).
- La especificación exige «sin segunda biblioteca visual ni segundo router».

## Decisión

**Todo el LMS es un conjunto de `WasichaiModule` montados en una sola `<WasichaiApp config modules />`**, junto a los
módulos de Wasichai que se usen para administración. Los módulos LMS son los de [ADR-0003](0003-monolito-modular-y-modulos-ui-por-experiencia.md).

- El catálogo público (`catalogModule`) declara sus rutas con `chrome: 'public'`: se ve sin sesión y sin el armazón de
  la aplicación.
- Los módulos con sesión usan `chrome: 'shell'`; su menú se muestra según `GET /auth/me/permissions` (cortesía: el
  servidor decide).
- Ningún enlace se escribe a mano; cada módulo tiene su `boundaries.test.ts`.
- El nombre del producto viene de configuración (`VITE_APP_NAME`), nunca del código del dominio.

**Desviación consciente de sgspe-ui.** No se copia su portal con router propio, porque el requisito prohíbe un segundo
router y porque las rutas `public` del registro cubren lo que el portal de sgspe resolvía por fuera.

## Alternativas consideradas

- **Portal propio + `WasichaiApp` en `/admin`, como sgspe-ui.** Rechazado: dos routers, contra la especificación; y
  dos estilos de pantalla (la desventaja que el propio ADR-0024 de sgspe anota).
- **Aplicación propia sin `WasichaiApp`.** Rechazado: reimplementa login, administración de usuarios y roles,
  auditoría y modelo.
- **Proponer a wasichai-ui un modo «portal».** Innecesario hoy: `chrome: 'public'` ya existe.

## Consecuencias

- Una sola aplicación, un login, un router; administración y LMS conviven con el mismo tema y componentes.
- El LMS depende del contrato `WasichaiModule`: un cambio en wasichai-ui puede romper módulos al subir de versión.
- Lo que el registro no ofrezca (p. ej. un *layout* editorial distinto para el catálogo) se resuelve dentro del
  componente de la ruta, no con un router aparte. Si hiciera falta un *slot* nuevo, se pide a wasichai-ui con este
  proyecto como segundo usuario concreto.
- A verificar en implementación: que las rutas `public` admiten rutas anidadas con parámetros (`cursos/:slug`), carga
  diferida (`lazy`) y que el *chrome* `public` no exige token para el `fetch` de datos.
