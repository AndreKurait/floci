package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StsSessionTagAuthorizerTest {
    private static final String ACCOUNT = "111122223333";
    private static final String USER = "arn:aws:iam::" + ACCOUNT + ":user/team/caller";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}";
    private static final String DENY = ALLOW.replace("Allow", "Deny");

    @ParameterizedTest
    @ValueSource(strings = {"account-allow", "account-missing-identity", "direct-allow", "foreign-allow",
            "foreign-missing-identity", "identity-deny", "boundary-deny", "boundary-implicit", "session-deny",
            "session-implicit", "scp-deny", "scp-implicit", "trust-deny", "trust-missing", "request-tag-allow",
            "request-tag-deny", "caller-tag-allow", "caller-tag-deny", "tag-key-deny", "missing-caller",
            "account-root-allow", "test-root-allow", "disappeared-caller-root"})
    @SuppressWarnings("unchecked")
    void sharedPolicyPipelinePreservesTagTrustAndCallerCeilings(String scenario) {
        IamService iam = mock(IamService.class);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
        Instance<ScpProvider> providers = mock(Instance.class);
        ScpProvider scps = mock(ScpProvider.class);
        when(providers.isResolvable()).thenReturn(true);
        when(providers.get()).thenReturn(scps);
        IamRole role = new IamRole();
        role.setArn("arn:aws:iam::" + (scenario.startsWith("foreign-") ? "444455556666" : ACCOUNT)
                + ":role/team/target");
        String principal = scenario.equals("direct-allow") ? USER : ACCOUNT;
        String condition = switch (scenario) {
            case "request-tag-allow" -> ",\"Condition\":{\"StringEquals\":{\"aws:RequestTag/Project\":\"session\"}}";
            case "request-tag-deny" -> ",\"Condition\":{\"StringEquals\":{\"aws:RequestTag/Project\":\"other\"}}";
            case "caller-tag-allow" -> ",\"Condition\":{\"StringEquals\":{\"aws:PrincipalTag/Project\":\"caller\"}}";
            case "caller-tag-deny" -> ",\"Condition\":{\"StringEquals\":{\"aws:PrincipalTag/Project\":\"session\"}}";
            case "tag-key-deny" -> ",\"Condition\":{\"ForAllValues:StringEquals\":{\"aws:TagKeys\":[\"Other\"]}}";
            default -> "";
        };
        role.setAssumeRolePolicyDocument("{\"Version\":\"2012-10-17\",\"Statement\":{\"Effect\":\""
                + (scenario.equals("trust-deny") ? "Deny" : "Allow")
                + "\",\"Action\":\"" + (scenario.equals("trust-missing") ? "sts:AssumeRole" : "sts:TagSession")
                + "\",\"Principal\":{\"AWS\":\"" + principal + "\"}" + condition + "}}");
        CallerContext caller = CallerContext.of(List.of(ALLOW));
        switch (scenario) {
            case "account-missing-identity", "foreign-missing-identity", "direct-allow" ->
                    caller = CallerContext.of(List.of());
            case "identity-deny" -> caller = CallerContext.of(List.of(DENY));
            case "boundary-deny" -> caller = new CallerContext(List.of(ALLOW), null, DENY);
            case "boundary-implicit" -> caller = new CallerContext(List.of(ALLOW), null, "{\"Statement\":[]}");
            case "session-deny" -> caller = new CallerContext(List.of(ALLOW), DENY, null);
            case "session-implicit" -> caller = new CallerContext(List.of(ALLOW), "{\"Statement\":[]}", null);
            case "scp-deny" -> when(scps.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(List.of(DENY)));
            case "scp-implicit" -> when(scps.effectiveScpLevels(ACCOUNT))
                    .thenReturn(List.of(List.of(ALLOW.replace("\"Action\":\"*\"", "\"Action\":\"ssm:*\""))));
            case "missing-caller", "account-root-allow", "test-root-allow", "disappeared-caller-root" -> caller = null;
            default -> { }
        }
        when(iam.resolveCallerContext("key")).thenReturn(caller);
        IamService.CallerArns identity = new IamService.CallerArns(USER, USER,
                IamService.CallerArns.TYPE_USER, "user-id", "caller")
                .withPrincipalTags(Map.of("Project", "caller"));
        String accessKey = "account-root-allow".equals(scenario) ? ACCOUNT
                : "test-root-allow".equals(scenario) ? "test" : "key";
        if (scenario.contains("root")) {
            identity = IamService.CallerArns.accountRoot("arn:aws:iam::" + ACCOUNT + ":root", ACCOUNT);
        }
        StsSessionTagAuthorizer authorizer = new StsSessionTagAuthorizer(iam,
                new AssumeRolePolicyEvaluator(new ObjectMapper(), evaluator), evaluator, providers);
        boolean expected = List.of("account-allow", "direct-allow", "foreign-allow", "request-tag-allow",
                "caller-tag-allow", "account-root-allow", "test-root-allow").contains(scenario);
        assertEquals(expected, authorizer.allows(accessKey, identity, ACCOUNT, role,
                StsSessionTags.context(Map.of("Project", "session"))));
    }
}
