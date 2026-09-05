# Development

Requirements: Java 21, Docker, Node 20+ and Gradle only through the checked-in wrapper.

```bash
./gradlew test
./gradlew build
cd frontend && npm install && npm run build
docker compose -f deployment/docker-compose.yml config
```

Use `SPRING_R2DBC_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`, `SPRING_FLYWAY_URL`, `SPRING_REDIS_HOST` and `GATEWAY_API_KEY` for local configuration. Keep local secrets in an untracked environment file.
