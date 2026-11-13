FROM alpine:3.21
RUN apk add --no-cache curl openssh
COPY entrypoint.sh /entrypoint.sh
ENTRYPOINT ["sh", "/entrypoint.sh"]
