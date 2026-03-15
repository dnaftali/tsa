# Certificate Setup Guide
# TSA — tsa.thing.my.id

This document describes how to provision the TSA certificate from your **smallstep CA** at `192.168.1.2`.

---

## Prerequisites

- `step` CLI installed on the server that will run the TSA application.
- Access to the smallstep CA at `192.168.1.2` (SSH or network).
- Root CA certificate already trusted (see below for bootstrap).

---

## Step 1 — Bootstrap the step CLI (if not already done)

Run once on the machine where you'll issue the certificate (or on the CA server itself):

```bash
# Bootstrap trust for the CA at 192.168.1.2
# You will be asked to confirm the root CA fingerprint.
step ca bootstrap \
  --ca-url https://192.168.1.2 \
  --install \
  --fingerprint 30821e25ea710f8f3666970f50c93b8a59432eb831a3b7b8871132e5b685b285
```

To find the root CA fingerprint, run this **on the CA server** (`192.168.1.2`):

```bash
root@armbian:~# step certificate fingerprint /root/.step/certs/root_ca.crt
```

---

## Step 2 — Create a provisioner template for timeStamping EKU (once, on CA server)

By default, smallstep certificates don't include the `timeStamping` EKU. You need to create a custom X.509 template.

**On the CA server (`root@192.168.1.2`), create the template file:**

```bash
cat > /root/tsa-template.json << 'EOF'
{
  "subject": {{ toJson .Subject }},
  "sans": {{ toJson .SANs }},
  "keyUsage": ["digitalSignature"],
  "extKeyUsage": ["timeStamping"],
  "basicConstraints": {
    "isCA": false
  }
}
EOF
```

---

## Step 3 — Issue the TSA certificate

### Option A: Issue from the CA server directly (simplest)

Run **on the CA server** (`root@192.168.1.2`):

```bash
step ca certificate "tsa.thing.my.id" \
  /root/tsa-cert.pem \
  /root/tsa-key.pem \
  --kty EC \
  --curve P-256 \
  --san tsa.thing.my.id \
  --template /root/tsa-template.json \
  --not-after 87600h \
  --provisioner <YOUR_PROVISIONER_NAME>
```

> **Tip**: To list available provisioners, run `step ca provisioner list`.  
> For JWK provisioner you will be prompted for a password; for ACME you need to satisfy the DNS challenge.

### Option B: Request from the TSA application machine (remote)

```bash
step ca certificate "tsa.thing.my.id" \
  config/tsa-cert.pem \
  config/tsa-key.pem \
  --kty EC \
  --curve P-256 \
  --san tsa.thing.my.id \
  --ca-url https://192.168.1.2 \
  --root <PATH_TO_ROOT_CA_CRT> \
  --template '<TEMPLATE_JSON_INLINE_OR_FILE>' \
  --not-after 87600h
```

---

## Step 4 — Verify the issued certificate

```bash
step certificate inspect config/tsa-cert.pem | grep -A5 "Extended Key Usage"
```

You should see:
```
X509v3 Extended Key Usage:
    Time Stamping
```

---

## Step 5 — Place certificates for the application

Copy the two PEM files to the `config/` subdirectory of the application:

```
tsa.thing.my.id/
└── config/
    ├── tsa-cert.pem     ← PEM X.509 TSA certificate (from step 3)
    └── tsa-key.pem      ← PEM private key (from step 3)
```

Or override the paths in `application.yml`:

```yaml
tsa:
  certificate-path: /etc/tsa/tsa-cert.pem
  private-key-path:  /etc/tsa/tsa-key.pem
```

---

## Step 6 — Run the application

```bash
# Build
./mvnw clean package -DskipTests

# Run
java -jar target/tsa-1.0.0.jar
```

Or with a specific config directory:

```bash
java -jar target/tsa-1.0.0.jar \
  --tsa.certificate-path=/etc/tsa/tsa-cert.pem \
  --tsa.private-key-path=/etc/tsa/tsa-key.pem
```

---

## Step 7 — Test the TSA endpoint

```bash
# Create a test TSQ (requires openssl ≥ 1.1 or openssl 3)
openssl ts -query -data /etc/hostname -no_nonce -sha256 -out /tmp/request.tsq

# Send to the TSA
curl -s \
  -H "Content-Type: application/timestamp-query" \
  --data-binary @/tmp/request.tsq \
  http://localhost:8080/ \
  -o /tmp/response.tsr

# Verify the response
openssl ts -reply -in /tmp/response.tsr -text

# Full verification (requires the CA root cert and TSA cert)
openssl ts -verify \
  -data /etc/hostname \
  -in /tmp/response.tsr \
  -CAfile config/tsa-cert.pem \
  -untrusted config/tsa-cert.pem
```

---

## Certificate Renewal

The `step` CA supports automatic renewal. To set up a renewal cron job:

```bash
# Add to crontab — renew 72 hours before expiry
0 */12 * * * step ca renew --force config/tsa-cert.pem config/tsa-key.pem \
  && systemctl restart tsa
```

---

## Security Notes

- **Keep `tsa-key.pem` private.** Set file permissions: `chmod 600 config/tsa-key.pem`
- Consider using a PKCS#12 keystore or a hardware security module (HSM) for production deployments.
- The TSA certificate should have a relatively short validity (e.g., 1–2 years) and be renewed regularly.
