# Multipaz Server Bundle
# Single image containing all reference servers and web frontend

FROM eclipse-temurin:17-jre-jammy

# Install nginx for reverse proxy
RUN apt-get update && apt-get install -y --no-install-recommends \
   nginx less sqlite3 libsqlite3-dev curl && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r multipaz && useradd -r -g multipaz multipaz

WORKDIR /app

# Copy shared dependency JARs (deduplicated across all servers)
COPY multipaz-server-deployment/build/docker-staging/libs/ /app/libs/

# Copy thin server JARs
COPY multipaz-server-deployment/build/docker-staging/jars/ /app/jars/

# Copy web frontend (built by Gradle before docker build)
COPY multipaz-server-frontend/build/dist/js/productionExecutable /app/web
RUN chmod -R 755 /app/web

# Copy nginx configuration. nginx-validatopia.conf is only swapped into place at container start
# (start-servers.sh) when PROFILE=validatopia; nginx.conf remains the default profile's config.
COPY multipaz-server-deployment/docker/nginx.conf /etc/nginx/nginx.conf
COPY multipaz-server-deployment/docker/nginx-validatopia.conf /etc/nginx/nginx-validatopia.conf
COPY multipaz-server-deployment/docker/nginx-validatopia-locations.conf /etc/nginx/conf.d/validatopia-locations.conf

# Copy Multipaz services configurations
COPY multipaz-server-deployment/docker/services/backend.conf /etc/multipaz/backend.conf
COPY multipaz-server-deployment/docker/services/csa.conf /etc/multipaz/csa.conf
COPY multipaz-server-deployment/docker/services/openid4vci.conf /etc/multipaz/openid4vci.conf
COPY multipaz-server-deployment/docker/services/records.conf /etc/multipaz/records.conf
COPY multipaz-server-deployment/docker/services/verifier.conf /etc/multipaz/verifier.conf

# Copy initial data
COPY multipaz-server-deployment/docker/init/records.json /app/init/records.json

# Placeholder personas for the Validatopia profile (docs/validatopia/PLAN.md's Component G);
# start-servers.sh seeds /app/data/personas from this on first boot only, never overwriting an
# admin's own upload.
COPY multipaz-server-deployment/docker/init/personas/ /app/seed/personas/

# Copy startup script
COPY multipaz-server-deployment/docker/start-servers.sh /app/start-servers.sh
RUN chmod +x /app/start-servers.sh

# Create directories for data and logs
RUN mkdir -p /app/data /app/logs /etc/nginx/conf.d && chown -R multipaz:multipaz /app

# nginx needs to write to these directories
RUN mkdir -p /var/lib/nginx/body /var/log/nginx /run /run/nginx && \
    chown -R multipaz:multipaz /var/lib/nginx /var/log/nginx /run /run/nginx /etc/nginx /etc/multipaz

USER multipaz

# Expose unified port (nginx; 8443 when TLS_CERT/TLS_KEY are mounted for the validatopia
# profile) and individual service ports (used directly in MODE=direct).
EXPOSE 8000 8443 8004 8005 8006 8007 8008

ENTRYPOINT ["/app/start-servers.sh"]
