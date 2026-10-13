package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the rule that an action a Query handler dispatches is an action routing sends to that
 * handler. The two lists are maintained by hand in different files, so they drift: #5032 was 28
 * IAM operations that had drifted out, and the same gap exists today for most other Query
 * services, which is what this test makes visible and stops growing.
 *
 * <p>Routing only decides anything when a request arrives without a usable credential scope,
 * because otherwise the service comes from the SigV4 scope. On that path there is the action name
 * and the {@code Version} the request declares, and {@code inferServiceFromAction} falls back to
 * SQS, so a missing name answers {@code UnsupportedOperation} in the SQS namespace rather than
 * failing visibly. {@code AwsQueryServiceResolver} backs {@code IamEnforcementFilter}
 * through the same code, so the same operation is also authorized under the wrong namespace, which
 * is the part worth a test.
 *
 * <p>Each action is routed with its own service's {@code Version}, as any Query client sends it,
 * and again without one, as a hand-rolled request may leave it out. Without it a name two services
 * share goes to whichever is tried first, so that path is only held to reaching a service that
 * dispatches the name.
 *
 * <p>The known gaps live in {@code src/test/resources/query-routing-known-gaps.tsv} rather than
 * here, so that fixing one is a line removed from a data file. The list is checked both ways: a new
 * gap fails {@link #everyDispatchedActionRoutesToItsOwnService} or
 * {@link #withoutAVersionEveryActionReachesAServiceThatDispatchesIt}, and a gap that has been fixed
 * without delisting fails {@link #theKnownGapListHasNoStaleEntries}.
 */
class AwsQueryActionRoutingParityTest {

    /** The handler whose dispatch switch defines what a service accepts, and the service it is. */
    private record QueryHandler(String service, Path source, String switchVariable) {

        private QueryHandler(String service, String path) {
            this(service, Path.of(path), "action");
        }
    }

    /** A heading in the gap file: what it labels, and the three counts it claims for its rows. */
    private record GapHeading(String label, String line, int total, int unrouted, int claimed) {

        private static GapHeading of(String label, Matcher heading, int firstCount) {
            return new GapHeading(label, heading.group(),
                    Integer.parseInt(heading.group(firstCount)),
                    Integer.parseInt(heading.group(firstCount + 1)),
                    Integer.parseInt(heading.group(firstCount + 2)));
        }

        /** Records a mismatch when the unrouted and claimed rows counted under it disagree with it. */
        private void check(int[] rows, List<String> wrong) {
            int rowTotal = rows[0] + rows[1];
            if (total != rowTotal || unrouted != rows[0] || claimed != rows[1]) {
                wrong.add("the " + label + " heading says " + total + " (" + unrouted + " unrouted, "
                        + claimed + " claimed) but the rows under it are " + rowTotal + " ("
                        + rows[0] + " unrouted, " + rows[1] + " claimed): " + line);
            }
        }
    }

    private static final String HANDLERS_ROOT = "src/main/java/io/github/hectorvent/floci/services/";

    /**
     * Every Query handler in the tree, with the service its actions must reach.
     *
     * <p>Several entries share a service on purpose. DocDB and Neptune speak the RDS wire shape and
     * their action names are indistinguishable from RDS's, so by name they can only resolve to
     * {@code rds}. ELB Classic and ELBv2 both serve {@code elasticloadbalancing}, which is why
     * their sets are checked as one and why {@code isElbClassicRequest} exists to split them on
     * {@code Version} afterwards.
     *
     * <p>SQS is listed even though it is the fallback routing ends in, because being the default
     * does not make it safe: a name an earlier service claims never reaches it.
     */
    private static final List<QueryHandler> HANDLERS = List.of(
            // StsQueryHandler sits in the iam package, beside IamQueryHandler.
            new QueryHandler("sts", HANDLERS_ROOT + "iam/StsQueryHandler.java"),
            new QueryHandler("iam", HANDLERS_ROOT + "iam/IamQueryHandler.java"),
            new QueryHandler("sns", HANDLERS_ROOT + "sns/SnsQueryHandler.java"),
            new QueryHandler("elasticache", HANDLERS_ROOT + "elasticache/ElastiCacheQueryHandler.java"),
            new QueryHandler("elasticache", HANDLERS_ROOT + "elasticache/ElastiCacheServerlessQueryHandler.java"),
            new QueryHandler("rds", HANDLERS_ROOT + "rds/RdsQueryHandler.java"),
            new QueryHandler("rds", HANDLERS_ROOT + "docdb/DocDbQueryHandler.java"),
            new QueryHandler("rds", HANDLERS_ROOT + "neptune/NeptuneQueryHandler.java"),
            // CloudWatch normalises the action before dispatching, so its switch reads a different
            // variable. Named here rather than guessed at by the parser.
            new QueryHandler("monitoring",
                    Path.of(HANDLERS_ROOT + "cloudwatch/metrics/CloudWatchMetricsQueryHandler.java"),
                    "normalizedAction"),
            new QueryHandler("cloudformation",
                    HANDLERS_ROOT + "cloudformation/CloudFormationQueryHandler.java"),
            new QueryHandler("email", HANDLERS_ROOT + "ses/SesQueryHandler.java"),
            new QueryHandler("ec2", HANDLERS_ROOT + "ec2/Ec2QueryHandler.java"),
            new QueryHandler("elasticloadbalancing", HANDLERS_ROOT + "elbv2/ElbV2QueryHandler.java"),
            new QueryHandler("elasticloadbalancing", HANDLERS_ROOT + "elb/ElbClassicQueryHandler.java"),
            new QueryHandler("autoscaling", HANDLERS_ROOT + "autoscaling/AutoScalingQueryHandler.java"),
            new QueryHandler("elasticbeanstalk",
                    HANDLERS_ROOT + "elasticbeanstalk/ElasticBeanstalkQueryHandler.java"),
            new QueryHandler("redshift", HANDLERS_ROOT + "redshift/RedshiftQueryHandler.java"),
            new QueryHandler("sqs", HANDLERS_ROOT + "sqs/SqsQueryHandler.java"));

    /**
     * The {@code Version} each service's clients send, which is the {@code apiVersion} in its
     * botocore model. Stated here rather than read back from the controller, so a wrong version
     * there fails this test instead of agreeing with it. ELBv2's stands for both load balancing
     * APIs, since they route to one service by either version and are only split afterwards.
     */
    private static final Map<String, String> API_VERSIONS = Map.ofEntries(
            Map.entry("sts", "2011-06-15"),
            Map.entry("iam", "2010-05-08"),
            Map.entry("sns", "2010-03-31"),
            Map.entry("sqs", "2012-11-05"),
            Map.entry("elasticache", "2015-02-02"),
            Map.entry("rds", "2014-10-31"),
            Map.entry("monitoring", "2010-08-01"),
            Map.entry("cloudformation", "2010-05-15"),
            Map.entry("email", "2010-12-01"),
            Map.entry("ec2", "2016-11-15"),
            Map.entry("elasticloadbalancing", "2015-12-01"),
            Map.entry("autoscaling", "2011-01-01"),
            Map.entry("elasticbeanstalk", "2010-12-01"),
            Map.entry("redshift", "2012-12-01"));

    private static final Path KNOWN_GAPS = Path.of("src/test/resources/query-routing-known-gaps.tsv");

    /**
     * A {@code case} arm's label list, which may carry several names and may wrap across lines.
     * Only quoted labels are taken, so a {@code case} on an enum constant contributes nothing.
     */
    private static final Pattern CASE_LABELS =
            Pattern.compile("case\\s+((?:\"[A-Za-z0-9]+\"\\s*,?\\s*)+)\\s*(?:->|:)");
    private static final Pattern QUOTED_LABEL = Pattern.compile("\"([A-Za-z0-9]+)\"");

    /** {@code # 189 entries: 171 unrouted, 18 claimed by another service.} */
    private static final Pattern TOTAL_HEADING = Pattern.compile(
            "^#\\s*(\\d+) entries: (\\d+) unrouted, (\\d+) claimed by another service\\.?$");
    /** {@code # autoscaling: 17 (13 unrouted, 4 claimed elsewhere)} */
    private static final Pattern SERVICE_HEADING = Pattern.compile(
            "^#\\s*([a-z0-9-]+): (\\d+) \\((\\d+) unrouted, (\\d+) claimed elsewhere\\)$");

    @Test
    void everyDispatchedActionRoutesToItsOwnService() throws IOException {
        Set<String> known = knownGaps();
        List<String> unexpected = new ArrayList<>();

        for (QueryHandler handler : HANDLERS) {
            for (String action : dispatchedActions(handler)) {
                String reached = reached(handler, action);
                if (!handler.service().equals(reached)
                        && !known.contains(gapKey(handler.service(), action, reached))) {
                    unexpected.add(handler.service() + " dispatches " + action
                            + " but a request for it reaches " + reached);
                }
            }
        }

        assertTrue(unexpected.isEmpty(),
                "An action a handler dispatches has to route to that handler, or a request without "
                        + "a credential scope answers in the wrong namespace and is authorized "
                        + "under it too. Add the name to its service's set in AwsQueryController; "
                        + "a name another service also defines is then told apart by the request's "
                        + "Version. New: " + unexpected);
    }

    /**
     * Without a {@code Version} the name decides alone, and a name two services define goes to the
     * one tried first, which is right for either of them. What is wrong is reaching a service that
     * does not dispatch the name at all, and the check above cannot see it, because a
     * {@code Version} goes straight to the service it names: a set claiming a name its handler
     * lacks, tried ahead of the service that has it, takes every such request.
     */
    @Test
    void withoutAVersionEveryActionReachesAServiceThatDispatchesIt() throws IOException {
        Set<String> known = knownGaps();
        Map<String, Set<String>> dispatched = dispatchedByService();
        List<String> unexpected = new ArrayList<>();

        for (QueryHandler handler : HANDLERS) {
            for (String action : dispatchedActions(handler)) {
                String reached = reachedWithoutVersion(action);
                if (!dispatched.getOrDefault(reached, Set.of()).contains(action)
                        && !known.contains(gapKey(handler.service(), action, reached))) {
                    unexpected.add(handler.service() + " dispatches " + action
                            + " but without a Version a request for it reaches " + reached);
                }
            }
        }

        assertTrue(unexpected.isEmpty(),
                "Without a Version an action has to reach a service that dispatches it, or the "
                        + "request answers UnsupportedOperation in a namespace it was never for. "
                        + "A set in AwsQueryController claims a name its handler does not "
                        + "dispatch, ahead of the service that does: " + unexpected);
    }

    /**
     * The other direction, which is what keeps the list honest: once a gap is fixed its line has to
     * go, or the file slowly stops describing the tree and a later regression hides behind a stale
     * entry.
     */
    @Test
    void theKnownGapListHasNoStaleEntries() throws IOException {
        Map<String, Set<String>> dispatched = dispatchedByService();
        Set<String> live = new LinkedHashSet<>();
        for (QueryHandler handler : HANDLERS) {
            for (String action : dispatchedActions(handler)) {
                String reached = reached(handler, action);
                if (!handler.service().equals(reached)) {
                    live.add(gapKey(handler.service(), action, reached));
                }
                String reachedWithoutVersion = reachedWithoutVersion(action);
                if (!dispatched.getOrDefault(reachedWithoutVersion, Set.of()).contains(action)) {
                    live.add(gapKey(handler.service(), action, reachedWithoutVersion));
                }
            }
        }

        List<String> stale = new ArrayList<>(knownGaps());
        stale.removeAll(live);

        assertTrue(stale.isEmpty(),
                "These are listed as known routing gaps but route correctly now, so the lines "
                        + "should come out of " + KNOWN_GAPS + ": " + stale);
    }

    /**
     * A new Query service must be checked too. Without this a handler added next to the others is
     * simply not looked at, which is the shape of the drift this test exists to stop.
     */
    @Test
    void everyQueryHandlerInTheTreeIsRegistered() throws IOException {
        Set<Path> registered = new TreeSet<>();
        for (QueryHandler handler : HANDLERS) {
            registered.add(handler.source().normalize());
        }

        List<String> unregistered = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(Path.of(HANDLERS_ROOT))) {
            tree.filter(path -> path.getFileName().toString().endsWith("QueryHandler.java"))
                    .map(Path::normalize)
                    .filter(path -> !registered.contains(path))
                    .forEach(path -> unregistered.add(path.toString()));
        }

        assertTrue(unregistered.isEmpty(),
                "Every *QueryHandler.java has to be registered in this test with the service its "
                        + "actions must reach, so its dispatch list is compared against routing. "
                        + "Unregistered: " + unregistered);
    }

    /**
     * The headings in the gap file count the rows under them, and a fix that removes rows makes
     * them wrong. They are worth keeping, because the scale and the split between the two kinds of
     * gap are the first thing a reader wants, so the test keeps them honest instead.
     *
     * <p>Every heading is checked, not just the total: a per-service fix is exactly the change that
     * invalidates its own service heading. The headings must also be present, so deleting one
     * cannot make this pass by having nothing left to compare.
     *
     * <p>The file is walked in order, so a service heading is held to the rows physically below it,
     * up to the next heading, rather than to its service's rows wherever they sit. Each row has to
     * sit under its own service's heading, and a service gets one heading, so a misplaced row or a
     * section split in two fails even when every service's overall count still adds up.
     */
    @Test
    void theKnownGapHeadingsCountTheRowsBelowThem() throws IOException {
        List<String> wrong = new ArrayList<>();
        Set<String> headed = new TreeSet<>();
        int[] allRows = new int[2];
        GapHeading total = null;
        GapHeading section = null;
        int[] sectionRows = new int[2];

        for (String raw : Files.readAllLines(KNOWN_GAPS)) {
            String line = raw.strip();
            Matcher totalHeading = TOTAL_HEADING.matcher(line);
            Matcher serviceHeading = SERVICE_HEADING.matcher(line);
            if (totalHeading.matches()) {
                if (total != null) {
                    wrong.add("the total heading appears twice: " + line);
                }
                total = GapHeading.of("total", totalHeading, 1);
            } else if (serviceHeading.matches()) {
                if (section != null) {
                    section.check(sectionRows, wrong);
                }
                section = GapHeading.of(serviceHeading.group(1), serviceHeading, 2);
                sectionRows = new int[2];
                if (!headed.add(section.label())) {
                    wrong.add("the " + section.label() + " heading appears twice, splitting its rows: " + line);
                }
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                String[] columns = line.split("\t");
                int column = "sqs".equals(columns[2]) ? 0 : 1;
                allRows[column]++;
                if (section == null) {
                    wrong.add(line + " comes before any service heading");
                } else {
                    sectionRows[column]++;
                    if (!section.label().equals(columns[0])) {
                        wrong.add(line + " sits under the " + section.label() + " heading, not its own");
                    }
                }
            }
        }
        if (section != null) {
            section.check(sectionRows, wrong);
        }

        if (total == null) {
            wrong.add("the total heading is missing, so nothing states the overall count");
        } else {
            total.check(allRows, wrong);
        }

        assertTrue(wrong.isEmpty(),
                "A heading in " + KNOWN_GAPS + " no longer counts the rows under it, or a row is not "
                        + "under its own service's heading. Removing a gap means updating the heading "
                        + "that counted it, including the total. " + wrong);
    }

    /**
     * Where an unsigned request for the action goes, carrying the handler's own {@code Version} the
     * way every Query client sends it.
     */
    private static String reached(QueryHandler handler, String action) {
        String version = API_VERSIONS.get(handler.service());
        assertTrue(version != null, "No API version listed for " + handler.service());
        return AwsQueryController.inferServiceFromAction(action, version);
    }

    /** Where an unsigned request for the action goes when it carries no {@code Version} at all. */
    private static String reachedWithoutVersion(String action) {
        return AwsQueryController.inferServiceFromAction(action, null);
    }

    /**
     * Every name each service dispatches, across all of its handlers. A service with no Query
     * handler, as Cognito has none, dispatches nothing.
     */
    private static Map<String, Set<String>> dispatchedByService() throws IOException {
        Map<String, Set<String>> dispatched = new HashMap<>();
        for (QueryHandler handler : HANDLERS) {
            dispatched.computeIfAbsent(handler.service(), service -> new TreeSet<>())
                    .addAll(dispatchedActions(handler));
        }
        return dispatched;
    }

    /** One line of the known-gap file: the service, the action, and what it reaches instead. */
    private static String gapKey(String service, String action, String reached) {
        return service + "\t" + action + "\t" + reached;
    }

    private static Set<String> knownGaps() throws IOException {
        Set<String> gaps = new LinkedHashSet<>();
        for (String line : Files.readAllLines(KNOWN_GAPS)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            gaps.add(trimmed);
        }
        return gaps;
    }

    /**
     * The action names a handler dispatches, read from its own dispatch switch and nothing else.
     *
     * <p>Bounded to that one switch on purpose. A handler holds other switches on other strings,
     * for parameter names and for state values, and taking every {@code case} in the file picks
     * those up as actions: the docs generator does exactly that and carries per-service exclusions
     * to undo it.
     */
    private static Set<String> dispatchedActions(QueryHandler handler) throws IOException {
        return dispatchedActions(Files.readString(handler.source()), handler.switchVariable(),
                handler.source().toString());
    }

    /**
     * The same, over source held as a string, so the parser can be tested on its own.
     *
     * <p>Brace counting runs over a masked copy rather than the source, because a {@code }} inside
     * a comment or a string literal would otherwise close the switch early and silently shorten the
     * action list. That is the worst way for this test to fail: a handler with no listed gaps would
     * keep passing both checks while no longer checking anything. Masking comments also means a
     * commented-out {@code case} arm does not count as an action.
     */
    private static Set<String> dispatchedActions(String source, String switchVariable, String where) {
        String masked = maskCommentsAndBracesInLiterals(source);
        Matcher dispatch = Pattern.compile("switch\\s*\\(\\s*" + switchVariable + "\\s*\\)")
                .matcher(masked);
        assertTrue(dispatch.find(),
                where + " has no switch (" + switchVariable + "), so this test cannot tell what it "
                        + "dispatches. Point the registry at the right variable.");

        int open = masked.indexOf('{', dispatch.end());
        assertTrue(open >= 0, "no dispatch switch body in " + where);
        int close = -1;
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            char character = masked.charAt(i);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            }
        }
        assertTrue(close > open, "unbalanced dispatch switch in " + where);

        Set<String> actions = new TreeSet<>();
        Matcher arm = CASE_LABELS.matcher(masked.substring(open, close));
        while (arm.find()) {
            Matcher label = QUOTED_LABEL.matcher(arm.group(1));
            while (label.find()) {
                actions.add(label.group(1));
            }
        }
        return actions;
    }

    /**
     * A copy of the source with comments blanked and braces inside string and character literals
     * replaced by spaces. The copy is the same length as the input, so offsets still line up, and
     * literal contents survive apart from braces, so {@code case "Name"} still reads.
     */
    private static String maskCommentsAndBracesInLiterals(String source) {
        StringBuilder masked = new StringBuilder(source);
        int i = 0;
        while (i < source.length()) {
            char character = source.charAt(i);
            if (character == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    masked.setCharAt(i, ' ');
                    i++;
                }
            } else if (character == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? source.length() : end + 2;
                while (i < stop) {
                    if (source.charAt(i) != '\n') {
                        masked.setCharAt(i, ' ');
                    }
                    i++;
                }
            } else if (character == '"' || character == '\'') {
                i = maskLiteral(source, masked, i, character);
            } else {
                i++;
            }
        }
        return masked.toString();
    }

    /** Blanks the braces inside one literal and returns the index just past its closing quote. */
    private static int maskLiteral(String source, StringBuilder masked, int start, char quote) {
        int i = start + 1;
        while (i < source.length()) {
            char character = source.charAt(i);
            if (character == '\\') {
                i += 2;
                continue;
            }
            if (character == quote) {
                return i + 1;
            }
            if (character == '{' || character == '}') {
                masked.setCharAt(i, ' ');
            }
            i++;
        }
        return i;
    }

    // Parser tests. The three assertions above are only as good as what this reads out of a
    // handler, and a brace it miscounts costs coverage without failing anything.

    private static final String SWITCH_TEMPLATE = """
            class Handler {
                Response handle(String action) {
                    return switch (action) {
                        case "First" -> first();
            %s
                        case "Last" -> last();
                        default -> unsupported();
                    };
                }
            }
            """;

    @Test
    void aBraceInALineCommentDoesNotEndTheSwitch() {
        Set<String> actions = dispatchedActions(
                SWITCH_TEMPLATE.formatted("            // closing } in a comment"),
                "action", "<line comment>");
        assertTrue(actions.contains("Last"),
                "a } inside a line comment closed the switch early, so later arms were lost: "
                        + actions);
    }

    @Test
    void aBraceInABlockCommentDoesNotEndTheSwitch() {
        Set<String> actions = dispatchedActions(
                SWITCH_TEMPLATE.formatted("            /* closing } in a block } comment */"),
                "action", "<block comment>");
        assertTrue(actions.contains("Last"),
                "a } inside a block comment closed the switch early: " + actions);
    }

    @Test
    void aBraceInsideALiteralDoesNotEndTheSwitch() {
        Set<String> actions = dispatchedActions(
                SWITCH_TEMPLATE.formatted("            case \"Brace\" -> literal(\"}\", '}');"),
                "action", "<literal>");
        assertTrue(actions.containsAll(Set.of("Brace", "Last")),
                "a } inside a string or character literal closed the switch early: " + actions);
    }

    @Test
    void aCommentedOutArmIsNotAnAction() {
        Set<String> actions = dispatchedActions(
                SWITCH_TEMPLATE.formatted("            // case \"Ghost\" -> ghost();"),
                "action", "<commented arm>");
        assertTrue(!actions.contains("Ghost"),
                "a commented-out case arm was read as a dispatched action: " + actions);
    }

    @Test
    void theTemplateItselfParsesAsExpected() {
        Set<String> actions = dispatchedActions(SWITCH_TEMPLATE.formatted(""), "action",
                "<template>");
        assertTrue(actions.equals(Set.of("First", "Last")),
                "the parser test template should yield exactly its two arms, got " + actions);
    }
}
