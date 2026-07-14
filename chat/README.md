# Biddy 채팅 마이크로서비스 설계 및 구현 명세서

이 문서는 Biddy MSA 환경 내에 독립적으로 구축된 `chat` 모듈의 아키텍처와 구현된 파일별 역할을 정리한 문서입니다.

## 🏗 아키텍처 개요
1. **독립적인 웹소켓 서버**: 일반 API 트래픽과 웹소켓(STOMP) 연결을 분리하기 위해 `chat` 서비스를 신설했습니다.
2. **PostgreSQL DB + Redis Pub/Sub**: 다중 서버 환경에서 메시지 유실을 방지하기 위해 DB에 선 저장 후 Redis를 통해 브로드캐스팅하는 패턴을 적용했습니다.
3. **JWT 보안 검증**: 웹소켓 핸드셰이크가 끝난 후, STOMP의 `CONNECT` 프레임에서 JWT 토큰을 추출하여 인증을 수행합니다.

---

## 📂 디렉토리 및 파일별 세부 구현 내역

### 1. 루트 설정
* **`build.gradle`**
  - WebSocket, Redis, JPA, PostgreSQL, Security, JWT 관련 의존성을 추가하여 채팅 통신 및 저장이 가능하도록 설정했습니다.
* **`src/main/resources/application.yaml`**
  - 서비스 포트(8086), DB 접속 정보(`biddy_chat`), Redis 접속 정보, Eureka Client 설정을 추가했습니다.

### 2. 설정 계층 (Config)
* **`com.biddy.chat.config.WebSocketConfig.java`**
  - STOMP 엔드포인트(`/ws-chat`)를 개방하고 CORS를 허용했습니다.
  - 내장 Simple Broker(`/topic`)와 클라이언트 메시지 도착지 접두사(`/app`)를 설정했습니다.
  - 들어오는 메시지를 가로채어 JWT 검증을 수행하도록 `stompHandler`를 등록했습니다.
* **`com.biddy.chat.config.StompHandler.java`**
  - `ChannelInterceptor`를 구현했습니다. STOMP 연결(`CONNECT`) 시 넘어오는 `Authorization` 헤더의 JWT 토큰을 파싱하고, 유효성을 검증하여 Security 인증 객체를 부여합니다.
* **`com.biddy.chat.config.SecurityConfig.java`**
  - 채팅 서비스의 전반적인 스프링 시큐리티를 비활성화하고, 웹소켓 접근(`/ws-chat/**`) 및 API 요청을 허용했습니다. (상세 인증은 ApiGateway와 `StompHandler`에서 수행)

### 3. 도메인 계층 (Domain - Entity & Repository)
* **`com.biddy.chat.domain.model.ChatRoom.java`**
  - 채팅방 엔티티. `productId`, `buyerId`, `sellerId`를 맵핑합니다.
* **`com.biddy.chat.domain.model.ChatMessage.java`**
  - 채팅 메시지 엔티티. `roomId`를 외래키(논리적)로 가지며, 인덱스 처리를 통해 빠른 최신 대화 조회를 지원합니다.
* **`com.biddy.chat.domain.repository.ChatRoomRepository.java`**
  - 특정 상품과 구매자 간의 기존 채팅방을 조회하는 메서드를 포함합니다.
* **`com.biddy.chat.domain.repository.ChatMessageRepository.java`**
  - **커서 기반 페이징(Cursor-based Pagination)** 쿼리를 내장하여, 과거 대화 내역 조회 시 오프셋(Offset) 방식 대신 `lastMessageId` 기준으로 빠르고 효율적인 데이터 페이징을 수행합니다.

### 4. 핵심 비즈니스 로직 (Application)
* **`com.biddy.chat.application.ChatService.java`**
  - 사용자가 발송한 메시지를 먼저 PostgreSQL에 안전하게 저장(`chatMessageRepository.save()`)합니다.
  - 저장이 완료되면 응답 객체를 만들어 Redis의 지정된 채널(`chatRoomTopic`)로 Publish 합니다.
* **`com.biddy.chat.application.ChatRoomService.java`**
  - 신규 채팅방 생성 혹은 기존 방 조회를 담당합니다.
  - 과거 대화 기록을 사이즈(size) 단위로 불러와 클라이언트에 전달하는 비즈니스 로직을 담당합니다.

### 5. 인프라 연동 (Infrastructure - Redis Pub/Sub)
* **`com.biddy.chat.infrastructure.redis.RedisConfig.java`**
  - RedisPubSub을 위해 `RedisTemplate`과 `RedisMessageListenerContainer` 빈을 등록하고, `chatRoomTopic` 채널을 구독(Sub)할 수 있도록 리스너를 매핑했습니다.
* **`com.biddy.chat.infrastructure.redis.RedisPublisher.java`**
  - 수신받은 채팅 메시지를 Redis 채널로 전송(Publish)합니다.
* **`com.biddy.chat.infrastructure.redis.RedisSubscriber.java`**
  - Redis Topic에 메시지가 도착하면 이를 읽어 들인 후, 스프링의 `SimpMessageSendingOperations`를 이용해 실제 클라이언트들의 웹소켓 방(`/topic/room/{roomId}`)으로 메시지를 브로드캐스팅합니다.

### 6. 프레젠테이션 계층 (Presentation - Controller & DTO)
* **`com.biddy.chat.presentation.StompChatController.java`**
  - 클라이언트가 STOMP를 통해 `/app/chat.send` 목적지로 보내는 실시간 메시지를 수신합니다.
* **`com.biddy.chat.presentation.ChatRoomController.java`**
  - HTTP 통신 기반의 REST API 엔드포인트입니다.
  - `POST /api/chats/rooms` : 채팅방 개설 API
  - `GET /api/chats/rooms/{roomId}/messages` : 채팅방 과거 대화 내역 조회 API
* **`com.biddy.chat.presentation.dto.*`**
  - 클라이언트 및 타 서버 연동 시 필요한 Request, Response DTO 클래스들입니다.

### 7. 테스트용 클라이언트 (Frontend)
* **`src/main/resources/static/index.html`**
  - 복잡한 React 연동 없이 즉시 웹소켓 연결 및 JWT 인증을 테스트해 볼 수 있도록 구성된 바닐라 JavaScript 파일입니다.
  - STOMP.js 라이브러리를 통해 연결, 채널 구독, 메시지 전송 및 과거 내역(HTTP GET)을 불러와 렌더링하는 코드가 작성되어 있습니다.

---

## 🔗 연동된 타 모듈 파일 (참고)
* **`apigateway/src/main/resources/application.yml`**
  - `/api/chats/**` 및 `/ws-chat/**` 트래픽이 `CHAT-SERVICE`로 흐르도록 라우팅 규칙을 추가했습니다.
* **`apigateway/src/main/java/com/biddy/apigateway/security/JwtAuthenticationGlobalFilter.java`**
  - 웹소켓 연결의 최초 HTTP Upgrade 요청이 필터에서 차단되지 않도록 화이트리스트에 `/ws-chat`을 추가했습니다.
* **`settings.gradle` (Root)**
  - 신규 생성된 `chat` 모듈을 biddy 멀티모듈 프로젝트의 하위 모듈로 추가했습니다.
