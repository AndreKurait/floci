package io.floci.aws.services.firehose;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.firehose.model.DeliveryStreamDescription;
import io.floci.aws.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.floci.aws.services.firehose.model.Record;
import io.floci.aws.services.kinesis.KinesisService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.PutObjectOptions;
import io.floci.aws.testing.TestTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When the buffer flusher delivers, on a timeline the test advances: the shipped one-second tick and
 * the buffering intervals AWS uses. {@code FirehoseFlushIntegrationTest} runs the flusher's own thread.
 */
class FirehoseFlushTickTest {

    private TestTime time;
    private S3Service s3Service;
    private EmulatorConfig config;
    private Map<String, Object> backends;

    @BeforeEach
    void setUp() {
        time = TestTime.create();
        s3Service = mock(S3Service.class);
        backends = new HashMap<>();
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().firehose().enabled()).thenReturn(true);
        when(config.services().firehose().tickIntervalSeconds()).thenReturn(1L);
        when(config.services().firehose().flushRecordCount()).thenReturn(0);
    }

    @Test
    void aRecordIsDeliveredByTheFirstTickAtOrAfterItsBufferingInterval() {
        FirehoseService service = startedService();
        service.createDeliveryStream("hinted", bufferingFor(60));
        time.advance(Duration.ofMillis(400));
        service.putRecord("hinted", record("held"));

        // Buffered 400 ms after a tick, so due at 60.4 s: the tick at 60 s is too early.
        time.advance(Duration.ofSeconds(60).plusMillis(599));
        verifyDeliveries(0);

        time.advance(Duration.ofMillis(1));
        verifyDeliveries(1);
    }

    @Test
    void aRecordBufferedOnATickIsDeliveredExactlyOneBufferingIntervalLater() {
        FirehoseService service = startedService();
        service.createDeliveryStream("hinted", bufferingFor(60));
        service.putRecord("hinted", record("held"));

        time.advance(Duration.ofSeconds(60).minusMillis(1));
        verifyDeliveries(0);

        time.advance(Duration.ofMillis(1));
        verifyDeliveries(1);
    }

    @Test
    void aStreamWithoutHintsHoldsItsRecordsForTheDefaultFiveMinutes() {
        FirehoseService service = startedService();
        service.createDeliveryStream("plain", null);
        service.putRecord("plain", record("held"));

        time.advance(Duration.ofSeconds(300).minusMillis(1));
        verifyDeliveries(0);

        time.advance(Duration.ofMillis(1));
        verifyDeliveries(1);
    }

    @Test
    void aTickThatFailsDoesNotStopTheNextOne() {
        // The first tick cannot read the delivery streams; the ones after it can.
        AccountAwareStorageBackend<Object> streams = spy(AccountAwareStorageBackend.inMemory("000000000000"));
        doThrow(new IllegalStateException("storage is briefly unavailable")).doCallRealMethod()
                .when(streams).scanAllAccountEntries(any());
        backends.put("firehose/streams.json", streams);
        FirehoseService service = startedService();
        service.createDeliveryStream("hinted", bufferingFor(60));
        service.putRecord("hinted", record("held"));

        time.advance(Duration.ofSeconds(1));
        verify(streams, times(1)).scanAllAccountEntries(any());
        verifyDeliveries(0);

        time.advance(Duration.ofSeconds(59));
        verify(streams, times(60)).scanAllAccountEntries(any());
        verifyDeliveries(1);
    }

    @Test
    void aFlusherDisabledByConfigurationNeverDelivers() {
        when(config.services().firehose().enabled()).thenReturn(false);
        FirehoseService service = startedService();
        service.createDeliveryStream("plain", null);
        service.putRecord("plain", record("held"));

        time.advance(Duration.ofHours(1));

        verifyDeliveries(0);
    }

    private FirehoseService startedService() {
        // A backend per store, keyed by file name, as the real factory reuses backends by path.
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> backends.computeIfAbsent(
                        invocation.getArgument(0) + "/" + invocation.getArgument(1),
                        k -> AccountAwareStorageBackend.inMemory("000000000000")));
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        FirehoseService service = new FirehoseService(storageFactory, s3Service,
                new KinesisService(storageFactory, regionResolver), regionResolver, time.clock(), config,
                mock(FirehoseParquetConverter.class), mock(FirehoseLambdaTransformer.class), null, null,
                time.scheduler());
        service.onStart(null);
        return service;
    }

    private static S3Destination bufferingFor(int intervalSeconds) {
        S3Destination s3 = new S3Destination();
        s3.setBucketArn("arn:aws:s3:::tick-bucket");
        DeliveryStreamDescription.BufferingHints hints = new DeliveryStreamDescription.BufferingHints();
        hints.setSizeInMBs(5);
        hints.setIntervalInSeconds(intervalSeconds);
        s3.setBufferingHints(hints);
        return s3;
    }

    private static Record record(String data) {
        return new Record(data.getBytes(StandardCharsets.UTF_8));
    }

    private void verifyDeliveries(int count) {
        verify(s3Service, count == 0 ? never() : times(count)).putObject(anyString(), anyString(),
                any(byte[].class), anyString(), anyMap(), any(PutObjectOptions.class));
    }
}
