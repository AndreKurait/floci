package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeDeployAgentCommandsTest {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String HOST = "arn:aws:ec2:us-east-1:111111111111:instance/i-agent";
    private static final Duration POLL_WAIT = Duration.ofSeconds(60);
    private static final Duration REDELIVERY = Duration.ofSeconds(5);
    private final TestTime time = TestTime.create();
    private final TimelineTimer timer = new TimelineTimer(time);
    private CodeDeployAgentCommands mailbox;

    @BeforeEach
    void setUp() {
        mailbox = mailbox(Duration.ZERO, Duration.ZERO);
    }

    @AfterEach
    void stopPolling() {
        mailbox.shutdown();
    }

    private CodeDeployAgentCommands mailbox(Duration pollWait, Duration redelivery) {
        return new CodeDeployAgentCommands(new ObjectMapper(), signer(), pollWait, redelivery);
    }

    /** The production poll wait and redelivery interval, on the test's timeline. */
    private CodeDeployAgentCommands timelineMailbox() {
        return new CodeDeployAgentCommands(new ObjectMapper(), signer(), POLL_WAIT, REDELIVERY, timer);
    }

    private static CodeDeploySpecificationSigner signer() {
        CodeDeploySpecificationSigner signer = mock(CodeDeploySpecificationSigner.class);
        when(signer.sign(anyString())).thenAnswer(call -> "signed:" + call.getArgument(0));
        return signer;
    }

    @Test
    void automaticLaunchesCanOverlapTheGroupButNeverItsExistingHost() {
        CodeDeployAgentCommands.Session user = open("d-user", HOST);
        String launched = HOST + "-new";
        CodeDeployAgentCommands.Session launch = mailbox.open(
                ACCOUNT, REGION, "d-launch", "group", List.of(launched), Map.of(), false);
        assertThrows(AwsException.class, () -> mailbox.open(
                ACCOUNT, REGION, "d-overlap", "group", List.of(HOST), Map.of(), false));
        assertThrows(AwsException.class, () -> mailbox.open(
                ACCOUNT, REGION, "d-duplicate", "group", List.of(launched), Map.of(), false));
        mailbox.close(user);
        assertThrows(AwsException.class, () -> mailbox.open(
                ACCOUNT, REGION, "d-user-overlap", "group", List.of(launched), Map.of()));
        CodeDeployAgentCommands.Session nextUser = open("d-user-next", HOST);
        mailbox.close(launch);
        assertThrows(AwsException.class, () -> open("d-third-user", HOST + "-third"));
        mailbox.close(nextUser);
        mailbox.open(ACCOUNT, REGION, "d-after", "group", List.of(launched), Map.of());
    }

    @Test
    void agentAcknowledgementAndCompletionDriveTheResultAndRetriesRemainIdempotent() {
        CodeDeployAgentCommands.Session session = open("d-first", HOST);
        CodeDeployAgentCommands.Ticket ticket = mailbox.enqueue(session, HOST, "DownloadBundle");
        CompletionStage<CodeDeployAgentCommands.Result> result = ticket.result();
        Map<String, Object> polled = mailbox.poll(ACCOUNT, REGION, HOST);
        String identifier = identifier(polled);
        assertEquals(polled, mailbox.poll(ACCOUNT, REGION, HOST));
        assertFalse(result.toCompletableFuture().isDone());
        assertFalse(ticket.accepted().toCompletableFuture().isDone());

        assertEquals(Map.of("CommandStatus", "InProgress"), mailbox.acknowledge(ACCOUNT, REGION, identifier));
        assertTrue(ticket.accepted().toCompletableFuture().isDone());
        assertEquals(Map.of("CommandStatus", "InProgress"), mailbox.acknowledge(ACCOUNT, REGION, identifier));
        assertEquals(Map.of(), mailbox.poll(ACCOUNT, REGION, HOST));
        assertFalse(result.toCompletableFuture().isDone());

        mailbox.complete(ACCOUNT, REGION, identifier, "Succeeded", "downloaded");
        mailbox.complete(ACCOUNT, REGION, identifier, "Succeeded", "retry");
        assertEquals(new CodeDeployAgentCommands.Result("Succeeded", "downloaded"),
                result.toCompletableFuture().join());
        assertEquals(Map.of("CommandStatus", "Succeeded"), mailbox.update(ACCOUNT, REGION, identifier));
        assertThrows(AwsException.class, () -> mailbox.complete(ACCOUNT, REGION, identifier, "Failed", "late"));
        assertEquals("Succeeded", result.toCompletableFuture().join().status());
    }

    @Test
    void commandAndSpecificationReadsStayInTheDeploymentAccountRegionAndHost() {
        CodeDeployAgentCommands.Session session = open("d-scope", HOST);
        mailbox.enqueue(session, HOST, "Install");
        String identifier = identifier(mailbox.poll(ACCOUNT, REGION, HOST));
        assertEquals(Map.of(), mailbox.poll("222222222222", REGION, HOST));
        assertEquals(Map.of(), mailbox.poll(ACCOUNT, "us-west-2", HOST));
        assertThrows(AwsException.class, () -> mailbox.acknowledge("222222222222", REGION, identifier));
        assertThrows(AwsException.class, () -> mailbox.complete(ACCOUNT, "us-west-2", identifier, "Succeeded", ""));
        assertThrows(AwsException.class, () -> mailbox.specification(ACCOUNT, REGION, "d-scope", "another-host"));
        assertThrows(AwsException.class, () -> mailbox.specification("222222222222", REGION, "d-scope", HOST));
        assertThrows(AwsException.class, () -> mailbox.specification(ACCOUNT, "us-west-2", "d-scope", HOST));
        assertEquals("CodeDeploy", mailbox.specification(ACCOUNT, REGION, "d-scope", HOST).get("DeploymentSystem"));
        assertEquals(Map.of("CommandStatus", "Pending"), mailbox.update(ACCOUNT, REGION, identifier));
    }

    @Test
    void aCompletionBeforeAcknowledgementCannotAdvanceTheDeployment() {
        CodeDeployAgentCommands.Session session = open("d-unacknowledged", HOST);
        CompletionStage<CodeDeployAgentCommands.Result> result = mailbox.enqueue(session, HOST, "Install").result();
        String identifier = identifier(mailbox.poll(ACCOUNT, REGION, HOST));
        assertThrows(AwsException.class, () -> mailbox.complete(ACCOUNT, REGION, identifier, "Succeeded", ""));
        assertThrows(AwsException.class, () -> mailbox.complete(ACCOUNT, REGION, identifier, "InProgress", ""));
        assertFalse(result.toCompletableFuture().isDone());
        mailbox.acknowledge(ACCOUNT, REGION, identifier);
        mailbox.complete(ACCOUNT, REGION, identifier, "Failed", "{\"error_code\":3}");
        assertEquals(new CodeDeployAgentCommands.Result("Failed", "{\"error_code\":3}"),
                result.toCompletableFuture().join());
    }

    @Test
    void cancellingQueuedWorkPreventsALateAcknowledgementFromStartingIt() {
        CodeDeployAgentCommands.Session session = open("d-pending-stop", HOST);
        CodeDeployAgentCommands.Ticket ticket = mailbox.enqueue(session, HOST, "Install");
        String command = identifier(mailbox.poll(ACCOUNT, REGION, HOST));

        assertTrue(mailbox.cancelPending(session, HOST));
        assertEquals(Map.of("CommandStatus", "Failed"), mailbox.acknowledge(ACCOUNT, REGION, command));
        assertTrue(ticket.accepted().toCompletableFuture().isCompletedExceptionally());
        assertCancelled(ticket.result());
        assertEquals(Map.of(), mailbox.poll(ACCOUNT, REGION, HOST));
        assertThrows(AwsException.class, () -> mailbox.complete(ACCOUNT, REGION, command, "Succeeded", ""));
    }

    @Test
    void acknowledgementBeforeCancellationPreservesTheActiveCommandAndItsCompletion() {
        CodeDeployAgentCommands.Session session = open("d-active-stop", HOST);
        CodeDeployAgentCommands.Ticket ticket = mailbox.enqueue(session, HOST, "Install");
        String command = identifier(mailbox.poll(ACCOUNT, REGION, HOST));
        mailbox.acknowledge(ACCOUNT, REGION, command);

        assertFalse(mailbox.cancelPending(session, HOST));
        assertEquals(Map.of("CommandStatus", "InProgress"), mailbox.update(ACCOUNT, REGION, command));
        assertFalse(ticket.result().toCompletableFuture().isDone());
        mailbox.complete(ACCOUNT, REGION, command, "Succeeded", "completed after stop");
        assertEquals(new CodeDeployAgentCommands.Result("Succeeded", "completed after stop"),
                ticket.result().toCompletableFuture().join());
        assertFalse(mailbox.cancelPending(session, HOST));
    }

    @Test
    void cancellationReleasesTheHostAndRejectsLateCompletionFromThePreviousDeployment() {
        CodeDeployAgentCommands.Session first = open("d-stopped", HOST);
        CompletionStage<CodeDeployAgentCommands.Result> pending = mailbox.enqueue(first, HOST, "Install").result();
        String previous = identifier(mailbox.poll(ACCOUNT, REGION, HOST));
        mailbox.acknowledge(ACCOUNT, REGION, previous);
        mailbox.close(first);
        mailbox.close(first);
        assertCancelled(pending);
        assertEquals(Map.of(), mailbox.poll(ACCOUNT, REGION, HOST));
        assertThrows(IllegalStateException.class, () -> mailbox.enqueue(first, HOST, "AfterInstall"));

        CodeDeployAgentCommands.Session next = open("d-recovery", HOST);
        CompletionStage<CodeDeployAgentCommands.Result> recovery = mailbox.enqueue(next, HOST, "Install").result();
        assertNotEquals(previous, identifier(mailbox.poll(ACCOUNT, REGION, HOST)));
        assertThrows(AwsException.class, () -> mailbox.complete(ACCOUNT, REGION, previous, "Succeeded", "stale"));
        assertFalse(recovery.toCompletableFuture().isDone());
    }

    @Test
    void aConflictingGroupDeploymentDoesNotReserveItsOtherTargets() {
        open("d-busy", HOST);
        assertThrows(AwsException.class, () -> mailbox.open(ACCOUNT, REGION, "d-conflict", "group",
                List.of("free-host", HOST), Map.of("DeploymentId", "d-conflict")));
        CodeDeployAgentCommands.Session independent = mailbox.open(ACCOUNT, REGION, "d-independent",
                "another-group", List.of("free-host"), Map.of("DeploymentId", "d-independent"));
        mailbox.enqueue(independent, "free-host", "DownloadBundle");
        assertTrue(mailbox.poll(ACCOUNT, REGION, "free-host").containsKey("HostCommand"));
    }

    @Test
    void independentGroupsCanDeployToTheSameHostWithoutCrossingCommandResults() {
        CodeDeployAgentCommands.Session first = open("d-first", HOST);
        CodeDeployAgentCommands.Session second = mailbox.open(ACCOUNT, REGION, "d-second", "another-group",
                List.of(HOST), Map.of("DeploymentId", "d-second"));
        CodeDeployAgentCommands.Ticket firstTicket = mailbox.enqueue(first, HOST, "Install");
        CodeDeployAgentCommands.Ticket secondTicket = mailbox.enqueue(second, HOST, "Install");
        String firstId = identifier(mailbox.poll(ACCOUNT, REGION, HOST));
        mailbox.acknowledge(ACCOUNT, REGION, firstId);
        String secondId = identifier(mailbox.poll(ACCOUNT, REGION, HOST));
        assertNotEquals(firstId, secondId);
        mailbox.acknowledge(ACCOUNT, REGION, secondId);
        mailbox.complete(ACCOUNT, REGION, secondId, "Succeeded", "{}");
        assertFalse(firstTicket.result().toCompletableFuture().isDone());
        assertEquals("Succeeded", secondTicket.result().toCompletableFuture().join().status());
        mailbox.close(second);
        mailbox.complete(ACCOUNT, REGION, firstId, "Failed", "{}");
        assertEquals("Failed", firstTicket.result().toCompletableFuture().join().status());
    }

    @Test
    void theSpecificationIsCapturedBeforeTheCallerChangesItsInput() {
        Map<String, Object> revision = new HashMap<>(Map.of("Key", "original.zip"));
        mailbox.open(ACCOUNT, REGION, "d-snapshot", "group", List.of(HOST), Map.of("Revision", revision));
        revision.put("Key", "changed.zip");
        Map<?, ?> specification = (Map<?, ?>) mailbox.specification(ACCOUNT, REGION, "d-snapshot", HOST)
                .get("DeploymentSpecification");
        Map<?, ?> envelope = (Map<?, ?>) specification.get("GenericEnvelope");
        assertEquals("PKCS7/JSON", envelope.get("Format"));
        assertEquals("signed:{\"Revision\":{\"Key\":\"original.zip\"}}", envelope.get("Payload"));
    }

    @Test
    void onlyTheSelectedHostCanReceiveTheNextCommandAfterItsPreviousResult() {
        CodeDeployAgentCommands.Session session = open("d-order", HOST);
        mailbox.enqueue(session, HOST, "DownloadBundle");
        assertThrows(IllegalStateException.class, () -> mailbox.enqueue(session, HOST, "Install"));
        assertThrows(IllegalArgumentException.class, () -> mailbox.enqueue(session, "another-host", "Install"));
        String previous = identifier(mailbox.poll(ACCOUNT, REGION, HOST));
        mailbox.acknowledge(ACCOUNT, REGION, previous);
        mailbox.complete(ACCOUNT, REGION, previous, "Succeeded", "");
        mailbox.enqueue(session, HOST, "Install");
        assertNotEquals(previous, identifier(mailbox.poll(ACCOUNT, REGION, HOST)));
    }

    @Test
    void shutdownUnblocksPendingWorkAndClearsCommandAccess() {
        CodeDeployAgentCommands.Session session = open("d-shutdown", HOST);
        CodeDeployAgentCommands.Ticket ticket = mailbox.enqueue(session, HOST, "Install");
        CompletionStage<CodeDeployAgentCommands.Result> result = ticket.result();
        String identifier = identifier(mailbox.poll(ACCOUNT, REGION, HOST));
        mailbox.shutdown();
        assertCancelled(result);
        assertTrue(ticket.accepted().toCompletableFuture().isCompletedExceptionally());
        assertEquals(Map.of(), mailbox.poll(ACCOUNT, REGION, HOST));
        assertThrows(AwsException.class, () -> mailbox.acknowledge(ACCOUNT, REGION, identifier));
    }

    @Test
    void aPollBeforeDeploymentAdmissionWakesOnlyForItsOwnAccountAndHost() throws Exception {
        mailbox = timelineMailbox();
        CompletableFuture<Map<String, Object>> other = waitingPoll("222222222222", HOST);
        CompletableFuture<Map<String, Object>> selected = waitingPoll(ACCOUNT, HOST);
        timer.forgetWaits();
        CodeDeployAgentCommands.Session session = open("d-new", HOST);
        mailbox.enqueue(session, HOST, "DownloadBundle");
        assertTrue(selected.get(2, TimeUnit.SECONDS).containsKey("HostCommand"));
        // The other account's poll was woken too; it is back in its wait before the check.
        timer.awaitWaitOn(mailbox);
        assertFalse(other.isDone());
        mailbox.shutdown();
        assertEquals(Map.of(), other.get(2, TimeUnit.SECONDS));
    }

    @Test
    void acknowledgementAndTheNextPhaseWakeAPollWithoutRedeliveringTheBusyCommand() throws Exception {
        mailbox = timelineMailbox();
        CodeDeployAgentCommands.Session session = open("d-phases", HOST);
        mailbox.enqueue(session, HOST, "DownloadBundle");
        String first = identifier(mailbox.poll(ACCOUNT, REGION, HOST));
        CompletableFuture<Map<String, Object>> next = waitingPoll(ACCOUNT, HOST);
        timer.forgetWaits();
        mailbox.acknowledge(ACCOUNT, REGION, first);
        timer.awaitWaitOn(mailbox);
        time.advance(REDELIVERY);
        assertFalse(next.isDone(), "An acknowledged command is not delivered again");
        mailbox.complete(ACCOUNT, REGION, first, "Succeeded", "");
        mailbox.enqueue(session, HOST, "Install");
        Map<String, Object> response = next.get(2, TimeUnit.SECONDS);
        assertNotEquals(first, identifier(response));
        assertEquals("Install", ((Map<?, ?>) response.get("HostCommand")).get("CommandName"));
    }

    @Test
    void aLostResponseBecomesAvailableAgainWithoutAcknowledgingTheCommand() throws Exception {
        mailbox = timelineMailbox();
        CodeDeployAgentCommands.Session session = open("d-lost-response", HOST);
        CodeDeployAgentCommands.Ticket ticket = mailbox.enqueue(session, HOST, "Install");
        String first = identifier(mailbox.poll(ACCOUNT, REGION, HOST));
        CompletableFuture<Map<String, Object>> again = waitingPoll(ACCOUNT, HOST);
        time.advance(REDELIVERY.minusMillis(1));
        assertFalse(again.isDone());
        time.advance(Duration.ofMillis(1));
        assertEquals(first, identifier(again.get(2, TimeUnit.SECONDS)));
        assertFalse(ticket.accepted().toCompletableFuture().isDone());
        assertFalse(ticket.result().toCompletableFuture().isDone());
    }

    @Test
    void aLostResponseIsRedeliveredAgainAfterEachRedeliveryInterval() throws Exception {
        mailbox = timelineMailbox();
        CodeDeployAgentCommands.Session session = open("d-lost-twice", HOST);
        mailbox.enqueue(session, HOST, "Install");
        String first = identifier(mailbox.poll(ACCOUNT, REGION, HOST));
        time.advance(REDELIVERY);
        assertEquals(first, identifier(mailbox.poll(ACCOUNT, REGION, HOST)));
        CompletableFuture<Map<String, Object>> third = waitingPoll(ACCOUNT, HOST);
        time.advance(REDELIVERY.minusMillis(1));
        assertFalse(third.isDone());
        time.advance(Duration.ofMillis(1));
        assertEquals(first, identifier(third.get(2, TimeUnit.SECONDS)));
    }

    @Test
    void anIdlePollReturnsEmptyExactlyWhenItsPollWaitEnds() throws Exception {
        mailbox = timelineMailbox();
        CompletableFuture<Map<String, Object>> idle = waitingPoll(ACCOUNT, HOST);
        time.advance(POLL_WAIT.minusMillis(1));
        assertFalse(idle.isDone());
        time.advance(Duration.ofMillis(1));
        assertEquals(Map.of(), idle.get(2, TimeUnit.SECONDS));
    }

    @Test
    void aCommandQueuedLateInThePollWaitIsStillDeliveredToThatPoll() throws Exception {
        mailbox = timelineMailbox();
        CompletableFuture<Map<String, Object>> idle = waitingPoll(ACCOUNT, HOST);
        time.advance(POLL_WAIT.minusMillis(1));
        CodeDeployAgentCommands.Session session = open("d-late", HOST);
        mailbox.enqueue(session, HOST, "Install");
        assertEquals("Install", ((Map<?, ?>) idle.get(2, TimeUnit.SECONDS).get("HostCommand")).get("CommandName"));
    }

    @Test
    void anIdlePollOnTheSystemTimerHasABoundedDeadlineAndInterruptionPreservesTheInterruptFlag() throws Exception {
        Duration timeout = Duration.ofMillis(60);
        mailbox = mailbox(timeout, Duration.ofSeconds(5));
        long started = System.nanoTime();
        assertEquals(Map.of(), mailbox.poll(ACCOUNT, REGION, HOST));
        assertTrue(System.nanoTime() - started >= timeout.toNanos());

        mailbox = mailbox(Duration.ofSeconds(5), Duration.ofSeconds(5));
        CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            assertEquals(Map.of(), mailbox.poll(ACCOUNT, REGION, HOST));
            interrupted.complete(Thread.currentThread().isInterrupted());
        });
        assertTrue(interrupted.get(2, TimeUnit.SECONDS));
        worker.join();
    }

    /** Starts a poll that has nothing to deliver and returns once it is waiting. */
    private CompletableFuture<Map<String, Object>> waitingPoll(String account, String host)
            throws InterruptedException {
        CompletableFuture<Map<String, Object>> response = new CompletableFuture<>();
        timer.forgetWaits();
        Thread.ofVirtual().start(() -> response.complete(mailbox.poll(account, REGION, host)));
        timer.awaitWaitOn(mailbox);
        assertFalse(response.isDone(), "The poll must wait for eligible work");
        return response;
    }

    private CodeDeployAgentCommands.Session open(String execution, String host) {
        return mailbox.open(ACCOUNT, REGION, execution, "group", List.of(host), Map.of("DeploymentId", execution));
    }

    private static String identifier(Map<String, Object> response) {
        return (String) ((Map<?, ?>) response.get("HostCommand")).get("HostCommandIdentifier");
    }

    private static void assertCancelled(CompletionStage<CodeDeployAgentCommands.Result> result) {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> result.toCompletableFuture().join());
        assertInstanceOf(CancellationException.class, failure.getCause() == null ? failure : failure.getCause());
    }
}
