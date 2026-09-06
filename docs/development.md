# Development

Requirements: Java 21, Docker, Node 20+ and Gradle only through the checked-in wrapper.

```bash
./gradlew test
./gradlew build
npm --prefix frontend ci
npm --prefix frontend run build
docker compose config --quiet
```

Use `SPRING_R2DBC_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `SPRING_FLYWAY_URL`, `SPRING_REDIS_HOST` and `GATEWAY_API_KEY` for local configuration. Keep local secrets in an untracked environment file.

## Backend verification

`./gradlew test` includes unit tests and `AdminApiIntegrationTest`. Docker must be running: Testcontainers starts isolated MySQL 8.4 and Redis 7.4 instances on temporary ports and removes them after the tests. No existing database is used. The integration suite runs the real Flyway migration and exercises authentication, all four CRUD resources, constraints, credential masking, connection tests and transactional manual sync.

`HttpProviderModelDiscoveryTest` uses a local HTTP fixture to verify authentication, URL selection, pagination, invalid responses and timeouts without contacting an external provider.

## Full Docker and browser verification

The image builds and serves the Vue admin UI with the backend. Compose waits for MySQL and Redis, and the gateway health check verifies application/storage readiness. Host ports are configurable to avoid conflicts with local services:

```bash
MYSQL_PORT=13306 REDIS_PORT=16379 GATEWAY_PORT=18080 \
  docker compose -p llm-gateway-phase1 up --build -d --wait --wait-timeout 180
curl --fail http://localhost:18080/actuator/health
```

Install the Playwright browser once from `frontend/`:

```bash
npx playwright install --only-shell chromium
```

Alternatively, set `PLAYWRIGHT_CHANNEL=chrome` to use an installed Google Chrome.

The browser suite exercises the running UI and uses a local model-catalog fixture. It creates uniquely named records and deletes only its own records. When the gateway runs in Docker, point `PROVIDER_FIXTURE_HOST` at the Docker network's host-side address so the gateway can reach that fixture:

```bash
TEST_PROVIDER_HOST="$(docker network inspect llm-gateway-phase1_default --format '{{(index .IPAM.Config 0).Gateway}}')"
GATEWAY_URL=http://localhost:18080 PROVIDER_FIXTURE_HOST="$TEST_PROVIDER_HOST" \
  npm --prefix frontend run test:e2e
```

For a gateway running directly on the host, the fixture host defaults to `127.0.0.1`. Set `GATEWAY_API_KEY` for the suite if using a non-default gateway token. Screenshots are saved under `frontend/test-results/`.

After verification, remove the dedicated test stack and its temporary data:

```bash
docker compose -p llm-gateway-phase1 down --volumes
```
