package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.s3.model.S3KmsEnvelope;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3KmsEncryptionCallerTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void delegatedCheckUsesFormCallerAndRestoresBucketAccount(boolean denied) {
        RequestContext request = new RequestContext();
        request.setAccountId("111111111111");
        request.setAccessKeyId("form-key");
        request.setSessionToken("form-token");
        IamService iam = mock(IamService.class);
        when(iam.resolveAccountId("form-key")).thenReturn(Optional.of("222222222222"));
        KmsService kms = mock(KmsService.class);
        IamEnforcementFilter authorization = mock(IamEnforcementFilter.class);
        String arn = "arn:aws:kms:us-east-1:111111111111:key/test";
        S3KmsEnvelope envelope = new S3KmsEnvelope(1, arn, new byte[] {1}, Map.of(), new byte[8]);
        doAnswer(call -> {
            assertEquals("222222222222", request.getAccountId());
            assertEquals("form-key", request.getAccessKeyId());
            assertEquals("form-token", request.getSessionToken());
            Map<String, List<String>> context = call.getArgument(2);
            assertEquals(List.of("222222222222"), context.get("kms:CallerAccount"));
            if (denied) {
                throw new AwsException("AccessDenied", "denied", 403);
            }
            return null;
        }).when(authorization).authorizeDelegatedResource(eq("kms:Decrypt"), eq(arn), any());
        when(kms.decryptAndResolveKey(any(), any(), eq("us-east-1"), eq(arn)))
            .thenAnswer(call -> {
                assertEquals("111111111111", request.getAccountId());
                return new KmsService.DecryptResult(new byte[32], arn, "SYMMETRIC_DEFAULT");
            });
        S3KmsEncryption encryption = new S3KmsEncryption(kms, authorization, request,
            new RegionResolver("us-east-1", "111111111111"), iam);
        if (denied) {
            assertThrows(AwsException.class, () -> encryption.decrypt(envelope));
        } else {
            try (S3KmsEncryption.Material material = encryption.decrypt(envelope)) {
                assertEquals(32, material.plaintextKey().length);
            }
        }
        assertEquals("111111111111", request.getAccountId());
        assertEquals("form-key", request.getAccessKeyId());
        assertEquals("form-token", request.getSessionToken());
    }
}
