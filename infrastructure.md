# AI Practice Platform - Infrastructure & Setup

## Comprehensive Infrastructure Stack
- **Messaging:** StreamBase Kafka (`broker.subartaghosh.co.in:9092`) with SASL_SSL and SCRAM-SHA-512 authentication.

## Operational Decisions: The "Why" and "How"

### 1. Multi-Stage Docker Builds: Performance vs. Size
**Problem:** Standard Docker images for Spring Boot and React often exceed 1GB, leading to slow deployments and higher storage costs.
**Solution:** 
- **The Choice:** We implemented **Multi-Stage Builds**. 
    - **Backend:** Stage 1 uses a full Maven image to compile code. Stage 2 copies only the `.jar` to a slim Alpine JRE image. Result: Image size reduced from ~800MB to ~200MB.
    - **Frontend:** Stage 1 builds the production assets via Node. Stage 2 serves these static files via a lightweight Nginx container.
- **Rationale:** This ensures that production environments only contain what is strictly necessary for runtime, minimizing the attack surface and increasing security.

### 2. Environment Management: Decoupling Secrets
**Problem:** Hardcoding API keys or database passwords in code (or even Dockerfiles) is a critical security risk.
**Solution:**
- **Dynamic Injection:** We use `${VAR_NAME}` syntax in `docker-compose.yml` and `application.properties`. 
- **How it works:** Sensitive values like `GEMINI_API_KEY` are read from the host's environment or a `.env` file at runtime.
- **Rationale:** This architecture allows the same Docker image to be used across Staging and Production without modification, simply by changing the environment variables provided by the host (e.g., Render or AWS).

### 3. Database Strategy: Relational vs. NoSQL
**Problem:** AI-generated data (questions, plans) is semi-structured and might seem like a good fit for NoSQL (MongoDB).
**Solution:**
- **The Choice:** We chose **PostgreSQL**.
- **Why?** While the AI output is JSON, the core relationship (User -> Plan -> Items -> Quizzes) is strictly relational. PostgreSQL offers robust ACID compliance and native JSONB support, giving us the "best of both worlds" (Structured relational data + flexible JSON storage).
- **Future-Proofing:** We've recommended **Flyway** for migrations to ensure that as the schema evolves, database updates are version-controlled and repeatable.

### Backend
- `SPRING_DATASOURCE_URL`: JDBC URL for Postgres (e.g., `jdbc:postgresql://db:5432/aiplatform`).
- `SPRING_DATASOURCE_USERNAME`: Database user (default: `postgres`).
- `SPRING_DATASOURCE_PASSWORD`: Database password (default: `postgres`).
- `GEMINI_API_KEY`: **Critical** - Your Google Gemini API key.
- `JWT_SECRET`: Secret key used for signing JWT tokens.
- `FRONTEND_URL`: URL of the frontend (for CORS configuration).
- `SPRING_KAFKA_BOOTSTRAP_SERVERS`: StreamBase Kafka broker URL (`broker.subartaghosh.co.in:9092`).
- `SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL`: `SASL_SSL`.
- `SPRING_KAFKA_PROPERTIES_SASL_MECHANISM`: `SCRAM-SHA-512`.
- `SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG`: SASL SCRAM-SHA-512 Jaas login module config with username and password.

### Frontend
- `VITE_API_BASE_URL`: The URL of the backend API (e.g., `http://localhost:8080/api`).

## Local Setup (Standard)
1. **Clone the repo.**
2. **Backend:**
    - Ensure Postgres is running.
    - Create database `aiplatform`.
    - Set env variables in `application.properties` or environment.
    - Run `./mvnw spring-boot:run`.
3. **Frontend:**
    - `npm install`
    - `npm run dev`

## Docker Setup (Recommended)
You can launch the entire stack with a single command:
```bash
docker-compose up --build
```
The `docker-compose.yml` file is configured to:
- Build both frontend and backend images from their respective `Dockerfiles`.
- Link them using a shared network.
- Persistent the Postgres data using a Docker volume (`db_data`).

## Deployment Guidelines
- **Render/Railway/Vercel:** The project is configured for easy deployment on these platforms. Refer to `vercel.json` for frontend-specific settings and `genezio.yaml` if using Genezio.
- **Database Migrations:** Currently handled by Hibernate's `ddl-auto`. For production, it is recommended to switch to **Flyway** or **Liquibase**.

## Development Tools
- **Swagger/OpenAPI:** (Planned) To be added for automated API documentation.
- **Postman Collection:** A collection of API requests for testing is available in the `docs/` folder (if created).

---

[Back to Overview](./overview.md)
