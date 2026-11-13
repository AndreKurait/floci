package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ses.model.Contact;
import io.github.hectorvent.floci.services.ses.model.ContactList;
import io.github.hectorvent.floci.services.ses.model.ListManagementOptions;
import io.github.hectorvent.floci.services.ses.model.Topic;
import io.github.hectorvent.floci.testing.ManualClock;
import io.github.hectorvent.floci.testing.ParkingStore;
import io.github.hectorvent.floci.testing.ThreadStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the extracted contact domain. New facet: a multi-store service
 * (contact lists + contacts) constructed with just its two stores and a clock — the two SesService
 * constructor arguments collapse into one. Also exercises the list-management behaviour the send
 * path calls into.
 */
class SesContactServiceTest {

    private static final String REGION = "us-east-1";
    private static final String LIST = "news";
    private SesContactService service;

    @BeforeEach
    void setUp() {
        service = new SesContactService(new InMemoryStorage<>(), new InMemoryStorage<>(), Clock.systemUTC());
        service.createContactList(LIST, "desc",
                List.of(new Topic("Sports", "Sports", "OPT_OUT", "d")), List.of(), REGION);
    }

    @Test
    void secondContactList_hitsOneListLimit() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createContactList("other", "d", List.of(), List.of(), REGION));
        assertEquals("BadRequestException", e.getErrorCode());
    }

    @Test
    void createContact_thenGet_roundTrips() {
        service.createContact(LIST, "a@example.com", List.of(), false, null, REGION);
        assertEquals("a@example.com", service.getContact(LIST, "a@example.com", REGION).contact().getEmailAddress());
    }

    @Test
    void deleteContactList_purgesContacts() {
        service.createContact(LIST, "a@example.com", List.of(), false, null, REGION);
        service.deleteContactList(LIST, REGION);
        // The list — and its contacts — are gone.
        assertThrows(AwsException.class, () -> service.listContacts(LIST, REGION));
    }

    @Test
    void getOrAutoCreateContact_createsOnSend() {
        ContactList list = service.getContactList(LIST, REGION);
        Contact created = service.getOrAutoCreateContact(list, "auto@example.com", REGION);
        assertEquals("auto@example.com", created.getEmailAddress());
        assertTrue(service.getContact(LIST, "auto@example.com", REGION).contact() != null);
    }

    @Test
    void unsubscribeAll_thenListManagementOptedOut() {
        ContactList list = service.getContactList(LIST, REGION);
        service.unsubscribeContact(LIST, "bye@example.com", null, REGION);
        Contact c = service.getContact(LIST, "bye@example.com", REGION).contact();
        assertTrue(service.isListManagementOptedOut(c, list, null));
    }

    @Test
    void collectListManagementOptOuts_usesInjectedExtractor_flagsOptedOut() {
        // Sports defaults to OPT_OUT (see setUp), so an auto-created contact is opted out of it.
        Map<String, String> optOuts = service.collectListManagementOptOuts(
                List.of("Alice <alice@example.com>", "", "Bob <bob@example.com>"),
                new ListManagementOptions(LIST, "Sports"), REGION,
                address -> address.substring(address.indexOf('<') + 1, address.indexOf('>')));
        // The ORIGINAL envelope strings key the result; the extractor only feeds the contact lookup.
        assertEquals(Map.of("Alice <alice@example.com>", SesRecipientEvents.REASON_LIST_OPT_OUT,
                        "Bob <bob@example.com>", SesRecipientEvents.REASON_LIST_OPT_OUT),
                optOuts);

        AwsException e = assertThrows(AwsException.class, () -> service.collectListManagementOptOuts(
                List.of("alice@example.com"), new ListManagementOptions(LIST, "ghost-topic"),
                REGION, address -> address));
        assertEquals("Topic ghost-topic does not exist in contact list news.", e.getMessage());

        assertTrue(service.collectListManagementOptOuts(
                List.of("alice@example.com"), null, REGION, address -> address).isEmpty());
    }

    @Test
    void contactListTimestampsComeFromTheInjectedClock() {
        Instant createdAt = Instant.parse("2031-03-04T05:06:07Z");
        ManualClock clock = new ManualClock(createdAt);
        SesContactService timed = new SesContactService(new InMemoryStorage<>(), new InMemoryStorage<>(), clock);

        ContactList created = timed.createContactList(LIST, "desc", List.of(), List.of(), REGION);
        assertEquals(createdAt, created.getCreatedTimestamp());
        assertEquals(createdAt, created.getLastUpdatedTimestamp());

        clock.advance(Duration.ofHours(36));
        ContactList updated = timed.updateContactList(LIST, "changed", true, null, REGION);
        assertEquals(createdAt, updated.getCreatedTimestamp());
        assertEquals(createdAt.plus(Duration.ofHours(36)), updated.getLastUpdatedTimestamp());
    }

    @Test
    void anUpdateRacingADeleteDoesNotResurrectTheContactList() throws Exception {
        ParkingStore<ContactList> lists = new ParkingStore<>();
        SesContactService racing = new SesContactService(lists, new InMemoryStorage<>(), Clock.systemUTC());
        racing.createContactList(LIST, "desc", List.of(), List.of(), REGION);

        // The update has read the list and is about to write it back.
        lists.parkNextRead();
        Thread update = new Thread(() -> racing.updateContactList(LIST, "changed", true, null, REGION));
        update.start();
        assertTrue(lists.awaitParked(), "the update never reached its read");
        Thread delete = new Thread(() -> racing.deleteContactList(LIST, REGION));
        delete.start();
        // Serialized, the delete is blocked behind the update; unserialized, it has already finished.
        ThreadStates.awaitBlockedOrDone(delete);
        lists.release();
        update.join(5_000);
        delete.join(5_000);

        AwsException gone = assertThrows(AwsException.class, () -> racing.getContactList(LIST, REGION));
        assertEquals("NotFoundException", gone.getErrorCode());
    }
}
