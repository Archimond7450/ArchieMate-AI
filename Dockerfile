# ==================== Build stage ====================
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# Install sbt
RUN curl -L https://github.com/sbt/sbt/releases/download/v1.10.11/sbt-1.10.11.tgz | tar xz -C /opt && \
    ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt

# Copy build files
COPY project/ project/
COPY build.sbt .

# Download dependencies first (for caching)
RUN sbt update

# Copy source
COPY backend/ backend/
COPY shared/ shared/
COPY frontend/ frontend/

# Compile Scala.js and build backend (universal zip)
RUN sbt frontend/fastOptJS \
    && mkdir -p /app/backend/src/main/resources/public \
    && cp frontend/target/scala-3.6.4/archiemate-frontend-fastopt.js /app/backend/src/main/resources/public/ \
    && cp frontend/index.html /app/backend/src/main/resources/public/ \
    && sbt backend/universal:packageBin

# Extract the zip to a directory for the runtime stage
RUN apt-get update && apt-get install -y unzip && \
    unzip -q /app/backend/target/universal/archiemate-backend-*.zip -d /app/backend/target/universal/extracted && \
    apt-get purge -y unzip && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

# ==================== Runtime stage ====================
FROM eclipse-temurin:21-jre

# Create non-root user
RUN groupadd -r archiemate && useradd -r -g archiemate archiemate

WORKDIR /app

# Copy the packaged application
COPY --from=build /app/backend/target/universal/extracted/* /app/

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
CMD ["/app/bin/archiemate-backend"]
