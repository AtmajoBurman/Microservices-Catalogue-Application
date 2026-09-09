# Task Tracker

## Phase 1 — Architecture
- [x] Design defined in implementation plan

## Phase 2 — Project Setup
- [x] Create directory structure

## Phase 3 — Catalogue Service
- [x] pom.xml
- [x] CatalogueServiceApplication.java
- [x] Item.java (model)
- [x] ItemRepository.java (in-memory store)
- [x] ItemController.java
- [x] application.properties

## Phase 4 — Booking Service
- [x] pom.xml
- [x] BookingServiceApplication.java
- [x] BookingRequest.java (DTO)
- [x] BookingController.java
- [x] AppConfig.java (RestTemplate bean)
- [x] application.properties

## Phase 5 — Local Testing
- [x] Run catalogue-service locally (Docker JRE container, port 18080)
- [x] Run booking-service locally (Docker JRE container, port 18081)
- [x] Test all scenarios — 7/7 passed

## Phase 6 — Dockerization
- [x] Dockerfile (catalogue-service) — multi-stage, JRE-Alpine
- [x] Dockerfile (booking-service) — multi-stage, JRE-Alpine

## Phase 7 — Docker Compose
- [x] docker-compose.yml — bridge network app-net, health check, depends_on

## Phase 8 — Final Verification
- [x] docker compose up --build — both images built successfully (~86 MB each)
- [x] Verify all business rules in containers — 16/16 passed
- [x] README.md written
