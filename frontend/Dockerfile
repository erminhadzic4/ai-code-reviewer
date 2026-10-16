# Two stages: Node builds the static files, nginx serves them. The final image holds no
# Node, no node_modules and no sources.

FROM node:24.21.0-alpine3.24 AS build
WORKDIR /build

# Dependencies first: this layer is rebuilt only when the lock file changes.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:1.30.6-alpine3.24
# Replaces the image's default site.
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
