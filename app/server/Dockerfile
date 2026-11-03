# 构建上下文是 app/（不是本目录），因为 Maven 需要父 pom 与 common 模块。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom，把依赖层缓存住：改代码不会重新下载依赖。
COPY pom.xml ./
COPY common/pom.xml common/
COPY server/pom.xml server/
COPY auth/pom.xml auth/
RUN mvn -B -pl server -am -DskipTests dependency:go-offline

COPY common/src common/src
COPY server/src server/src
RUN mvn -B -pl server -am -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 appuser
COPY --from=build /build/server/target/server-*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
