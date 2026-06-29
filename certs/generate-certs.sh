#!/usr/bin/env bash
# =============================================================================
# Certificate Generation Script — SmartHome IoT Platform
# =============================================================================
# Generates a self-signed CA, RabbitMQ server cert, and per-service PKCS12
# keystores for mTLS between all microservices (Phase 3.7).
#
# Usage:  cd certs && bash generate-certs.sh
# Output: ca.pem, ca-key.pem, ca-truststore.p12, server.pem, server-key.pem,
#         plus <service-name>.p12 for each microservice.
# =============================================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

KEYSTORE_PASSWORD="changeit"
CERT_DAYS=3650
RSA_BITS=4096

# --- Self-signed CA ---
echo "==> Generating CA certificate..."
openssl req -x509 -newkey rsa:${RSA_BITS} -days ${CERT_DAYS} -nodes \
  -keyout ca-key.pem -out ca.pem \
  -subj "/CN=SmartHome Dev CA"

# --- Server certificate for RabbitMQ ---
echo "==> Generating RabbitMQ server certificate..."
openssl req -newkey rsa:${RSA_BITS} -nodes \
  -keyout server-key.pem -out server.csr \
  -subj "/CN=rabbitmq"

cat > server-ext.cnf <<EOF
subjectAltName=DNS:rabbitmq,DNS:localhost,IP:127.0.0.1
EOF

openssl x509 -req -in server.csr -CA ca.pem -CAkey ca-key.pem \
  -CAcreateserial -out server.pem -days ${CERT_DAYS} \
  -extfile server-ext.cnf

# --- PKCS12 truststore (CA only, for Spring Boot clients) ---
echo "==> Generating CA truststore (PKCS12)..."
openssl pkcs12 -export -nokeys -in ca.pem \
  -out ca-truststore.p12 -password pass:${KEYSTORE_PASSWORD} -name ca

# =============================================================================
# Per-service PKCS12 keystores for mTLS (Phase 3.7)
# =============================================================================
# Each service gets its own certificate signed by the CA. The PKCS12 keystore
# bundles the service cert + private key and is used for:
#   1. server.ssl.key-store  — so the service listens on HTTPS
#   2. SSL bundle keystore   — so outbound RestClient/WebClient presents the
#                              client cert during the TLS handshake (mutual TLS)
# =============================================================================

# All services that need a certificate (Docker service name = CN + SAN)
SERVICES=(
  "gateway-service"
  "processing-service"
  "device-registry-service"
  "history-service"
  "notification-service"
  "sensor-simulator-service"
  "iam-service"
  "api-gateway"
)

generate_service_cert() {
  local svc="$1"
  echo "==> Generating certificate for ${svc}..."

  # Generate key + CSR
  openssl req -newkey rsa:${RSA_BITS} -nodes \
    -keyout "${svc}-key.pem" -out "${svc}.csr" \
    -subj "/CN=${svc}"

  # SAN extension: Docker service name + localhost + loopback
  cat > "${svc}-ext.cnf" <<EOF
subjectAltName=DNS:${svc},DNS:localhost,IP:127.0.0.1
extendedKeyUsage=serverAuth,clientAuth
EOF

  # Sign with CA
  openssl x509 -req -in "${svc}.csr" -CA ca.pem -CAkey ca-key.pem \
    -CAcreateserial -out "${svc}.pem" -days ${CERT_DAYS} \
    -extfile "${svc}-ext.cnf"

  # Bundle cert + key into PKCS12 keystore
  openssl pkcs12 -export \
    -in "${svc}.pem" -inkey "${svc}-key.pem" \
    -certfile ca.pem \
    -out "${svc}.p12" -password pass:${KEYSTORE_PASSWORD} \
    -name "${svc}"

  # Cleanup intermediate files
  rm -f "${svc}.csr" "${svc}-ext.cnf" "${svc}-key.pem" "${svc}.pem"
}

for svc in "${SERVICES[@]}"; do
  generate_service_cert "${svc}"
done

# --- Cleanup remaining temp files ---
rm -f server.csr server-ext.cnf ca.srl

echo ""
echo "=== Certificates generated in ${SCRIPT_DIR} ==="
echo "  CA:         ca.pem, ca-key.pem"
echo "  Truststore: ca-truststore.p12 (password: ${KEYSTORE_PASSWORD})"
echo "  RabbitMQ:   server.pem, server-key.pem"
echo "  Services:   ${SERVICES[*]/%/.p12}"
echo ""
