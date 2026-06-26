#
# Reproducible reference build - fixed for 2026
#
FROM debian:bullseye-slim AS build-stage

# bullseye đã EOL, dùng archive
ENV DEBIAN_FRONTEND=noninteractive
RUN echo 'deb http://archive.debian.org/debian bullseye main' > /etc/apt/sources.list && \
    echo 'deb http://archive.debian.org/debian-security bullseye-security main' >> /etc/apt/sources.list && \
    echo 'deb http://archive.debian.org/debian bullseye-backports main' >> /etc/apt/sources.list && \
    echo 'Acquire::Check-Valid-Until "false";' > /etc/apt/apt.conf.d/99no-check

# install debian packages
RUN --mount=target=/var/lib/apt/lists,type=cache,sharing=locked \
    --mount=target=/var/cache/apt,type=cache,sharing=locked \
    /bin/rm -f /etc/apt/apt.conf.d/docker-clean && \
    /usr/bin/apt-get update && \
    /usr/bin/apt-get --yes --no-install-recommends install disorderfs openjdk-11-jdk-headless gradle sdkmanager && \
    /bin/ln -fs /usr/share/zoneinfo/CET /etc/localtime && \
    /usr/sbin/dpkg-reconfigure --frontend noninteractive tzdata && \
    /bin/ln -s /proc/self/mounts /etc/mtab && \
    /usr/sbin/adduser --disabled-login --gecos "" builder

# give up privileges
USER builder

# copy project source code
WORKDIR /home/builder
COPY --chown=builder / project/

# accept SDK licenses
ENV ANDROID_HOME /home/builder/android-sdk
RUN --mount=target=/home/builder/android-sdk,type=cache,uid=1000,gid=1000,sharing=locked \
    yes | /usr/bin/sdkmanager --licenses >/dev/null

# build project
RUN --mount=target=/home/builder/android-sdk,type=cache,uid=1000,gid=1000,sharing=locked \
    --mount=target=/home/builder/.gradle,type=cache,uid=1000,gid=1000,sharing=locked \
    if [ -e /dev/fuse ] ; \
      then /bin/mv project project.u && /bin/mkdir project && \
      /usr/bin/disorderfs --sort-dirents=yes --reverse-dirents=no project.u project ; \
    fi && \
    /usr/bin/gradle --project-dir project/ --no-build-cache --no-daemon --no-parallel clean :wallet:assembleRelease && \
    if [ -e /dev/fuse ] ; \
      then /bin/fusermount -u project | true && /bin/rmdir project && /bin/mv project.u project ; \
    fi

# export build output
FROM scratch AS export-stage
COPY --from=build-stage /home/builder/project/wallet/build/outputs/apk/*/release/bitcoin-wallet-*-release-unsigned.apk /