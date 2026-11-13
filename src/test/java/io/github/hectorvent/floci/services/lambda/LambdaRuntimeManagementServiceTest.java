package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.LambdaRuntimeManagementConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LambdaRuntimeManagementServiceTest {

    static final String ACCOUNT = "000000000000";
    static final String REGION = "us-east-1";
    static final String PIN = "arn:aws:lambda:us-east-1::runtime:owned-opaque-build";
    static final ObjectMapper MAPPER = new ObjectMapper();
    private final InMemoryStorage<String, LambdaFunction> rawFunctions = new InMemoryStorage<>();
    private final InMemoryStorage<String, LambdaAlias> rawAliases = new InMemoryStorage<>();
    private final InMemoryStorage<String, LambdaRuntimeManagementConfig> rawSettings = new InMemoryStorage<>();
    private LambdaFunctionStore functions;
    private LambdaAliasStore aliases;
    private LambdaRuntimeManagementService service;

    @BeforeEach
    void setUp() {
        functions = new LambdaFunctionStore(new AccountAwareStorageBackend<>(rawFunctions, null, ACCOUNT),
                new AccountAwareStorageBackend<>(rawSettings, null, ACCOUNT));
        aliases = new LambdaAliasStore(new AccountAwareStorageBackend<>(rawAliases, null, ACCOUNT));
        service = new LambdaRuntimeManagementService(functions, aliases, new RegionResolver(REGION, ACCOUNT));
        functions.create(REGION, function("one", "$LATEST", ACCOUNT, REGION));
        functions.create(REGION, function("two", "$LATEST", ACCOUNT, REGION));
        functions.save(REGION, function("one", "1", ACCOUNT, REGION));
    }

    @Test
    void defaultsAreReadOnlyAndReferencesSelectTheSameFunction() {
        Set<String> keys = Set.copyOf(rawFunctions.keys());
        for (String reference : new String[]{"one", ACCOUNT + ":function:one",
                "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:one", "function:one"}) {
            assertEquals("Auto", service.get(REGION, reference, null).configuration().updateRuntimeOn());
        }
        assertTrue(rawSettings.isEmpty());
        assertEquals(keys, rawFunctions.keys());
    }

    @Test
    void latestVersionAndSiblingSettingsAreIndependent() throws Exception {
        put("one", null, "Manual", PIN);
        assertEquals(PIN, service.get(REGION, "one", "$LATEST").configuration().runtimeVersionArn());
        assertEquals("Auto", service.get(REGION, "one", "1").configuration().updateRuntimeOn());
        assertEquals("Auto", service.get(REGION, "two", null).configuration().updateRuntimeOn());
        put("one", "1", "FunctionUpdate", null);
        assertEquals("Manual", service.get(REGION, "one", null).configuration().updateRuntimeOn());
        assertEquals("FunctionUpdate", service.get(REGION, "one:1", null).configuration().updateRuntimeOn());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Auto", "FunctionUpdate"})
    void nonManualAcceptsValidSuppliedPinAndClearsIt(String mode) throws Exception {
        put("one", null, "Manual", PIN);
        put("one", null, mode, PIN);
        assertEquals(new LambdaRuntimeManagementConfig(mode, null),
                service.get(REGION, "one", null).configuration());
        assertTrue(rawSettings.scan(key -> true).stream().allMatch(value -> value.runtimeVersionArn() == null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "true", "{\"UpdateRuntimeOn\":null}",
            "{\"UpdateRuntimeOn\":1}", "{\"UpdateRuntimeOn\":\"manual\"}",
            "{\"UpdateRuntimeOn\":\"Manual\"}", "{\"UpdateRuntimeOn\":\"Manual\",\"RuntimeVersionArn\":null}",
            "{\"UpdateRuntimeOn\":\"Manual\",\"RuntimeVersionArn\":3}",
            "{\"UpdateRuntimeOn\":\"Manual\",\"RuntimeVersionArn\":\"bad\"}",
            "{\"UpdateRuntimeOn\":\"Auto\",\"RuntimeVersionArn\":[]}"})
    void invalidRequestsLeaveStoredPinUnchanged(String json) throws Exception {
        put("one", null, "Manual", PIN);
        AwsException failure = assertThrows(AwsException.class,
                () -> service.put(REGION, "one", null, MAPPER.readTree(json)));
        assertEquals("InvalidParameterValueException", failure.getErrorCode());
        assertEquals(PIN, service.get(REGION, "one", null).configuration().runtimeVersionArn());
    }

    @Test
    void runtimeArnBoundsAndOpaqueSuffixAreModeled() throws Exception {
        String prefix = "arn:aws:lambda:us-east-1::runtime:";
        String longest = prefix + "x".repeat(2048 - prefix.length());
        put("one", null, "Manual", longest);
        assertEquals(longest, service.get(REGION, "one", null).configuration().runtimeVersionArn());
        assertThrows(AwsException.class, () -> put("one", null, "Manual", longest + "x"));
        assertEquals(longest, service.get(REGION, "one", null).configuration().runtimeVersionArn());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad/qualifier", "bad.qualifier"})
    void malformedQualifiersDoNotMutate(String qualifier) {
        assertThrows(AwsException.class, () -> service.get(REGION, "one", qualifier));
        assertTrue(rawSettings.isEmpty());
    }

    @Test
    void conflictingAndOverlongSelectorsAreRejected() {
        assertThrows(AwsException.class, () -> service.get(REGION, "one:1", "2"));
        assertThrows(AwsException.class, () -> service.get(REGION, "one", "x".repeat(129)));
        assertThrows(AwsException.class, () -> service.get(REGION, "x".repeat(65), null));
        assertThrows(AwsException.class, () -> service.get(REGION, "x".repeat(257), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "one:99", "one:missing-alias", "one:$LATEST.PUBLISHED",
            "111111111111:function:one", "arn:aws:lambda:us-west-2:000000000000:function:one",
            "arn:aws-cn:lambda:us-east-1:000000000000:function:one"})
    void missingOrForeignReferencesCannotReachLocalSettings(String reference) throws Exception {
        put("one", null, "Manual", PIN);
        Set<String> keys = Set.copyOf(rawFunctions.keys());
        AwsException failure = assertThrows(AwsException.class, () -> service.get(REGION, reference, null));
        assertEquals("ResourceNotFoundException", failure.getErrorCode());
        assertThrows(AwsException.class, () -> put(reference, null, "Auto", null));
        assertEquals(keys, rawFunctions.keys());
        assertEquals(PIN, service.get(REGION, "one", null).configuration().runtimeVersionArn());
    }

    @Test
    void aliasUsesPrimaryVersionAndRetainsRequestedResponseArn() throws Exception {
        LambdaAlias alias = new LambdaAlias();
        alias.setName("live");
        alias.setFunctionName("one");
        alias.setFunctionVersion("1");
        alias.setAliasArn("arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:one:live");
        alias.setRoutingConfig(Map.of("99", 0.5));
        aliases.save(REGION, alias);
        put("one", "live", "Manual", PIN);
        assertEquals(PIN, service.get(REGION, "one", "1").configuration().runtimeVersionArn());
        assertEquals(alias.getAliasArn(), service.get(REGION, "one:live", null).functionArn());
        assertEquals("Auto", service.get(REGION, "one", null).configuration().updateRuntimeOn());
    }

    @Test
    void legacyReadsAndTagsDoNotMigrateEitherStore() {
        LambdaFunction legacy = function("legacy", "$LATEST", ACCOUNT, REGION);
        legacy.setTags(Map.of("owner", "team", "empty", ""));
        rawFunctions.put("lambda::us-east-1::legacy::$LATEST", legacy);
        LambdaFunction version = function("legacy", "1", ACCOUNT, REGION);
        rawFunctions.put("lambda::us-east-1::legacy::1", version);
        LambdaAlias alias = new LambdaAlias();
        alias.setName("live");
        alias.setFunctionName("legacy");
        alias.setFunctionVersion("1");
        alias.setAliasArn("arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:legacy:live");
        rawAliases.put("alias::us-east-1::legacy::live", alias);
        Set<String> functionKeys = Set.copyOf(rawFunctions.keys());
        Set<String> aliasKeys = Set.copyOf(rawAliases.keys());
        assertEquals("Auto", service.get(REGION, "legacy", "live").configuration().updateRuntimeOn());
        assertEquals(Map.of("owner", "team", "empty", ""),
                service.authorizationTags(REGION, ACCOUNT, "legacy", "live"));
        assertEquals(Map.of(), service.authorizationTags(REGION, "111111111111", "legacy", "live"));
        assertEquals(functionKeys, rawFunctions.keys());
        assertEquals(aliasKeys, rawAliases.keys());
        assertNull(legacy.getRuntimeManagementGeneration());
        assertTrue(rawSettings.isEmpty());
    }

    @Test
    void qualifiedPolicyResourceKeepsTheRequestSelector() {
        assertEquals("arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:one:live",
                LambdaRuntimeManagementReference.resolve("one:live", "live", REGION, ACCOUNT).authorizationArn());
        assertEquals("arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:one:$LATEST",
                LambdaRuntimeManagementReference.resolve("one", "$LATEST", REGION, ACCOUNT).authorizationArn());
    }

    private void put(String name, String version, String mode, String pin) throws Exception {
        service.put(REGION, name, version, MAPPER.readTree(MAPPER.writeValueAsString(
                pin == null ? Map.of("UpdateRuntimeOn", mode)
                        : Map.of("UpdateRuntimeOn", mode, "RuntimeVersionArn", pin))));
    }

    static LambdaFunction function(String name, String version, String account, String region) {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName(name);
        function.setVersion(version);
        function.setAccountId(account);
        function.setFunctionArn(AwsArnUtils.Arn.of("lambda", region, account,
                "function:" + name + ("$LATEST".equals(version) ? "" : ":" + version)).toString());
        function.setRole(AwsArnUtils.Arn.global(AwsArnUtils.parse(function.getFunctionArn()).partition(),
                "iam", account, "role/runtime-test").toString());
        return function;
    }
}
