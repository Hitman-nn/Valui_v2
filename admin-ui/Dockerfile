FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .
ARG VITE_BASE_URL=/admin/
ENV VITE_BASE_URL=${VITE_BASE_URL}
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80
# API_UPSTREAM: "host.docker.internal:8080" (dev) or "app:8080" (prod, Docker network)
ENV API_UPSTREAM=host.docker.internal:8080
CMD ["nginx", "-g", "daemon off;"]
