package id.my.thing.tsa;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.security.Security;

/**
 * Integration test for the TSA controller.
 *
 * NOTE: This test requires valid `config/tsa-cert.pem` and `config/tsa-key.pem`
 *       files to be present (or override via @TestPropertySource).
 *
 *       To run without real certificates, point to test fixtures in src/test/resources.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Override certificate paths to use test resources if available
        "tsa.certificate-path=config/tsa-cert.pem",
        "tsa.private-key-path=config/tsa-key.pem"
})
class TsaApplicationTests {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void contextLoads() {
        // Just verifies that the Spring context starts correctly
    }

    // Uncomment this test once certificates are in place.
    // @Test
    // void testTimestampEndpoint() throws Exception {
    //     TimeStampRequestGenerator gen = new TimeStampRequestGenerator();
    //     gen.setCertReq(true);
    //
    //     byte[] data = "Hello, TSA!".getBytes();
    //     MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
    //     byte[] hash = sha256.digest(data);
    //
    //     TimeStampRequest req = gen.generate(TSPAlgorithms.SHA256, hash, BigInteger.valueOf(1));
    //     byte[] rawRequest = req.getEncoded();
    //
    //     MvcResult result = mockMvc.perform(post("/")
    //                     .contentType("application/timestamp-query")
    //                     .content(rawRequest))
    //             .andExpect(status().isOk())
    //             .andReturn();
    //
    //     byte[] rawResponse = result.getResponse().getContentAsByteArray();
    //     TimeStampResponse resp = new TimeStampResponse(rawResponse);
    //     resp.validate(req);
    //
    //     assertNotNull(resp.getTimeStampToken());
    //     assertEquals(0, resp.getStatus());
    // }
}
