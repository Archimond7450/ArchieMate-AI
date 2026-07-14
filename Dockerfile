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
COPY frontend/ frontend/

# Compile Scala.js
RUN sbt frontend/fullOptJS

# Copy the compiled Scala.js output to backend's public resources
RUN mkdir -p /app/backend/src/main/resources/public && \
    cp frontend/target/scala-3.6.4/archiemate-frontend-opt.js /app/backend/src/main/resources/public/ && \
    cp frontend/index.html /app/backend/src/main/resources/public/

# Build backend (fat jar) with static frontend included
RUN sbt clean backend/assembly

# ==================== Runtime stage ====================
FROM eclipse-temurin:21-jre

# Create non-root user
RUN groupadd -r archiemate && useradd -r -g archiemate archiemate

WORKDIR /app

# Copy built artifacts
COPY --from=build /app/backend/target/scala-3.6.4/archiemate-backend-assembly-*.jar /app/archiemate.jar

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
    "-jar", "/app/archiemate.jar"]
