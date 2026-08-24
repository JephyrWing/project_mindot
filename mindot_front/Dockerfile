# ===== Build assets =====
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm install
COPY . .
ARG VITE_API_BASE_URL=/

RUN npm run build

# ===== Nginx serve =====
FROM nginx:1.27-alpine
# default.conf로 copy하면 알아서 덮어써짐, nginx.conf의 이름은 꼭 nginx-conf.conf일 필요는 없음. 확장자만 맞으면 됨
COPY ./nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
