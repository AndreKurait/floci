FROM alpine:3.21 AS init-build
RUN apk add --no-cache build-base
COPY ec2-init.c /src/ec2-init.c
RUN cc -Os -static -Wall -Wextra -Werror -o /floci-ec2-init /src/ec2-init.c

FROM alpine:3.21
RUN apk add --no-cache nftables iptables iproute2 socat curl dante-server
COPY --from=init-build /floci-ec2-init /usr/local/bin/floci-ec2-init
CMD ["sleep", "2147483647"]
