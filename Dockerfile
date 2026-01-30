# Multi-stage build for Spring Boot application (Gradle)
# Gradle 공식 이미지 사용 (wrapper JAR 없이 빌드)
FROM gradle:8.5-jdk17-alpine AS build
WORKDIR /app

# 빌드 설정만 먼저 복사해 의존성 레이어 캐시
COPY build.gradle .
COPY settings.gradle .
RUN gradle dependencies --no-daemon || true

# 소스 복사 및 빌드
COPY src ./src
RUN gradle bootJar -x test --no-daemon

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
