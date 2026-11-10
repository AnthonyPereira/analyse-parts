# Image de production : un seul conteneur, Spring Boot sert l'API et le site React (ADR 0016).
# Construction : docker build -t analyse-parts .

# Réglages de la JVM, communs à l'entraînement et à l'exécution (le cache AOT les veut identiques).
# 512 Mo au total sur l'offre gratuite : un tas de 300 Mo environ, un ramasse-miettes simple,
# une pile de threads réduite. 0,1 processeur seulement : le compilateur C1 seul
# (TieredStopAtLevel=1) laisse le processeur au démarrage. Tâches planifiées à l'heure de Paris.
ARG JVM_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -XX:ReservedCodeCacheSize=64m -XX:TieredStopAtLevel=1 -Duser.timezone=Europe/Paris"

# 1. Le site React, compilé en fichiers statiques
FROM node:24-alpine AS front
WORKDIR /front
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2. Le backend, avec le site copié dans static/ (servi par Spring à la racine)
FROM eclipse-temurin:27-jdk AS back
WORKDIR /back
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN sh mvnw -q -B dependency:go-offline
COPY backend/src src
COPY --from=front /front/dist src/main/resources/static
RUN sh mvnw -q -B -DskipTests package && cp target/backend-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --destination extracted

# 3. Entraînement du cache AOT (JEP 483/514) : l'application démarre une fois sur un PostgreSQL
# temporaire, Java enregistre les classes chargées et liées. Render coupe le service s'il n'ouvre
# pas son port en 3 minutes environ, et le serveur gratuit n'a que 0,1 processeur.
FROM eclipse-temurin:27-jre AS train
ARG JVM_OPTS
ENV JAVA_TOOL_OPTIONS=${JVM_OPTS}
RUN apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends postgresql >/dev/null
WORKDIR /app
COPY --from=back /back/extracted/ ./
RUN pg_ctlcluster $(ls /etc/postgresql) main start \
    && su postgres -c "psql -q -c \"create user training password 'training'\" -c 'create database training owner training'" \
    && java -XX:AOTCacheOutput=app.aot -Dspring.profiles.active=training -Dspring.context.exit=onRefresh -jar app.jar \
    && test -s app.aot

# 4. L'image finale : un JRE seul, le jar extrait (plus rapide à charger) et son cache AOT
FROM eclipse-temurin:27-jre
ARG JVM_OPTS
ENV JAVA_TOOL_OPTIONS=${JVM_OPTS}
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=back /back/extracted/ ./
COPY --from=train /app/app.aot ./
USER app
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "app.jar"]
