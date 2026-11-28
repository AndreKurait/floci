package io.floci.aws.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsServer;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.CallerCallAuthorizer;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceCallAuthorizer;
import io.floci.aws.core.common.ServiceRoleCallAuthorizer;
import io.floci.aws.services.apigateway.AwsServiceRouter;
import io.floci.aws.services.apigateway.VtlTemplateEngine;
import io.floci.aws.services.apigatewayv2.model.Integration;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.testing.ExtraCaConfigs;
import io.floci.aws.testing.LocalHttpsServer;
import io.floci.aws.testing.ParallelLocks;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class WebSocketIntegrationTlsAuthorizationTest {

    @TempDir
    Path directory;

    @Test
    void configuredPrivateCaAndLambdaAuthorizationBothApplyToTheInjectedInvoker() throws Exception {
        FlociCertificateAuthority trusted = FlociCertificateAuthority.loadOrCreate(directory.resolve("trusted"));
        FlociCertificateAuthority untrusted = FlociCertificateAuthority.loadOrCreate(directory.resolve("untrusted"));
        EmulatorConfig config = ExtraCaConfigs.config(false, Optional.of(trusted.certificatePath().toString()));
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        @SuppressWarnings("unchecked")
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(call -> List.<ResourcePolicyProvider>of().iterator());
        ObjectMapper mapper = new ObjectMapper();
        LambdaService lambda = mock(LambdaService.class);
        WebSocketIntegrationInvoker invoker = new WebSocketIntegrationInvoker(
                lambda, mock(AwsServiceRouter.class), mapper, mock(VtlTemplateEngine.class), config,
                new ServiceCallAuthorizer(config, new IamPolicyEvaluator(mapper), providers),
                new RegionResolver("us-east-1", "111122223333"),
                mock(ServiceRoleCallAuthorizer.class), mock(CallerCallAuthorizer.class));
        AtomicInteger trustedRequests = new AtomicInteger();
        AtomicInteger untrustedRequests = new AtomicInteger();
        HttpsServer trustedServer = LocalHttpsServer.hook(trusted, exchange -> trustedRequests.incrementAndGet());
        HttpsServer untrustedServer = LocalHttpsServer.hook(untrusted, exchange -> untrustedRequests.incrementAndGet());
        try {
            assertEquals(200, invoke(invoker, "HTTP_PROXY", LocalHttpsServer.hookUrl(trustedServer)).statusCode());
            assertEquals(1, trustedRequests.get());
            assertEquals(500, invoke(invoker, "HTTP_PROXY", LocalHttpsServer.hookUrl(untrustedServer)).statusCode());
            assertEquals(0, untrustedRequests.get());

            String target = "arn:aws:lambda:us-west-2:444455556666:function:handler:live";
            assertEquals(500, invoke(invoker, "AWS_PROXY",
                    "arn:aws:apigateway:us-west-2:lambda:path/2015-03-31/functions/"
                            + target + "/invocations").statusCode());
            verifyNoInteractions(lambda);
        } finally {
            invoker.shutdown();
            trustedServer.stop(0);
            untrustedServer.stop(0);
        }
    }

    private static WebSocketIntegrationInvoker.IntegrationResult invoke(
            WebSocketIntegrationInvoker invoker, String type, String uri) {
        Integration integration = new Integration();
        integration.setIntegrationType(type);
        integration.setIntegrationUri(uri);
        return invoker.invoke("us-east-1", "api", "prod", "$connect", integration, "{}",
                Map.of(), Map.of(), Map.of());
    }
}
