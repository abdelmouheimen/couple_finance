# Outbound-only SMTP relay with DKIM signing. Reachable only from the stack's internal network (no published port):
# it never receives mail from the Internet and relays only for private networks.
FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends postfix opendkim opendkim-tools ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
EXPOSE 25
ENTRYPOINT ["/entrypoint.sh"]
