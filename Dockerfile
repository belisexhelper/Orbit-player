# ---- sajt i admin panel ----
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---- backend ----
FROM node:22-alpine AS api
WORKDIR /api
COPY backend/package.json backend/package-lock.json ./
RUN npm ci
COPY backend/ ./
RUN npm run build && npm prune --omit=dev

# ---- produkcija ----
FROM node:22-alpine
ENV NODE_ENV=production PORT=8080 DATA_DIR=/data WEB_DIR=/app/web
WORKDIR /app
COPY --from=api /api/node_modules ./node_modules
COPY --from=api /api/dist ./dist
COPY --from=api /api/package.json ./
COPY --from=web /web/dist ./web
RUN mkdir -p /data && chown node:node /data
USER node
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:8080/api/health || exit 1
CMD ["node", "dist/server.js"]
