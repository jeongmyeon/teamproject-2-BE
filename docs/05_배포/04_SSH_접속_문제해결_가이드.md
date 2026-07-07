# AWS EC2 SSH 접속 문제 해결 가이드

## 문서 정보
- **프로젝트**: Biddy 실시간 경매 플랫폼
- **버전**: 1.0
- **작성일**: 2026-07-07
- **대상**: AWS EC2 인스턴스 SSH 접속 및 보안 설정

---

## 1. 발생한 경고 메시지 분석

### 경고 1: Identity file not accessible
```
Warning: Identity file team03-key.pem not accessible: No such file or directory.
```

**원인**: SSH 클라이언트가 지정된 키 파일을 찾을 수 없음

**심각도**: ⚠️ 중간 (접속은 가능할 수 있으나, 의도한 키를 사용하지 않음)

### 경고 2: Post-quantum key exchange 미사용
```
** WARNING: connection is not using a post-quantum key exchange algorithm.
** This session may be vulnerable to "store now, decrypt later" attacks.
** The server may need to be upgraded. See https://openssh.com/pq.html
```

**원인**: 양자 컴퓨터 시대를 대비한 암호화 알고리즘을 사용하지 않음

**심각도**: ℹ️ 낮음 (현재는 실질적 위험 없음, 미래 대비 권장사항)

---

## 2. 키 파일 문제 해결

### 2-1. 키 파일 위치 확인

```bash
# 현재 디렉토리 확인
pwd

# 키 파일 검색
find ~ -name "team03-key.pem" -o -name "*.pem" 2>/dev/null

# 또는 AWS 콘솔에서 다운로드한 위치 확인 (보통)
ls ~/Downloads/*.pem
ls ~/.ssh/*.pem
```

### 2-2. 키 파일 권한 설정

SSH 키는 **반드시 소유자만 읽을 수 있도록** 권한 설정이 필요합니다.

```bash
# 키 파일 권한 변경 (필수)
chmod 400 team03-key.pem

# 또는 (좀 더 관대한 설정)
chmod 600 team03-key.pem

# 권한 확인
ls -l team03-key.pem
# 출력 예시: -r-------- 1 user staff 1234 Jul 7 10:00 team03-key.pem
```

**권한 설명**:
- `400`: 소유자만 읽기 가능 (권장)
- `600`: 소유자만 읽기/쓰기 가능
- `644`, `755` 등: ❌ SSH가 거부 (보안상 위험)

### 2-3. 올바른 SSH 접속 명령어

```bash
# 기본 접속 (키 파일 경로 명시)
ssh -i /path/to/team03-key.pem ubuntu@<EC2_PUBLIC_IP>

# 예시
ssh -i ~/Downloads/team03-key.pem ubuntu@54.180.123.45

# Private IP로 접속 (VPN 또는 Bastion 사용 시)
ssh -i ~/Downloads/team03-key.pem ubuntu@10.0.1.10
```

### 2-4. SSH Config 파일 설정 (권장)

매번 긴 명령어를 입력하지 않도록 설정 파일을 만듭니다.

```bash
# SSH config 디렉토리 생성 (없는 경우)
mkdir -p ~/.ssh
chmod 700 ~/.ssh

# config 파일 생성/수정
nano ~/.ssh/config
```

**~/.ssh/config 내용**:

```
# Biddy K8s 마스터 노드
Host biddy-master
    HostName 54.180.123.45
    User ubuntu
    IdentityFile ~/Downloads/team03-key.pem
    StrictHostKeyChecking no
    UserKnownHostsFile /dev/null
    ServerAliveInterval 60
    ServerAliveCountMax 3

# Biddy K8s 워커 노드 1
Host biddy-worker1
    HostName 54.180.123.46
    User ubuntu
    IdentityFile ~/Downloads/team03-key.pem
    StrictHostKeyChecking no
    UserKnownHostsFile /dev/null
    ServerAliveInterval 60
    ServerAliveCountMax 3

# Bastion Host (Private 서브넷 접근용)
Host biddy-bastion
    HostName 54.180.123.50
    User ubuntu
    IdentityFile ~/Downloads/team03-key.pem

# Private 인스턴스 (Bastion 경유)
Host biddy-master-private
    HostName 10.0.1.10
    User ubuntu
    IdentityFile ~/Downloads/team03-key.pem
    ProxyJump biddy-bastion
```

