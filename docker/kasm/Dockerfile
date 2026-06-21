FROM ghcr.io/linuxserver/baseimage-kasmvnc:ubuntunoble

ENV DEBIAN_FRONTEND=noninteractive
ENV DISPLAY=:1
ENV POPOTO_REPO=https://github.com/BoiledElectricity/popoto_discover.git
ENV POPOTO_BRANCH=main
ENV POPOTO_WORK=/opt/popoto-discover/work
ENV POPOTO_CACHE=/opt/popoto-discover/cache
ENV POPOTO_JAR=/opt/popoto-discover/cache/popoto-discover.jar
ENV TITLE="Popoto Discover"

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        dbus-x11 \
        fonts-dejavu \
        git \
        iproute2 \
        iputils-ping \
        libasound2t64 \
        libgtk-3-0 \
        net-tools \
        openjdk-21-jdk \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/popoto-discover /custom-cont-init.d
COPY docker/kasm/init-popoto-discover.sh /custom-cont-init.d/20-popoto-discover
COPY docker/kasm/popoto-autostart /defaults/autostart
COPY docker/kasm/popoto-discover-launcher.sh /usr/local/bin/popoto-discover-launcher.sh
RUN chmod +x /custom-cont-init.d/20-popoto-discover /usr/local/bin/popoto-discover-launcher.sh

EXPOSE 3000 3001
