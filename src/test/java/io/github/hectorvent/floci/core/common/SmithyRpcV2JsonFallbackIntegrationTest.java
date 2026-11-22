package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An AWS JSON service whose botocore model lists both "smithy-rpc-v2-cbor" and "json" is answered on the Smithy
 * RPC v2 CBOR path by its AWS JSON handler, as an SDK that prefers CBOR calls it:
 * POST /service/{X-Amz-Target prefix without its dot}/operation/{operation}. One test per service; a service whose
 * model lists only "json" keeps its 404.
 */
@QuarkusTest
class SmithyRpcV2JsonFallbackIntegrationTest {

    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void acmPcaAnswersWithTaggedTimestamps() throws Exception {
        JsonNode created = call("ACMPrivateCA", "CreateCertificateAuthority", """
                {"CertificateAuthorityType": "ROOT",
                 "CertificateAuthorityConfiguration": {"KeyAlgorithm": "RSA_2048", "SigningAlgorithm": "SHA256WITHRSA",
                     "Subject": {"CommonName": "rpcv2-fallback.invalid"}}}
                """);
        String arn = created.path("CertificateAuthorityArn").asText();
        assertTrue(arn.startsWith("arn:aws:acm-pca:"), arn);

        byte[] described = callBytes("ACMPrivateCA", "DescribeCertificateAuthority",
                "{\"CertificateAuthorityArn\": \"" + arn + "\"}");
        assertEquals(arn, CBOR_MAPPER.readTree(described).path("CertificateAuthority").path("Arn").asText());
        assertEquals(1, tagOf(described, "CreatedAt"), "CreatedAt is not a CBOR timestamp (tag 1)");

        assertTrue(call("ACMPrivateCA", "ListCertificateAuthorities", "{}")
                .path("CertificateAuthorities").isArray());
    }

    @Test
    void cloudHsmV2Answers() throws Exception {
        assertTrue(call("BaldrApiService", "DescribeClusters", "{}").path("Clusters").isArray());
    }

    @Test
    void codeBuildAnswers() throws Exception {
        assertTrue(call("CodeBuild_20161006", "ListProjects", "{}").path("projects").isArray());
    }

    @Test
    void codeDeployAnswers() throws Exception {
        assertTrue(call("CodeDeploy_20141006", "ListApplications", "{}").path("applications").isArray());
    }

    @Test
    void codePipelineAnswers() throws Exception {
        assertTrue(call("CodePipeline_20150709", "ListPipelines", "{}").path("pipelines").isArray());
    }

    @Test
    void comprehendAnswers() throws Exception {
        assertTrue(call("Comprehend_20171127", "DetectDominantLanguage", """
                {"Text": "Hello from the RPC v2 CBOR path"}
                """).path("Languages").isArray());
    }

    @Test
    void dataSyncAnswers() throws Exception {
        assertTrue(call("FmrsService", "ListTasks", "{}").path("Tasks").isArray());
    }

    @Test
    void memoryDbAnswers() throws Exception {
        assertTrue(call("AmazonMemoryDB", "DescribeClusters", "{}").path("Clusters").isArray());
    }

    @Test
    void networkFirewallAnswers() throws Exception {
        assertTrue(call("NetworkFirewall_20201112", "ListFirewalls", "{}").path("Firewalls").isArray());
    }

    @Test
    void serviceCatalogAnswers() throws Exception {
        assertTrue(call("AWS242ServiceCatalogService", "ListPortfolios", "{}").path("PortfolioDetails").isArray());
    }

    @Test
    void cloudMapAnswers() throws Exception {
        assertTrue(call("Route53AutoNaming_v20170314", "ListNamespaces", "{}").path("Namespaces").isArray());
    }

    @Test
    void textractAnswers() throws Exception {
        assertTrue(call("Textract", "DetectDocumentText", """
                {"Document": {"S3Object": {"Bucket": "rpcv2-fallback", "Name": "page.png"}}}
                """).path("Blocks").isArray());
    }

    @Test
    void transcribeAnswers() throws Exception {
        assertTrue(call("Transcribe", "ListTranscriptionJobs", "{}").path("TranscriptionJobSummaries").isArray());
    }

    @Test
    void translateAnswers() throws Exception {
        assertTrue(call("AWSShineFrontendService_20170701", "ListLanguages", "{}").path("Languages").isArray());
    }

    @Test
    void jsonOnlyServiceKeepsItsNotFound() throws Exception {
        given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree("{}")))
        .when()
            .post("/service/TrentService/operation/ListKeys")
        .then()
            .statusCode(404);
    }

    private static JsonNode call(String service, String operation, String json) throws Exception {
        return CBOR_MAPPER.readTree(callBytes(service, operation, json));
    }

    private static byte[] callBytes(String service, String operation, String json) throws Exception {
        return given()
            .contentType("application/cbor")
            .accept("application/cbor")
            .header("smithy-protocol", "rpc-v2-cbor")
            .body(CBOR_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree(json)))
        .when()
            .post("/service/" + service + "/operation/" + operation)
        .then()
            .statusCode(200)
            .header("smithy-protocol", "rpc-v2-cbor")
            .contentType("application/cbor")
            .extract().asByteArray();
    }

    /** The CBOR tag of the first value of {@code member} at any depth; -1 for none. */
    private static int tagOf(byte[] body, String member) throws Exception {
        try (CBORParser parser = (CBORParser) CBOR_MAPPER.getFactory().createParser(body)) {
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (token == JsonToken.FIELD_NAME && member.equals(parser.currentName())) {
                    parser.nextToken();
                    return parser.getCurrentTag();
                }
            }
        }
        return -1;
    }
}