**config 파일 권한 설정**:

```bash
chmod 600 ~/.ssh/config
```

**간편 접속**:

```bash
# 이제 짧은 명령어로 접속 가능
ssh biddy-master
ssh biddy-worker1
```

---

## 3. Post-Quantum 암호화 경고 해결

### 3-1. 경고의 의미

- **Post-Quantum Cryptography (PQC)**: 양자 컴퓨터가 상용화되어도 안전한 암호화 방식
- **Store Now, Decrypt Later**: 현재 암호화된 통신을 저장해두고 미래에 양자 컴퓨터로 복호화하는 공격

**현재 위험도**:
- ✅ 일반 사용자/기업: 낮음 (양자 컴퓨터 상용화는 10년 이상 소요 예상)
- ⚠️ 정부/금융/의료: 중간 (장기간 보안이 필요한 데이터는 고려 필요)

### 3-2. 경고 제거 방법 (선택사항)

OpenSSH 9.0 이상에서 PQC 지원이 추가되었으나, 서버와 클라이언트 모두 업그레이드가 필요합니다.

#### **클라이언트 (로컬) OpenSSH 업그레이드**

**macOS**:
```bash
# Homebrew 설치 (없는 경우)
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# OpenSSH 최신 버전 설치
brew install openssh

# 버전 확인
/opt/homebrew/bin/ssh -V
# OpenSSH_9.6p1 이상이어야 PQC 지원

# PATH 우선순위 변경 (선택사항)
echo 'export PATH="/opt/homebrew/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

**Ubuntu/Debian**:
```bash
# 시스템 업데이트
sudo apt update && sudo apt upgrade -y

# OpenSSH 버전 확인
ssh -V
# OpenSSH_9.6p1 이상이어야 PQC 지원

# 구버전인 경우 (Ubuntu 24.04 LTS 이상 권장)
# 또는 소스 컴파일 필요 (복잡함)
```

#### **서버 (EC2 인스턴스) OpenSSH 업그레이드**

```bash
# EC2 인스턴스 접속 후
ssh ubuntu@<EC2_IP>

# 시스템 업데이트
sudo apt update && sudo apt upgrade -y

# OpenSSH 서버 버전 확인
ssh -V
sshd -V

# Ubuntu 24.04 LTS로 업그레이드 (주의: 운영 중 시스템은 백업 필수)
sudo do-release-upgrade
```

#### **PQC 알고리즘 활성화 (OpenSSH 9.6+)**

**클라이언트 측 설정** (~/.ssh/config):

```
# PQC 알고리즘 우선 사용
Host *
    KexAlgorithms sntrup761x25519-sha512@openssh.com,curve25519-sha256,curve25519-sha256@libssh.org
    PubkeyAcceptedAlgorithms ssh-ed25519,rsa-sha2-512,rsa-sha2-256
```

**서버 측 설정** (/etc/ssh/sshd_config):

```bash
# EC2 인스턴스에서 수정
sudo nano /etc/ssh/sshd_config

# 다음 내용 추가
KexAlgorithms sntrup761x25519-sha512@openssh.com,curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512,diffie-hellman-group18-sha512

