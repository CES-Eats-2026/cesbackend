# Multi-stage build for Spring Boot application (Gradle)
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Gradle wrapper + 설정 파일
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# 의존성만 먼저 다운로드 (캐시 활용)
RUN ./gradlew dependencies --no-daemon || true

# 소스 복사 및 빌드
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 앱 유저 및 데이터 디렉터리
RUN addgroup -S spring && adduser -S spring -G spring
RUN mkdir -p /app/data /app/logs && chown -R spring:spring /app/data /app/logs

# 빌드된 JAR 복사
COPY --from=build /app/build/libs/*.jar app.jar

USER spring:spring

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/health || exit 1

ENTRYPOINT ["java", "-jar", "-Dspring.profiles.active=prod", "app.jar"]
