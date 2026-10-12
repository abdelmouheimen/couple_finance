#!/bin/sh
# Creates /opt/couplefinance/.env from .env.example on first deploy and fills every empty secret.
# Existing values are never overwritten: rotating a secret is a deliberate manual edit.
set -eu
cd "$(dirname "$0")"

if [ ! -f .env ]; then
    cp .env.example .env
fi
chmod 600 .env

set_if_empty() {
    if grep -q "^$1=$" .env; then
        value=$2
        # '|' never occurs in the generated values (hex, base64, JSON without '|').
        sed -i "s|^$1=$|$1=$value|" .env
        echo "generated $1"
    fi
}

set_if_empty POSTGRES_PASSWORD "$(openssl rand -hex 24)"
set_if_empty MINIO_ROOT_PASSWORD "$(openssl rand -hex 24)"
set_if_empty MINIO_KMS_SECRET_KEY "couplefinance:$(openssl rand -base64 32)"
set_if_empty CURSOR_KEY "$(openssl rand -base64 32)"

# Private P-256 JWK signing the access tokens (security.md section 3).
if grep -q '^JWT_SIGNING_JWK=$' .env; then
    jwk=$(openssl ecparam -name prime256v1 -genkey -noout | openssl ec -outform DER 2>/dev/null | python3 -c '
import base64, sys, uuid
der = sys.stdin.buffer.read()
# SEC1 ECPrivateKey for a named P-256 curve: d at [7:39], uncompressed point 04|x|y at [56:121].
d, x, y = der[7:39], der[57:89], der[89:121]
assert len(der) == 121 and der[56] == 4, "unexpected key encoding"
b = lambda v: base64.urlsafe_b64encode(v).rstrip(b"=").decode()
print("{\"kty\":\"EC\",\"crv\":\"P-256\",\"alg\":\"ES256\",\"use\":\"sig\",\"kid\":\"%s\",\"x\":\"%s\",\"y\":\"%s\",\"d\":\"%s\"}"
      % (uuid.uuid4(), b(x), b(y), b(d)))')
    sed -i "s|^JWT_SIGNING_JWK=$|JWT_SIGNING_JWK=$jwk|" .env
    echo "generated JWT_SIGNING_JWK"
fi

# Never deploy with the placeholder domain: presigned URLs and mail identity would be wrong.
if grep -q 'example\.com' .env; then
    echo "Set the real domain in $(pwd)/.env (RECEIPT_PUBLIC_ENDPOINT, MAIL_*) before deploying." >&2
    exit 1
fi
