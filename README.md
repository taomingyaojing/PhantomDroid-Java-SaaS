<div align="center">

#   PhantomDroid Java SaaS

**Enterprise Android Cloud Phone Orchestration Platform**

[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)](https://spring.io/projects/spring-boot)
[![SQLite](https://img.shields.io/badge/SQLite-3.45-blue)](https://sqlite.org/)
[![JWT](https://img.shields.io/badge/JWT-HS256-ff69b4)](https://jwt.io/)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

**Zero external databases · Lightweight auth · Multi-tenant isolation · 8c16G = ~120 containers**

</div>

---

##   Screenshots

> *Screenshots coming soon — currently the dashboard is live on our demo server.*

<details>
<summary><b>   Login & Register</b></summary>

```
┌─────────────────────────────────────────────────────┐
│                    PhantomDroid                       │
│           Multi-tenant Cloud Phone Orchestration      │
│                                                      │
│   ┌──────LOGIN──────────REGISTER──────────┐          │
│                                             │
│   Username                                   │
│   ┌─────────────────────────────────┐        │
│   │ admin                           │        │
│   └─────────────────────────────────┘        │
│                                              │
│   Password                                   │
│   ┌─────────────────────────────────┐        │
│   │ ●●●●●●●●●●                      │        │
│   └─────────────────────────────────┘        │
│                                              │
│   ┌─────────────────────────────────┐        │
│   │           LOGIN                │        │
│   └─────────────────────────────────┘        │
│                                              │
│   ℹ️ First user promoted to ADMIN            │
└─────────────────────────────────────────────┘
```
</details>

<details>
<summary><b>   Main Dashboard</b></summary>

```
┌──────────────────────────────────────────────────────────┐
│   PhantomDroid v2.0     User admin    Phones 12  ...  │
├──────────────────┬───────────────────────────────────────┤
│  Batch Launch    │   Devices (12)                        │
│  ┌──────────┐    │  ┌──────────┐  ┌──────────┐          │
│  │ Count 2  │    │  │ Samsung  │  │ Xiaomi   │          │
│  └──────────┘    │  │ RUNNING   │  │ RUNNING   │          │
│  [Launch]        │  │ :5595     │  │ :5596     │          │
│                  │  │ [Home][Bak]│  │ [Home][Bak]│          │
│  Location Spoof  │  │ ┌────────┐│  │ ┌────────┐│          │
│  [New York]      │  │ │ Screen ││  │ │ Screen ││          │
│  [Move All]      │  │ └────────┘│  │ └────────┘│          │
│                  │  │ GPS:40.7N │  │ GPS:48.9N │          │
│  Fingerprint     │  │ Uptime 2h │  │ Uptime 1h │          │
│  [Randomize]     │  │ [Destroy] │  │ [Destroy] │          │
│                  │  └──────────┘  └──────────┘          │
│  Install APK     │  ┌──────────┐  ┌──────────┐          │
│  [Install All]   │  │ OnePlus  │  │ Pixel    │          │
│                  │  │ RUNNING   │  │ RUNNING   │          │
│  Destroy         │  │ :5597     │  │ :5598     │          │
│  [Destroy All]   │  └──────────┘  └──────────┘          │
└──────────────────┴───────────────────────────────────────┘
```
</details>

<details>
<summary><b>   Live Streaming</b></summary>

```
┌──────────────────────────────┐
│       Samsung Galaxy S24      │
│  ● RUNNING  :5595             │
│                               │
│  ┌────────────────────────┐   │
│  │                        │   │
│  │    [LIVE]              │   │
│  │    ┌──────────────┐    │   │
│  │    │              │    │   │
│  │    │    Phone     │    │   │
│  │    │   Screen     │    │   │
│  │    │              │    │   │
│  │    │              │    │   │
│  │    └──────────────┘    │   │
│  │                        │   │
│  └────────────────────────┘   │
│                               │
│  [Stop] [Home] [Back] [Recents] [Fullscreen] │
│                               │
│  GPS: 40.7128, -74.0060       │
│  Uptime: 2h 15m               │
│                               │
└──────────────────────────────┘
```
</details>

---

## ✨ Features

###   Core Capabilities

| Feature | Description |
|---------|-------------|
| **Batch Launch** | Create 1-50 Redroid containers in seconds |
| **Live Streaming** | 2fps screencap + touch/key injection via WebSocket |
| **GPS Spoofing** | One-click teleport to NYC, London, Tokyo, or custom coords |
| **Fingerprint Spoofing** | Randomized device fingerprint (brand, model, IMEI, Android ID) |
| **Silent APK Install** | Download + install apps via ADB with a single URL |
| **Idle Auto-Reap** | Auto-destroy containers after configurable TTL |

###   Security & Multi-Tenancy

| Feature | Description |
|---------|-------------|
| **No External DB** | SQLite single-file `phantom.db` — no MySQL/PG/Redis |
| **Lightweight JWT** | Pure Servlet Filter, zero Spring Security dependencies |
| **BCrypt Passwords** | Irreversible hashing, never stored in plaintext |
| **Multi-Tenant Isolation** | Physical data separation per user |
| **Cross-User Blocking** | Any cross-user access returns HTTP 403 Forbidden |
| **WebSocket Auth** | JWT token in connection URL, ownership verified per command |

### ⚡ Performance

| Metric | Value |
|--------|-------|
| **Max Containers** | ~120 (1c/1.5G per container on 8c16G) |
| **Auth Layer RAM** | ~5 MB (HikariCP + JPA cache) |
| **CPU Overhead** | < 1% per authenticated request |
| **Startup Time** | ~7 seconds from cold start |
| **DB Latency** | < 5ms read (SQLite WAL mode) |

---

##   Architecture

```
┌────────────────────────────────────────────────────────────┐
│                       Browser (Vue 3)                       │
│   Login → Store JWT → Bearer Token → REST + WebSocket      │
└───────────┬────────────────────────────────────┬────────────┘
            │ REST (Authorization: Bearer ...)   │ WS (?token=...)
┌───────────▼────────────────────────────────────▼────────────┐
│                  Spring Boot (port 8000)                      │
│                                                               │
│  ┌──────────────┐  ┌────────────────┐  ┌───────────────────┐ │
│  │  JwtFilter    │  │ DeviceController│  │ WebSocketHandler  │ │
│  │  (Servlet)    │──│ Ownership Check │──│ JWT Auth + Stream │ │
│  └──────┬───────┘  └───────┬────────┘  └────────┬──────────┘ │
│         │                  │                    │            │
│  ┌──────▼──────────────────▼────────────────────▼──────────┐ │
│  │                  Service / Manager Layer                 │ │
│  │      DockerContainerManager, UserContext (ThreadLocal)   │ │
│  └──────┬─────────────────────────────────────┬───────────┘ │
│         │                                     │             │
│  ┌──────▼──────────┐              ┌───────────▼───────────┐ │
│  │  SQLite + JPA    │              │   Docker SDK + ADB    │ │
│  │  (phantom.db)    │              │   Redroid Container   │ │
│  └─────────────────┘              └───────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

###   Authentication Flow

```
┌─────────┐     POST /api/auth/login       ┌─────────────────┐
│ Browser │ ──────────────────────────────▶ │ AuthController  │
│         │     {username, password}        │                 │
│         │ ◀────────────────────────────── │ Verify BCrypt   │
│         │     {token, userId, role}       │ Generate JWT    │
│         │                                 └─────────────────┘
│         │     GET /api/device/list
│         │     Authorization: Bearer <JWT>
│         │ ──────────────────────────────▶ ┌─────────────────┐
│         │                                 │ JwtFilter       │
│         │                                 │ Validate Token  │
│         │                                 │ Set UserContext │
│         │                                 └───────┬─────────┘
│         │                                         │
│         │                                         ▼
│         │                                 ┌─────────────────┐
│         │ ◀────────────────────────────── │ DeviceController│
│         │     {devices: [...]}            │ Ownership Check │
└─────────┘                                 └─────────────────┘
```

---

##   Quick Start

### Prerequisites

```bash
# Java 21+
java -version

# Docker with Redroid image
docker pull redroid/redroid:11.0.0-latest

# ADB
adb --version

# Maven
mvn --version
```

### Build & Run

```bash
# Clone
git clone git@github.com:taomingyaojing/PhantomDroid-Java-SaaS.git
cd PhantomDroid-Java-SaaS

# Build
mvn clean package -DskipTests

# Run (SQLite phantom.db auto-creates in current directory)
java -jar target/phantomdroid-saas.jar
```

### First-Time Setup

1. Open `http://localhost:8000`
2. **Register** the first user — automatically promoted to `ADMIN`
3. **Login** with your credentials
4. **Launch containers** from the sidebar

---

##   API Reference

### Authentication

```
POST /api/auth/register        Register new user (auto-BCrypt)
POST /api/auth/login           Login, returns JWT token
```

**Example:**
```json
// Request
POST /api/auth/login
{ "username": "admin", "password": "secret123" }

// Response
{
  "code": 200,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "userId": 1,
    "username": "admin",
    "role": "ADMIN",
    "tokenExpiryMs": 86400000
  }
}
```

### Device Management

All endpoints require `Authorization: Bearer <token>` header.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/device/list` | List current user's devices |
| `GET` | `/api/device/status` | Server status summary |
| `POST` | `/api/device/launch` | Batch launch containers |
| `POST` | `/api/device/modify` | GPS / fingerprint spoof |
| `POST` | `/api/device/install-app` | Silent APK install |
| `POST` | `/api/device/start-stream/{port}` | Start scrcpy stream |
| `POST` | `/api/device/stop-stream/{port}` | Stop stream |
| `DELETE` | `/api/device/{port}` | Destroy single container |
| `DELETE` | `/api/device/destroy-all` | Destroy all (current user) |

### WebSocket

```
ws://host:8000/ws/devices?token=<JWT>
```

- Binary frames for touch/key injection
- Text frames for screencap streaming (~2fps base64 PNG)
- Heartbeat broadcast (device status every 5s)

### Error Codes

| Code | Description |
|:----:|-------------|
| 200 | Success |
| 400 | Validation error |
| 401 | Token missing / expired / invalid |
| 403 | Cross-user access denied |
| 409 | Username already exists |
| 500 | Internal server error |

---

##   Configuration

Edit `application.yml` or override via environment:

```yaml
jwt:
  secret: "Your-256-bit-secret-key-here"
  expiration-ms: 86400000      # Token TTL (24h)

spring:
  datasource:
    url: jdbc:sqlite:${user.dir}/phantom.db   # Database path

phantomdroid:
  container:
    cpu-count: 1                 # CPU per container
    memory-mb: 1536              # RAM per container (MB)
    idle-ttl-minutes: 60         # Auto-destroy after idle
    adb-port-start: 5555         # ADB port range start
```

---

##   Security Architecture Details

### Why SQLite?

| Requirement | Solution |
|-------------|----------|
| Zero external middleware | ✅ Single `phantom.db` file |
| Concurrent reads | ✅ WAL journal mode |
| Concurrent writes | ✅ Busy timeout + HikariCP pool |
| Auto-schema | ✅ JPA `ddl-auto: update` |
| Footprint | ✅ ~300KB JDBC driver |

### Why No Spring Security?

| Concern | Our Approach |
|---------|--------------|
| Memory overhead | Pure Servlet Filter ~50KB |
| Startup time | No SecurityContext init |
| Complexity | 1 file, 80 lines of Filter code |
| Thread safety | ThreadLocal (cleared in `finally`) |

### Permission Model

```
┌─────────────────────────────────────────┐
│              User A                       │
│  Devices: [Samsung, Xiaomi, OnePlus]     │
│  Can: launch, modify, stream, destroy    │
│  ONLY their own devices                  │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│              User B                       │
│  Devices: [Pixel, Motorola]              │
│  Can: launch, modify, stream, destroy    │
│  ONLY their own devices                  │
└─────────────────────────────────────────┘

User A → DELETE /api/device/5595  ✅ Own device
User A → DELETE /api/device/6600  ❌ 403 Forbidden (User B's device)
```

---

##   Project Structure

```
src/main/java/com/phantomdroid/
├── PhantomDroidApplication.java      # Entry point
├── config/
│   ├── PhantomDroidProperties.java   # Configuration mapping
│   └── WebSocketConfig.java          # WS endpoint registration
├── constant/
│   └── ScrcpyConstants.java          # Streaming constants
├── controller/
│   ├── AuthController.java           # Register / Login
│   └── DeviceController.java         # Device lifecycle (multi-tenant)
├── dto/
│   ├── ApiResponse.java              # Unified response wrapper
│   ├── AppInstallDTO.java            # Install request
│   ├── BatchLaunchDTO.java           # Launch request
│   ├── DeviceDTO.java                # Device transfer object
│   └── DeviceModifyDTO.java          # Modify request
├── entity/
│   ├── Device.java                   # Device entity (JPA, @ManyToOne → User)
│   └── User.java                     # User entity (BCrypt password)
├── exception/
│   └── GlobalAsyncExceptionHandler.java  # 401/403/400/500 handling
├── filter/
│   └── JwtFilter.java                # JWT auth filter (Servlet, no Spring Security)
├── handler/
│   └── DeviceWebSocketHandler.java   # WS handler (auth + stream + touch)
├── manager/
│   └── DockerContainerManager.java   # Docker lifecycle + ADB ops
├── repository/
│   ├── DeviceRepository.java         # Multi-tenant queries
│   └── UserRepository.java           # User queries
└── util/
    ├── FingerprintGenerator.java     # Random device fingerprint
    ├── JwtUtil.java                  # JJWT sign/verify
    ├── ScrcpyStreamUtil.java         # Scrcpy process mgmt
    └── UserContext.java              # ThreadLocal context
```

---

##   Performance

### 8c16G Server Capacity

| Container Spec | Max Count | Notes |
|:-------------:|:---------:|-------|
| 1c / 1.5G | ~120 | Default config |
| 2c / 3G | ~60 | Higher per-device perf |
| 0.5c / 1G | ~200 | Minimal profile |

### Auth Layer Overhead

```
JAR Size:        79 MB (with all dependencies)
Auth Code:       ~5 KB (5 Java files)
JWT Library:     ~1.2 MB (jjwt)
SQLite Library:  ~14 MB (xerial JDBC)
BCrypt:          ~500 KB (spring-security-crypto)
Runtime RAM:     ~5 MB at steady state
CPU Impact:      <1% per request
```

---

## 🛠 Tech Stack

| Layer | Technology |
|-------|-----------|
| **Language** | Java 21 |
| **Framework** | Spring Boot 3.2.5 |
| **Database** | SQLite 3.45 (xerial-jdbc) |
| **ORM** | Hibernate 6.4 + Spring Data JPA |
| **Auth** | JJWT 0.12.5 (HMAC-SHA256) |
| **Password** | BCrypt (spring-security-crypto) |
| **Containers** | Docker Java SDK 3.4.0 |
| **Streaming** | ADB screencap + WebSocket |
| **Frontend** | Vue 3 + Element Plus |
| **Serialization** | Jackson |

---

##   License

MIT License — feel free to use, modify, and distribute.

---

<div align="center">

**Made with by the PhantomDroid Team**

[Report Bug](https://github.com/taomingyaojing/PhantomDroid-Java-SaaS/issues) · [Request Feature](https://github.com/taomingyaojing/PhantomDroid-Java-SaaS/issues)

</div>
