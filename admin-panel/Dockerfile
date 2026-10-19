# symptree admin-panel — Vite + React + TS SPA (FR-XKH348).
# The static bundle is built on the developer machine (npm run build → dist/, driven
# by scripts/deploy.sh), mirroring how core/messaging ship prebuilt jars. This image
# only serves the prebuilt dist via nginx, which also reverse-proxies /api → core so
# the SPA stays same-origin (no CORS) in every environment. No Node/source lands in
# the image or on the deploy host.

# Context is the project root (see docker-compose), so paths are prefixed with
# admin-panel/.
FROM nginx:1.27-alpine AS runner
COPY admin-panel/nginx.conf /etc/nginx/conf.d/default.conf
COPY admin-panel/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
