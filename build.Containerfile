FROM debian:bullseye-slim AS build-stage
ENV DEBIAN_FRONTEND=noninteractive

# bullseye EOL -> dùng archive
RUN echo 'deb http://archive.debian.org/debian bullseye main' > /etc/apt/sources.list && \
    echo 'deb http://archive.debian.org/debian-security bullseye-security main' >> /etc/apt/sources.list && \
    echo 'Acquire::Check-Valid-Until "false";' > /etc/apt/apt.conf.d/99no-check

RUN apt-get update && \
    apt-get -y install disorderfs openjdk-11-jdk-headless gradle sdkmanager && \
    ln -fs /usr/share/zoneinfo/CET /etc/localtime && \
    dpkg-reconfigure -f noninteractive tzdata && \
    ln -s /proc/self/mounts /etc/mtab && \
    adduser --disabled-login --gecos "" builder

USER builder
WORKDIR /home/builder
COPY --chown=builder / project/

ENV ANDROID_HOME=/home/builder/android-sdk
RUN yes | sdkmanager --licenses >/dev/null

RUN --mount=target=/home/builder/android-sdk,type=cache,uid=1000 \
    --mount=target=/home/builder/.gradle,type=cache,uid=1000 \
    cd project && gradle --no-build-cache --no-daemon clean :wallet:assembleRelease

FROM scratch AS export-stage
COPY --from=build-stage /home/builder/project/wallet/build/outputs/apk/dev/release/*.apk /
COPY --from=build-stage /home/builder/project/wallet/build/outputs/apk/prod/release/*.apk /