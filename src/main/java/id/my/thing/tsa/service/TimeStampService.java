package id.my.thing.tsa.service;

import id.my.thing.tsa.config.TsaProperties;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.*;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Time Stamp Authority Service (RFC 3161).
 *
 * Parses incoming binary TimeStampReq, generates a signed
 * TimeStampToken backed by the configured TSA certificate and
 * ECDSA private key, and returns the encoded TimeStampResp.
 */
@Slf4j
@Service
public class TimeStampService {

    private final TsaProperties properties;

    /** Monotonically-increasing serial for issued tokens. */
    private final AtomicLong serialCounter = new AtomicLong(System.currentTimeMillis());


    private X509Certificate certificate;
    private PrivateKey privateKey;

    public TimeStampService(TsaProperties properties) throws Exception {
        this.properties = properties;
        loadCredentials();
    }

    // -----------------------------------------------------------------------
    // Credential loading
    // -----------------------------------------------------------------------

    private void loadCredentials() throws Exception {
        log.info("Loading TSA certificate  : {}", properties.getCertificatePath());
        log.info("Loading TSA private key  : {}", properties.getPrivateKeyPath());

        // Certificate
        try (PEMParser p = new PEMParser(new FileReader(properties.getCertificatePath()))) {
            Object obj = p.readObject();
            if (!(obj instanceof X509CertificateHolder holder)) {
                throw new IllegalStateException(
                        "Certificate file must contain a PEM X.509 certificate. Got: " +
                                (obj == null ? "null" : obj.getClass().getName()));
            }
            this.certificate = new JcaX509CertificateConverter()
                    .setProvider("BC")
                    .getCertificate(holder);
            log.info("TSA certificate subject : {}", holder.getSubject());
        }

        // Private key (supports both PKCS#8 and traditional PEM formats)
        try (PEMParser p = new PEMParser(new FileReader(properties.getPrivateKeyPath()))) {
            Object obj = p.readObject();
            JcaPEMKeyConverter conv = new JcaPEMKeyConverter().setProvider("BC");
            PrivateKey rawKey;
            if (obj instanceof PEMKeyPair kp) {
                rawKey = conv.getKeyPair(kp).getPrivate();
            } else if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo pki) {
                rawKey = conv.getPrivateKey(pki);
            } else {
                throw new IllegalStateException(
                        "Private key file must contain a PEM private key. Got: " +
                                (obj == null ? "null" : obj.getClass().getName()));
            }
            // Re-derive through BC's own KeyFactory so the signing engine gets a
            // BC-native ECPrivateKey it can identify (avoids "can't identify EC private key")
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance(rawKey.getAlgorithm(), "BC");
            this.privateKey = kf.generatePrivate(
                    new java.security.spec.PKCS8EncodedKeySpec(rawKey.getEncoded()));
            log.info("TSA private key loaded  : algorithm={}", privateKey.getAlgorithm());
        }
    }

    // -----------------------------------------------------------------------
    // Token generation
    // -----------------------------------------------------------------------

    /**
     * Processes a DER-encoded {@code TimeStampReq} and returns a
     * DER-encoded {@code TimeStampResp} (RFC 3161).
     *
     * @param rawRequest the raw binary TSQ from the HTTP request body
     * @return the raw binary TSR to write into the HTTP response body
     */
    public byte[] generateTimeStampResponse(byte[] rawRequest) throws Exception {
        // Parse request
        TimeStampRequest tsq = new TimeStampRequest(rawRequest);

        ASN1ObjectIdentifier digestAlgOid = tsq.getMessageImprintAlgOID();
        String signingAlgorithm = resolveSigningAlgorithm(digestAlgOid, privateKey);
        log.debug("Processing TSQ: digestAlg={}, signingAlg={}", digestAlgOid, signingAlgorithm);

        // Build signer
        SignerInfoGenerator signerInfoGen = new JcaSimpleSignerInfoGeneratorBuilder()
                .setProvider("BC")
                .build(signingAlgorithm, privateKey, certificate);

        // Build digest calculator for the cert hash (ESSCertIDv2 requires SHA-256)
        DigestCalculatorProvider digestCalcProvider = new JcaDigestCalculatorProviderBuilder()
                .setProvider("BC")
                .build();
        DigestCalculator digestCalculator = digestCalcProvider
                .get(new org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder()
                        .find("SHA-256"));

        // Build token generator
        TimeStampTokenGenerator tokenGen = new TimeStampTokenGenerator(
                signerInfoGen,
                digestCalculator,
                new ASN1ObjectIdentifier(properties.getPolicyOid())
        );

        if (properties.isIncludeCertificate()) {
            tokenGen.addCertificates(new JcaCertStore(Collections.singletonList(certificate)));
        }

        // Build response generator
        TimeStampResponseGenerator responseGen = new TimeStampResponseGenerator(
                tokenGen,
                TSPAlgorithms.ALLOWED
        );

        BigInteger serial = BigInteger.valueOf(serialCounter.incrementAndGet());
        Date genTime = Date.from(Instant.now());

        TimeStampResponse response = responseGen.generate(tsq, serial, genTime);
        log.info("TimeStampToken issued: serial={}", serial);

        return response.getEncoded();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Maps a digest algorithm OID + private key type to a JCA signature algorithm name.
     * Supports both RSA and EC keys so the correct algorithm is used regardless of
     * which key type the certificate was issued with.
     */
    private String resolveSigningAlgorithm(ASN1ObjectIdentifier digestAlgOid, PrivateKey key) {
        String keyAlg = key.getAlgorithm().toUpperCase(); // "RSA" or "EC"
        String suffix = keyAlg.equals("RSA") ? "RSA" : "ECDSA";

        if (digestAlgOid.equals(NISTObjectIdentifiers.id_sha256))  return "SHA256with" + suffix;
        if (digestAlgOid.equals(NISTObjectIdentifiers.id_sha384))  return "SHA384with" + suffix;
        if (digestAlgOid.equals(NISTObjectIdentifiers.id_sha512))  return "SHA512with" + suffix;
        if (digestAlgOid.equals(OIWObjectIdentifiers.idSHA1))      return "SHA1with"   + suffix;

        log.warn("Unknown digest OID {} – defaulting to SHA256with{}", digestAlgOid, suffix);
        return "SHA256with" + suffix;
    }
}
