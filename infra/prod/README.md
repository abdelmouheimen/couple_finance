# Production deployment

Single-server production stack, deployed automatically on every push to `develop`
(`.github/workflows/deploy.yml`): the full CI runs first, then the backend image is built, copied to the server
over SSH and started by `deploy.sh`, which waits for the readiness probe and rolls back to the previous image when
it fails.

| Service    | Role                                                                     | Exposure                          |
|------------|--------------------------------------------------------------------------|-----------------------------------|
| `backend`  | Spring Boot API (`SPRING_PROFILES_ACTIVE=prod`)                          | `127.0.0.1:8091` + `edge` network |
| `postgres` | Database (volume `postgres-data`)                                        | internal only                     |
| `minio`    | Private receipt bucket, SSE with a KMS key; phones use presigned URLs    | `edge` network                    |
| `clamav`   | Upload malware scanning (uploads refused until ready)                    | internal only                     |
| `postfix`  | Outbound-only SMTP relay with DKIM signing (`SPRING_MAIL_HOST=postfix`)  | internal only, no published port  |

Everything lives in `/opt/couplefinance` on the server. Secrets exist only in `/opt/couplefinance/.env`
(mode 600), generated once by `init-env.sh` and never overwritten; nothing secret is in the repository or in
GitHub except the deploy SSH key.

## GitHub configuration

Repository → Settings → Environments → `production`, secrets:

| Secret               | Value                                                                  |
|----------------------|------------------------------------------------------------------------|
| `DEPLOY_HOST`        | server IP or host name                                                 |
| `DEPLOY_USER`        | `deploy` (non-root user in the `docker` group)                          |
| `DEPLOY_SSH_KEY`     | private ed25519 key whose public part is in `~deploy/.ssh/authorized_keys` |
| `DEPLOY_KNOWN_HOSTS` | output of `ssh-keyscan -t ed25519 <host>` (pins the server key)         |

## One-time server setup

```sh
adduser --disabled-password --gecos "" deploy && usermod -aG docker deploy
install -d -o deploy -g deploy /opt/couplefinance
# as deploy: add the public deploy key to ~/.ssh/authorized_keys
# then fill the public values (domain, mail identity) before the first deploy:
cp .env.example /opt/couplefinance/.env && chmod 600 /opt/couplefinance/.env   # edit RECEIPT_PUBLIC_ENDPOINT, MAIL_*
```

Reverse proxy (TLS): attach the proxy container to the `couplefinance-edge` network and route
`api.<domain>` → `backend:8080` and `files.<domain>` → `minio:9000` (the latter must preserve the `Host` header:
presigned URLs are signed for `RECEIPT_PUBLIC_ENDPOINT`).

## Email (DNS)

Mail is sent as `MAIL_FROM` through the `postfix` relay. For it to be delivered rather than marked as spam:

- **SPF**: `TXT <domain>` → `v=spf1 ip4:<server IP> -all`
- **DKIM**: `TXT couplefinance._domainkey.<domain>`, value printed by `docker compose logs postfix` at first start
  (key persisted in the `dkim-keys` volume)
- **DMARC**: `TXT _dmarc.<domain>` → `v=DMARC1; p=quarantine; rua=mailto:<admin address>`
- **PTR** (reverse DNS, set in the hosting provider's panel): server IP → `MAIL_HOSTNAME`

## Operations

```sh
cd /opt/couplefinance
docker compose --env-file .env ps
docker compose --env-file .env logs -f backend
# manual rollback: set IMAGE_TAG in .env to the previous tag (docker images couplefinance-backend), then
docker compose --env-file .env up -d
```
