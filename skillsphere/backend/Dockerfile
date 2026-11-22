
# Multi-stage Dockerfile for SkillSphere Backend (Java 21)

# Stage 1: Build JAR
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Create non-root user
RUN addgroup --system spring && adduser --system spring --ingroup spring

# Create writable upload directory and assign ownership
RUN mkdir -p /app/uploads \
    && chown -R spring:spring /app/uploads \
    && chown spring:spring /app

# Copy application JAR
COPY --from=build --chown=spring:spring /app/target/skillsphere-backend-*.jar app.jar

# Run as non-root user
USER spring:spring

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
