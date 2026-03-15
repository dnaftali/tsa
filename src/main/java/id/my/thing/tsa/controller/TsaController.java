package id.my.thing.tsa.controller;

import id.my.thing.tsa.service.TimeStampService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP endpoint for the RFC 3161 Time Stamp Authority.
 *
 * Clients POST a DER-encoded TimeStampReq with Content-Type
 * {@code application/timestamp-query} and receive a DER-encoded
 * TimeStampResp with Content-Type {@code application/timestamp-reply}.
 *
 * Standard path  : POST /
 * Alternative    : POST /tsa
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class TsaController {

    /** MIME types defined by RFC 3161 */
    private static final String MIME_TIMESTAMP_QUERY = "application/timestamp-query";
    private static final String MIME_TIMESTAMP_REPLY = "application/timestamp-reply";

    private final TimeStampService timeStampService;

    // ------------------------------------------------------------------
    // Primary endpoint — POST /
    // ------------------------------------------------------------------
    @PostMapping(
            value = "/",
            consumes = {MIME_TIMESTAMP_QUERY, MediaType.APPLICATION_OCTET_STREAM_VALUE},
            produces = MIME_TIMESTAMP_REPLY
    )
    public ResponseEntity<byte[]> timestamp(@RequestBody byte[] request) {
        return processRequest(request);
    }

    // ------------------------------------------------------------------
    // Alias endpoint — POST /tsa  (convenient for reverse-proxy setups)
    // ------------------------------------------------------------------
    @PostMapping(
            value = "/tsa",
            consumes = {MIME_TIMESTAMP_QUERY, MediaType.APPLICATION_OCTET_STREAM_VALUE},
            produces = MIME_TIMESTAMP_REPLY
    )
    public ResponseEntity<byte[]> timestampAlias(@RequestBody byte[] request) {
        return processRequest(request);
    }

    // ------------------------------------------------------------------
    // Health / info  — GET /
    // ------------------------------------------------------------------
    @GetMapping(value = "/", produces = MediaType.TEXT_PLAIN_VALUE)
    public String info() {
        return "TSA tsa.thing.my.id is running. POST a DER-encoded TimeStampReq to /";
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------
    private ResponseEntity<byte[]> processRequest(byte[] request) {
        if (request == null || request.length == 0) {
            log.warn("Received empty TSQ payload");
            return ResponseEntity.badRequest().build();
        }
        try {
            byte[] response = timeStampService.generateTimeStampResponse(request);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(MIME_TIMESTAMP_REPLY))
                    .body(response);
        } catch (Exception e) {
            log.error("Failed to generate TimeStampResponse", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
