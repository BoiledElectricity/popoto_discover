FROM eclipse-temurin:21-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive
ENV GRADLE_USER_HOME=/root/.gradle

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        git \
        python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /tmp/gradle-bootstrap
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/wrapper/ gradle/wrapper/
RUN chmod +x gradlew \
    && ./gradlew --no-daemon test shadowJar >/dev/null \
    && rm -rf /tmp/gradle-bootstrap

WORKDIR /work
