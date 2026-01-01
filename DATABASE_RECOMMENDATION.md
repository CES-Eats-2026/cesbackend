# 데이터베이스 선택 가이드

## 현재 상황
- **현재 DB**: H2 파일 기반 (`jdbc:h2:file:./data/ceseats`)
- **사용 목적**: 장소 조회수 추적 (PlaceView 엔티티)
- **프로젝트 단계**: MVP
- **배포 환경**: Gabia (리눅스 서버)

## H2 파일 기반 DB의 단점

### 1. 동시성 제한
- 동시 접속자 수가 많을 때 성능 저하
- 동시 쓰기 작업 시 락(lock) 경합 발생
- 트랜잭션 충돌 가능성

### 2. 확장성 제한
- 단일 서버에서만 사용 가능
- 클러스터링/리플리케이션 불가능
- 수평 확장 불가능

### 3. 프로덕션 환경 부적합
- 대용량 트래픽 처리에 부적합
- 데이터 손실 위험 (파일 손상 시)
- 백업/복구 기능 제한적

### 4. 운영 관리 제한
- 모니터링 도구 제한적
- 성능 튜닝 옵션 부족
- 전문 DBA 지원 어려움

## 추천 데이터베이스

### 1. PostgreSQL (최우선 추천) ⭐

**장점:**
- 오픈소스, 무료
- 강력한 기능 (JSON, Full-text search, GIS 등)
- 높은 동시성 처리
- Spring Boot와 완벽한 호환성
- 프로덕션 환경에서 널리 사용
- 확장성 우수

**단점:**
- H2보다 설정이 복잡
- 별도 서버 설치 필요

**언제 사용:**
- 프로덕션 배포 시
- 향후 확장 계획이 있을 때
- 복잡한 쿼리가 필요할 때

**설치:**
```bash
# Ubuntu/Debian
sudo apt-get update
sudo apt-get install postgresql postgresql-contrib

# 시작
sudo systemctl start postgresql
sudo systemctl enable postgresql
```

**Spring Boot 설정:**
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/ceseats
spring.datasource.username=ceseats
spring.datasource.password=your_password
spring.datasource.driver-class-name=org.postgresql.Driver
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
```

---

### 2. MySQL / MariaDB

**장점:**
- 널리 사용됨 (커뮤니티 크고 자료 많음)
- 간단한 설정
- Spring Boot와 호환성 우수
- 가벼움

**단점:**
- PostgreSQL보다 기능 제한적
- 일부 고급 기능 부족

**언제 사용:**
- 간단한 CRUD 위주
- MySQL에 익숙한 팀
- 호스팅 서비스에서 제공하는 경우

**설치:**
```bash
# Ubuntu/Debian
sudo apt-get install mysql-server

# 또는 MariaDB
sudo apt-get install mariadb-server
```

**Spring Boot 설정:**
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/ceseats?useSSL=false&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=your_password
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect
```

---

### 3. SQLite (경량 옵션)

**장점:**
- 매우 가벼움 (단일 파일)
- 설정 불필요
- 서버 설치 불필요
- 읽기 성능 우수

**단점:**
- 동시 쓰기 제한 (읽기는 괜찮음)
- 프로덕션 대용량 트래픽 부적합
- 네트워크 접근 불가 (로컬 파일만)

**언제 사용:**
- 소규모 프로젝트
- 단일 서버 환경
- 읽기 위주 작업

**Spring Boot 설정:**
```properties
spring.datasource.url=jdbc:sqlite:./data/ceseats.db
spring.datasource.driver-class-name=org.sqlite.JDBC
spring.jpa.database-platform=org.hibernate.dialect.SQLiteDialect
```

---

## 현재 프로젝트 추천

### 단기 (현재 MVP 단계)
**H2 파일 기반 유지** 또는 **SQLite로 전환**
- 이유: 
  - 현재 조회수 추적 정도의 간단한 용도
  - 설정 변경 최소화
  - 빠른 개발/배포

### 중장기 (프로덕션 확장 시)
**PostgreSQL로 전환**
- 이유:
  - 프로덕션 환경에 적합
  - 향후 기능 확장 시 유연성
  - 높은 동시성 처리
  - 무료이면서 강력한 기능

## 마이그레이션 전략

### H2 → PostgreSQL 마이그레이션 예시

1. **PostgreSQL 설치 및 DB 생성**
```sql
CREATE DATABASE ceseats;
CREATE USER ceseats_user WITH PASSWORD 'your_password';
GRANT ALL PRIVILEGES ON DATABASE ceseats TO ceseats_user;
```

2. **H2 데이터 내보내기**
```bash
# H2 콘솔에서 SQL 덤프
# 또는 Spring Boot에서 직접 마이그레이션
```

3. **application.properties 수정**
```properties
# 개발 환경
spring.profiles.active=dev
# application-dev.properties에서 H2 사용

# 프로덕션 환경
# application-prod.properties에서 PostgreSQL 사용
```

4. **의존성 추가 (pom.xml)**
```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

## 결론

**현재 단계**: H2 파일 기반 유지 (또는 SQLite)
- MVP 단계에서는 충분
- 빠른 개발/배포 가능

**프로덕션 전환 시**: PostgreSQL
- 무료이면서 강력
- 확장성 우수
- Spring Boot와 완벽한 호환

