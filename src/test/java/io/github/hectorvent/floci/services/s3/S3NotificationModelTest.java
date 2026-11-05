package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.s3.model.FilterRule;
import io.github.hectorvent.floci.services.s3.model.LambdaNotification;
import io.github.hectorvent.floci.services.s3.model.QueueNotification;
import io.github.hectorvent.floci.services.s3.model.TopicNotification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class S3NotificationModelTest {

    // --- matchesKey ---

    @Test
    void matchesKeyWithNullFilterRulesFromJacksonDeserialization() {
        QueueNotification qn = new QueueNotification("id", "arn:aws:sqs:us-east-1:000000000000:q",
                List.of("s3:ObjectCreated:*"), null);
        assertTrue(qn.matchesKey("anything"));

        TopicNotification tn = new TopicNotification("id", "arn:aws:sns:us-east-1:000000000000:t",
                List.of("s3:ObjectCreated:*"), null);
        assertTrue(tn.matchesKey("anything"));

        LambdaNotification ln = new LambdaNotification("id", "arn:aws:lambda:us-east-1:000000000000:function:test",
                List.of("s3:ObjectCreated:*"), null);
        assertTrue(ln.matchesKey("anything"));
    }

    @Test
    void matchesKeyWithEmptyFilterRulesMatchesAll() {
        QueueNotification qn = new QueueNotification("id", "arn", List.of("s3:ObjectCreated:*"));
        assertTrue(qn.matchesKey("anything"));
    }

    @Test
    void matchesKeyEnforcesAllRules() {
        QueueNotification qn = new QueueNotification("id", "arn", List.of("s3:ObjectCreated:*"),
                List.of(new FilterRule("prefix", "images/"), new FilterRule("suffix", ".jpg")));
        assertTrue(qn.matchesKey("images/photo.jpg"));
        assertFalse(qn.matchesKey("images/photo.png"));
        assertFalse(qn.matchesKey("docs/photo.jpg"));

        LambdaNotification ln = new LambdaNotification("id", "arn", List.of("s3:ObjectCreated:*"),
                List.of(new FilterRule("prefix", "images/"), new FilterRule("suffix", ".jpg")));
        assertTrue(ln.matchesKey("images/photo.jpg"));
        assertFalse(ln.matchesKey("images/photo.png"));
        assertFalse(ln.matchesKey("docs/photo.jpg"));
    }

    // --- FilterRule.matches ---

    @Test
    void filterRulePrefixMatch() {
        FilterRule rule = new FilterRule("prefix", "images/");
        assertTrue(rule.matches("images/photo.jpg"));
        assertFalse(rule.matches("docs/file.txt"));
        assertFalse(rule.matches(null));
    }

    @Test
    void filterRuleSuffixMatch() {
        FilterRule rule = new FilterRule("suffix", ".jpg");
        assertTrue(rule.matches("images/photo.jpg"));
        assertFalse(rule.matches("images/photo.png"));
    }

    @Test
    void filterRuleUnknownNameDoesNotMatch() {
        FilterRule rule = new FilterRule("extension", ".jpg");
        assertFalse(rule.matches("photo.jpg"));
    }

    @Test
    void filterRuleNameIsCaseInsensitive() {
        assertTrue(new FilterRule("Prefix", "images/").matches("images/photo.jpg"));
        assertTrue(new FilterRule("SUFFIX", ".jpg").matches("photo.jpg"));
    }

    @ParameterizedTest
    @CsvSource({
        "prefix, AWS%3AApplication/, AWS:Application/node.json, true",
        "prefix, AWS%3AApplication/, AWS%3AApplication/node.json, false",
        "prefix, reports+from+nodes/, reports from nodes/node.json, true",
        "prefix, reports+from+nodes/, reports+from+nodes/node.json, false",
        "suffix, %2Bresult.json, node+result.json, true",
        "suffix, %2Bresult.json, node result.json, false",
        "prefix, r%C3%A9sum%C3%A9/%25/, résumé/%/node.json, true",
        "prefix, literal%253A/, literal%3A/node.json, true",
        "prefix, literal%253A/, literal:/node.json, false",
        "suffix, %252B.json, node%2B.json, true",
        "suffix, %252B.json, node+.json, false"
    })
    void filterRulesDecodeFormEncodingExactlyOnce(String name, String value, String key, boolean expected) {
        assertEquals(expected, new FilterRule(name, value).matches(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "%0", "%GG"})
    void malformedStoredFilterDoesNotMatchOrInterruptOtherDestinations(String value) {
        assertFalse(new FilterRule("prefix", value).matches(value + "/object"));
        assertFalse(new FilterRule("suffix", value).matches("object/" + value));
    }
}
