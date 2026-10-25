# Build constraints shared by all agents

Environment: Windows, Git Bash. JDK = `C:/Users/sahukari.rao/.jdks/graalvm-jdk-21.0.7` (export JAVA_HOME inline per command is allowed in this project; never globally/persistently). Maven: `C:/Users/sahukari.rao/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn.cmd` (add `-s` nothing; use default settings). Node 24 + npm available. **Docker is NOT installed** -> Postgres/Mongo/Redis/RabbitMQ cannot run here and Testcontainers tests cannot execute. Therefore:
- Runtime profiles: `local` (default; H2 in PostgreSQL mode, in-memory cache (Caffeine), in-memory event relay, in-memory AI/KB doc store) and `docker` (PostgreSQL, Redis, RabbitMQ, MongoDB). Same code via interfaces (CacheService, EventPublisher/relay, DocumentStore). Flyway: `db/migration` (portable SQL working on H2-PG-mode AND PostgreSQL), `db/migration-pg` (PostgreSQL-only: partial indexes, GIN full-text, RLS policies) only added under profile `docker`.
- Tests needing Docker are tagged `@Tag("docker")` / named `*DockerIT`, excluded by default (surefire excludedGroups=docker), and reported honestly as NOT EXECUTED.
- NEVER claim anything executed unless you ran it. Never put real secrets anywhere; dummy local values only.
- Do NOT run `git push`, `curl`, `wget`, `rm -rf`. Write only inside this repo folder.
- Packages: `com.example.cx.core.*` (core-service), `com.example.cx.notification`, `com.example.cx.gateway`, `com.example.cx.dsa`.
- Ports: gateway 8080, core 8081, notification 8082, frontend dev 5173 (React), frontend-angular dev 4200.
- Keep API exactly per docs/API-CONTRACT.md; if a change is unavoidable, edit that file and mention it in your report.
