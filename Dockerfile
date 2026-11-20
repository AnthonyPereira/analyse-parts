# Image de production : un seul conteneur, Spring Boot sert l'API et le site React (ADR 0016).
# Elle tourne sur le serveur maison (ADR 0022) : construite par GitHub Actions, publiée sur ghcr.io.
# Construction locale : docker build -t analyse-parts .

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

# 3. L'image finale : un JRE seul et le jar extrait (plus rapide à charger).
# Mémoire : 75 % de la limite du conteneur (1 Go dans docker-compose.yml). Le portable a 4 threads :
# compilateur JIT complet par défaut, plus de cache AOT ni de réglages pour 0,1 processeur (Render).
# Tâches planifiées à l'heure de Paris.
FROM eclipse-temurin:27-jre
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=Europe/Paris"
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=back /back/extracted/ ./
USER app
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
