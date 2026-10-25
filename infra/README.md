# infra

Docker Compose stack, Dockerfiles, Prometheus and Grafana provisioning.

**Verification status: NOT RUN.** Docker is not installed in the authoring environment. Only YAML/JSON syntax was checked (see below);
the compose stack, the images and the Grafana dashboard have never been started.

```
cp env.example .env                       # dummy values; edit the CHANGE_ME ones
docker compose -f infra/docker-compose.yml --env-file .env up -d                   # postgres, mongo, redis, rabbitmq, prometheus, grafana
scripts/run-local.sh build                                                         # produces the jars the app images copy
docker compose -f infra/docker-compose.yml --env-file .env --profile apps up -d --build   # + core, notification, gateway, frontend
```

* Required secrets use `${VAR:?...}`: compose refuses to start when they are missing (no silent defaults). All published ports bind to 127.0.0.1.
* App images are runtime-only (copy a pre-built jar, run as a non-root user). The frontend image builds with Node and serves via unprivileged nginx;
  `VITE_API_BASE_URL` is baked in at build time.
* The gateway exposes actuator on an internal management port (8090), which Prometheus scrapes; it is not published to the host.
* Dashboard `grafana/dashboards/cx-platform-overview.json` uses `http_server_requests_seconds_*`, `hikaricp_connections_*`, `cx_cache_total{result}`,
  RabbitMQ queue depth (`rabbitmq_queue_messages_ready`, from the RabbitMQ prometheus plugin on port 15692) and gateway counters. The p95 latency
  panel needs `management.metrics.distribution.percentiles-histogram.http.server.requests=true` on the service, otherwise it stays empty.
* Running services on the host (not in compose) while Prometheus runs in compose: edit `prometheus.yml` targets to `host.docker.internal:<port>`.
* MongoDB is provisioned for the AI/KB document store of the docker profile; the services configure it via their own properties.

Syntax validation performed: see the final report (YAML parsed with a Node YAML parser, JSON parsed with `JSON.parse`).
