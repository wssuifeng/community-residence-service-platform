FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY backend/mvnw backend/pom.xml ./backend/
COPY backend/.mvn ./backend/.mvn
RUN chmod +x backend/mvnw
RUN cd backend && ./mvnw -B -DskipTests dependency:go-offline
COPY backend/src ./backend/src
RUN cd backend && ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jre

WORKDIR /app
RUN mkdir -p /app/uploads
COPY --from=build /workspace/backend/target/residence-backend-0.1.0-SNAPSHOT.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=prod \
    UPLOAD_DIR=/app/uploads

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
