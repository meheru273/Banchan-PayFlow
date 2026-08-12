# Build context is the repo root (see render.yaml dockerContext) because the
# multi-module build needs the parent pom and the common module.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY common/pom.xml common/
COPY payment-service/pom.xml payment-service/
# Warm the dependency cache; tolerated to fail because go-offline cannot
# resolve reactor-internal artifacts (payflow-common) before they are built.
RUN mvn -B -q dependency:go-offline || true
COPY common/src common/src
COPY payment-service/src payment-service/src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/payment-service/target/payment-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
