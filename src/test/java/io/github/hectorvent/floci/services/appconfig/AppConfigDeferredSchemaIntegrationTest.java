package io.github.hectorvent.floci.services.appconfig;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.BadRequestException;
import software.amazon.awssdk.services.appconfig.model.Validator;
import software.amazon.awssdk.services.appconfig.model.ValidatorType;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class AppConfigDeferredSchemaIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"type\":\"not-a-json-type\"}"})
    void sdkStoresMalformedDeclarationsAndDefersFailureUntilValidation(String declaration) {
        try (AppConfigClient client = AppConfigClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test"))).build()) {
            String app = client.createApplication(request -> request.name("deferred-schema")).id();
            try {
                String profile = client.createConfigurationProfile(request -> request.applicationId(app)
                        .name("settings").locationUri("hosted").validators(validator(declaration))).id();
                try {
                    assertEquals(declaration, client.getConfigurationProfile(request -> request.applicationId(app)
                            .configurationProfileId(profile)).validators().getFirst().content());
                    int version = client.createHostedConfigurationVersion(request -> request.applicationId(app)
                            .configurationProfileId(profile).contentType("application/json")
                            .content(SdkBytes.fromUtf8String("10"))).versionNumber();
                    try {
                        assertEquals(400, assertThrows(BadRequestException.class,
                                () -> client.validateConfiguration(request -> request.applicationId(app)
                                        .configurationProfileId(profile).configurationVersion(Integer.toString(version))))
                                .statusCode());
                        assertEquals(200, client.updateConfigurationProfile(request -> request.applicationId(app)
                                .configurationProfileId(profile).validators(validator("{\"type\":\"integer\"}")))
                                .sdkHttpResponse().statusCode());
                        assertEquals(204, client.validateConfiguration(request -> request.applicationId(app)
                                .configurationProfileId(profile).configurationVersion(Integer.toString(version)))
                                .sdkHttpResponse().statusCode());
                        assertEquals(200, client.updateConfigurationProfile(request -> request.applicationId(app)
                                .configurationProfileId(profile).validators(validator(declaration)))
                                .sdkHttpResponse().statusCode());
                        assertEquals(declaration, client.getConfigurationProfile(request -> request.applicationId(app)
                                .configurationProfileId(profile)).validators().getFirst().content());
                        assertEquals(400, assertThrows(BadRequestException.class,
                                () -> client.validateConfiguration(request -> request.applicationId(app)
                                        .configurationProfileId(profile).configurationVersion(Integer.toString(version))))
                                .statusCode());
                    } finally {
                        client.deleteHostedConfigurationVersion(request -> request.applicationId(app)
                                .configurationProfileId(profile).versionNumber(version));
                    }
                } finally {
                    client.deleteConfigurationProfile(request -> request.applicationId(app)
                            .configurationProfileId(profile));
                }
            } finally {
                client.deleteApplication(request -> request.applicationId(app));
            }
        }
    }

    private static Validator validator(String content) {
        return Validator.builder().type(ValidatorType.JSON_SCHEMA).content(content).build();
    }
}
