#!/bin/bash

# 가비아 블록 스토리지 설정 스크립트
# 사용법: ./setup-blockstorage.sh [블록스토리지-디바이스] [마운트-경로]

set -e

BLOCK_DEVICE=${1:-""}
MOUNT_PATH=${2:-"/mnt/blockstorage"}
POSTGRES_DIR="${MOUNT_PATH}/postgresql"

if [ -z "${BLOCK_DEVICE}" ]; then
    echo "사용법: ./setup-blockstorage.sh [블록스토리지-디바이스] [마운트-경로]"
    echo "예시: ./setup-blockstorage.sh /dev/xvdb /mnt/blockstorage"
    echo ""
    echo "사용 가능한 블록 디바이스 확인:"
    lsblk
    echo ""
    echo "또는:"
    sudo fdisk -l | grep -i "disk /dev"
    exit 1
fi

echo "=========================================="
echo "가비아 블록 스토리지 설정"
echo "=========================================="
echo "블록 디바이스: ${BLOCK_DEVICE}"
echo "마운트 경로: ${MOUNT_PATH}"
echo "PostgreSQL 데이터 경로: ${POSTGRES_DIR}"
echo ""

# 1. 블록 디바이스 확인
echo "1. 블록 디바이스 확인 중..."
if [ ! -b "${BLOCK_DEVICE}" ]; then
    echo "❌ 블록 디바이스를 찾을 수 없습니다: ${BLOCK_DEVICE}"
    echo "사용 가능한 디바이스:"
    lsblk
    exit 1
fi
echo "✅ 블록 디바이스 확인: ${BLOCK_DEVICE}"

# 2. 파일 시스템 확인 및 생성
echo "2. 파일 시스템 확인 중..."
FS_TYPE=$(sudo blkid -s TYPE -o value ${BLOCK_DEVICE} 2>/dev/null || echo "")

if [ -z "${FS_TYPE}" ]; then
    echo "파일 시스템이 없습니다. ext4로 생성 중..."
    read -p "계속하시겠습니까? (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "취소되었습니다."
        exit 1
    fi
    sudo mkfs.ext4 -F ${BLOCK_DEVICE}
    echo "✅ 파일 시스템 생성 완료"
else
    echo "✅ 파일 시스템 확인: ${FS_TYPE}"
fi

# 3. 마운트 포인트 생성
echo "3. 마운트 포인트 생성 중..."
if [ ! -d "${MOUNT_PATH}" ]; then
    sudo mkdir -p ${MOUNT_PATH}
    echo "✅ 마운트 포인트 생성: ${MOUNT_PATH}"
else
    echo "✅ 마운트 포인트 존재: ${MOUNT_PATH}"
fi

# 4. 마운트
echo "4. 블록 스토리지 마운트 중..."
if mountpoint -q ${MOUNT_PATH}; then
    echo "⚠️  이미 마운트되어 있습니다: ${MOUNT_PATH}"
    echo "마운트 정보:"
    mount | grep ${MOUNT_PATH}
else
    sudo mount ${BLOCK_DEVICE} ${MOUNT_PATH}
    echo "✅ 마운트 완료"
fi

# 5. 영구 마운트 설정 (/etc/fstab)
echo "5. 영구 마운트 설정 중..."
UUID=$(sudo blkid -s UUID -o value ${BLOCK_DEVICE})
FSTAB_ENTRY="UUID=${UUID} ${MOUNT_PATH} ext4 defaults 0 2"

if grep -q "${MOUNT_PATH}" /etc/fstab; then
    echo "⚠️  /etc/fstab에 이미 등록되어 있습니다"
else
    echo "${FSTAB_ENTRY}" | sudo tee -a /etc/fstab
    echo "✅ /etc/fstab에 추가 완료"
fi

# 6. PostgreSQL 데이터 디렉토리 생성 및 권한 설정
echo "6. PostgreSQL 데이터 디렉토리 설정 중..."
if [ ! -d "${POSTGRES_DIR}" ]; then
    sudo mkdir -p ${POSTGRES_DIR}
    echo "✅ 디렉토리 생성: ${POSTGRES_DIR}"
else
    echo "✅ 디렉토리 존재: ${POSTGRES_DIR}"
fi

# PostgreSQL 컨테이너 사용자 권한 (UID 999, GID 999)
sudo chown 999:999 ${POSTGRES_DIR}
sudo chmod 700 ${POSTGRES_DIR}
echo "✅ 권한 설정 완료"

# 7. 확인
echo ""
echo "=========================================="
echo "✅ 설정 완료!"
echo "=========================================="
echo ""
echo "마운트 확인:"
df -h | grep ${MOUNT_PATH}
echo ""
echo "PostgreSQL 데이터 디렉토리:"
ls -ld ${POSTGRES_DIR}
echo ""
echo "다음 단계:"
echo "1. GitHub Actions로 배포 실행"
echo "2. 또는 수동으로 PostgreSQL 컨테이너 시작"
echo ""

