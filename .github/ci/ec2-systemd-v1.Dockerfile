FROM public.ecr.aws/amazonlinux/amazonlinux@sha256:957ff255a4ca9c60138ea636b8ae09e29ba146ea7bc95d0dae3923a5faeeb122
RUN . /etc/os-release && test "$ID" = amzn && test "$VERSION_ID" = 2
RUN yum -y install systemd systemd-sysv dbus cloud-init openssh-server cronie iproute procps-ng util-linux curl \
    && systemctl enable cloud-init-local.service cloud-init.service cloud-config.service \
       cloud-final.service sshd.service crond.service \
    && yum clean all
CMD ["/sbin/init"]