# sshd 재시작
sudo systemctl restart sshd
```

### 3-3. 권장사항

대부분의 경우 이 경고는 **무시해도 안전**합니다. 다음의 경우만 대응을 고려하세요:

1. ✅ **정부/금융/의료** 기관의 고도 기밀 데이터
2. ✅ **10년 이상** 보안이 유지되어야 하는 데이터
3. ✅ **규제 준수** (PCI-DSS, HIPAA 등) 요구사항

**Biddy 프로젝트의 경우**:
- 현재 단계에서는 **대응 불필요**
- Ubuntu 24.04 LTS + OpenSSH 9.6+ 환경이 갖춰지면 자연스럽게 해결됨

---

## 4. SSH 접속 실전 가이드

### 4-1. 키 파일 관리 모범 사례

```bash
# 1. 전용 디렉토리 생성
mkdir -p ~/.ssh/biddy-keys
chmod 700 ~/.ssh/biddy-keys

# 2. 키 파일 이동 및 권한 설정
mv ~/Downloads/team03-key.pem ~/.ssh/biddy-keys/
chmod 400 ~/.ssh/biddy-keys/team03-key.pem

# 3. SSH config 업데이트
nano ~/.ssh/config
```

**업데이트된 config**:

```
Host biddy-*
    User ubuntu
    IdentityFile ~/.ssh/biddy-keys/team03-key.pem
    StrictHostKeyChecking no
    UserKnownHostsFile /dev/null
    ServerAliveInterval 60
    ServerAliveCountMax 3
    LogLevel ERROR

Host biddy-master
    HostName 54.180.123.45

Host biddy-worker1
    HostName 54.180.123.46
```

### 4-2. 접속 테스트

```bash
# 1. 키 파일 존재 확인
test -f ~/.ssh/biddy-keys/team03-key.pem && echo "OK" || echo "키 파일 없음"

# 2. 키 파일 권한 확인
ls -l ~/.ssh/biddy-keys/team03-key.pem

# 3. SSH 연결 테스트 (verbose 모드)
ssh -v -i ~/.ssh/biddy-keys/team03-key.pem ubuntu@<EC2_IP>

# 4. 간편 접속 (config 사용)
ssh biddy-master

# 5. 연결 성공 확인
hostname
uname -a
```

### 4-3. 일반적인 SSH 접속 오류 해결

#### **오류 1: Permission denied (publickey)**

```bash
# 원인: 키 파일 권한 문제
# 해결:
chmod 400 ~/.ssh/biddy-keys/team03-key.pem

# 또는 EC2 사용자명 확인
ssh -i ~/.ssh/biddy-keys/team03-key.pem ec2-user@<IP>  # Amazon Linux
ssh -i ~/.ssh/biddy-keys/team03-key.pem ubuntu@<IP>    # Ubuntu
```

#### **오류 2: WARNING: UNPROTECTED PRIVATE KEY FILE!**

```bash
# 원인: 키 파일 권한이 너무 개방적
# 해결:
chmod 400 ~/.ssh/biddy-keys/team03-key.pem
```

#### **오류 3: Connection timed out**

```bash
# 원인: 보안 그룹에서 SSH 포트 미허용
# 해결: AWS 콘솔에서 보안 그룹 확인
# 1. EC2 인스턴스 → 보안 탭 → 보안 그룹 클릭
# 2. 인바운드 규칙 → 편집
# 3. SSH (22) 포트에 내 IP 추가
```

#### **오류 4: Host key verification failed**

```bash
# 원인: EC2 인스턴스 재생성으로 호스트 키 변경
# 해결:
ssh-keygen -R <EC2_IP>

# 또는 config에서 검증 비활성화 (개발 환경만)
StrictHostKeyChecking no
UserKnownHostsFile /dev/null
```

---

## 5. 보안 강화 팁

### 5-1. SSH 키 백업

```bash
# 안전한 위치에 백업 (암호화 필수)
cp ~/.ssh/biddy-keys/team03-key.pem /Volumes/외장하드/backup/

# 또는 암호화 백업
tar czf - ~/.ssh/biddy-keys | openssl enc -aes-256-cbc -e > ~/biddy-ssh-keys-backup.tar.gz.enc

