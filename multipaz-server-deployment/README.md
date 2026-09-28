# Multipaz Server Bundle Deployment

This directory contains everything needed to build and run all Multipaz reference servers and the web frontend as a single container image.

## What's Included

The container bundles:
- **Web Frontend** (`multipaz-server-frontend`) - Kotlin/JS React application at `/`
- **Verifier Server** - at `/verifier/` (port 8006)
- **OpenID4VCI Server** (Issuer) - at `/openid4vci/` (port 8007)
- **Records Server** (System of Record) - at `/records/` (port 8004)
- **CSA Server** (Credential Security Agent) - at `/csa/` (port 8005)
- **Backend Server** - at `/backend/` (port 8008)
- **nginx** - reverse proxy routing all services through port 8000

## Prerequisites

- Java 17+ (for building)
- Podman or Docker (for running containers)

## Installing Podman

Podman is recommended over Docker because it's free and does not require
a license for commercial use.

### macOS

```bash
# Install Podman
brew install podman

# Initialize the Podman virtual machine (one-time setup)
# Podman runs Linux containers in a VM on macOS
podman machine init

# Start the VM
podman machine start
```

After this setup, Podman is ready to use. The VM persists across reboots, but you need to
run `podman machine start` after restarting your Mac.

**Troubleshooting:**
- If you get errors about the machine not running: `podman machine start`
- If you get permission errors: `podman machine stop && podman machine start`
- To completely reset: `podman machine rm` then `podman machine init` again

### Linux

On Linux, Podman runs natively without a VM:

```bash
# Ubuntu/Debian
sudo apt-get update && sudo apt-get install -y podman

# Fedora
sudo dnf install -y podman
```

No additional setup is required.

## Building

### Build for your machine's architecture (fastest)

```bash
./gradlew :multipaz-server-deployment:buildDockerImage
```

### Build for a specific architecture

```bash
# For x86_64 servers (e.g., most cloud VMs)
./gradlew :multipaz-server-deployment:buildDockerImageAmd64

# For ARM64 servers (e.g., AWS Graviton, Apple Silicon)
./gradlew :multipaz-server-deployment:buildDockerImageArm64
```

Note: Building for a non-native architecture uses emulation and is slower.

### What gets built

The build creates a container image stored locally. View your images with:

```bash
podman images
```

You'll see entries like:
```
REPOSITORY                  TAG           IMAGE ID      SIZE
multipaz/server-bundle      latest        abc123...     500 MB
multipaz/server-bundle      0.97.0-pre.1  abc123...     500 MB
```

## Running Locally

```bash
podman run --rm -p 8000:8000 multipaz/server-bundle:latest
```

Then open http://localhost:8000 in your browser.

To stop: press `Ctrl+C`

### Shell in the container

```bash
# First, find the container ID
podman ps

# Then exec into it
podman exec -it <container_id> /bin/bash
```

### Accessing individual services

All services are available through the nginx proxy on port 8000:

| Service             | URL                               |
|---------------------|-----------------------------------|
| Web Frontend        | http://localhost:8000/            |
| Verifier            | http://localhost:8000/verifier/   |
| OpenID4VCI (Issuer) | http://localhost:8000/openid4vci/ |
| Records             | http://localhost:8000/records/    |
| CSA                 | http://localhost:8000/csa/        |
| Backend             | http://localhost:8000/backend/    |

## Deploying to a Server

### Step 1: Export the image to a file

```bash
# For amd64 (most Linux servers)
podman save -o multipaz-server-bundle-amd64.tar multipaz/server-bundle:latest-amd64

# Or for arm64
podman save -o multipaz-server-bundle-arm64.tar multipaz/server-bundle:latest-arm64
```

This creates a `.tar` file (typically 700+ MB) containing the complete image.

### Step 2: Copy to the server

```bash
scp multipaz-server-bundle-amd64.tar user@yourserver:/path/to/destination/
```

### Step 3: Load and run on the server

On the server:

```bash
# Load the image
podman load -i multipaz-server-bundle-amd64.tar

# Run it
podman run -d --rm \
    -p 127.0.0.1:8000:8000 \
    -e BASE_URL=https://your-domain.com \
    -e ADMIN_PASS=<password> \
    multipaz/server-bundle:latest-amd64
```

The `-d` flag runs it in the background. Remove `--rm` if you want the container to persist after
stopping.

Use option `-v </your/db/folder>:/app/data:z` to mount the folder where databases are stored 
to a folder on your host machine. This way data will not be erased between the container runs.
Similarly `-v </your/logs/folder>:/app/logs:z` will ensure that log files are preserved.

