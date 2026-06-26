# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM node:20-alpine AS build

WORKDIR /app

# 先复制package文件并安装依赖（层缓存优化）
# Copy package files first — npm ci layer is cached unless package-lock.json changes
COPY package*.json ./
RUN npm ci --silent

# 复制源码并构建
COPY . .
RUN npm run build

# ── Stage 2: Serve with nginx ──────────────────────────────────────────────────
# nginx:alpine ~50MB — much smaller than the node build image
FROM nginx:alpine

# 将Vite构建产物复制到nginx的web根目录
# Copy the Vite build output to nginx's web root
COPY --from=build /app/dist /usr/share/nginx/html

# 使用自定义nginx配置模板（处理React Router + API反向代理）
# nginx官方镜像在启动时会自动对/etc/nginx/templates/*.template做envsubst替换，
# 写入/etc/nginx/conf.d/ —— 这样BACKEND_HOST可以在容器启动时按环境注入，
# 同一个镜像本地docker-compose和Azure Container Apps都能用，不需要为每个环境重新build。
#
# Custom nginx config template: handles React Router routing + API proxy.
# The official nginx image auto-envsubst's /etc/nginx/templates/*.template at container
# startup into /etc/nginx/conf.d/ — so BACKEND_HOST is injected per-environment at runtime;
# the same image works unmodified in docker-compose and Azure Container Apps.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

EXPOSE 80
