# Image de production : un seul conteneur, Spring Boot sert l'API et le site React (ADR 0016).
# Construction : docker build -t analyse-parts .

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
RUN sh mvnw -q -B -DskipTests package && cp target/backend-*.jar app.jar

# 3. L'image finale : un JRE seul
FROM eclipse-temurin:27-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=back /back/app.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod
# 512 Mo au total sur l'offre gratuite : un tas de 300 Mo environ, un ramasse-miettes simple
# (un seul cœur), une pile de threads réduite. Les tâches planifiées sont à l'heure de Paris.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -XX:ReservedCodeCacheSize=64m -Duser.timezone=Europe/Paris"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
