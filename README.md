# XML Parsing Pipeline — Java / Spring Boot (Gradle)

A concurrent RSS/XML feed processor built with Java 17, Spring Boot 3.2, Gradle, and PostgreSQL.

---

## How to Run

### Prerequisites
- Docker and Docker Compose installed (no local Java or Gradle needed)

### Start the service

```bash
cd xml_pipeline_java_gradle
docker-compose up --build
```

The API will be available at `http://localhost:8081`.

> **Note:** Flyway runs migrations on startup — no manual DB setup needed.

### Submit a job with all 100 URLs

```bash
# Start a job using the built-in 100 URLs
curl -s -X POST http://localhost:8081/jobs/default | python3 -m json.tool
# → {"job_id": "...", "status": "pending", "total_urls": 100}

# Poll status
curl -s http://localhost:8081/jobs/<job_id> | python3 -m json.tool

# Per-URL task breakdown
curl -s http://localhost:8081/jobs/<job_id>/tasks | python3 -m json.tool

# Only failed tasks
curl -s "http://localhost:8081/jobs/<job_id>/tasks?status=failed"
```

### Submit a custom list of URLs

```bash
curl -s -X POST http://localhost:8081/jobs \
  -H "Content-Type: application/json" \
  -d '{"urls": ["https://www.anduril.com/feed.xml", "https://www.androidcentral.com/feeds.xml"]}'
```

### Check Feed Record In DB

```bash
# connect to psql DB
docker exec -it xml_pipeline_gradle_db psql -U xmluser -d xmlpipeline

# Query a Feed corresponding to a task ID
SELECT title, author, link, published_date FROM feed_records WHERE task_id = '92a55461-f7ad-405d-bc4a-15c6b7171e75';
```


### Stop everything

```bash
docker-compose down -v    # -v also removes the postgres volume
```

---

## Build with Gradle (local)

Requires Java 17+ installed locally and PostgreSQL running on port 5432.

```bash
# Compile
./gradlew compileJava

# Build fat JAR
./gradlew bootJar

# Run locally
./gradlew bootRun

# Run tests
./gradlew test

# Skip tests when building
./gradlew bootJar -x test
```

The fat JAR is output to `build/libs/xml-pipeline.jar`.

---

## API Reference

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/jobs` | Start job with custom URL list (body: `{"urls": [...]}`) |
| `POST` | `/jobs/default` | Start job with built-in 100 URLs |
| `GET` | `/jobs?limit=20` | List recent jobs |
| `GET` | `/jobs/{id}` | Job status + live counts + elapsed time |
| `GET` | `/jobs/{id}/tasks?status=` | Per-URL task details (optional status filter) |
| `GET` | `/jobs/health` | Liveness check |

---

## Design Decisions

### Concurrency: CachedThreadPool + Semaphore (Java 17)

**Choice:** `Executors.newCachedThreadPool()` + `java.util.concurrent.Semaphore(20)` + `CountDownLatch`.

**How it works:**
- `@Async("asyncExecutor")` fires the entire job in a background thread, returning immediately to the HTTP caller.
- Inside `processJob()`, one `Runnable` per URL is submitted to the shared `jobExecutor` (CachedThreadPool).
- A `Semaphore(MAX_CONCURRENT)` caps how many URLs are fetched simultaneously — threads that exceed the limit block cheaply waiting for a permit.
- A `CountDownLatch(urls.size())` waits for all tasks to finish before writing the final job status.

```
HTTP caller
    │
    └─► JobService.createJob()  ──@Async──►  ProcessorService.processJob()  (asyncExecutor thread)
                                                        │
                                          ┌─────────────┼──────────────┐
                                     URL-1 task    URL-2 task    URL-3 task   (jobExecutor threads)
                                          │              │              │
                                       Semaphore(20) — at most 20 HTTP requests at once
                                          │              │              │
                                       fetch → parse → persist      (blocking I/O)
                                          └──────────────┴──────────────┘
                                                   CountDownLatch
                                                        │
                                               finaliseJob() → DB
