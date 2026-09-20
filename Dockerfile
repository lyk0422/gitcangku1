# syntax=docker/dockerfile:1
FROM gradle:8.14.4-jdk21@sha256:cc90b5198f747c454f4a3e17495b626fbdfa7ac77f1ad08502c355e6f81e6772 AS build
USER root
WORKDIR /workspace
RUN chown gradle:gradle /workspace
COPY --chown=gradle:gradle build.gradle settings.gradle gradle.properties ./
USER gradle
ARG GSB_BUILD_SCOPE=local
RUN --mount=type=cache,id=${GSB_BUILD_SCOPE}-gradle,target=/home/gradle/.gradle,uid=1000,gid=1000,sharing=locked \
    gradle --no-daemon dependencies --configuration runtimeClasspath
COPY --chown=gradle:gradle src/main ./src/main
RUN --mount=type=cache,id=${GSB_BUILD_SCOPE}-gradle,target=/home/gradle/.gradle,uid=1000,gid=1000,sharing=locked \
    gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy@sha256:61d6c7b34d36aee3f45d043101259f97f3c6d428dc2a6f75513789983c5e254f
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/build/libs/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]
