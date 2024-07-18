FROM ktis-bastion01.container.ipc.kt.com:5000/arsenal/nginx:1.17.9-alpine-http_header

ENV TZ Asia/Seoul
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone 

COPY ./dist /usr/share/nginx/html
COPY ./devops/jenkins/vue-nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
