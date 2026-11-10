# BrewFlow

POS для кассы, кухонный экран (KDS) и админка для одного кафе.

- `backend/` — Spring Boot 4, Java 21, PostgreSQL 18
- `web/` — Next.js 16, React 19, TypeScript

## Запуск

Нужны Java 21, Node.js ≥ 20.9 и PostgreSQL 18.

```bash
bash scripts/dev-db.sh start
```

```bash
cd backend && ./mvnw package -DskipTests
```

```bash
APP_MODE=TRAINING COOKIE_SECURE=false java -jar backend/target/brewflow-backend-0.3.2-SNAPSHOT.jar
```

```bash
cd web && npm ci && npm run build && npm run start
```

Сайт: http://localhost:3000, Swagger: http://localhost:8080/swagger-ui.html.

Подробности о проверках — в [docs/EVIDENCE.md](docs/EVIDENCE.md).
