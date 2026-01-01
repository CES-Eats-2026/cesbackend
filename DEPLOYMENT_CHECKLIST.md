# 배포 전 확인 체크리스트

## 1. 사전 확인 스크립트 실행

```bash
cd backend
./pre-deployment-check.sh [gabia-user]@[gabia-host] [deploy-path]
```

예시:
```bash
./pre-deployment-check.sh ubuntu@123.456.789.0 /opt/ceseats
```

## 2. 수동 확인 사항

### ✅ GitHub Secrets 설정 확인
- [ ] `DOCKER_USERNAME` - Docker Hub 사용자명
- [ ] `DOCKER_PASSWORD` - Docker Hub 비밀번호 또는 Access Token
- [ ] `GABIA_HOST` - 가비아 서버 주소
- [ ] `GABIA_USER` - SSH 사용자명
- [ ] `GABIA_SSH_KEY` - SSH Private Key
- [ ] `GABIA_DEPLOY_PATH` - 배포 경로
- [ ] `GOOGLE_PLACES_API_KEY` - Google Places API 키
- [ ] `OPENAI_API_KEY` - OpenAI API 키 (선택사항)

### ✅ 서버 환경 확인
- [ ] Docker 설치 확인: `docker --version`
- [ ] Docker 실행 권한 확인: `docker ps`
- [ ] 배포 경로 생성 및 권한 확인
- [ ] 포트 8080, 8081 사용 가능 확인
- [ ] 디스크 공간 충분한지 확인

### ✅ 네트워크 및 보안
- [ ] SSH 접속 테스트
- [ ] 방화벽 설정 확인 (포트 8080, 8081 열림)
- [ ] 클라우드 보안 그룹 설정 확인 (가비아)

### ✅ Docker Hub 확인
- [ ] Docker Hub 로그인 테스트: `docker login`
- [ ] 이미지 푸시 권한 확인

## 3. 첫 배포 테스트

### 방법 1: GitHub Actions를 통한 자동 배포
1. `main` 브랜치에 코드 푸시
2. GitHub Actions 탭에서 워크플로우 실행 확인
3. 배포 로그 확인

### 방법 2: 수동 배포 테스트
```bash
# 서버에 SSH 접속
ssh [gabia-user]@[gabia-host]

# 배포 스크립트 다운로드 및 실행
cd /opt/ceseats
export DOCKER_USERNAME=your-dockerhub-username
export GOOGLE_PLACES_API_KEY=your-api-key
export OPENAI_API_KEY=your-openai-key
./blue-green-deploy.sh
```

## 4. 배포 후 확인

### 헬스 체크
```bash
curl http://[gabia-host]/api/health
```

### 컨테이너 상태 확인
```bash
ssh [gabia-user]@[gabia-host] "docker ps | grep ceseats"
```

### 활성 환경 확인
```bash
ssh [gabia-user]@[gabia-host] "cat /opt/ceseats/.active-port"
```

### 로그 확인
```bash
# Blue 환경 로그
ssh [gabia-user]@[gabia-host] "docker logs ceseats-blue"

# Green 환경 로그
ssh [gabia-user]@[gabia-host] "docker logs ceseats-green"
```

## 5. 문제 해결

### Docker 실행 권한 오류
```bash
sudo usermod -aG docker $USER
# 로그아웃 후 다시 로그인
```

### 포트 충돌
```bash
# 사용 중인 포트 확인
sudo lsof -i :8080
sudo lsof -i :8081

# 프로세스 종료 (필요한 경우)
sudo kill -9 [PID]
```

### 디스크 공간 부족
```bash
# 사용하지 않는 Docker 이미지/컨테이너 정리
docker system prune -a
```

### 방화벽 설정
```bash
# UFW 사용 시
sudo ufw allow 8080/tcp
sudo ufw allow 8081/tcp
sudo ufw reload

# firewalld 사용 시
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --permanent --add-port=8081/tcp
sudo firewall-cmd --reload
```

## 6. 롤백 방법

문제 발생 시 이전 환경으로 즉시 롤백:

```bash
ssh [gabia-user]@[gabia-host]
cd /opt/ceseats

# 현재 활성 환경 확인
cat .active-port

# 반대 환경으로 전환
./blue-green-deploy.sh blue  # 또는 green
```

