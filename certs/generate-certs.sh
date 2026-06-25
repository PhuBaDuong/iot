#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# --- Self-signed CA ---
openssl req -x509 -newkey rsa:4096 -days 3650 -nodes \
  -keyout ca-key.pem -out ca.pem \
  -subj "/CN=SmartHome Dev CA"

# --- Server certificate for RabbitMQ ---
openssl req -newkey rsa:4096 -nodes \
  -keyout server-key.pem -out server.csr \
  -subj "/CN=rabbitmq"

cat > server-ext.cnf <<EOF
subjectAltName=DNS:rabbitmq,DNS:localhost,IP:127.0.0.1
EOF

openssl x509 -req -in server.csr -CA ca.pem -CAkey ca-key.pem \
  -CAcreateserial -out server.pem -days 3650 \
  -extfile server-ext.cnf

# --- PKCS12 truststore (CA only, for Spring Boot clients) ---
openssl pkcs12 -export -nokeys -in ca.pem \
  -out ca-truststore.p12 -password pass:changeit -name ca

# --- Cleanup temp files ---
rm -f server.csr server-ext.cnf ca.srl
echo "Certificates generated in $SCRIPT_DIR"
