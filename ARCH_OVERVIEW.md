# Microservices Catalogue Application: Architecture Overview

| | |
|---|---|
| **Stack** | Java 17 · Spring Boot 3.2.5 · Maven · Docker · Docker Compose |
| **Transport** | Synchronous HTTP/REST over an isolated Docker bridge network (`app-net`) |
| **Storage** | In-memory `ConcurrentHashMap` (no external database) |
| **Last reviewed** | 2026-10-16 |

## Table of Contents

1. [System Context](#1-system-context)
2. [Container and Deployment View](#2-container-and-deployment-view)
3. [Internal Component View](#3-internal-component-view)
4. [API Reference](#4-api-reference)
5. [Startup Sequence](#5-startup-sequence)
6. [Workflow Walkthroughs](#6-workflow-walkthroughs)
7. [Concurrency and Thread-Safety Model](#7-concurrency-and-thread-safety-model)
8. [Docker Build Pipeline](#8-docker-build-pipeline)
9. [Design Decisions and Trade-offs](#9-design-decisions-and-trade-offs)
10. [Known Limitations and Next Steps](#10-known-limitations-and-next-steps)

---

## 1. System Context

Two services, one external client type. The Booking Service is the only one that
contains business logic; the Catalogue Service owns the data and is the final
authority on stock integrity.

```mermaid
flowchart LR
    client["Client<br/>(browser / curl / Postman)"]

    subgraph stack["Docker Compose stack"]
        booking["Booking Service<br/>host port 8081"]
        catalogue["Catalogue Service<br/>host port 8080"]
    end

    client -->|"POST /booking"| booking
    client -->|"GET /catalogue/items"| catalogue
    booking -->|"GET item<br/>PUT reduce"| catalogue
```

| Actor | Responsibility |
|---|---|
| **Client** | Any HTTP client: browser, curl, Postman, or another service |
| **Booking Service** | Validates requests and orchestrates the booking; calls the Catalogue Service |
| **Catalogue Service** | Owns the item catalogue and enforces stock integrity |

> **Note:** Both ports are published to the host in the default Compose file, which
> means the internal `PUT /reduce` endpoint is also reachable by external clients.
> See [Section 10](#10-known-limitations-and-next-steps).

---

## 2. Container and Deployment View

Each service runs in its own container on a shared user-defined bridge network.
Docker's embedded DNS resolves `catalogue-service` to the container IP.

```mermaid
flowchart TB
    host(["Host machine<br/>0.0.0.0:8080 and 0.0.0.0:8081"])

    subgraph docker["Docker Host"]
        subgraph net["Network: app-net (bridge, internal DNS)"]
            direction LR
            subgraph b["Container: booking-service (port 8081)"]
                direction TB
                bc["BookingController"]
                cfg["AppConfig<br/>(RestTemplate bean)"]
                dto["DTOs: BookingRequest, CatalogueItem"]
            end
            subgraph c["Container: catalogue-service (port 8080)"]
                direction TB
                ic["ItemController"]
                repo["ItemRepository<br/>(ConcurrentHashMap)"]
                model["Item model"]
            end
            b -->|"HTTP via http://catalogue-service:8080<br/>(CATALOGUE_SERVICE_URL)"| c
        end
    end

    host -->|"8081"| b
    host -->|"8080"| c
```

### Inter-service communication

| From | Call | Purpose |
|---|---|---|
| booking-service | `GET /catalogue/items/{id}` | Existence check and current stock |
| booking-service | `PUT /catalogue/items/{id}/reduce?quantity=N` | Atomic stock deduction |

- **Protocol:** plain HTTP; inter-service traffic stays on the virtual `app-net` network.
- **Service discovery:** Docker DNS. The base URL is injected through the
  `CATALOGUE_SERVICE_URL` environment variable (`http://catalogue-service:8080`).
- **HTTP client:** Spring `RestTemplate`, registered as a singleton `@Bean` in `AppConfig`.

---

## 3. Internal Component View

### 3.1 Catalogue Service

```mermaid
flowchart TB
    app["CatalogueServiceApplication<br/>@SpringBootApplication"]
    ctrl["controller.ItemController<br/>@RestController /catalogue/items/**"]
    repo["repository.ItemRepository<br/>@Repository"]
    store[("ConcurrentHashMap&lt;String, Item&gt;<br/>seeded: ITEM001, ITEM002, ITEM003")]
    model["model.Item"]

    app --> ctrl
    ctrl -->|"delegates"| repo
    repo -->|"reads: get() non-blocking<br/>writes: synchronized(item)"| store
    store --- model
```

### 3.2 Booking Service

```mermaid
flowchart TB
    app["BookingServiceApplication<br/>@SpringBootApplication"]
    cfg["config.AppConfig<br/>produces RestTemplate bean<br/>reads CATALOGUE_SERVICE_URL"]
    ctrl["controller.BookingController<br/>@RestController POST /booking"]
    req["dto.BookingRequest<br/>{ itemId, quantity }"]
    item["dto.CatalogueItem<br/>(Catalogue response)"]

    app --> cfg
    app --> ctrl
    cfg -->|"injects RestTemplate"| ctrl
    ctrl --> req
    ctrl --> item
```

### 3.3 Domain model

```mermaid
classDiagram
    class Item {
        +String itemId
        +String name
        +price
        +int availableCount
    }
    class BookingRequest {
        +String itemId
        +int quantity
    }
    class CatalogueItem {
        +String itemId
        +String name
        +price
        +int availableCount
    }
    BookingRequest ..> Item : refers to by itemId
    CatalogueItem ..> Item : mirrors (JSON)
```

`CatalogueItem` is the Booking Service's own copy of the Catalogue's response
shape. The two services share no code, only the JSON contract.

### 3.4 Booking decision logic

`BookingController` runs four steps. Steps 1 to 3 are cheap guards; step 4 is the
only one that mutates state.

```mermaid
flowchart TD
    start(["POST /booking"]) --> v{"itemId not blank<br/>and quantity > 0?"}
    v -- "no" --> e400a["400 Bad Request"]
    v -- "yes" --> get["GET /catalogue/items/{id}"]
    get --> f{"Item found?"}
    f -- "no (404)" --> e404["404 'item not found'"]
    f -- "yes" --> s{"quantity ≤ availableCount?"}
    s -- "no" --> e400b["400 'number of items exceeded'"]
    s -- "yes" --> put["PUT /catalogue/items/{id}/reduce?quantity=N"]
    put --> r{"Reduce succeeded?"}
    r -- "yes (200)" --> ok["200 'item booked'"]
    r -- "no (400, concurrent drain)" --> e400b
```

---

## 4. API Reference

### Catalogue Service: `http://localhost:8080`

| Method | Path | Purpose | Caller | Success | Errors |
|---|---|---|---|---|---|
| `GET` | `/catalogue/items` | List all items | Client, health check | `200` JSON array | n/a |
| `GET` | `/catalogue/items/{id}` | Fetch one item | Booking Service | `200` JSON object | `404` not found |
| `PUT` | `/catalogue/items/{id}/reduce?quantity=N` | Atomically deduct stock | Booking Service (internal) | `200` `"stock reduced"` | `400` insufficient stock · `404` not found |

### Booking Service: `http://localhost:8081`

| Method | Path | Purpose | Caller | Success | Errors |
|---|---|---|---|---|---|
| `POST` | `/booking` | Book an item | Client | `200` `"item booked"` | `400` invalid input or `"number of items exceeded"` · `404` `"item not found"` |

**Example**

```bash
curl -X POST http://localhost:8081/booking \
  -H "Content-Type: application/json" \
  -d '{"itemId":"ITEM001","quantity":2}'
# -> 200 item booked
```

---

## 5. Startup Sequence

Docker Compose enforces start order with a health check, so the Booking Service
never starts before the Catalogue Service can answer requests.

```mermaid
sequenceDiagram
    autonumber
    participant DC as Docker Compose
    participant CAT as catalogue-service
    participant BOOK as booking-service

    DC->>CAT: Start container (port 8080)
    Note over CAT: Spring Boot boots, seeds 3 items
    loop every 10s (timeout 5s, max 5 retries, after 20s start_period)
        DC->>CAT: GET /catalogue/items
        CAT-->>DC: 200 OK
    end
    Note over DC,CAT: Status = healthy
    DC->>BOOK: Start container (depends_on: service_healthy)
    Note over BOOK: RestTemplate wired,<br/>Docker DNS resolves catalogue-service
```

> **Why it matters:** without the health-check dependency, the first booking request
> could hit a Catalogue Service that is still starting and fail with a connection
> error or `503`.

---

## 6. Workflow Walkthroughs

### Scenario A: Successful booking

**Request:** `POST /booking` with `{ "itemId": "ITEM001", "quantity": 2 }`
**Pre-condition:** ITEM001 (Wireless Headphones), `availableCount = 10`

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant B as Booking Service
    participant K as Catalogue Service

    C->>B: POST /booking {ITEM001, qty 2}
    Note over B: Validate: itemId not blank, qty > 0
    B->>K: GET /catalogue/items/ITEM001
    K-->>B: 200 {availableCount: 10}
    Note over B: Pre-check: 2 <= 10 OK
    B->>K: PUT /catalogue/items/ITEM001/reduce?quantity=2
    Note over K: synchronized(item): check 10 >= 2, set count = 8
    K-->>B: 200 "stock reduced"
    B-->>C: 200 "item booked"
```

1. The client posts the booking request to port `8081`.
2. `BookingController` validates the input; both checks pass.
3. Booking Service fetches the item from the Catalogue Service over `app-net`.
4. The response is deserialised into `CatalogueItem`; the requested quantity (2) does not exceed stock (10).
5. Booking Service calls `PUT .../reduce?quantity=2`.
6. `ItemRepository.reduceStock()` locks the `Item`, re-checks `10 >= 2`, and sets `availableCount = 8`.
7. `200 "stock reduced"` returns to Booking Service, which replies `200 "item booked"`.

Stock stays at 8 for the lifetime of the container.

### Scenario B: Stock exceeded (short-circuit)

**Request:** `POST /booking` with `{ "itemId": "ITEM002", "quantity": 99 }`
**Pre-condition:** ITEM002 (Mechanical Keyboard), `availableCount = 5`

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant B as Booking Service
    participant K as Catalogue Service

    C->>B: POST /booking {ITEM002, qty 99}
    Note over B: Validate OK
    B->>K: GET /catalogue/items/ITEM002
    K-->>B: 200 {availableCount: 5}
    Note over B: Pre-check: 99 > 5 FAIL
    Note over B,K: No PUT is issued, stock untouched
    B-->>C: 400 "number of items exceeded"
```

The Booking Service stops at the pre-check, so the Catalogue Service sees no write
and ITEM002 stays at 5.

### Scenario C: Item does not exist

**Request:** `POST /booking` with `{ "itemId": "ITEM999", "quantity": 1 }`

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant B as Booking Service
    participant K as Catalogue Service

    C->>B: POST /booking {ITEM999, qty 1}
    B->>K: GET /catalogue/items/ITEM999
    K-->>B: 404 Not Found
    B-->>C: 404 "item not found"
```

### Scenario D: Concurrent bookings drain stock between check and deduct

**Pre-condition:** ITEM003 has `availableCount = 1`; two clients each request 1 unit.

```mermaid
sequenceDiagram
    autonumber
    actor C1 as Client 1
    actor C2 as Client 2
    participant B as Booking Service
    participant K as Catalogue Service

    C1->>B: POST /booking {ITEM003, 1}
    C2->>B: POST /booking {ITEM003, 1}
    B->>K: GET ITEM003 (for C1)
    B->>K: GET ITEM003 (for C2)
    K-->>B: availableCount = 1
    K-->>B: availableCount = 1
    Note over B: Both pre-checks pass (optimistic read)
    B->>K: PUT reduce qty=1 (C1)
    Note over K: Lock acquired: 1 >= 1, count = 0
    K-->>B: 200 "stock reduced"
    B->>K: PUT reduce qty=1 (C2)
    Note over K: Lock acquired: 0 < 1, rejected
    K-->>B: 400 insufficient
    B-->>C1: 200 "item booked"
    B-->>C2: 400 "number of items exceeded"
```

The pre-check is only an optimistic early exit. The authoritative check is inside
`reduceStock()`, so overselling cannot happen.

---

## 7. Concurrency and Thread-Safety Model

The Catalogue Service uses two complementary levels.

| Level | Mechanism | Effect |
|---|---|---|
| 1 | `ConcurrentHashMap` | Threads reading different (or the same) items never block each other |
| 2 | `synchronized(item)` inside `reduceStock()` | The check-then-deduct pair runs atomically per item |

**With the lock** (two concurrent bookings of 2 units, stock = 10):

```mermaid
sequenceDiagram
    participant A as Thread A
    participant I as Item ITEM001
    participant B as Thread B

    A->>I: acquire lock
    Note over I: check 10 >= 2, set 8
    A->>I: release lock
    B->>I: acquire lock
    Note over I: check 8 >= 2, set 6
    B->>I: release lock
    Note over I: Final = 6 (correct)
```

**Without the lock** (race condition): both threads read 10, both pass the check,
both write 8. Final stock is 8 instead of 6, so one unit is given away.

> **Key insight:** the Booking Service pre-check (step 3) is an *optimistic read*.
> The race-safe check lives in `ItemRepository.reduceStock()`. That is why the
> `PUT /reduce` call can still return `400` even after the pre-check passed, and
> why the Booking Service must map that response to `400 "number of items exceeded"`.

**Scope of the guarantee:** per-item locking is correct only for a single JVM and a
single Catalogue replica, and only while the same `Item` instance stays in the map
for the lifetime of the process.

---

## 8. Docker Build Pipeline

Both services use identical multi-stage Dockerfiles.

```mermaid
flowchart LR
    subgraph s1["Stage 1: Builder (maven:3.9.6-eclipse-temurin-17)"]
        direction TB
        a["COPY pom.xml<br/>mvn dependency:go-offline<br/>(layer-cached)"] --> b["COPY src/"]
        b --> c["mvn package<br/>-> target/app.jar (~20 MB fat JAR)"]
    end
    subgraph s2["Stage 2: Runtime (eclipse-temurin:17-jre-alpine)"]
        direction TB
        d["COPY --from=builder app.jar"] --> e["Run as non-root user"]
        e --> f["ENTRYPOINT java -jar app.jar"]
    end
    c -->|"JAR only"| d
```

The runtime image has no Maven, no `javac`, and no source code.

| Image | Base | Approx. size (compressed) |
|---|---|:---:|
| `catalogue-service:latest` | `eclipse-temurin:17-jre-alpine` | ~86 MB |
| `booking-service:latest` | `eclipse-temurin:17-jre-alpine` | ~86 MB |

---

## 9. Design Decisions and Trade-offs

| Decision | Choice | Rationale | Trade-off |
|---|---|---|---|
| **Data store** | In-memory `ConcurrentHashMap` | No external dependencies; fast; easy to reason about | Data resets on restart; not suitable for production persistence |
| **Inter-service transport** | Synchronous REST via `RestTemplate` | Standard, language-agnostic, trivially testable with curl | Temporal coupling: Catalogue downtime blocks all bookings |
| **Service discovery** | Docker DNS | Built into Compose; no Consul or Eureka needed | Single Docker network only; not portable to multi-host clusters |
| **Startup ordering** | `depends_on` + health check | Prevents premature calls from Booking Service | Adds ~20 s to startup; not a substitute for retries or circuit breakers |
| **Concurrency control** | `synchronized` on the item object | Fine-grained per-item locking; prevents overselling without DB transactions | Blocking; valid for one JVM and one replica only |
| **Build strategy** | Multi-stage Docker build | No local JDK or Maven needed; lean, secure runtime image | Slower first build (dependency download), cached afterwards |

---

## 10. Known Limitations and Next Steps

| # | Limitation | Suggested improvement |
|---|---|---|
| 1 | `PUT /reduce` is an internal operation but is reachable from the host because port `8080` is published. Any client can deplete stock without going through booking. | Remove the host port mapping for `catalogue-service` (or restrict to `127.0.0.1`), or expose only `GET` routes publicly. |
| 2 | The pre-check (`GET` then compare) duplicates the authoritative check and adds a network round trip. | Keep `GET` only for the `404` distinction and let `reduce` decide stock sufficiency, or drop the quantity pre-check. |
| 3 | `RestTemplate` has no explicit connect/read timeouts, retries, or circuit breaker. A slow Catalogue Service can exhaust Booking threads. | Configure timeouts on the `RestTemplate` bean; add Resilience4j if the system grows. |
| 4 | Booking is not idempotent. A client retry after a timeout can deduct stock twice. | Accept an idempotency key on `POST /booking`. |
| 5 | `PUT /reduce` is a state change on a non-idempotent verb with query parameters. | Prefer `POST /catalogue/items/{id}/reservations` with a body if the API is to be public. |
| 6 | State is lost on restart and cannot be shared across replicas. | Move to a database with conditional updates (`UPDATE ... WHERE available_count >= ?`) to replace the JVM lock. |

---

*Maintained alongside the source code. Update this document whenever endpoints, ports, or the Compose topology change.*
