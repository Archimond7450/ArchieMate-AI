# ==================== Build stage ====================
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Install sbt
RUN curl -L https://github.com/sbt/sbt/releases/download/v1.10.11/sbt-1.10.11.tgz | tar xz -C /opt && \
    ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt

# Copy build files
COPY project/ project/
COPY build.sbt .

# Download dependencies (cache layer)
RUN sbt update

# Copy source
COPY backend/ backend/
COPY shared/ shared/

# Build backend
RUN sbt backend/package

# ==================== Frontend build stage ====================
FROM node:20-alpine AS frontend-build

WORKDIR /app

# Copy frontend build files
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci

# Copy frontend source
COPY frontend/ frontend/

# Build frontend
RUN npm run build

# ==================== Runtime stage ====================
FROM eclipse-temurin:21-jre

# Create non-root user
RUN groupadd -r archiemate && useradd -r -g archiemate archiemate

WORKDIR /app

# Copy built artifacts
COPY --from=build /app/backend/target/scala-3.*/archiemate-backend_3-*.jar /app/archiemate.jar

# Copy built frontend
COPY --from=frontend-build /app/dist /app/dist

# Copy configuration
COPY backend/src/main/resources/application.conf /app/conf/application.conf

# Environment variables
ENV SERVER_HOST=0.0.0.0
ENV SERVER_PORT=8080

# Expose HTTP port
EXPOSE 8080

# Switch to non-root user
USER archiemate

# Run the application
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dconfig.file=/app/conf/application.conf", \
    "-jar", "/app/archiemate.jar"]
