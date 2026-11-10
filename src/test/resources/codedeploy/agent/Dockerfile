ARG BASE_IMAGE
FROM python@sha256:213242b537a1c166a0c53e903461fc18d5518e7196c93dbecda452660a848918 AS certificates
FROM ${BASE_IMAGE}
COPY --from=certificates /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY agent.deb /tmp/agent.deb
RUN dpkg-deb --extract /tmp/agent.deb / \
    && rm /tmp/agent.deb \
    && sed -i 's@http://@https://@g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends openssh-server ca-certificates busybox-static \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /run/sshd /var/lib
ENTRYPOINT ["sh", "-ec", "trap 'exit 0' TERM; sleep 2147483647 & wait"]
