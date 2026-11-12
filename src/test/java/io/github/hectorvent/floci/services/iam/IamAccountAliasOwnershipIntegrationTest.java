package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class IamAccountAliasOwnershipIntegrationTest {
    private static final String FIRST = "841304211001";
    private static final String SECOND = "841304211002";

    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void signedSdkCallsPreserveOwnershipThroughCollisionReplacementAndDeletion(String region) {
        try (IamClient first = client(FIRST, region); IamClient second = client(SECOND, region)) {
            try {
                first.createAccountAlias(request -> request.accountAlias("alias-sdk-first"));
                second.createAccountAlias(request -> request.accountAlias("alias-sdk-second"));
                assertThrows(EntityAlreadyExistsException.class, () ->
                        second.createAccountAlias(request -> request.accountAlias("alias-sdk-first")));
                assertEquals(List.of("alias-sdk-first"), first.listAccountAliases().accountAliases());
                assertEquals(List.of("alias-sdk-second"), second.listAccountAliases().accountAliases());

                assertThrows(NoSuchEntityException.class, () ->
                        second.deleteAccountAlias(request -> request.accountAlias("alias-sdk-first")));
                first.createAccountAlias(request -> request.accountAlias("alias-sdk-replacement"));
                second.createAccountAlias(request -> request.accountAlias("alias-sdk-first"));
                assertEquals(List.of("alias-sdk-replacement"), first.listAccountAliases().accountAliases());
                assertEquals(List.of("alias-sdk-first"), second.listAccountAliases().accountAliases());

                first.deleteAccountAlias(request -> request.accountAlias("alias-sdk-replacement"));
                second.createAccountAlias(request -> request.accountAlias("alias-sdk-replacement"));
                assertEquals(List.of("alias-sdk-replacement"), second.listAccountAliases().accountAliases());
                assertTrue(first.listAccountAliases().accountAliases().isEmpty());
                first.createAccountAlias(request -> request.accountAlias("alias-sdk-first"));
                assertEquals(List.of("alias-sdk-first"), first.listAccountAliases().accountAliases());
            } finally {
                removeAlias(first);
                removeAlias(second);
            }
            assertTrue(first.listAccountAliases().accountAliases().isEmpty());
            assertTrue(second.listAccountAliases().accountAliases().isEmpty());
        }
    }

    private IamClient client(String accountId, String region) {
        return IamClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accountId, "test")))
                .build();
    }

    private static void removeAlias(IamClient client) {
        for (String alias : client.listAccountAliases().accountAliases()) {
            assertTrue(alias.startsWith("alias-sdk-"));
            client.deleteAccountAlias(request -> request.accountAlias(alias));
        }
    }
}
