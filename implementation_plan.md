# Microservices Catalogue & Booking Application

## Overview

Build two Spring Boot microservices — **Catalogue Service** and **Booking Service** — packaged as self-contained Docker images using multi-stage builds. The final images require no JDK, Maven, or Tomcat on the host machine.

## Architecture

```
┌─────────────────────┐         REST          ┌──────────────────────┐
│                     │ ─────────────────────► │                      │
│   Booking Service   │  GET/PUT /catalogue/   │  Catalogue Service   │
│   (port 8081)       │  items/{id}            │  (port 8080)         │
│                     │ ◄───────────────────── │                      │
└─────────────────────┘                        └──────────────────────┘
         ▲                                               ▲
         │                                               │
    External API                                   External API
    POST /booking                                  GET /catalogue/items
    (client)                                       (client / booking)
```

**No API Gateway, no Eureka, no message broker** — just two services talking over HTTP inside a Docker network.

## Technology Stack

- **Language**: Java 17
- **Framework**: Spring Boot 3.x (embedded Tomcat — no external Tomcat needed)
- **Build**: Maven (multi-stage Docker build — no Maven needed on host)
- **Container**: Docker + Docker Compose
- **Data store**: In-memory (`ConcurrentHashMap`) — no database needed

## Project Structure

```
microservices_catalogue/
├── catalogue-service/
│   ├── src/main/java/com/catalogue/...
│   ├── pom.xml
│   └── Dockerfile
├── booking-service/
│   ├── src/main/java/com/booking/...
│   ├── pom.xml
│   └── Dockerfile
├── docker-compose.yml
└── README.md
```

## Proposed Changes

### Catalogue Service

#### [NEW] `catalogue-service/pom.xml`
Spring Boot 3 parent, Web starter, packaging as executable JAR.

#### [NEW] `catalogue-service/src/.../CatalogueServiceApplication.java`
Main Spring Boot entry point.

#### [NEW] `catalogue-service/src/.../model/Item.java`
Fields: `itemId` (String), `name`, `price` (double), `availableCount` (int).

#### [NEW] `catalogue-service/src/.../repository/ItemRepository.java`
In-memory store initialised with exactly 3 items. Uses `ConcurrentHashMap` for thread safety.

#### [NEW] `catalogue-service/src/.../controller/ItemController.java`
- `GET /catalogue/items` — return all items
- `GET /catalogue/items/{id}` — return single item (404 if not found)
- `PUT /catalogue/items/{id}/reduce?quantity=N` — reduce count atomically; returns 400 if insufficient

#### [NEW] `catalogue-service/Dockerfile`
Multi-stage: stage 1 = `maven:3.9-eclipse-temurin-17` for build; stage 2 = `eclipse-temurin:17-jre-alpine` for runtime.

---

### Booking Service

#### [NEW] `booking-service/pom.xml`
Spring Boot 3 parent, Web starter, RestTemplate / WebClient for HTTP.

#### [NEW] `booking-service/src/.../BookingServiceApplication.java`
Main entry point. Declares `RestTemplate` bean.

#### [NEW] `booking-service/src/.../controller/BookingController.java`
- `POST /booking` — accepts `{ "itemId": "...", "quantity": N }`
  1. Calls Catalogue Service `GET /catalogue/items/{id}` → 404 → "item not found"
  2. Checks `availableCount >= quantity` → false → "number of items exceeded"
  3. Calls `PUT /catalogue/items/{id}/reduce?quantity=N` → "item booked"

#### [NEW] `booking-service/src/.../config/AppConfig.java`
`RestTemplate` bean + catalogue service base URL from `application.properties`.

#### [NEW] `booking-service/Dockerfile`
Same multi-stage pattern as Catalogue Service.

---

### Docker Compose

#### [NEW] `docker-compose.yml`
- Service `catalogue-service`: exposes port 8080, bridge network `app-net`
- Service `booking-service`: exposes port 8081, depends_on catalogue-service, env var `CATALOGUE_URL=http://catalogue-service:8080`, bridge network `app-net`

---

### README

#### [NEW] `README.md`
Build, run, test, and transfer instructions.

## Verification Plan

### Phase 5 — Local Testing (before Docker)
Run both services locally with `mvn spring-boot:run` and test all scenarios using `curl`.

### Phase 8 — Container Testing
`docker compose up --build` then re-run all curl tests against containerised services.

### Test Matrix

| Scenario | Expected Response |
|---|---|
| GET unknown item | 404 / "item not found" |
| POST booking with unknown itemId | "item not found" |
| POST booking quantity > available | "number of items exceeded" |
| POST booking valid | "item booked" |
| POST booking exact remaining qty | "item booked" |
| POST booking after stock = 0 | "number of items exceeded" |
| Repeated valid bookings reducing stock | "item booked" each time; stock decreases |
