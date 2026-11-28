package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestSignatureProvenanceTest {
    @Test
    void accountAndAccessKeyDoNotEstablishAuthentication() {
        RequestContext request = new RequestContext();
        request.setAccountId("123456789012");
        request.setAccessKeyId("selected");
        assertFalse(request.isSignatureVerifiedFor("selected"));
        request.recordVerifiedSignature("selected");
        assertTrue(request.isSignatureVerifiedFor("selected"));
        assertFalse(request.isSignatureVerifiedFor("foreign"));
        assertFalse(request.isSignatureVerifiedFor(null));
    }
}
