# syntax=docker/dockerfile:1.7
# QuantGen SPA: build with Node, serve with unprivileged nginx (ARCHITECTURE §2, §9).

# ── Build ─────────────────────────────────────────────────────────────────────────────
FROM node:24.11-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# Production build: MSW, the mock worker and the demo login are stripped (see vite.config.ts).
RUN npm run build

# ── Serve ─────────────────────────────────────────────────────────────────────────────
FROM nginxinc/nginx-unprivileged:1.29-alpine AS runtime
# Upstream API host:port, resolved by the compose network (overridable).
ENV QG_API_UPSTREAM=api:8080
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
