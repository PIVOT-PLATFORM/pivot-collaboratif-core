# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
# EN08.3 : fr.pivot:pivot-core-starter (GitHub Packages, repo pivot-core) exige une
# authentification même en lecture — GITHUB_ACTOR/GITHUB_TOKEN injectés comme secrets
# BuildKit (jamais dans les layers ni le build cache) et lus par .mvn/settings.xml
# (server-id "pivot-core-packages", voir ce fichier).
RUN --mount=type=secret,id=github_actor,env=GITHUB_ACTOR \
    --mount=type=secret,id=github_token,env=GITHUB_TOKEN \
    --mount=type=cache,target=/root/.m2/repository \
    ./mvnw dependency:go-offline -B -q
COPY src/ src/
RUN --mount=type=secret,id=github_actor,env=GITHUB_ACTOR \
    --mount=type=secret,id=github_token,env=GITHUB_TOKEN \
    --mount=type=cache,target=/root/.m2/repository \
    ./mvnw package -DskipTests -B -q

# Runtime Alpine : surface OS minimale, CVE réduits. Builder jeté à la fin.
FROM eclipse-temurin:25-jre-alpine
# curl est absent de l'image Alpine JRE de base (surface OS minimale) — ajouté uniquement pour
# le HEALTHCHECK ci-dessous, seul appelant sur cette image. Doit rester avant `USER pivot` :
# apk a besoin de privilèges root pour écrire son log/verrou (sinon "Permission denied").
RUN apk upgrade --no-cache && apk add --no-cache curl
WORKDIR /app
RUN addgroup -S pivot && adduser -S -G pivot pivot
COPY --from=builder /workspace/target/*.jar app.jar
USER pivot
EXPOSE 8083
# EN04.2 — port de management Actuator (application.yml, management.server.port), séparé du
# port applicatif (:8083), non routé par nginx. 9083, pas 8081 : table des ports de service
# documentée (pivot-core/docker-compose.prod.yml, convention EN17.7) — ce module
# (pivot-collaboratif-core) est 8083/9083 ; 8081 est le port de management de pivot-core
# lui-même (un autre container). Isolation réseau (pas de publication host, réseau Docker
# interne uniquement) appliquée côté compose — EXPOSE ici documente le port, ne l'ouvre pas
# au host à lui seul.
EXPOSE 9083
# EN04.4 — timing aligné sur le pattern pivot-core (interval 10s, timeout 5s, start-period 30s,
# retries 3). Le HEALTHCHECK d'un éventuel docker-compose.prod.yml doit rester synchronisé avec
# ces valeurs — celui-ci s'applique quand l'image tourne en standalone (docker run, sans compose).
# Pas de context-path ici : management.server.port différent de server.port fait tourner
# Actuator sur son propre contexte de servlet, sans le préfixe /api/collaboratif.
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:9083/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
