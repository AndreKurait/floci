package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer.Permission;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceRoleSourceAccountTest {
    @Test
    @SuppressWarnings("unchecked")
    void recipientRoleCanTrustTheSourceAccountWithoutChangingTheOriginalOverload() {
        String recipient = "222222222222";
        String source = "111111111111";
        String roleArn = "arn:aws:iam::" + recipient + ":role/receiver";
        String stream = "arn:aws:kinesis:us-west-2:" + recipient + ":stream/owned";
        String group = "arn:aws:logs:us-east-1:" + source + ":log-group:/owned:*";
        ObjectMapper mapper = new ObjectMapper();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        IamService iam = mock(IamService.class);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);
        Instance<ScpProvider> scps = mock(Instance.class);
        ServiceRoleAuthorizer authorizer = new ServiceRoleAuthorizer(config, iam, evaluator,
                new AssumeRolePolicyEvaluator(mapper, evaluator), scps);
        IamRole role = new IamRole("owned", "receiver", "/", roleArn, """
                {"Statement":[{"Effect":"Allow","Principal":{"Service":"logs.amazonaws.com"},
                "Action":"sts:AssumeRole","Condition":{"StringEquals":{"aws:SourceAccount":"111111111111"},
                "ArnLike":{"aws:SourceArn":"arn:aws:logs:us-east-1:111111111111:*"}}}]}
                """);
        when(iam.findRole(recipient, "receiver")).thenReturn(Optional.of(role));
        when(iam.resolvePrincipalContext(roleArn)).thenReturn(CallerContext.of(List.of("""
                {"Statement":[{"Effect":"Allow","Action":"kinesis:PutRecord","Resource":"%s"}]}
                """.formatted(stream))));
        List<Permission> permissions = List.of(new Permission("kinesis:PutRecord", stream));
        assertTrue(authorizer.refusal(roleArn, recipient, "logs.amazonaws.com", group, source, permissions).isEmpty());
        assertTrue(authorizer.refusal(roleArn, recipient, "logs.amazonaws.com", group, permissions).isPresent());
        assertTrue(authorizer.refusal(roleArn, recipient, "logs.amazonaws.com", group, "333333333333",
                permissions).isPresent());
        assertTrue(authorizer.refusal(roleArn, source, "logs.amazonaws.com", group, source, permissions).isPresent());
        assertTrue(authorizer.refusal(roleArn, recipient, "s3.amazonaws.com", group, source,
                permissions).isPresent());
        assertTrue(authorizer.refusal(roleArn, recipient, "logs.amazonaws.com", group, source,
                List.of(new Permission("kinesis:PutRecord", stream + "-other"))).isPresent());
    }
}
