package io.floci.aws.config;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards the build wiring of the AppConfig compatibility clock in {@code compatibility-tests/clock}.
 *
 * <p>The fixture is a standalone module, so nothing in Maven ties its Quarkus version to the root
 * POM's, and it must reach a Floci build only through the opt-in {@code compat-clock} profile:
 * release builds never name that profile, which is what keeps the test clock out of them.
 */
class CompatClockBuildTest {

    private static final Path ROOT_POM = Path.of("pom.xml");
    private static final Path FIXTURE_POM = Path.of("compatibility-tests", "clock", "pom.xml");

    private static final String QUARKUS_VERSION = "/project/properties/quarkus.platform.version";
    private static final String FIXTURE_DEPENDENCY = "dependency[groupId='io.floci.testing']";
    private static final String PROFILE = "/project/profiles/profile[id='compat-clock']";

    private final XPath xpath = XPathFactory.newInstance().newXPath();

    @Test
    void fixtureUsesTheRootQuarkusPlatformVersion() throws Exception {
        String rootVersion = xpath.evaluate(QUARKUS_VERSION, parse(ROOT_POM));

        assertFalse(rootVersion.isBlank(), "pom.xml must declare quarkus.platform.version");
        assertEquals(rootVersion, xpath.evaluate(QUARKUS_VERSION, parse(FIXTURE_POM)),
                FIXTURE_POM + " must use the root POM's quarkus.platform.version");
    }

    @Test
    void fixtureIsADependencyOnlyInTheOptInProfile() throws Exception {
        Document root = parse(ROOT_POM);

        assertEquals(1, count(PROFILE + "/dependencies/" + FIXTURE_DEPENDENCY, root),
                "The compat-clock profile must add the fixture");
        assertEquals(1, count("//" + FIXTURE_DEPENDENCY, root),
                "Only the compat-clock profile may depend on the fixture");
        assertEquals(0, count(PROFILE + "/activation", root),
                "The compat-clock profile must stay opt-in: an activation could put the fixture in a release build");
    }

    private int count(String expression, Document pom) throws Exception {
        return ((Double) xpath.evaluate("count(" + expression + ")", pom, XPathConstants.NUMBER)).intValue();
    }

    private static Document parse(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(pom.toFile());
    }
}
