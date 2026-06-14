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

# 使用自定义nginx配置（处理React Router + API反向代理）
# Custom nginx config: handles React Router client-side routing + API proxy
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