# 복원
openssl enc -aes-256-cbc -d -in ~/biddy-ssh-keys-backup.tar.gz.enc | tar xz -C ~/
```

### 5-2. EC2 인스턴스 SSH 보안 강화

```bash
# EC2 인스턴스에서 실행
sudo nano /etc/ssh/sshd_config

# 다음 설정 변경
PermitRootLogin no                      # root 로그인 차단
PasswordAuthentication no               # 비밀번호 인증 차단 (키만 허용)
PubkeyAuthentication yes                # 공개키 인증 활성화
MaxAuthTries 3                          # 최대 로그인 시도 3회
ClientAliveInterval 300                 # 5분마다 연결 유지 확인
ClientAliveCountMax 2                   # 2회 응답 없으면 연결 종료
AllowUsers ubuntu                       # ubuntu 사용자만 허용

# sshd 재시작
sudo systemctl restart sshd
```

### 5-3. Fail2Ban 설치 (무차별 대입 공격 차단)

```bash
# EC2 인스턴스에서 실행
sudo apt update
sudo apt install -y fail2ban

# Fail2Ban 설정
sudo nano /etc/fail2ban/jail.local

# 다음 내용 추가
[sshd]
enabled = true
port = 22
filter = sshd
logpath = /var/log/auth.log
maxretry = 3
bantime = 3600
findtime = 600

# Fail2Ban 시작
sudo systemctl enable fail2ban
sudo systemctl start fail2ban

# 상태 확인
sudo fail2ban-client status sshd
```

### 5-4. 2FA (Two-Factor Authentication) 활성화 (선택사항)

```bash
# Google Authenticator 설치
sudo apt install -y libpam-google-authenticator

# 사용자별 설정
google-authenticator
# QR 코드 스캔 → Google Authenticator 앱에 등록

# PAM 설정 변경
sudo nano /etc/pam.d/sshd
# 다음 줄 추가
auth required pam_google_authenticator.so

# sshd_config 수정
sudo nano /etc/ssh/sshd_config
# 다음 변경
ChallengeResponseAuthentication yes

# sshd 재시작
sudo systemctl restart sshd
```

---

## 6. 빠른 참조 (Quick Reference)

### 필수 명령어

```bash
# 키 파일 권한 설정
chmod 400 team03-key.pem

# SSH 접속 (기본)
ssh -i team03-key.pem ubuntu@<IP>

# SSH 접속 (verbose)
ssh -v -i team03-key.pem ubuntu@<IP>

# SSH 접속 (config 사용)
ssh biddy-master

# 파일 전송 (로컬 → 원격)
scp -i team03-key.pem local-file.txt ubuntu@<IP>:/home/ubuntu/

# 파일 전송 (원격 → 로컬)
scp -i team03-key.pem ubuntu@<IP>:/home/ubuntu/remote-file.txt ./

# 디렉토리 전송 (재귀)
scp -r -i team03-key.pem local-dir ubuntu@<IP>:/home/ubuntu/

# SSH 터널링 (포트 포워딩)
ssh -L 8080:localhost:8080 -i team03-key.pem ubuntu@<IP>

# Known hosts 삭제
ssh-keygen -R <IP>
```

### 체크리스트

접속 전 확인사항:
- [ ] 키 파일 경로 확인 (`ls -l team03-key.pem`)
- [ ] 키 파일 권한 확인 (400 or 600)
- [ ] EC2 인스턴스 Public IP 확인
- [ ] 보안 그룹 SSH 포트 허용 확인
- [ ] 사용자명 확인 (ubuntu / ec2-user)

---

## 7. 추가 자료

- [OpenSSH 공식 문서](https://www.openssh.com/)
- [AWS EC2 SSH 접속 가이드](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/AccessingInstancesLinux.html)
- [Post-Quantum Cryptography 설명](https://openssh.com/pq.html)
- [SSH Config 파일 문법](https://man.openbsd.org/ssh_config)
- [Fail2Ban 공식 문서](https://www.fail2ban.org/)

---

**문서 버전**: 1.0
**최종 수정일**: 2026-07-07
**작성자**: Biddy Dev Team