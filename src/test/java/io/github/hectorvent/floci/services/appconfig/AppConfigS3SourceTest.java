package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationContent;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppConfigS3SourceTest {
    private static final String ACCOUNT = "111111111111";
    private static final String BUCKET = "configuration-source";
    private static final String ROLE = "arn:aws:iam::111111111111:role/retrieval";
    private static final String VERSION = "immutable-version";
    private static final byte[] CONTENT = "{\"limit\":10}".getBytes(StandardCharsets.UTF_8);

    private final S3Service s3 = mock(S3Service.class);
    private final ServiceRoleCallAuthorizer authorizer = mock(ServiceRoleCallAuthorizer.class);
    private final AppConfigS3Source source = new AppConfigS3Source(
            s3, authorizer, new RegionResolver("us-east-1", ACCOUNT));
    private final ConfigurationProfile profile = new ConfigurationProfile();
    private final S3Object object = new S3Object(BUCKET, "settings.json", CONTENT.clone(), "application/json");

    @BeforeEach
    void prepareSource() {
        profile.setLocationUri("s3://" + BUCKET + "/settings.json");
        profile.setRetrievalRoleArn(ROLE);
        profile.setType("AWS.Freeform");
        object.setVersionId(VERSION);
        when(s3.bucketPartition(BUCKET)).thenReturn("aws");
        when(s3.getBucketOwnerAccountId(BUCKET)).thenReturn(ACCOUNT);
        when(s3.getBucketVersioning(BUCKET)).thenReturn("Enabled");
        when(s3.getObjectMetadata(BUCKET, "settings.json", VERSION)).thenReturn(object);
        when(s3.getObject(BUCKET, "settings.json", VERSION)).thenReturn(object);
        when(authorizer.authorizeWithoutSourceContext(eq("appconfig"), eq(ROLE), anyString(), anyString(),
                eq(ACCOUNT), eq("aws"), anyMap())).thenReturn(Decision.ALLOW);
    }

    @Test
    void retrievesTheExactVersionAndCopiesItsContent() {
        object.getTags().put("environment", "test");
        ConfigurationContent content = source.retrieve(profile, VERSION);
        object.getData()[0] = 0;
        content.content()[1] = 0;

        assertArrayEquals(CONTENT, content.content());
        assertEquals("application/json", content.contentType());
        verify(s3).getObject(BUCKET, "settings.json", VERSION);
        verify(authorizer).authorizeWithoutSourceContext(eq("appconfig"), eq(ROLE),
                eq("s3:GetObjectVersion"), eq("arn:aws:s3:::configuration-source/settings.json"),
                eq(ACCOUNT), eq("aws"), argThat(context ->
                        List.of(VERSION).equals(context.get("s3:VersionId"))
                                && List.of("test").equals(context.get("s3:ExistingObjectTag/environment"))
                                && List.of("true").equals(context.get("aws:SecureTransport"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"s3:GetObjectVersion", "s3:GetBucketVersioning"})
    void aDeniedRetrievalActionPreventsReadingContent(String action) {
        when(authorizer.authorizeWithoutSourceContext(eq("appconfig"), eq(ROLE), eq(action), anyString(),
                eq(ACCOUNT), eq("aws"), anyMap())).thenReturn(Decision.DENY);

        assertEquals("BadRequestException",
                assertThrows(AwsException.class, () -> source.retrieve(profile, VERSION)).getErrorCode());
        verify(s3, never()).getObject(anyString(), anyString(), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "null"})
    void rejectsMutableOrAbsentVersionIdentifiers(String version) {
        assertThrows(AwsException.class, () -> source.retrieve(profile, version));
        verify(s3, never()).getObject(anyString(), anyString(), any());
    }

    @Test
    void rejectsAnUnversionedBucket() {
        when(s3.getBucketVersioning(BUCKET)).thenReturn(null);
        assertEquals("BadRequestException",
                assertThrows(AwsException.class, () -> source.retrieve(profile, VERSION)).getErrorCode());
        verify(s3, never()).getObject(anyString(), anyString(), any());
    }

    @Test
    void retainsAccessToAnImmutableVersionAfterVersioningIsSuspended() {
        when(s3.getBucketVersioning(BUCKET)).thenReturn("Suspended");
        assertArrayEquals(CONTENT, source.retrieve(profile, VERSION).content());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GLACIER", "DEEP_ARCHIVE", "GLACIER_IR"})
    void rejectsUnsupportedStorageBeforeReadingBytes(String storageClass) {
        object.setStorageClass(storageClass);
        assertThrows(AwsException.class, () -> source.retrieve(profile, VERSION));
        verify(s3, never()).getObject(anyString(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD", "STANDARD_IA", "ONEZONE_IA", "REDUCED_REDUNDANCY", "INTELLIGENT_TIERING"})
    void supportsAvailableSseS3Objects(String storageClass) {
        object.setStorageClass(storageClass);
        object.setServerSideEncryption("AES256");
        assertArrayEquals(CONTENT, source.retrieve(profile, VERSION).content());
    }

    @Test
    void doesNotReturnAnObjectRequiringUnimplementedKmsAuthorization() {
        object.setServerSideEncryption("aws:kms");
        assertThrows(AwsException.class, () -> source.retrieve(profile, VERSION));
        verify(s3, never()).getObject(anyString(), anyString(), any());
    }

    @Test
    void rejectsOversizedObjectsBeforeReadingTheirBytes() {
        object.setSize(1024 * 1024 + 1L);
        assertThrows(AwsException.class, () -> source.retrieve(profile, VERSION));
        verify(s3, never()).getObject(anyString(), anyString(), any());
    }

    @Test
    void reportsMissingObjectVersionsAsDeploymentErrors() {
        when(s3.getObjectMetadata(BUCKET, "settings.json", VERSION))
                .thenThrow(new AwsException("NoSuchVersion", "Not found", 404));
        assertEquals("BadRequestException",
                assertThrows(AwsException.class, () -> source.retrieve(profile, VERSION)).getErrorCode());
        verify(s3, never()).getObject(anyString(), anyString(), any());
    }

    @Test
    void retrievesAnObjectArnWithTheSameVersionAndAuthorization() {
        profile.setLocationUri("arn:aws:s3:::configuration-source/settings.json");
        assertArrayEquals(CONTENT, source.retrieve(profile, VERSION).content());
        verify(authorizer).authorizeWithoutSourceContext(eq("appconfig"), eq(ROLE),
                eq("s3:GetObjectVersion"), eq(profile.getLocationUri()), eq(ACCOUNT), eq("aws"), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:s3:us-east-1::configuration-source/settings.json",
            "arn:aws:s3::111111111111:configuration-source/settings.json",
            "arn:aws:s3:::configuration-source",
            "arn:aws:s3:::configuration-source/"
    })
    void rejectsArnsThatDoNotIdentifyAnS3Object(String location) {
        profile.setLocationUri(location);
        assertThrows(AwsException.class, () -> AppConfigS3Source.validateProfile(profile));
    }

    @Test
    void aSourceArnCannotChangeTheBucketsPartition() {
        profile.setLocationUri("arn:aws-cn:s3:::configuration-source/settings.json");
        assertThrows(AwsException.class, () -> source.retrieve(profile, VERSION));
        verify(s3, never()).getObject(anyString(), anyString(), any());
    }

    @Test
    void anS3UriCannotAccessAnotherPartition() {
        when(s3.bucketPartition(BUCKET)).thenReturn("aws-cn");
        assertThrows(AwsException.class, () -> source.retrieve(profile, VERSION));
        verify(s3, never()).getObject(anyString(), anyString(), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"retrieval", "arn:aws:iam::111111111111:user/retrieval",
            "arn:aws:iam:::role/retrieval", "arn:aws:iam:us-east-1:111111111111:role/retrieval"})
    void requiresARetrievalRoleForS3Profiles(String role) {
        profile.setRetrievalRoleArn(role);
        assertThrows(AwsException.class, () -> AppConfigS3Source.validateProfile(profile));
    }
}
