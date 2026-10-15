# Microservices Catalogue Application — Architecture Overview

> **Stack:** Java 17 · Spring Boot 3.2.5 · Maven · Docker · Docker Compose  
> **Transport:** HTTP/REST over an isolated Docker bridge network (`app-net`)  
> **Storage:** In-memory `ConcurrentHashMap` — no external database

---

## Table of Contents

1. [System Context](#1-system-context)
2. [Container / Deployment View](#2-container--deployment-view)
3. [Internal Component View](#3-internal-component-view)
4. [API Surface Map](#4-api-surface-map)
5. [Startup Sequence](#5-startup-sequence)
6. [Workflow Walkthroughs](#6-workflow-walkthroughs)
   - [Scenario A — Successful Booking](#scenario-a--successful-booking)
   - [Scenario B — Booking Fails (Stock Exceeded)](#scenario-b--booking-fails-stock-exceeded)
7. [Concurrency & Thread-Safety Model](#7-concurrency--thread-safety-model)
8. [Docker Build Pipeline](#8-docker-build-pipeline)
9. [Design Decisions & Trade-offs](#9-design-decisions--trade-offs)

---

## 1. System Context

The highest-level view shows who interacts with the system and how.

```
┌────────────────────────────────────────────────────────────────────┐
│                        External Boundary                           │
│                                                                    │
│   ┌──────────┐    HTTP :8081     ┌──────────────────────────────┐  │
│   │          │ ─────────────────▶│                              │  │
│   │  Client  │                   │  Microservices Catalogue App │  │
│   │ (Browser │ ◀─────────────────│  (Docker Compose stack)      │  │
│   │ / curl / │    JSON / text    │                              │  │
│   │ Postman) │                   └──────────────────────────────┘  │
│   │          │    HTTP :8080     ┌──────────────────────────────┐  │
│   │          │ ─────────────────▶│  Catalogue Service (public   │  │
│   │          │ ◀─────────────────│  read-only endpoint)         │  │
│   └──────────┘    JSON array     └──────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

| Actor | Role |
|---|---|
| **Client** | Any HTTP client — browser, curl, Postman, or another service |
| **Booking Service** | Orchestrates booking logic; calls Catalogue Service internally |
| **Catalogue Service** | Owns the item catalogue; enforces stock integrity |

---

## 2. Container / Deployment View

Both services run as separate Docker containers inside a shared bridge network.

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  Docker Host                                                                  ║
║                                                                               ║
║  ┌───────────────────────────────────────────────────────────────────────┐    ║
║  │  Network: app-net  (Docker bridge, internal DNS enabled)              │    ║
║  │                                                                       │    ║
║  │  ┌─────────────────────────────┐    ┌─────────────────────────────┐   │    ║
║  │  │  booking-service            │    │  catalogue-service          │   │    ║
║  │  │  Image: booking-service:    │    │  Image: catalogue-service:  │   │    ║
║  │  │         latest              │    │         latest              │   │    ║
║  │  │  Port:  8081 (container)    │    │  Port:  8080 (container)    │   │    ║
║  │  │                             │    │                             │   │    ║
║  │  │  Spring Boot app            │    │  Spring Boot app            │   │    ║
║  │  │  ├── BookingController      │    │  ├── ItemController         │   │    ║
║  │  │  ├── AppConfig (RestTemplate│    │  ├── ItemRepository         │   │    ║
║  │  │  ├── BookingRequest (DTO)   │    │  │   └── ConcurrentHashMap  │   │    ║
║  │  │  └── CatalogueItem  (DTO)   │    │  └── Item (model)           │   │    ║
║  │  │                             │    │                             │   │    ║
║  │  │  ENV: CATALOGUE_SERVICE_URL │───▶│  Health: GET /catalogue/    │   │    ║
║  │  │  = http://catalogue-service │    │          items (10s probe)  │   │    ║
║  │  │    :8080                    │    │                             │   │    ║
║  │  └─────────────────────────────┘    └─────────────────────────────┘   │    ║
║  │         │   Internal REST calls over app-net   ▲                      │    ║
║  │         └────────────────────────────────────── ┘                     │    ║
║  └───────────────────────────────────────────────────────────────────────┘    ║
║                                                                               ║
║  Host port mapping:                                                           ║
║    0.0.0.0:8081  ──▶  booking-service:8081                                    ║
║    0.0.0.0:8080  ──▶  catalogue-service:8080                                  ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Inter-service Communication

```
booking-service  ──[GET  /catalogue/items/{id}]────────────────────▶  catalogue-service
booking-service  ──[PUT  /catalogue/items/{id}/reduce?quantity=N]──▶  catalogue-service
```

- **Protocol:** Plain HTTP — traffic never leaves Docker's virtual `app-net` network
- **Service Discovery:** Docker DNS — the name `catalogue-service` resolves automatically to the container IP
- **HTTP Client:** Spring's `RestTemplate`, registered as a singleton `@Bean` in `AppConfig`

---

## 3. Internal Component View

### 3.1 Catalogue Service

```
com.catalogue
│
├── CatalogueServiceApplication.java   ← @SpringBootApplication entry point
│
├── controller/
│   └── ItemController.java            ← @RestController  /catalogue/items/**
│         │
│         │  delegates to
│         ▼
├── repository/
│   └── ItemRepository.java            ← @Repository
│         │  Data store: ConcurrentHashMap<String, Item>
│         │  Seed:       ITEM001 · ITEM002 · ITEM003
│         │
│         │  Read  → ConcurrentHashMap.get()        (non-blocking)
│         │  Write → synchronized(item) { check + deduct }  (atomic)
│         ▼
└── model/
    └── Item.java                      ← Plain domain object
          Fields: itemId, name, price, availableCount
```

### 3.2 Booking Service

```
com.booking
│
├── BookingServiceApplication.java     ← @SpringBootApplication entry point
│
├── config/
│   └── AppConfig.java                 ← Produces RestTemplate @Bean
│         │                              Reads CATALOGUE_SERVICE_URL from env
│         ▼
├── controller/
│   └── BookingController.java         ← @RestController  POST /booking
│         │
│         │  Step 1: Validate input   (itemId not blank, quantity > 0)
│         │  Step 2: GET  /catalogue/items/{id}    → existence check
│         │  Step 3: Compare quantity vs availableCount
│         │  Step 4: PUT  /catalogue/items/{id}/reduce?quantity=N
│         ▼
└── dto/
    ├── BookingRequest.java            ← Deserialises POST body { itemId, quantity }
    └── CatalogueItem.java             ← Deserialises Catalogue Service response
```

---

## 4. API Surface Map

### Catalogue Service — `http://localhost:8080`

| Method | Path | Purpose | Caller | Success | Error |
|--------|------|---------|--------|---------|-------|
| `GET` | `/catalogue/items` | List all items | Client / Health check | `200` + JSON array | — |
| `GET` | `/catalogue/items/{id}` | Fetch single item | Booking Service | `200` + JSON object | `404` if not found |
| `PUT` | `/catalogue/items/{id}/reduce?quantity=N` | Atomically deduct stock | Booking Service (internal) | `200 "stock reduced"` | `400` insufficient · `404` missing |

### Booking Service — `http://localhost:8081`

| Method | Path | Purpose | Caller | Success | Error |
|--------|------|---------|--------|---------|-------|
| `POST` | `/booking` | Book an item | Client | `200 "item booked"` | `404 "item not found"` · `400 "number of items exceeded"` |

---

## 5. Startup Sequence

Docker Compose enforces a strict startup order via a health check:

```
Docker Compose
     │
     ├─①─▶  Start  catalogue-service  (port 8080)
     │           │
     │           │  Health probe: GET /catalogue/items
     │           │  interval: 10s  ·  timeout: 5s  ·  retries: 5  ·  start_period: 20s
     │           │
     │           └─ ✅ HEALTHY  (Spring Boot ready; 3 seed items in store)
     │
     └─②─▶  Start  booking-service  (port 8081)
                │
                │  depends_on: catalogue-service (condition: service_healthy)
                │
                └─ ✅ READY  (RestTemplate wired; Docker DNS resolves catalogue-service)
```

> **Why this matters:** Without the health-check dependency, Booking Service could start before Catalogue Service is ready, producing `503 Service Unavailable` on the very first request.

---

## 6. Workflow Walkthroughs

---

### Scenario A — Successful Booking

> **Request:** `POST /booking`  `{ "itemId": "ITEM001", "quantity": 2 }`  
> **Pre-condition:** ITEM001 (Wireless Headphones) — `availableCount = 10`

```
Client                    Booking Service                Catalogue Service
  │                             │                                │
  │── POST /booking ───────────▶│                                │
  │   { itemId:"ITEM001",       │                                │
  │     quantity: 2 }           │                                │
  │                             │                                │
  │                        [Step 1] Validate input               │
  │                        itemId  ≠ blank  ✅                   │
  │                        quantity > 0     ✅                   │
  │                             │                                │
  │                        [Step 2] Check existence              │
  │                             │── GET /catalogue/items/ ──────▶│
  │                             │        ITEM001                 │
  │                             │                         Look up ITEM001
  │                             │                         ConcurrentHashMap → found ✅
  │                             │                                │
  │                             │◀── 200 { itemId:"ITEM001", ───│
  │                             │    availableCount: 10 }        │
  │                             │                                │
  │                        [Step 3] Stock check                  │
  │                        quantity (2) ≤ availableCount (10) ✅ │
  │                             │                                │
  │                        [Step 4] Deduct stock                 │
  │                             │── PUT /catalogue/items/ ──────▶│
  │                             │   ITEM001/reduce?quantity=2    │
  │                             │                         synchronized(item) {
  │                             │                           10 ≥ 2 ✅
  │                             │                           availableCount = 8
  │                             │                         }
  │                             │◀── 200 "stock reduced" ────────│
  │                             │                                │
  │◀── 200 "item booked" ───────│                                │
  │                             │                                │
```

**Step-by-step narration:**

1. The client sends `POST http://localhost:8081/booking` with body `{ "itemId": "ITEM001", "quantity": 2 }`.
2. `BookingController` validates that `itemId` is not blank and `quantity` is greater than zero — both conditions pass.
3. Booking Service issues `GET http://catalogue-service:8080/catalogue/items/ITEM001` over the internal `app-net` Docker network.
4. Catalogue Service's `ItemController` looks up `ITEM001` in the `ConcurrentHashMap` and finds it. It returns a JSON object including `availableCount: 10`.
5. Booking Service deserialises the response into a `CatalogueItem` DTO and verifies that the requested quantity (2) does not exceed available stock (10) — it does not.
6. Booking Service calls `PUT http://catalogue-service:8080/catalogue/items/ITEM001/reduce?quantity=2`.
7. Catalogue Service's `ItemRepository.reduceStock()` enters a `synchronized` block on the `Item` object, confirms `10 >= 2`, then atomically sets `availableCount = 8`.
8. Catalogue Service returns `200 "stock reduced"`.
9. Booking Service returns `200 "item booked"` to the original client. Stock is now permanently 8 for the lifetime of the container.

---

### Scenario B — Booking Fails (Stock Exceeded)

> **Request:** `POST /booking`  `{ "itemId": "ITEM002", "quantity": 99 }`  
> **Pre-condition:** ITEM002 (Mechanical Keyboard) — `availableCount = 5`

```
Client                    Booking Service                Catalogue Service
  │                             │                                │
  │── POST /booking ───────────▶│                                │
  │   { itemId:"ITEM002",       │                                │
  │     quantity: 99 }          │                                │
  │                             │                                │
  │                        [Step 1] Validate input               │
  │                        itemId  ≠ blank  ✅                   │
  │                        quantity > 0     ✅                   │
  │                             │                                │
  │                        [Step 2] Check existence              │
  │                             │── GET /catalogue/items/ ──────▶│
  │                             │        ITEM002                 │
  │                             │                         Look up ITEM002
  │                             │                         → found ✅
  │                             │◀── 200 { itemId:"ITEM002", ───│
  │                             │    availableCount: 5 }         │
  │                             │                                │
  │                        [Step 3] Stock check                  │
  │                        quantity (99) > availableCount (5)    │
  │                        ❌ FAIL — short-circuit               │
  │                             │                                │
  │                             │       (no PUT issued)          │
  │                             │                                │
  │◀── 400 "number of ─────────│                                │
  │    items exceeded"          │                                │
  │                             │                                │
```

**Step-by-step narration:**

1. The client sends `POST http://localhost:8081/booking` with body `{ "itemId": "ITEM002", "quantity": 99 }`.
2. `BookingController` validates input — `itemId` is non-blank, `quantity` is positive — both pass.
3. Booking Service calls `GET http://catalogue-service:8080/catalogue/items/ITEM002` to verify the item exists and retrieve current stock.
4. Catalogue Service finds ITEM002 and returns `{ availableCount: 5 }`.
5. Booking Service compares: `99 > 5` — the requested quantity exceeds available stock.
6. Booking Service **short-circuits immediately** — it never issues the `PUT /reduce` call. Stock is never touched.
7. The client receives `400 Bad Request` with body `"number of items exceeded"`. ITEM002's stock remains at 5.

---

## 7. Concurrency & Thread-Safety Model

The Catalogue Service handles concurrent booking requests safely through a two-level strategy:

```
Level 1 — ConcurrentHashMap
──────────────────────────────────────────────────────────────────
  Multiple threads can read different items simultaneously
  without blocking each other.

  Thread-A reads ITEM001  ┐
  Thread-B reads ITEM002  ┘  → both proceed concurrently ✅

Level 2 — synchronized(item) block  [inside reduceStock()]
──────────────────────────────────────────────────────────────────
  The "check-then-deduct" pair is wrapped in a synchronized block
  on the individual Item object.

  Two concurrent bookings for ITEM001 (stock = 10, qty = 2 each):

    Thread-A acquires lock on ITEM001
      → checks 10 ≥ 2  ✅
      → sets availableCount = 8
      → releases lock
    Thread-B acquires lock on ITEM001
      → checks  8 ≥ 2  ✅
      → sets availableCount = 6
      → releases lock

    Final: availableCount = 6  ✅  (correct)

  Without synchronization (race condition):
    Thread-A reads 10, Thread-B reads 10
    Both see 10 ≥ 2, both deduct
    Final: availableCount = 8  ❌  (oversell bug — one unit given away free)
```

> **Key insight:** Booking Service's pre-check (Step 3) is an **optimistic read**. The authoritative, race-safe check happens inside `ItemRepository.reduceStock()` on the Catalogue Service side. This is why Step 4 (`PUT /reduce`) can still return `400` if concurrent requests drain the stock between Steps 3 and 4.

---

## 8. Docker Build Pipeline

Both services use identical **multi-stage Dockerfiles** to produce lean runtime images:

```
Stage 1 — Builder  (maven:3.9.6-eclipse-temurin-17)
────────────────────────────────────────────────────────────────────
  ① COPY pom.xml  →  mvn dependency:go-offline  (layer-cached)
  ② COPY src/     →  mvn compile  →  .class files
  ③ mvn package   →  target/app.jar  (executable fat JAR, ~20 MB)
                          │
                          │  COPY --from=builder  (only the JAR)
                          ▼
Stage 2 — Runtime  (eclipse-temurin:17-jre-alpine)
────────────────────────────────────────────────────────────────────
  • Slim JRE only   — no Maven, no javac, no source code
  • Final image:    ~86 MB compressed
  • Runs as:        non-root user  (security best practice)
  • Entrypoint:     java -jar app.jar
```

| Image | Base | Approx. Size |
|---|---|:---:|
| `catalogue-service:latest` | `eclipse-temurin:17-jre-alpine` | ~86 MB |
| `booking-service:latest` | `eclipse-temurin:17-jre-alpine` | ~86 MB |

---

## 9. Design Decisions & Trade-offs

| Decision | Choice Made | Rationale | Trade-off |
|---|---|---|---|
| **Data store** | In-memory `ConcurrentHashMap` | Zero external dependencies; fast; simple to reason about | Data resets on container restart; not suitable for production persistence |
| **Inter-service transport** | Synchronous HTTP REST via `RestTemplate` | Standard, language-agnostic; trivially testable with curl | Synchronous coupling — Catalogue unavailability blocks all bookings |
| **Service discovery** | Docker DNS (container hostname) | Built into Docker Compose; no extra tooling (Consul, Eureka, etc.) | Only works inside the same Docker network; not portable to multi-host clusters |
| **Startup ordering** | `depends_on` + health check probe | Prevents premature REST calls from Booking Service | Adds ~20 s to startup; not a substitute for circuit breakers in production |
| **Concurrency control** | `synchronized` on individual item object | Fine-grained locking per item; prevents overselling without a DB transaction | Blocking; would not scale beyond a single JVM / single replica |
| **Build strategy** | Multi-stage Docker build | No local JDK or Maven required on the host; final image is lean and secure | Slightly longer first build (Maven dependency download, cached on subsequent rebuilds) |

---

*Document maintained alongside source code. Last reviewed: 2026-10-15.*
