# TSA — Time Stamp Authority
### `tsa.thing.my.id`

A Spring Boot 3 / Java 21 implementation of an RFC 3161 Time Stamp Authority backed by BouncyCastle and signed by your internal smallstep CA.

---

## Project structure

```
tsa.thing.my.id/
├── config/                       ← Place PEM certificate & key here (gitignored)
│   ├── tsa-cert.pem
│   └── tsa-key.pem
├── src/
│   └── main/
│       ├── java/id/my/thing/tsa/
│       │   ├── TsaApplication.java
│       │   ├── config/TsaProperties.java
│       │   ├── controller/TsaController.java
│       │   ├── exception/GlobalExceptionHandler.java
│       │   └── service/TimeStampService.java
│       └── resources/
│           └── application.yml
├── CERTIFICATE-SETUP.md          ← How to issue the TSA cert from smallstep
└── pom.xml
```

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 21 |
| Maven | 3.9+ |
| smallstep CA | running at `192.168.1.2` |

---

## Setup

### 1. Get the TSA certificate

Follow all steps in **[CERTIFICATE-SETUP.md](./CERTIFICATE-SETUP.md)** to issue a certificate with the `timeStamping` EKU from your smallstep CA.

Place the resulting files:

```
config/tsa-cert.pem
config/tsa-key.pem
```

### 2. Build

```bash
./mvnw clean package -DskipTests
```

### 3. Run

```bash
java -jar target/tsa-1.0.0.jar
```

---

## Configuration

All settings can be overridden in `application.yml` or via environment variables / command-line args.

| Property | Default | Description |
|---|---|---|
| `tsa.certificate-path` | `config/tsa-cert.pem` | Path to TSA certificate (PEM) |
| `tsa.private-key-path` | `config/tsa-key.pem` | Path to TSA private key (PEM) |
| `tsa.policy-oid` | `1.3.6.1.4.1.13762.3` | TSA policy OID embedded in tokens |
| `tsa.include-certificate` | `true` | Embed signing cert in token |
| `server.port` | `8080` | HTTP listen port |

---

## HTTP Endpoints

| Method | Path | Content-Type | Description |
|--------|------|--------------|-------------|
| `POST` | `/` | `application/timestamp-query` | Issue timestamp token (RFC 3161) |
| `POST` | `/tsa` | `application/timestamp-query` | Alias for `/` |
| `GET` | `/` | `text/plain` | Service health/info response |
| `GET` | `/actuator/health` | `application/json` | Spring actuator health |

---

## Quick test (OpenSSL)

```bash
# 1. Create a timestamp request for a file
openssl ts -query -data myfile.txt -no_nonce -sha256 -out request.tsq

# 2. Send to the TSA
curl -s \
  -H "Content-Type: application/timestamp-query" \
  --data-binary @request.tsq \
  http://tsa.thing.my.id/ \
  -o response.tsr

# 3. Inspect the token
openssl ts -reply -in response.tsr -text

# 4. Verify the token
openssl ts -verify \
  -data myfile.txt \
  -in response.tsr \
  -CAfile config/tsa-cert.pem \
  -untrusted config/tsa-cert.pem
```

---

## Reverse Proxy (Nginx example)

```nginx
server {
    listen 443 ssl;
    server_name tsa.thing.my.id;

    ssl_certificate     /etc/ssl/tsa.thing.my.id.crt;
    ssl_certificate_key /etc/ssl/tsa.thing.my.id.key;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```