Note: this will deploy the bundle as HTTP service on port 8000, you would still need to use your
environment to expose it as HTTP service. Also, if not running on the root of the domain (e.g.
your BASE_URL is `https://foo.com/bar` rather than `https://foo.bar`), handlers for `/.well-known` 
urls have to be mapped correctly, e.g. for ngnix:
```
location = /.well-known/oauth-authorization-server/bar/openid4vci {
    proxy_pass http://localhost:8000/.well-known/oauth-authorization-server/openid4vci;
}

location = /.well-known/openid-credential-issuer/bar/openid4vci {
    proxy_pass http://localhost:8000/.well-known/openid-credential-issuer/openid4vci;
}
```

Make sure that your domain is resolved to a correct address and not loopback. Specifically,
from withing the container in the example above, `https://foo.com` must be reachable. Some
hosting services map `foo.com` to `127.0.0.1` on the host itself, such mapping will cause problems
and must be removed at very least in the container environment.

## Configuration

### Environment Variables

| Variable | Default                 | Description                                                                     |
|----------|-------------------------|---------------------------------------------------------------------------------|
| `BASE_URL` | `http://localhost:8000` | Base URL for all services (used in protocol messages)                           |
| `MODE` | `proxy`                 | `proxy` for nginx routing, `direct` for port-only access to individual services |
| `ADMIN_PASS` | `multipaz`        | default is only used for localhost deployment, otherwise it must be specified

## Validatopia Profile

`PROFILE=validatopia` runs only the OpenID4VCI (issuer + admin site) and Backend
(device-attestation) services behind a hardened nginx config, instead of the full bundle above
(see `docs/validatopia/PLAN.md`). Admin accounts use Argon2id-hashed passwords and mandatory TOTP,
not `ADMIN_PASS`.

```bash
podman run -d --rm \
    -p 127.0.0.1:8000:8000 \
    -e PROFILE=validatopia \
    -e BASE_URL=https://validatopia.your-domain.com \
    -e ADMIN_BOOTSTRAP_USER=admin \
    -e ADMIN_BOOTSTRAP_PASS=<a strong password> \
    -v /your/data/folder:/app/data:z \
    -v /your/logs/folder:/app/logs:z \
    multipaz/server-bundle:latest
```

The first admin account is bootstrapped from `ADMIN_BOOTSTRAP_USER`/`ADMIN_BOOTSTRAP_PASS` and
must complete TOTP enrollment on its first login (see the admin site's login page). The container
**refuses to start** if `ADMIN_BOOTSTRAP_PASS` is empty and `BASE_URL` isn't a loopback address.

### Environment variables specific to this profile

| Variable | Default | Description |
|----------|---------|--------------|
| `ADMIN_BOOTSTRAP_USER` | `admin` | Username for the first admin account, created on first boot if no accounts exist yet. |
| `ADMIN_BOOTSTRAP_PASS` | *(none)* | Password for that account. Required unless `BASE_URL` is a loopback address. |
| `ADMIN_ALLOW_CIDR` | *(none, unrestricted)* | Comma-separated IPv4/IPv6 CIDR blocks allowed to reach `/admin_*` endpoints. |
| `IDV_DEMO_MODE` | `false` | Passed through to the server as `idv_demo_mode`; informational for now (the admin-editable settings, e.g. "accept untrusted CSCA", are the actual runtime controls — see the Settings admin page). |
| `TLS_CERT` / `TLS_KEY` | *(none)* | Paths (inside the container, so mount them via `-v`) to a certificate/key pair for nginx to terminate TLS on port 8443 directly. **Not needed, and not the expected setup, if you front this container with your own reverse proxy** (e.g. Caddy, nginx, an ALB) that already terminates TLS — which is the normal case for `BASE_URL` being `https://...` while the container itself only speaks plain HTTP on port 8000. In that setup, bind the container's port to `127.0.0.1` (as in the example above) and point your reverse proxy at it. nginx trusts `X-Forwarded-For`/`X-Forwarded-Proto` from private-network peers only (see `nginx-validatopia-locations.conf`), so rate limiting and `ADMIN_ALLOW_CIDR` see the real client address rather than your reverse proxy's. |

### Placeholder personas

The image ships two placeholder test personas (fake identities, not real people) so the container
is demoable immediately; an admin's own `personas.json` upload (via the Personas admin page)
always takes precedence and is never overwritten by this.

### Container smoke test

```bash
./gradlew :multipaz-server-deployment:buildDockerImage
./multipaz-server-deployment/validatopia-smoke.sh
```

