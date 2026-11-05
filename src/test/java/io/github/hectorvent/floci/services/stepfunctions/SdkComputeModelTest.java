package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkComputeModelTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ec2QueryUsesModeledWireNamesAndPreservesOpaqueValues() throws Exception {
        JsonNode input = mapper.readTree("""
                {"MinCount":1,"MaxCount":1,"LaunchTemplate":{"LaunchTemplateId":"lt-1234","Version":"7"},
                 "DryRun":false,"UserData":"eCAmKys=","NetworkInterfaces":[{"DeviceIndex":0,
                 "DeleteOnTermination":true,"Groups":["sg-1234"]}],
                 "TagSpecifications":[{"ResourceType":"instance","Tags":[{"Key":"lowerCase",
                 "Value":"a+&=é"}]}]}
                """);
        byte[] encoded = new SdkComputeModel("ec2", "RunInstances").query("RunInstances", input);
        Map<String, String> query = Arrays.stream(new String(encoded, StandardCharsets.UTF_8).split("&"))
                .map(pair -> pair.split("=", 2)).collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
        assertEquals("RunInstances", query.get("Action"));
        assertEquals("2016-11-15", query.get("Version"));
        assertEquals("7", query.get("LaunchTemplate.Version"));
        assertEquals("false", query.get("DryRun"));
        assertEquals("sg-1234", query.get("NetworkInterface.1.SecurityGroupId.1"));
        assertEquals("true", query.get("NetworkInterface.1.DeleteOnTermination"));
        assertEquals("lowerCase", query.get("TagSpecification.1.Tag.1.Key"));
        assertEquals("a+&=é", query.get("TagSpecification.1.Tag.1.Value"));
        assertEquals("eCAmKys=", query.get("UserData"));
    }

    @Test
    void describeResponseUsesSdkCollectionsAndScalarTypes() throws Exception {
        JsonNode result = new SdkComputeModel("ec2", "DescribeInstances").queryResponse("""
                <DescribeInstancesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
                <requestId>request</requestId><reservationSet><item><ownerId>123456789012</ownerId>
                <instancesSet><item><instanceId>i-1234</instanceId><instanceState><code>16</code>
                <name>running</name></instanceState><ebsOptimized>true</ebsOptimized>
                <launchTime>2026-11-05T00:00:00Z</launchTime><groupSet/>
                <tagSet><item><key>lowerCase</key><value>opaque</value></item></tagSet>
                </item></instancesSet></item></reservationSet></DescribeInstancesResponse>
                """);
        JsonNode instance = result.path("Reservations").path(0).path("Instances").path(0);
        assertEquals("i-1234", instance.path("InstanceId").asText());
        assertTrue(instance.path("State").path("Code").isIntegralNumber());
        assertEquals(16, instance.path("State").path("Code").asInt());
        assertTrue(instance.path("EbsOptimized").isBoolean());
        assertTrue(instance.path("EbsOptimized").asBoolean());
        assertEquals("2026-11-05T00:00:00.000Z", instance.path("LaunchTime").asText());
        assertTrue(instance.path("SecurityGroups").isArray());
        assertTrue(instance.path("SecurityGroups").isEmpty());
        assertEquals("lowerCase", instance.path("Tags").path(0).path("Key").asText());
        assertFalse(result.has("RequestId"));
    }

    @Test
    void ssmKeepsParameterKeysAndFormatsOnlyModeledTimestamps() throws Exception {
        SdkComputeModel model = new SdkComputeModel("ssm", "SendCommand");
        JsonNode request = mapper.readTree("""
                {"DocumentName":"command","Parameters":{"lowerCase":["value"],"RequestedDateTime":["opaque"]},
                 "CloudWatchOutputConfig":{"CloudWatchOutputEnabled":true}}
                """);
        assertEquals(request, model.jsonRequest(request));
        JsonNode response = model.jsonResponse(mapper.readTree("""
                {"Command":{"RequestedDateTime":1.125,"TargetCount":1,
                 "Parameters":{"RequestedDateTime":["opaque"]}},"UnknownWireMember":123}
                """));
        assertEquals("1970-01-01T00:00:01.125Z", response.path("Command").path("RequestedDateTime").asText());
        assertTrue(response.path("Command").path("TargetCount").isIntegralNumber());
        assertEquals("opaque", response.path("Command").path("Parameters").path("RequestedDateTime").path(0).asText());
        assertFalse(response.has("UnknownWireMember"));
    }

    @Test
    void legacySsmMembersUseSdkPascalCaseAndNativeWireNames() throws Exception {
        SdkComputeModel model = new SdkComputeModel("ssm", "DescribeInstanceInformation");
        assertEquals(mapper.readTree("""
                {"InstanceInformationFilterList":[{"key":"InstanceIds","valueSet":["i-1234"]}]}
                """), model.jsonRequest(mapper.readTree("""
                {"InstanceInformationFilterList":[{"Key":"InstanceIds","ValueSet":["i-1234"]}]}
                """)));
        assertThrows(AwsException.class, () -> model.jsonRequest(mapper.readTree("""
                {"InstanceInformationFilterList":[{"key":"InstanceIds","valueSet":["i-1234"]}]}
                """)));
    }

    @Test
    void errorNamesFollowModeledSdkExceptionClasses() {
        SdkComputeModel ec2 = new SdkComputeModel("ec2", "DescribeInstances");
        for (String code : new String[]{"UnauthorizedOperation", "InvalidInstanceID.NotFound",
                "RequestLimitExceeded", "AccessDeniedException"}) {
            assertEquals("Ec2.Ec2Exception", ec2.exceptionName("Ec2", code));
        }
        SdkComputeModel ssm = new SdkComputeModel("ssm", "GetCommandInvocation");
        assertEquals("Ssm.InvocationDoesNotExistException", ssm.exceptionName("Ssm", "InvocationDoesNotExist"));
        assertEquals("Ssm.InvalidInstanceIdException", ssm.exceptionName("Ssm", "InvalidInstanceId"));
        assertEquals("Ssm.AccessDeniedException", ssm.exceptionName("Ssm", "AccessDeniedException"));
        assertEquals("Ssm.SsmException", ssm.exceptionName("Ssm", "UnmodeledError"));
    }

    @Test
    void singularQueryFieldsMissingRequiredMembersAndWrongTypesFailBeforeSending() throws Exception {
        for (String input : new String[]{"{\"Filter\":[]}", "{\"Filters\":\"not-a-list\"}"}) {
            JsonNode request = mapper.readTree(input);
            assertThrows(AwsException.class,
                    () -> new SdkComputeModel("ec2", "DescribeInstances").query("DescribeInstances", request));
        }
        assertThrows(AwsException.class, () -> new SdkComputeModel("ec2", "RunInstances")
                .query("RunInstances", mapper.readTree("{\"MaxCount\":1}")));
        assertThrows(AwsException.class, () -> new SdkComputeModel("ssm", "SendCommand")
                .jsonRequest(mapper.readTree("{\"DocumentName\":\"command\",\"TimeoutSeconds\":\"60\"}")));
    }

    @Test
    void onlySupportedLowerCamelRequestResponseActionsAreSelected() {
        for (String resource : new String[]{"arn:aws:states:::aws-sdk:ec2:runInstances.sync",
                "arn:aws:states:::aws-sdk:ec2:RunInstances", "arn:aws:states:::aws-sdk:ssm:startSession"}) {
            assertFalse(SdkComputeTaskInvoker.supports(StatesIntegration.parse(resource).orElseThrow()));
        }
        assertTrue(SdkComputeTaskInvoker.supports(StatesIntegration.parse(
                "arn:aws-cn:states:::aws-sdk:ec2:runInstances").orElseThrow()));
    }

    @Test
    void xmlDoesNotResolveExternalEntities() {
        assertThrows(Exception.class, () -> new SdkComputeModel("ec2", "DescribeInstances").queryResponse("""
                <!DOCTYPE response [<!ENTITY xxe SYSTEM "file:///unavailable">]>
                <DescribeInstancesResponse><reservationSet>&xxe;</reservationSet></DescribeInstancesResponse>
                """));
    }
}
