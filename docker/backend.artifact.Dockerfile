FROM eclipse-temurin:17-jre

WORKDIR /app
RUN mkdir -p /app/uploads
COPY backend/target/residence-backend-0.1.0-SNAPSHOT.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=prod \
    UPLOAD_DIR=/app/uploads

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
