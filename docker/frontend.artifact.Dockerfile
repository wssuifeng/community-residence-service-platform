FROM nginx:1.30.6-alpine

COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/dist /usr/share/nginx/html

EXPOSE 80
