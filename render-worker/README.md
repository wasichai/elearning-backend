# render-worker

Trabajador de render de vídeo de wasichai-learning (RF-MED-02): renderiza con [Remotion](https://www.remotion.dev) el guion
que le manda el backend. Es un programa aparte, con su propio `package.json`; ni el backend ni el `package.json` raíz lo
instalan. Diseño: [ADR-0039](../docs/adr/0039-remotion-como-motor-de-render-con-licencia-por-despliegue.md) y
[ADR-0040](../docs/adr/0040-renderizador-de-video-en-un-trabajador-node.md).

> **Licencia de Remotion**: quien construye y usa este trabajador responde de su licencia de Remotion
> ([remotion.dev/license](https://www.remotion.dev/license)). El proyecto no publica ninguna imagen que lo incluya. Lo que
> lleva y con qué licencia (ffmpeg GPL incluido): [docs/legal/licensing.md](../docs/legal/licensing.md#el-trabajador-remotion-ffmpeg-y-fuentes).

- `schema/guion.schema.json`: lo único que acepta, datos y nunca código; con sus ejemplos en `schema/ejemplos/`
  ([docs/ai §12.1](../docs/ai/README.md#121-el-guion-de-vídeo-esquema-1)).
- `src/server.mjs`: `PUT /renders/{trabajo}`, `GET /renders/{trabajo}/video.mp4` y `/subtitulos.vtt`, `DELETE
/renders/{trabajo}` y `GET /health`, con `node:http` y `Authorization: Bearer`.
- `src/remotion/`: las composiciones del proyecto; `fonts/`: las fuentes OFL.
- Configuración por entorno: `src/main.mjs`. Despliegue: [docs/operations](../docs/operations/README.md#render-de-vídeo-opcional).

```bash
yarn install --frozen-lockfile
yarn test                                   # renderizador simulado
RENDER_WORKER_BROWSER=/usr/bin/google-chrome yarn test   # también un render de verdad
yarn run licenses                           # informe de licencias
docker build -t render-worker .
```
