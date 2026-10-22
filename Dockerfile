# syntax=docker/dockerfile:1
#
# One image for the whole system: the Spring Boot API, which also serves the three web apps
# (POS, customer display, back-office dashboard) built by Vite. One container behind the ALB,
# same origin for the apps and the API, no CORS.
#
#   docker build -t counterline-pos .
#
# Tests are not run here; run them separately (the backend's need Docker for Testcontainers).

# ---- 1. The web apps ----
FROM node:22-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- 2. The API, with the web apps as its static files ----
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /backend
# Dependencies first, so they stay cached while only the source changes.
COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY backend/src/ src/
COPY --from=frontend /frontend/dist/ src/main/resources/static/
RUN ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/pos-api-*.jar extract --layers --launcher --destination extracted

# ---- 3. Runtime: a JRE only, as a non-root user ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S pos && adduser -S -G pos pos
WORKDIR /app
# Spring Boot's layers, least to most often changed, so a code change ships a small layer.
COPY --from=backend /backend/extracted/dependencies/ ./
COPY --from=backend /backend/extracted/spring-boot-loader/ ./
COPY --from=backend /backend/extracted/snapshot-dependencies/ ./
COPY --from=backend /backend/extracted/application/ ./
USER pos
EXPOSE 8080
# Size the heap from the container's memory limit (the Fargate task size); restart on OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