```

**Why CachedThreadPool + Semaphore instead of a fixed thread pool?**
A `FixedThreadPool(20)` would work but wastes threads during DB writes (thread is blocked writing but not doing HTTP). CachedThreadPool creates threads on demand and reuses idle ones; the Semaphore is the actual concurrency limiter for HTTP specifically.

**Why `TaskPersistenceService` is a separate bean:**
Spring `@Transactional` works via AOP proxy. If `ProcessorService` called its own `@Transactional` methods directly (`this.markTaskInProgress()`), the proxy would be bypassed and every `@Modifying` UPDATE would throw `TransactionRequiredException`. Extracting DB operations into `TaskPersistenceService` ensures calls always go through the proxy.

### XML Parsing: Rome → DOM fallback

**Rome** (`com.rometools:rome`) is the Java equivalent of Python's `feedparser`:
- Handles RSS 0.9x/1.0/2.0, Atom 0.3/1.0, RDF out of the box
- Normalises field names across all feed formats
- Handles encoding detection from XML declaration

**DOM fallback** (`org.w3c.dom`, built-in) handles raw XML that Rome doesn't recognise.

**Malformed XML policy:**

| Situation | Behaviour |
|---|---|
| Rome `FeedException`, entries returned | Accept entries, log warning |
| Rome `FeedException`, no entries | Try DOM fallback |
| DOM `SAXException` (syntax error) | Throw `ParseException` (non-retryable, task failed) |
| Valid XML, 0 items | Return empty list (task: completed, 0 records) |

### Retry Policy

| Error | Behaviour |
|---|---|
| HTTP 404 | Fail immediately, no retry |
| HTTP 4xx (other) | Fail immediately, no retry |
| HTTP 5xx | Retry up to 3× with backoff 1s → 2s → 4s |
| Network timeout / IOException | Retry up to 3× with backoff 1s → 2s → 4s |
| Malformed XML / ParseException | Fail immediately, no retry |

### Database: PostgreSQL + Spring Data JPA + Flyway

Three tables:
```
jobs         – one row per job (status, total_urls, timestamps)
tasks        – one row per URL per job (status, error, records_extracted)
feed_records – one row per extracted article (title, link, author, published_date, summary)
```

Job status counts are computed live from the `tasks` table on every `GET /jobs/{id}` request
using `GROUP BY status`. This is accurate, avoids stale atomic-counter bugs, and the index on
`tasks.job_id` keeps the query fast.

Flyway runs `V1__initial_schema.sql` automatically on first startup.

### Logging: Logback + Logstash JSON Encoder

Every log line is a JSON object:
```json
{"@timestamp":"2024-01-01T12:00:00Z","level":"INFO","message":"fetch_started",
 "job_id":"abc-123","url":"https://hnrss.org/frontpage","attempt":1}
```

Key events logged: `fetch_started`, `fetch_success`, `fetch_retry`, `fetch_404`,
`fetch_failed`, `parse_success`, `parse_empty_feed`, `parse_error`,
`records_persisted`, `task_failed_permanent`, `job_completed`.

To trace a single job:
```bash
docker-compose logs app | grep '"job_id":"<uuid>"'
```

---

## Tradeoffs

### What I'd do differently with more time

1. **Persistent job queue.** Background jobs live in the Spring process. A restart mid-job
   causes in-flight tasks to be lost (DB shows "running" forever). A proper queue — Spring Batch,
   Quartz Scheduler, or a Redis-backed queue — would allow job resumption.

2. **Batch DB inserts.** Records are inserted per-task. For high-volume feeds,
   `JdbcTemplate.batchUpdate()` or `saveAll()` with larger batches would reduce round-trips.

3. **Per-domain rate limiting.** The semaphore limits total concurrency but doesn't prevent
   hammering a single domain. A per-domain `RateLimiter` would be
   more polite and avoid 429s.

4. **Pagination on `/tasks`.** Currently returns all rows for a job. Should add cursor-based pagination.

5. **Integration tests.** Testcontainers + WireMock would allow full end-to-end tests
   with a real Postgres and mock HTTP server.

---

## Scalability Analysis

### At 10× scale (1,000 URLs/job)

**Bottleneck 1 – DB connection pool.**
HikariCP defaults to `maximum-pool-size=20`. 1,000 threads waiting to write records
will queue on DB connections. Fix: increase pool size, or batch writes.

**Bottleneck 2 – DB write volume.**
1,000 URLs × ~30 records = ~30,000 INSERTs. Using `saveAll()` with batch size configured
(`spring.jpa.properties.hibernate.jdbc.batch_size=100`) reduces this to ~300 DB round-trips.

**Bottleneck 3 – `GET /jobs/{id}/tasks` response size.**
1,000 task rows × ~200 bytes = 200 KB JSON. Still fine, but should add pagination.

### At 100× scale (10,000 URLs/job)

**Bottleneck 1 – Single-process limit.**
Split into a web process (POST /jobs) and multiple worker processes consuming from a
durable queue (SQS, RabbitMQ, Kafka).

**Bottleneck 2 – DB insert throughput.**
10,000 URLs × 30 records = 300,000 rows per job. Fix: stage records in a temp CSV,
then `COPY FROM` (PostgreSQL bulk load: ~100k rows/sec vs. ~2k rows/sec for INSERTs).

**Bottleneck 3 – External rate limits.**
Dozens of simultaneous requests to the same origin will trigger 429s.
Need per-domain token bucket backed by Redis (shared across workers).

**Bottleneck 4 – Single DB node.**
300,000+ rows per job requires read replicas and connection pooling (PgBouncer).

---

## Demo Output (example)

```
curl -s http://localhost:8081/jobs/1cf94a8f-7179-4aee-861a-36cf202eb65e | python3 -m json.tool

{
    "status": "completed",
    "completed": 2,
    "failed": 0,
    "pending": 0,
    "job_id": "1cf94a8f-7179-4aee-861a-36cf202eb65e",
    "total_urls": 2,
    "in_progress": 0,
    "elapsed_seconds": 2.352,
    "created_at": "2026-06-04T05:10:28.055562",
    "started_at": "2026-06-04T05:10:28.09162",
    "completed_at": "2026-06-04T05:10:30.443965"
}

