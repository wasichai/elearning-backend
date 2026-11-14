# ADR-0044: La interfaz adopta el sistema de diseño Colofón y retira Quipu

**Estado**: aceptada · 2026-11-08 · reemplaza a [ADR-0026](0026-sistema-de-diseno-quipu.md) y
[ADR-0029](0029-sistema-de-diseno-quipu-aplicado.md) y mantiene [ADR-0005](0005-lms-en-el-registro-de-wasichai-ui.md) ·
[#382](https://github.com/wasichai/elearning-backend/issues/382)

## Contexto

- Quipu (ADR-0026, ADR-0029) vistió la interfaz como tema de app sobre `@wasichai/ui`. Una auditoría de la interfaz
  ([documento](https://claude.ai/artifact/2vSwNNERREDaTSCPQMbU1C)) encontró que su identidad (cordones, nudos, color maíz)
  pesa más que el contenido, que la lectura larga no tiene un sistema propio y que faltan pantallas que el estudiante
  necesita, como una agenda.
- Se exploraron cuatro direcciones y se puntuaron sobre 80: **A · Colofón** 61, **B · Instrumento** 60,
  **C · Candil** 62 y **D · Señal** 66. La recomendación de la auditoría fue D. El usuario eligió A.
- La épica es [wasichai/elearning-frontend#217](https://github.com/wasichai/elearning-frontend/issues/217). El lienzo de
  la dirección elegida: [canvas A](https://claude.ai/artifact/3FfeqzTPiBKQzEcKR7MqKc).

## Decisión

1. **Colofón sustituye a Quipu.** Es un tema `[data-theme]` que redefine los tokens de `@wasichai/ui` (`colofon`,
   `colofon-dark` y `colofon-lectura`), con estilos sobre `data-slot` y utilidades `@theme` de Tailwind 4. No se añade
   ninguna librería de componentes (regla de ADR-0005).
2. **Lenguaje.** Lectura primero, progreso honesto, filetes en lugar de cajas, un solo acento para actuar y estado nunca
   solo por color. Newsreader (títulos y lectura) y Source Sans 3 (interfaz), autoalojadas con su licencia OFL.
3. **Fuente de verdad en el repositorio.** Los artboards `.dc.html`, `canvas.json` y `tokens.json` viven en
   `elearning-frontend/docs/design-system/colofon/`; la documentación de Quipu pasa a `historico-quipu/`.
4. **Desaparece Quipu por completo:** `quipu*.css`, las fuentes Figtree y Literata, `QuipuMark`, `CordBanner`, los nudos de
   `Quipu.tsx` y el color maíz.
5. **Ni datos ni funciones inventados.** Lo que la API no da se oculta; no se pone un marcador en su lugar.

## Alternativas consideradas

- **B · Instrumento** (60/80). Rechazada: panel denso y técnico, menos apto para leer largo rato.
- **C · Candil** (62/80). Rechazada por la elección del usuario.
- **D · Señal** (66/80), la recomendada por la auditoría. No elegida: el usuario prefirió A por su carácter editorial y
  por la lectura.
- **Conservar Quipu y corregirlo.** Rechazada: los problemas de la auditoría vienen de su identidad, no de detalles.

## Consecuencias

- Se añade la **agenda del estudiante** que pide la pantalla «Horario»: `GET /api/lms/classroom/sessions`, de solo
  lectura, sobre el `SessionService` existente ([#383](https://github.com/wasichai/elearning-backend/issues/383)).
- La **lectura pasa a ser nativa, sin iframe**. Es una decisión de arquitectura propia y la recoge el ADR-0045
  ([#384](https://github.com/wasichai/elearning-backend/issues/384)); aquí solo se menciona.
- ADR-0026 y ADR-0029 pasan a «reemplazada por ADR-0044»; sus cuerpos no cambian.
- El frontend se migra en tareas con un PR cada una (plan en
  `elearning-frontend/docs/superpowers/plans/2026-11-08-colofon.md`); mientras tanto conviven Quipu y Colofón.
