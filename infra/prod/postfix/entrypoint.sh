#!/bin/sh
# Configures Postfix as an outbound-only relay signing with DKIM, then runs it in the foreground.
#   MAIL_DOMAIN     sender domain (From: no-reply@MAIL_DOMAIN), signed with DKIM   (required)
#   MAIL_HOSTNAME   HELO name; should match the server's reverse DNS (PTR)          (default: MAIL_DOMAIN)
#   DKIM_SELECTOR   DKIM selector published in DNS as <selector>._domainkey          (default: couplefinance)
set -eu

: "${MAIL_DOMAIN:?MAIL_DOMAIN is required}"
MAIL_HOSTNAME="${MAIL_HOSTNAME:-$MAIL_DOMAIN}"
DKIM_SELECTOR="${DKIM_SELECTOR:-couplefinance}"
KEY_DIR=/etc/opendkim/keys/$MAIL_DOMAIN

echo "$MAIL_DOMAIN" > /etc/mailname

# DKIM key: generated once, persisted in the volume mounted on /etc/opendkim/keys.
if [ ! -f "$KEY_DIR/$DKIM_SELECTOR.private" ]; then
    mkdir -p "$KEY_DIR"
    opendkim-genkey -b 2048 -h rsa-sha256 -r -s "$DKIM_SELECTOR" -d "$MAIL_DOMAIN" -D "$KEY_DIR"
fi
chown -R opendkim:opendkim /etc/opendkim/keys
chmod 600 "$KEY_DIR/$DKIM_SELECTOR.private"
echo "DKIM DNS record to publish (TXT $DKIM_SELECTOR._domainkey.$MAIL_DOMAIN):"
cat "$KEY_DIR/$DKIM_SELECTOR.txt"

# Clients allowed to relay (same ranges as mynetworks) are the ones whose mail is signed; opendkim's default is
# 127.0.0.1 only, which would leave the backend's mail (other container) unsigned.
printf '127.0.0.1\n10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n' > /etc/opendkim/internal-hosts

cat > /etc/opendkim.conf <<EOF
Syslog                  no
UMask                   007
Mode                    s
Canonicalization        relaxed/simple
Domain                  $MAIL_DOMAIN
Selector                $DKIM_SELECTOR
KeyFile                 $KEY_DIR/$DKIM_SELECTOR.private
InternalHosts           /etc/opendkim/internal-hosts
Socket                  inet:8891@127.0.0.1
UserID                  opendkim
PidFile                 /run/opendkim/opendkim.pid
OversignHeaders         From
EOF
mkdir -p /run/opendkim && chown opendkim:opendkim /run/opendkim

postconf -e \
    "myhostname = $MAIL_HOSTNAME" \
    "mydomain = $MAIL_DOMAIN" \
    "myorigin = \$mydomain" \
    "inet_interfaces = all" \
    "inet_protocols = ipv4" \
    "mydestination =" \
    "local_recipient_maps =" \
    "relay_domains =" \
    "mynetworks = 127.0.0.0/8 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16" \
    "smtpd_relay_restrictions = permit_mynetworks, reject" \
    "smtpd_recipient_restrictions = permit_mynetworks, reject" \
    "smtpd_banner = \$myhostname ESMTP" \
    "smtp_tls_security_level = may" \
    "smtp_tls_CAfile = /etc/ssl/certs/ca-certificates.crt" \
    "smtp_tls_loglevel = 1" \
    "message_size_limit = 1048576" \
    "milter_default_action = tempfail" \
    "milter_protocol = 6" \
    "smtpd_milters = inet:127.0.0.1:8891" \
    "non_smtpd_milters = inet:127.0.0.1:8891" \
    "maillog_file = /dev/stdout"
# No chroot (the Debian init script that would populate it is not used in a container).
postconf -F '*/*/chroot = n'
postconf -M 'postlog/unix-dgram=postlog unix-dgram n - n - 1 postlogd'

cp /etc/resolv.conf /var/spool/postfix/etc/resolv.conf 2>/dev/null || true
opendkim -x /etc/opendkim.conf
exec postfix start-fg
