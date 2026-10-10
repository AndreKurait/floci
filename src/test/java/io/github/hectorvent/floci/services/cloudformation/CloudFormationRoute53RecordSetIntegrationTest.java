package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::Route53::RecordSet} through the per-service provisioner: {@code Ref} is the record
 * name as the template wrote it, while the zone stores and lists the name fully qualified. It is
 * read back through an SSM parameter the template writes, because a stack status
 * alone proves nothing: an unowned type is stubbed CREATE_COMPLETE with an {@code arn:aws:stub}
 * physical id, which the parameter would expose.
 */
@QuarkusTest
class CloudFormationRoute53RecordSetIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String SSM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ssm/aws4_request";
    private static final String ROUTE53_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/route53/aws4_request";
    private static final Duration STACK_DELETE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STACK_DELETE_POLL_INTERVAL = Duration.ofMillis(50);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void recordGroupCreatesUpdatesAndDeletesItsBackingRecordsWithStableRef() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "record-group-" + suffix;
        String zoneName = suffix + ".group.example.com";
        String zone = createHostedZone(zoneName, stack);
        String unmanaged = "unmanaged." + zoneName;
        changeRecordSet(zone, "CREATE", unmanaged, "192.0.2.9");
        createStack(stack, groupTemplate(zone,
                record("first." + zoneName, "192.0.2.1") + "," + record("second." + zoneName, "192.0.2.2")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        String reference = stackOutput(stack, "GroupRef");
        assertEquals("Group", reference);
        assertChangeExists(stackOutput(stack, "GroupChange"));
        String created = listResourceRecordSets(zone);
        assertTrue(created.contains("first." + zoneName));
        assertTrue(created.contains("second." + zoneName));
        createOrUpdateGroup("UpdateStack", stack, groupTemplate(zone,
                record("first." + zoneName, "192.0.2.3") + "," + record("third." + zoneName, "192.0.2.4")));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals(reference, stackOutput(stack, "GroupRef"));
        assertChangeExists(stackOutput(stack, "GroupChange"));
        String updated = listResourceRecordSets(zone);
        assertTrue(updated.contains("192.0.2.3"), updated);
        assertTrue(updated.contains("third." + zoneName), updated);
        assertFalse(updated.contains("second." + zoneName), updated);
        deleteStack(stack);
        CfnStackWaits.awaitStackDeleted(stack);
        String deleted = listResourceRecordSets(zone);
        assertFalse(deleted.contains("first." + zoneName), deleted);
        assertFalse(deleted.contains("third." + zoneName), deleted);
        assertTrue(deleted.contains(unmanaged), deleted);
        changeRecordSet(zone, "DELETE", unmanaged, "192.0.2.9");
        deleteHostedZone(zone);
    }

    @Test
    void recordGroupZoneReplacementMovesRecordsAndDeletesOnlyTheOldGroup() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "record-group-move-" + suffix;
        String zoneName = suffix + ".group.example.com";
        String oldZone = createHostedZone(zoneName, stack + "-old");
        String newZone = createHostedZone(zoneName, stack + "-new");
        String name = "test." + zoneName;
        createStack(stack, groupTemplate(oldZone, record(name, "192.0.2.1")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        createOrUpdateGroup("UpdateStack", stack, groupTemplate(newZone, record(name, "192.0.2.2")));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertFalse(listResourceRecordSets(oldZone).contains(name));
        assertTrue(listResourceRecordSets(newZone).contains("192.0.2.2"));
        deleteStack(stack);
        CfnStackWaits.awaitStackDeleted(stack);
        assertFalse(listResourceRecordSets(newZone).contains(name));
        deleteHostedZone(oldZone);
        deleteHostedZone(newZone);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordGroupRestoresPriorRecordsWhenALaterResourceFails(boolean replaceZone) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "record-group-rollback-" + suffix;
        String zoneName = suffix + ".group.example.com";
        String oldZone = createHostedZone(zoneName, stack + "-old");
        String targetZone = replaceZone ? createHostedZone(zoneName, stack + "-new") : oldZone;
        String name = "test." + zoneName;
        createStack(stack, groupTemplate(oldZone, record(name, "192.0.2.1")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        String template = groupTemplate(targetZone, record(name, "192.0.2.2"))
                .replace("\"Group\":", """
                        "Fail":{"Type":"AWS::Route53::RecordSet","DependsOn":"Group",
                          "Properties":{"HostedZoneId":"%s","Name":"invalid.%s"}},
                        "Group":
                        """.formatted(targetZone, zoneName));
        createOrUpdateGroup("UpdateStack", stack, template);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        String restored = listResourceRecordSets(oldZone);
        assertTrue(restored.contains("192.0.2.1"), restored);
        assertFalse(restored.contains("192.0.2.2"), restored);
        if (replaceZone) {
            assertFalse(listResourceRecordSets(targetZone).contains(name));
        }
        deleteStack(stack);
        CfnStackWaits.awaitStackDeleted(stack);
        deleteHostedZone(oldZone);
        if (replaceZone) {
            deleteHostedZone(targetZone);
        }
    }

    @Test
    void recordGroupZoneReplacementHonorsRetainForTheOldGroup() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "record-group-retain-" + suffix;
        String zoneName = suffix + ".group.example.com";
        String oldZone = createHostedZone(zoneName, stack + "-old");
        String newZone = createHostedZone(zoneName, stack + "-new");
        String name = "test." + zoneName;
        createStack(stack, groupTemplate(oldZone, record(name, "192.0.2.1")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        String template = groupTemplate(newZone, record(name, "192.0.2.2"))
                .replace("\"Group\":{\"Type\":", "\"Group\":{\"UpdateReplacePolicy\":\"Retain\",\"Type\":");
        createOrUpdateGroup("UpdateStack", stack, template);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertTrue(listResourceRecordSets(oldZone).contains("192.0.2.1"));
        deleteStack(stack);
        CfnStackWaits.awaitStackDeleted(stack);
        assertFalse(listResourceRecordSets(newZone).contains(name));
        assertTrue(listResourceRecordSets(oldZone).contains("192.0.2.1"));
        changeRecordSet(oldZone, "DELETE", name, "192.0.2.1");
        deleteHostedZone(oldZone);
        deleteHostedZone(newZone);
    }

    @Test
    void failedReplacementCleanupStaysTrackedForStackDeletion() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "record-group-cleanup-" + suffix;
        String zoneName = suffix + ".group.example.com";
        String oldZone = createHostedZone(zoneName, stack + "-old");
        String newZone = createHostedZone(zoneName, stack + "-new");
        String name = "test." + zoneName;
        createStack(stack, groupTemplate(oldZone, record(name, "192.0.2.1")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        changeRecordSet(oldZone, "UPSERT", name, "192.0.2.9");
        createOrUpdateGroup("UpdateStack", stack, groupTemplate(newZone, record(name, "192.0.2.2")));
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_COMPLETE", state.status(), state.reason());
        assertTrue(listResourceRecordSets(oldZone).contains("192.0.2.9"));
        assertTrue(listResourceRecordSets(newZone).contains("192.0.2.2"));
        changeRecordSet(oldZone, "UPSERT", name, "192.0.2.1");
        deleteStack(stack);
        CfnStackWaits.awaitStackDeleted(stack);
        assertFalse(listResourceRecordSets(oldZone).contains(name));
        assertFalse(listResourceRecordSets(newZone).contains(name));
        deleteHostedZone(oldZone);
        deleteHostedZone(newZone);
    }

    @Test
    void recordGroupCollisionCommitsNoPartialRecordsAndPreservesTheOtherOwner() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "record-group-conflict-" + suffix;
        String zoneName = suffix + ".group.example.com";
        String zone = createHostedZone(zoneName, stack);
        String existing = "taken." + zoneName;
        changeRecordSet(zone, "CREATE", existing, "192.0.2.9");
        createStack(stack, groupTemplate(zone,
                record("new." + zoneName, "192.0.2.1") + "," + record(existing, "192.0.2.2")));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        String current = listResourceRecordSets(zone);
        assertFalse(current.contains("new." + zoneName), current);
        assertTrue(current.contains("192.0.2.9"), current);
        assertFalse(current.contains("192.0.2.2"), current);
        deleteStack(stack);
        CfnStackWaits.awaitStackDeleted(stack);
        changeRecordSet(zone, "DELETE", existing, "192.0.2.9");
        deleteHostedZone(zone);
    }

    @Test
    void recordGroupDeleteRefusesChangedValuesAndCanRetryAfterDriftIsRepaired() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "record-group-drift-" + suffix;
        String zoneName = suffix + ".group.example.com";
        String zone = createHostedZone(zoneName, stack);
        String name = "test." + zoneName;
        createStack(stack, groupTemplate(zone, record(name, "192.0.2.1")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        changeRecordSet(zone, "UPSERT", name, "192.0.2.2");
        deleteStack(stack);
        assertEquals("DELETE_FAILED", CfnStackWaits.awaitTerminal(stack).status());
        assertTrue(listResourceRecordSets(zone).contains("192.0.2.2"));
        changeRecordSet(zone, "UPSERT", name, "192.0.2.1");
        deleteStack(stack);
        CfnStackWaits.awaitStackDeleted(stack);
        assertFalse(listResourceRecordSets(zone).contains(name));
        deleteHostedZone(zone);
    }

    private String record(String name, String value) {
        return """
                {"Name":"%s","Type":"A","TTL":"300","ResourceRecords":["%s"]}
                """.formatted(name, value);
    }

    private String groupTemplate(String zone, String records) {
        return """
                {"Resources":{
                  "Group":{"Type":"AWS::Route53::RecordSetGroup",
                    "Properties":{"HostedZoneId":"%s","RecordSets":[%s]}}
                },"Outputs":{
                  "GroupRef":{"Value":{"Ref":"Group"}},
                  "GroupChange":{"Value":{"Fn::GetAtt":["Group","Id"]}}
                }}
                """.formatted(zone, records);
    }

    private String stackOutput(String stack, String key) {
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks").formParam("StackName", stack)
                .when().post("/").then().statusCode(200).extract().xmlPath()
                .getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member"
                        + ".find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private void assertChangeExists(String change) {
        String id = change.substring(change.lastIndexOf('/') + 1);
        given().header("Authorization", ROUTE53_AUTH)
        .when().get("/2013-04-01/change/" + id)
        .then().statusCode(200).body(containsString("<Status>INSYNC</Status>"));
    }

    private void createOrUpdateGroup(String action, String stack, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
    }

    private void deleteHostedZone(String zone) {
        given().header("Authorization", ROUTE53_AUTH)
        .when().delete("/2013-04-01/hostedzone/" + zone)
        .then().statusCode(200);
    }

    @Test
    void recordSetRefResolvesToTheRecordName() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-recordset-" + suffix;
        String recordName = "www." + suffix + ".cfn-it.example.com";
        String template = """
                {
                  "Resources": {
                    "Zone": {
                      "Type": "AWS::Route53::HostedZone",
                      "Properties": {"Name": "%s.cfn-it.example.com"}
                    },
                    "Www": {
                      "Type": "AWS::Route53::RecordSet",
                      "Properties": {
                        "HostedZoneId": {"Ref": "Zone"},
                        "Name": "%s",
                        "Type": "A",
                        "TTL": "300",
                        "ResourceRecords": ["10.0.0.1"]
                      }
                    },
                    "RefParam": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {"Name": "/r53-recordset/%s/ref", "Type": "String", "Value": {"Ref": "Www"}}
                    },
                    "ZoneParam": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {"Name": "/r53-recordset/%s/zone", "Type": "String", "Value": {"Ref": "Zone"}}
                    }
                  }
                }
                """.formatted(suffix, recordName, suffix, suffix);

        createStack(stackName, template);
        assertStackStatus(stackName, "CREATE_COMPLETE");
        try {
            assertEquals(recordName, parameterValue("/r53-recordset/" + suffix + "/ref"));

            // A stack status proves nothing on its own: read the record back from the zone to
            // confirm the provisioner wrote it (not stubbed) rather than leaving the zone empty.
            String zoneId = parameterValue("/r53-recordset/" + suffix + "/zone");
            String records = listResourceRecordSets(zoneId);
            assertTrue(records.contains(recordName), "record not written to zone: " + records);
            assertTrue(records.contains("10.0.0.1"), "record value not written to zone: " + records);
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    @Test
    void recordSetNameIsStoredFullyQualifiedAcrossCreateUpdateAndDelete() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-recordset-fqdn-" + suffix;
        String zoneName = suffix + ".cfn-fqdn-it.example.com";
        String firstName = "www." + zoneName;
        String secondName = "api." + zoneName;
        String template = """
                {
                  "Parameters": {
                    "RecordName": {"Type": "String"},
                    "Address": {"Type": "String"}
                  },
                  "Resources": {
                    "Zone": {
                      "Type": "AWS::Route53::HostedZone",
                      "Properties": {"Name": "%s"}
                    },
                    "Record": {
                      "Type": "AWS::Route53::RecordSet",
                      "Properties": {
                        "HostedZoneId": {"Ref": "Zone"},
                        "Name": {"Ref": "RecordName"},
                        "Type": "A",
                        "TTL": "300",
                        "ResourceRecords": [{"Ref": "Address"}]
                      }
                    },
                    "ZoneParam": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {"Name": "/r53-recordset/%s/fqdn-zone", "Type": "String", "Value": {"Ref": "Zone"}}
                    }
                  }
                }
                """.formatted(zoneName, suffix);

        stackRequest("CreateStack", stackName, template, firstName, "10.0.0.1");
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        String zoneId = parameterValue("/r53-recordset/" + suffix + "/fqdn-zone");
        String created = listResourceRecordSets(zoneId);
        assertTrue(created.contains("<Name>" + firstName + ".</Name>"), "name not fully qualified: " + created);
        assertFalse(created.contains("<Name>" + firstName + "</Name>"), "name stored as written: " + created);

        // A rename is a new record identity, so the update must also find and remove the old record.
        stackRequest("UpdateStack", stackName, template, secondName, "10.0.0.2");
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        String updated = listResourceRecordSets(zoneId);
        assertTrue(updated.contains("<Name>" + secondName + ".</Name>"), "renamed record missing: " + updated);
        assertTrue(updated.contains("10.0.0.2"), "updated value missing: " + updated);
        assertFalse(updated.contains(firstName), "superseded record left in zone: " + updated);

        // The zone refuses to delete while it still holds the record, so a clean stack delete and a
        // zone that is gone prove the stack delete found the fully-qualified record and removed it.
        deleteStack(stackName);
        CfnStackWaits.awaitStackDeleted(stackName);
        given()
            .header("Authorization", ROUTE53_AUTH)
        .when().get("/2013-04-01/hostedzone/" + zoneId)
        .then().statusCode(404);
    }

    @Test
    void createStackFailsRatherThanTakeOverARecordTheRoute53ApiCreated() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-recordset-taken-" + suffix;
        String zoneName = suffix + ".cfn-taken-it.example.com";
        String recordName = "www." + zoneName;
        String zoneId = createHostedZone(zoneName, "cfn-taken-" + suffix);
        changeRecordSet(zoneId, "CREATE", recordName + ".", "192.0.2.10");
        String template = """
                {
                  "Resources": {
                    "Www": {
                      "Type": "AWS::Route53::RecordSet",
                      "Properties": {
                        "HostedZoneId": "%s",
                        "Name": "%s",
                        "Type": "A",
                        "TTL": "300",
                        "ResourceRecords": ["192.0.2.20"]
                      }
                    }
                  }
                }
                """.formatted(zoneId, recordName);

        try {
            createStack(stackName, template);
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
            // AWS sends CREATE for a record the stack does not own yet, so the name being taken
            // fails the create and the rollback leaves the other owner's record alone.
            assertEquals("ROLLBACK_COMPLETE", state.status(), state.reason());
            assertTrue(state.reason().contains("already exists"), state.reason());
            String afterCreate = listResourceRecordSets(zoneId);
            assertTrue(afterCreate.contains("192.0.2.10"), "API record overwritten: " + afterCreate);
            assertFalse(afterCreate.contains("192.0.2.20"), "stack value written: " + afterCreate);
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }

        String afterDelete = listResourceRecordSets(zoneId);
        assertTrue(afterDelete.contains("<Name>" + recordName + ".</Name>"), "API record deleted: " + afterDelete);
        assertTrue(afterDelete.contains("192.0.2.10"), "API record value lost: " + afterDelete);

        // A DELETE must name the record's current values, so it succeeding proves them unchanged.
        changeRecordSet(zoneId, "DELETE", recordName + ".", "192.0.2.10");
        given()
            .header("Authorization", ROUTE53_AUTH)
        .when().delete("/2013-04-01/hostedzone/" + zoneId)
        .then().statusCode(200);
    }

    private String createHostedZone(String zoneName, String callerReference) {
        String location = given()
            .contentType("application/xml")
            .header("Authorization", ROUTE53_AUTH)
            .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                      <Name>%s</Name>
                      <CallerReference>%s</CallerReference>
                    </CreateHostedZoneRequest>
                    """.formatted(zoneName, callerReference))
        .when().post("/2013-04-01/hostedzone")
        .then().statusCode(201)
            .extract().header("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private void changeRecordSet(String zoneId, String action, String name, String address) {
        given()
            .contentType("application/xml")
            .header("Authorization", ROUTE53_AUTH)
            .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                      <ChangeBatch>
                        <Changes>
                          <Change>
                            <Action>%s</Action>
                            <ResourceRecordSet>
                              <Name>%s</Name>
                              <Type>A</Type>
                              <TTL>300</TTL>
                              <ResourceRecords>
                                <ResourceRecord><Value>%s</Value></ResourceRecord>
                              </ResourceRecords>
                            </ResourceRecordSet>
                          </Change>
                        </Changes>
                      </ChangeBatch>
                    </ChangeResourceRecordSetsRequest>
                    """.formatted(action, name, address))
        .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
        .then().statusCode(200);
    }

    private void stackRequest(String action, String stackName, String template, String recordName, String address) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "RecordName")
            .formParam("Parameters.member.1.ParameterValue", recordName)
            .formParam("Parameters.member.2.ParameterKey", "Address")
            .formParam("Parameters.member.2.ParameterValue", address)
        .when().post("/").then().statusCode(200);
    }

    private String listResourceRecordSets(String zoneId) {
        return given()
            .header("Authorization", ROUTE53_AUTH)
        .when().get("/2013-04-01/hostedzone/" + zoneId + "/rrset")
        .then().statusCode(200)
            .extract().asString();
    }

    private void createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
    }

    private void assertStackStatus(String stackName, String status) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + status + "</StackStatus>"));
    }

    private void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
    }

    private String parameterValue(String name) {
        return given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", SSM_AUTH)
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .body("{\"Name\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Parameter.Value");
    }
}
