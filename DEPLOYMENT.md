# Gabia 백엔드 배포 가이드

## 1. JAR 파일 빌드

### 로컬에서 빌드

```bash
cd backend
./mvnw clean package -DskipTests
```

또는

```bash
mvn clean package -DskipTests
```

빌드된 JAR 파일 위치: `target/ces-eats-backend-0.0.1-SNAPSHOT.jar`

## 2. 프로덕션 설정 파일 생성

`src/main/resources/application-prod.properties` 파일 생성:

```properties
spring.application.name=ces-eats-backend
server.port=8080

# CORS 설정 (프로덕션 프론트엔드 URL로 변경)
spring.web.cors.allowed-origins=https://your-vercel-app.vercel.app
spring.web.cors.allowed-methods=GET,POST,PUT,DELETE,OPTIONS
spring.web.cors.allowed-headers=*

# H2 Database 설정 (메모리 DB - 재시작 시 데이터 초기화됨)
spring.datasource.url=jdbc:h2:mem:ceseats
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.show-sql=false

spring.h2.console.enabled=false

# Google Places API Key (환경 변수로 설정)
google.places.api.key=${GOOGLE_PLACES_API_KEY}

# OpenAI API Key (환경 변수로 설정)
openai.api.key=${OPENAI_API_KEY}
openai.api.url=https://api.openai.com/v1/chat/completions
```

## 3. Gabia 서버에 배포

### 방법 1: SSH를 통한 배포

1. **JAR 파일 업로드**
   ```bash
   scp target/ces-eats-backend-0.0.1-SNAPSHOT.jar username@gabia-server:/path/to/app/
   ```

2. **SSH 접속**
   ```bash
   ssh username@gabia-server
   ```

3. **애플리케이션 실행**
   ```bash
   cd /path/to/app
   
   # 기존 프로세스 종료 (있다면)
   pkill -f ces-eats-backend
   
   # 환경 변수 설정 후 실행
   export GOOGLE_PLACES_API_KEY=your_api_key
   export OPENAI_API_KEY=your_openai_key
   
   # 백그라운드 실행
   nohup java -jar -Dspring.profiles.active=prod ces-eats-backend-0.0.1-SNAPSHOT.jar > app.log 2>&1 &
   ```

4. **프로세스 확인**
   ```bash
   ps aux | grep ces-eats-backend
   ```

5. **로그 확인**
   ```bash
   tail -f app.log
   ```

### 방법 2: systemd 서비스로 등록 (권장)

1. **서비스 파일 생성** (`/etc/systemd/system/ceseats.service`)
   ```ini
   [Unit]
   Description=CES Eats Backend Service
   After=network.target

   [Service]
   Type=simple
   User=your-username
   WorkingDirectory=/path/to/app
   Environment="GOOGLE_PLACES_API_KEY=your_api_key"
   Environment="OPENAI_API_KEY=your_openai_key"
   ExecStart=/usr/bin/java -jar -Dspring.profiles.active=prod /path/to/app/ces-eats-backend-0.0.1-SNAPSHOT.jar
   Restart=always
   RestartSec=10

   [Install]
   WantedBy=multi-user.target
   ```

2. **서비스 시작**
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable ceseats
   sudo systemctl start ceseats
   ```

3. **서비스 상태 확인**
   ```bash
   sudo systemctl status ceseats
   ```

4. **로그 확인**
   ```bash
   sudo journalctl -u ceseats -f
   ```

## 4. 방화벽 설정

Gabia 서버에서 포트 8080이 열려있는지 확인:

```bash
# 방화벽 상태 확인
sudo ufw status

# 포트 8080 열기 (필요한 경우)
sudo ufw allow 8080/tcp
```

## 5. 환경 변수 설정

### SSH 접속 시

`.bashrc` 또는 `.bash_profile`에 추가:

```bash
export GOOGLE_PLACES_API_KEY=your_google_places_api_key
export OPENAI_API_KEY=your_openai_api_key
```

### systemd 서비스 사용 시

서비스 파일의 `Environment` 섹션에 설정 (위 참고)

## 6. CORS 설정 업데이트

프로덕션 프론트엔드 URL을 `application-prod.properties`에 추가:

```properties
spring.web.cors.allowed-origins=https://your-vercel-app.vercel.app,https://your-custom-domain.com
```

또는 모든 도메인 허용 (개발용, 프로덕션에서는 권장하지 않음):

```properties
spring.web.cors.allowed-origins=*
```

## 7. 데이터베이스 설정 (선택사항)

현재는 H2 인메모리 DB를 사용하고 있습니다. 프로덕션에서는 MySQL 또는 PostgreSQL 사용을 권장합니다.

### MySQL 설정 예시

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/ceseats?useSSL=false&serverTimezone=UTC
spring.datasource.username=your_username
spring.datasource.password=your_password
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect
spring.jpa.hibernate.ddl-auto=update
```

`pom.xml`에 MySQL 의존성 추가:

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

## 8. 배포 확인

1. **애플리케이션 실행 확인**
   ```bash
   curl http://localhost:8080/api/recommendations
   ```

2. **외부에서 접근 확인**
   ```bash
   curl http://your-server-ip:8080/api/recommendations
   ```

3. **프론트엔드에서 API URL 업데이트**
   - Vercel 환경 변수: `NEXT_PUBLIC_API_URL=https://your-server-ip:8080/api`

## 9. 문제 해결

### 포트가 이미 사용 중인 경우

```bash
# 포트 사용 중인 프로세스 확인
sudo lsof -i :8080

# 프로세스 종료
sudo kill -9 PID
```

### 메모리 부족

JVM 메모리 설정:

```bash
java -Xms512m -Xmx1024m -jar ces-eats-backend-0.0.1-SNAPSHOT.jar
```

### 로그 확인

```bash
# 애플리케이션 로그
tail -f app.log

# systemd 서비스 로그
sudo journalctl -u ceseats -f
```

## 10. 자동 배포 스크립트 예시

`deploy.sh` 파일 생성:

```bash
#!/bin/bash

# 빌드
./mvnw clean package -DskipTests

# 서버에 업로드
scp target/ces-eats-backend-0.0.1-SNAPSHOT.jar username@gabia-server:/path/to/app/

# 서버에서 재시작
ssh username@gabia-server 'cd /path/to/app && sudo systemctl restart ceseats'
```

실행 권한 부여:

```bash
chmod +x deploy.sh
```

## 참고

- [Spring Boot 배포 가이드](https://spring.io/guides/gs/spring-boot-for-azure/)
- [Gabia 서버 관리 가이드](https://www.gabia.com/)

