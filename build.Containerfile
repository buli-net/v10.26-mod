# Reproducible reference build - trixie + JDK21 + Gradle Wrapper
FROM debian:trixie-slim AS build-stage

ENV DEBIAN_FRONTEND=noninteractive
RUN --mount=target=/var/lib/apt/lists,type=cache,sharing=locked \
    --mount=target=/var/cache/apt,type=cache,sharing=locked \
    apt-get update && \
    apt-get --yes --no-install-recommends install disorderfs openjdk-21-jdk-headless sdkmanager ca-certificates && \
    ln -fs /usr/share/zoneinfo/CET /etc/localtime && \
    dpkg-reconfigure --frontend noninteractive tzdata && \
    ln -s /proc/self/mounts /etc/mtab && \
    adduser --disabled-login --gecos "" builder

USER builder
WORKDIR /home/builder
COPY --chown=builder / project/
RUN chmod +x project/gradlew

ENV ANDROID_HOME=/home/builder/android-sdk
RUN --mount=target=/home/builder/android-sdk,type=cache,uid=1000,gid=1000,sharing=locked \
    yes | sdkmanager --licenses >/dev/null

RUN --mount=target=/home/builder/android-sdk,type=cache,uid=1000,gid=1000,sharing=locked \
    --mount=target=/home/builder/.gradle,type=cache,uid=1000,gid=1000,sharing=locked \
    if [ -e /dev/fuse ]; then \
      mv project project.u && mkdir project && \
      disorderfs --sort-dirents=yes --reverse-dirents=no project.u project; \
    fi && \
    cd project && \
    ./gradlew --no-build-cache --no-daemon --no-parallel clean :wallet:assembleRelease && \
    if [ -e /dev/fuse ]; then \
      sleep 1 && fusermount -u project && rmdir project && mv project.u project; \
    fi

FROM scratch AS export-stage
COPY --from=build-stage /home/builder/project/wallet/build/outputs/apk/dev/release/*.apk /
COPY --from=build-stage /home/builder/project/wallet/build/outputs/apk/prod/release/*.apk /