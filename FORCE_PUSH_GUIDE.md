# Git 히스토리 정리 후 푸시 가이드

## 현재 상태

`git filter-branch`를 실행했으므로 Git 히스토리가 재작성되었습니다.

## ⚠️ 중요 주의사항

1. **강제 푸시 필요**: 히스토리가 변경되었으므로 일반 푸시가 아닌 **force push**가 필요합니다
2. **팀원 협의**: 히스토리를 재작성했으므로 팀원들과 협의 후 진행하세요
3. **백업**: 혹시 모를 상황을 대비해 현재 브랜치를 백업하세요

## 단계별 진행

### 1. 변경사항 확인

```bash
cd backend
git status
git diff
```

### 2. 변경사항 커밋

```bash
# 변경된 파일들 스테이징
git add src/main/resources/application.properties
git add src/main/resources/application-local.properties
git add src/test/java/com/ceseats/PlaceTypeAnalyzer.java

# 커밋
git commit -m "Remove hardcoded API keys from history"
```

### 3. 백업 브랜치 생성 (선택사항, 권장)

```bash
# 현재 상태 백업
git branch backup-before-force-push
```

### 4. 강제 푸시

⚠️ **주의**: 이 작업은 되돌릴 수 없습니다!

```bash
# 강제 푸시
git push origin main --force

# 또는 더 안전한 방법 (force-with-lease)
git push origin main --force-with-lease
```

**차이점**:
- `--force`: 무조건 덮어쓰기
- `--force-with-lease`: 원격 브랜치가 다른 사람이 업데이트했다면 실패 (더 안전)

### 5. 확인

```bash
# 히스토리에서 API 키가 제거되었는지 확인
git log --all --full-history -p | grep -i "AIzaSy"

# 결과가 없으면 성공
```

## 팀원들에게 알림

히스토리가 재작성되었으므로 팀원들은 다음을 해야 합니다:

```bash
# 기존 클론 삭제 후 다시 클론
cd ..
rm -rf cesEats
git clone <repository-url>
cd cesEats

# 또는 기존 클론 업데이트 (복잡함)
git fetch origin
git reset --hard origin/main
```

## 문제 발생 시 복구

```bash
# 백업 브랜치로 복구
git reset --hard backup-before-force-push
git push origin main --force
```

## 대안: 히스토리 정리 없이 진행

만약 히스토리 정리를 하지 않고 싶다면:

```bash
# filter-branch 실행 취소
git reset --hard origin/main

# 그냥 새 커밋만 푸시
git add .
git commit -m "Remove hardcoded API keys"
git push origin main
```

이 방법은 히스토리에는 키가 남아있지만, 최신 코드에는 없습니다.

