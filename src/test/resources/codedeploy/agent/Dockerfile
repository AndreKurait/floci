ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY agent.deb /tmp/agent.deb
RUN dpkg-deb --extract /tmp/agent.deb / \
    && rm /tmp/agent.deb \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends openssh-server ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /run/sshd /var/lib
ENTRYPOINT ["sh", "-ec", "trap 'exit 0' TERM; sleep 2147483647 & wait"]
