package io.github.hectorvent.floci.services.swf;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.swf.SwfClient;
import software.amazon.awssdk.services.swf.model.SwfException;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class SwfDomainIamIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void aDomainGrantLetsASignedClientStartAndSignalOnlyInThatDomain() throws Exception {
        String domain = "scoped-" + UUID.randomUUID();
        try (SwfClient admin = client("test", "test")) {
            register(admin, domain);
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "swf-domain-", "workflow",
                    policy("Allow", domainArn(domain)));
                 SwfClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
                String run = actor.startWorkflowExecution(request -> request.domain(domain).workflowId("orders")
                        .workflowType(type -> type.name("orders").version("1"))).runId();
                assertFalse(run.isBlank());
                try {
                    actor.signalWorkflowExecution(request -> request.domain(domain).workflowId("orders")
                            .runId(run).signalName("ready"));
                    assertTrue(admin.getWorkflowExecutionHistory(request -> request.domain(domain)
                                    .execution(execution -> execution.workflowId("orders").runId(run)))
                            .events().stream().anyMatch(event -> "WorkflowExecutionSignaled".equals(event.eventTypeAsString())));
                    SwfException denied = assertThrows(SwfException.class, () ->
                            actor.startWorkflowExecution(request -> request.domain(domain + "-other").workflowId("orders")
                                    .workflowType(type -> type.name("orders").version("1"))));
                    assertTrue(denied.getMessage().contains("not authorized"));
                    assertThrows(SwfException.class, () -> actor.listDomains(request -> request.registrationStatus("REGISTERED")));
                } finally {
                    admin.terminateWorkflowExecution(request -> request.domain(domain).workflowId("orders").runId(run));
                }
            } finally {
                admin.deprecateDomain(request -> request.name(domain));
            }
        }
    }

    @Test
    void aDomainDenyOverridesAWildcardGrantBeforeTheWorkflowStarts() throws Exception {
        String domain = "denied-" + UUID.randomUUID();
        try (SwfClient admin = client("test", "test")) {
            register(admin, domain);
            String policy = """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"swf:*","Resource":"*"},
                      {"Effect":"Deny","Action":"swf:*","Resource":"%s"}]}""".formatted(domainArn(domain));
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "swf-denied-", "workflow", policy);
                 SwfClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
                SwfException denied = assertThrows(SwfException.class, () ->
                        actor.startWorkflowExecution(request -> request.domain(domain).workflowId("must-not-start")
                                .workflowType(type -> type.name("orders").version("1"))));
                assertTrue(denied.getMessage().contains("not authorized"));
                assertEquals(0, admin.countOpenWorkflowExecutions(request -> request.domain(domain)
                        .startTimeFilter(filter -> filter.oldestDate(Instant.EPOCH))).count());
            } finally {
                admin.deprecateDomain(request -> request.name(domain));
            }
        }
    }

    private SwfClient client(String accessKey, String secret) {
        return SwfClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
                .build();
    }

    private static void register(SwfClient admin, String domain) {
        admin.registerDomain(request -> request.name(domain).workflowExecutionRetentionPeriodInDays("1"));
        admin.registerWorkflowType(request -> request.domain(domain).name("orders").version("1")
                .defaultTaskList(list -> list.name("orders")).defaultTaskStartToCloseTimeout("30")
                .defaultExecutionStartToCloseTimeout("300").defaultChildPolicy("TERMINATE"));
    }

    private static String domainArn(String domain) {
        return "arn:aws:swf:us-east-1:000000000000:/domain/" + domain;
    }

    private static String policy(String effect, String resource) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"%s","Action":"swf:*","Resource":"%s"}]}""".formatted(effect, resource);
    }
}
