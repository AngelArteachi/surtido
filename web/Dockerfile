FROM node:24-alpine AS build
WORKDIR /app
COPY web/package.json web/package-lock.json ./
# `extra_ca` is optional, as in the services' Dockerfile.
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/extra_ca; fi; \
    npm ci --no-audit --no-fund
COPY web ./
RUN npx vite build

FROM nginx:1.31-alpine
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