curl -s http://localhost:8081/jobs/1cf94a8f-7179-4aee-861a-36cf202eb65e/tasks | python3 -m json.tool
 
[
    {
        "url": "https://www.cinemablend.com/feeds.xml",
        "status": "completed",
        "task_id": "f8f52b87-2a7c-4e92-87da-d4af305815c2",
        "records_extracted": 50,
        "attempt_count": 0,
        "started_at": "2026-06-04T05:10:28.098562",
        "completed_at": "2026-06-04T05:10:29.426739"
    },
    {
        "url": "https://www.gfinityesports.com/feed.xml",
        "status": "completed",
        "task_id": "92a55461-f7ad-405d-bc4a-15c6b7171e75",
        "records_extracted": 40,
        "attempt_count": 0,
        "started_at": "2026-06-04T05:10:28.098108",
        "completed_at": "2026-06-04T05:10:30.429654"
    }
]

xmlpipeline=# SELECT title, author, link, published_date FROM feed_records WHERE task_id = '92a55461-f7ad-405d-bc4a-15c6b7171e75';
                                           title                                            |      author      |                                                               link                                                               |   published_date    
--------------------------------------------------------------------------------------------+------------------+----------------------------------------------------------------------------------------------------------------------------------+---------------------
 How to Complete the Deadly Delivery Challenge in 007 First Light                           | Ashlee Manalang  | https://www.gfinityesports.com/article/how-to-complete-the-deadly-delivery-challenge-in-007-first-light                          | 2026-06-04 05:09:10
 How to Bypass the Laser Sensors in 007 First Light                                         | Ashlee Manalang  | https://www.gfinityesports.com/article/how-to-bypass-the-laser-sensors-in-007-first-light                                        | 2026-06-04 04:29:45
 GTA YouTuber Tried To Walk Into Rockstar Headquarters                                      | Ashlee Manalang  | https://www.gfinityesports.com/article/gta-youtuber-tr--More-- 
```

Failures are expected — many RSS feeds require auth, return 403, are behind WAFs, or have
moved. All are handled gracefully; the pipeline continues to completion.

---

## Project Structure

```
xml_pipeline_java_gradle/
├── src/main/java/com/xmlpipeline/
│   ├── XmlPipelineApplication.java           ← entry point (@EnableAsync)
│   ├── config/AppConfig.java                 ← HttpClient, thread pool, @Async executor beans
│   ├── controller/JobController.java         ← REST endpoints
│   ├── service/
│   │   ├── JobService.java                   ← create/read jobs
│   │   ├── ProcessorService.java             ← async orchestration (CachedThreadPool + Semaphore)
│   │   ├── TaskPersistenceService.java       ← all @Transactional DB operations
│   │   ├── FetcherService.java               ← HTTP fetch + exponential-backoff retry
│   │   └── ParserService.java                ← Rome + DOM fallback parser
│   ├── entity/   Job, Task, FeedRecord       ← JPA entities (3 DB tables)
│   ├── repository/                           ← Spring Data JPA repositories
│   ├── dto/                                  ← API request/response shapes
│   └── exception/                            ← FetchException, ParseException, RetryableFetchException
├── src/main/resources/
│   ├── application.yml                       ← DB, Flyway, app settings
│   ├── logback-spring.xml                    ← JSON structured logging config
│   └── db/migration/V1__initial_schema.sql   ← Flyway migration (3 tables + 4 indexes)
├── build.gradle                              ← Gradle build (Groovy DSL)
├── settings.gradle                           ← project name
├── gradlew / gradlew.bat                     ← Gradle wrapper scripts
├── gradle/wrapper/                           ← wrapper JAR + properties (Gradle 8.7)
├── Dockerfile                                ← Multi-stage: JDK builder → JRE runtime
├── docker-compose.yml                        ← app (port 8081) + postgres (port 5434)
└── urls.json                                 ← 100 RSS/XML feed URLs
```

---

## Maven vs Gradle — What Changed

This project is the Gradle equivalent of `xml_pipeline_java` (Maven).
All Java source files are identical; only the build tooling differs.

| | Maven (`xml_pipeline_java`) | Gradle (`xml_pipeline_java_gradle`) |
|---|---|---|
| Build file | `pom.xml` | `build.gradle` + `settings.gradle` |
| Compile | `mvn compile` | `./gradlew compileJava` |
| Build JAR | `mvn package` | `./gradlew bootJar` |
| Run locally | `mvn spring-boot:run` | `./gradlew bootRun` |
| Skip tests | `-DskipTests` | `-x test` |
| Output JAR location | `target/xml-pipeline-*.jar` | `build/libs/xml-pipeline.jar` |
| Docker build stage image | `maven:3.9-eclipse-temurin-21` | `eclipse-temurin:17-jdk-alpine` |
| DB host port | `5433` | `5434` |
