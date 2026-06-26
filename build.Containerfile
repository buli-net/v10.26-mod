FROM debian:bullseye-slim AS build-stage
ENV DEBIAN_FRONTEND=noninteractive

RUN echo 'deb http://archive.debian.org/debian bullseye main' > /etc/apt/sources.list && \
    mkdir -p /etc/apt/apt.conf.d && \
    echo 'Acquire::Check-Valid-Until "false";' > /etc/apt/apt.conf.d/99no-check

RUN apt-get update && \
    apt-get -y install --no-install-recommends disorderfs openjdk-11-jdk-headless gradle wget unzip && \
    ln -fs /usr/share/zoneinfo/CET /etc/localtime && \
    dpkg-reconfigure -f noninteractive tzdata && \
    ln -s /proc/self/mounts /etc/mtab && \
    adduser --disabled-login --gecos "" builder

USER builder
WORKDIR /home/builder
ENV ANDROID_HOME=/home/builder/android-sdk
RUN mkdir -p $ANDROID_HOME/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-8512546_latest.zip -O /tmp/tools.zip && \
    unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools && \
    mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest && \
    rm /tmp/tools.zip && \
    yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null

ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
WORKDIR /home/builder
COPY --chown=builder / project/

RUN --mount=target=/home/builder/android-sdk,type=cache,uid=1000 \
    --mount=target=/home/builder/.gradle,type=cache,uid=1000 \
    cd project && gradle --no-build-cache --no-daemon clean :wallet:assembleRelease

FROM scratch AS export-stage
COPY --from=build-stage /home/builder/project/wallet/build/outputs/apk/dev/release/*.apk /
COPY --from=build-stage /home/builder/project/wallet/build/outputs/apk/prod/release/*.apk /