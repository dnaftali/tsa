# Certificate Setup Guide
# TSA — tsa.thing.my.id

This guide explains how to provision the TSA certificate using **OpenSSL** to generate the CSR (with the mandatory critical `timeStamping` EKU) and directly signing it with the smallstep CA key.

> **Why OpenSSL instead of `step ca certificate`?**  
> `step` CLI v0.25.2 does not support the `--template` flag needed to force a critical EKU.  
> The `step ca sign` command also ignores CSR-requested extensions by default (security feature).  
> The reliable solution is to sign directly with OpenSSL using the CA's cert and key.

---

## Prerequisites

- Root access to the CA server at `192.168.1.2`
- `openssl` installed on the CA server
- smallstep Root CA cert at `/root/.step/certs/root_ca.crt`
- smallstep Intermediate CA cert at `/root/.step/certs/intermediate_ca.crt`
- smallstep Intermediate CA key at `/root/.step/secrets/intermediate_ca_key`

To confirm the paths:
```bash
ls /root/.step/certs/
ls /root/.step/secrets/
```

---

## Step 1 — Create the OpenSSL Configuration File

The `critical,` prefix on `extendedKeyUsage` is **mandatory** per RFC 3161. BouncyCastle enforces this strictly.

```bash
cat > /root/tsa-openssl.cnf << 'EOF'
[ req ]
default_bits       = 2048
distinguished_name = req_distinguished_name
req_extensions     = v3_req
prompt             = no

[ req_distinguished_name ]
CN = tsa.thing.my.id

[ v3_req ]
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, timeStamping

[ v3_ca ]
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, timeStamping
EOF
```

---

## Step 2 — Generate the Private Key and CSR

```bash
openssl req -new -newkey rsa:2048 -nodes \
  -keyout /root/tsa-key.pem \
  -out /root/tsa.csr \
  -config /root/tsa-openssl.cnf \
  -extensions v3_req
```

Verify the CSR contains the extensions:
```bash
openssl req -in /root/tsa.csr -text -noout | grep -A4 "Extended Key"
```

---

## Step 3 — Sign the CSR with the Smallstep Intermediate CA

```bash
openssl x509 -req \
  -in /root/tsa.csr \
  -CA /root/.step/certs/intermediate_ca.crt \
  -CAkey /root/.step/secrets/intermediate_ca_key \
  -CAcreateserial \
  -days 365 \
  -extensions v3_ca \
  -extfile /root/tsa-openssl.cnf \
  -out /root/tsa-cert.pem
```

> If the intermediate CA key is password-protected, OpenSSL will prompt for it.

---

## Step 4 — Verify the Certificate

```bash
openssl x509 -in /root/tsa-cert.pem -text -noout | grep -A4 "Extended Key"
```

Expected output:
```
X509v3 Extended Key Usage: critical
    Time Stamping
```

Also verify the full chain:
```bash
openssl verify -CAfile /root/.step/certs/root_ca.crt \
  -untrusted /root/.step/certs/intermediate_ca.crt \
  /root/tsa-cert.pem
```

---

## Step 5 — Deploy Certificates to the TSA Application

Copy the certificate and key to the TSA application's `config/` directory:

```bash
cp /root/tsa-cert.pem /root/tsa-key.pem /opt/tsa/config/
chmod 600 /opt/tsa/config/tsa-key.pem
```

The application expects:
```
/opt/tsa/
└── config/
    ├── tsa-cert.pem     ← signed TSA certificate
    └── tsa-key.pem      ← TSA private key (keep secret!)
```

Or override the paths via `application.yml` or command-line:
```bash
java -jar tsa.jar \
  --tsa.certificate-path=/etc/tsa/tsa-cert.pem \
  --tsa.private-key-path=/etc/tsa/tsa-key.pem
```

---

## Step 6 — Run the Application

```bash
java -jar /opt/tsa/tsa.jar
```

Startup log should show:
```
INFO  TSA certificate subject : CN=tsa.thing.my.id
INFO  TSA private key loaded  : algorithm=RSA
```

---

## Step 7 — Test the TSA Endpoint

```bash
# Create a timestamp request
openssl ts -query -data /etc/hostname -no_nonce -sha256 -out /tmp/request.tsq

# Send to the TSA
curl -s \
  -H "Content-Type: application/timestamp-query" \
  --data-binary @/tmp/request.tsq \
  http://localhost:8080/ \
  -o /tmp/response.tsr

# Inspect the token
openssl ts -reply -in /tmp/response.tsr -text

# Full verification
openssl ts -verify \
  -data /etc/hostname \
  -in /tmp/response.tsr \
  -CAfile /root/.step/certs/root_ca.crt \
  -untrusted /root/tsa-cert.pem
```

---

## Certificate Renewal

Repeat Steps 2–5 approximately every 365 days (or before expiry).
Set file permissions after each renewal:
```bash
chmod 600 /opt/tsa/config/tsa-key.pem
```

---

## Security Notes

- **Never expose `tsa-key.pem`** — set `chmod 600` and restrict access.
- The intermediate CA key (`/root/.step/secrets/intermediate_ca_key`) should be kept secure and only accessed when renewing certificates.
- For production, consider using a Hardware Security Module (HSM) for the TSA private key.
