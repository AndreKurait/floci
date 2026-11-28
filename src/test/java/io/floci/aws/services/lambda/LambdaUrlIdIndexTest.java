package io.floci.aws.services.lambda;

import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.lambda.model.LambdaAlias;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.model.LambdaUrlConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The function URL id index has to follow the URL config of the instance being saved. The in-memory
 * backend hands out the stored instance itself, so a caller changes that instance first and saves it
 * afterwards: the store cannot learn the previous URL by reading the stored copy.
 */
class LambdaUrlIdIndexTest {

    private static final String REGION = "us-east-1";

    @Test
    void aFunctionSavedWithoutItsUrlConfigIsNoLongerFoundByItsUrlId() {
        LambdaFunctionStore store = new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>());
        LambdaFunction function = function("orders");
        function.setUrlConfig(urlConfig("abc123"));
        store.save(REGION, function);
        assertSame(function, store.getByUrlId("abc123").orElseThrow());

        LambdaFunction stored = store.get(REGION, "orders").orElseThrow();
        stored.setUrlConfig(null);
        store.save(REGION, stored);

        assertFalse(store.getByUrlId("abc123").isPresent(), "a deleted function URL must stop resolving");
    }

    @Test
    void aFunctionSavedWithANewUrlIsFoundOnlyByTheNewUrlId() {
        LambdaFunctionStore store = new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>());
        LambdaFunction function = function("orders");
        function.setUrlConfig(urlConfig("abc123"));
        store.save(REGION, function);

        LambdaFunction stored = store.get(REGION, "orders").orElseThrow();
        stored.setUrlConfig(urlConfig("def456"));
        store.save(REGION, stored);

        assertFalse(store.getByUrlId("abc123").isPresent(), "the replaced URL id must stop resolving");
        assertSame(stored, store.getByUrlId("def456").orElseThrow());
    }

    @Test
    void savingAnotherFunctionLeavesAnIndexedUrlIdInPlace() {
        LambdaFunctionStore store = new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>());
        LambdaFunction function = function("orders");
        function.setUrlConfig(urlConfig("abc123"));
        store.save(REGION, function);

        store.save(REGION, function("billing"));

        assertTrue(store.getByUrlId("abc123").isPresent());
    }

    @Test
    void anAliasSavedWithoutItsUrlConfigIsNoLongerFoundByItsUrlId() {
        LambdaAliasStore store = new LambdaAliasStore(new InMemoryStorage<String, LambdaAlias>());
        LambdaAlias alias = new LambdaAlias();
        alias.setFunctionName("orders");
        alias.setName("live");
        alias.setUrlConfig(urlConfig("abc123"));
        store.save(REGION, alias);
        assertSame(alias, store.getByUrlId("abc123").orElseThrow());

        LambdaAlias stored = store.get(REGION, "orders", "live").orElseThrow();
        stored.setUrlConfig(null);
        store.save(REGION, stored);

        assertFalse(store.getByUrlId("abc123").isPresent(), "a deleted alias URL must stop resolving");
    }

    private static LambdaFunction function(String name) {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName(name);
        function.setVersion("$LATEST");
        return function;
    }

    private static LambdaUrlConfig urlConfig(String urlId) {
        LambdaUrlConfig config = new LambdaUrlConfig();
        config.setFunctionUrl("http://" + urlId + ".lambda-url.us-east-1.localhost:4566/");
        return config;
    }

    @Test
    void savingAnotherAccountsMutatedInstanceRemovesOnlyItsOldUrlId() {
        String defaultAccount = "000000000000";
        String otherAccount = "222222222222";
        LambdaFunctionStore store = new LambdaFunctionStore(
                AccountAwareStorageBackend.<LambdaFunction>inMemory(defaultAccount));
        LambdaFunction defaultFunction = function("orders");
        defaultFunction.setAccountId(defaultAccount);
        defaultFunction.setUrlConfig(urlConfig("default123"));
        store.saveForAccount(defaultAccount, REGION, defaultFunction);
        LambdaFunction otherFunction = function("orders");
        otherFunction.setAccountId(otherAccount);
        otherFunction.setUrlConfig(urlConfig("other123"));
        store.saveForAccount(otherAccount, REGION, otherFunction);

        LambdaFunction stored = store.getForAccount(otherAccount, REGION, "orders").orElseThrow();
        assertSame(otherFunction, stored);
        stored.setUrlConfig(null);
        store.saveForAccount(otherAccount, REGION, stored);

        assertFalse(store.getByUrlId("other123").isPresent());
        assertSame(defaultFunction, store.getByUrlId("default123").orElseThrow());
        assertSame(defaultFunction, store.getForAccount(defaultAccount, REGION, "orders").orElseThrow());
        assertSame(otherFunction, store.getForAccount(otherAccount, REGION, "orders").orElseThrow());
    }

}
