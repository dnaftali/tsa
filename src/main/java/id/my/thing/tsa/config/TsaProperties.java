package id.my.thing.tsa.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the Time Stamp Authority.
 *
 * Set these in application.yml under the 'tsa' prefix:
 *
 * tsa:
 *   certificate-path: /path/to/tsa-cert.pem
 *   private-key-path: /path/to/tsa-key.pem
 *   policy-oid: 1.2.3.4
 */
@Data
@Component
@ConfigurationProperties(prefix = "tsa")
public class TsaProperties {

    /**
     * Path to the PEM-encoded TSA signing certificate (with timeStamping EKU).
     */
    private String certificatePath = "config/tsa-cert.pem";

    /**
     * Path to the PEM-encoded TSA private key (unencrypted).
     */
    private String privateKeyPath = "config/tsa-key.pem";

    /**
     * TSA Policy OID to embed in the issued timestamp tokens.
     * This can be any OID that identifies your organisation's TSA policy.
     * Default is RFC 3161's test policy OID.
     */
    private String policyOid = "1.3.6.1.4.1.13762.3";

    /**
     * Whether to include the TSA's signing certificate in the token.
     */
    private boolean includeCertificate = true;
}
