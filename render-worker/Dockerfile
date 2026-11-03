# imagen del trabajador de render (ADR-0040 §1). La construye quien despliega: el proyecto no publica ninguna imagen con
# Remotion (ADR-0039 §7). Quien la construye y la usa responde de su licencia de Remotion (remotion.dev/license). Trae
# Chrome Headless Shell (lo descarga @remotion/renderer al construir) y el ffmpeg/ffprobe de @remotion/compositor, cuya
# compilación es GPL (docs/legal/licensing.md). En marcha no necesita salida a Internet: solo la red interna con el backend
FROM node:26-bookworm-slim

# lo que necesita Chrome Headless Shell en Debian (docs de Remotion, «Dockerizing a Remotion project»)
RUN apt-get update \
  && apt-get install -y --no-install-recommends ca-certificates libnss3 libdbus-1-3 libatk1.0-0 libgbm1 libasound2 libxrandr2 \
     libxkbcommon0 libxfixes3 libxcomposite1 libxdamage1 libatk-bridge2.0-0 libpango-1.0-0 libcairo2 libcups2 \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY package.json yarn.lock ./
RUN yarn install --frozen-lockfile --production && yarn cache clean
COPY src ./src
COPY fonts ./fonts
COPY schema ./schema
COPY scripts ./scripts
RUN node scripts/ensure-browser.mjs

ENV NODE_ENV=production PORT=8095 RENDER_WORKER_DIR=/tmp/render-worker
USER node
EXPOSE 8095
HEALTHCHECK --interval=30s --timeout=5s CMD node -e "fetch('http://127.0.0.1:8095/health').then(r => process.exit(r.ok ? 0 : 1), () => process.exit(1))"
CMD ["node", "src/main.mjs"]
