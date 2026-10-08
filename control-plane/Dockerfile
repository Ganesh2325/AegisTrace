FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/control-plane.jar /app/control-plane.jar
RUN groupadd --system aegis && useradd --system --gid aegis --home-dir /app --shell /usr/sbin/nologin aegis
USER aegis
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/control-plane.jar"]
