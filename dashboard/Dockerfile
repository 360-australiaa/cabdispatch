# Cab Dispatch admin dashboard -- production image. Vite bakes VITE_* env
# vars into the built JS at BUILD time (not runtime), so they must arrive as
# build ARGs here, not as a docker-compose `environment:` entry on the
# final image -- that would have no effect on an already-built static bundle.
FROM node:20-alpine AS builder

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Baked into the static bundle at build time -- see docker-compose.yml's
# dashboard.build.args for how these are supplied.
ARG VITE_API_URL
ARG VITE_MAPBOX_TOKEN
ENV VITE_API_URL=${VITE_API_URL}
ENV VITE_MAPBOX_TOKEN=${VITE_MAPBOX_TOKEN}

RUN npm run build

FROM nginx:1.27-alpine AS runtime
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

# 127.0.0.1, not localhost: on the Alpine image `localhost` can resolve to ::1
# first, and nginx below listens on IPv4 only, so the probe was refused while
# the site served perfectly well through Caddy -- `compose up --wait` then
# reported the dashboard "unhealthy" on the 2026-09-09 redeploy.
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -qO- http://127.0.0.1:80/ >/dev/null 2>&1 || exit 1