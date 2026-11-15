package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import jakarta.inject.Inject;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/2020-05-31/function/{Name}/test")
public class CloudFrontFunctionTestController {

    private final CloudFrontFunctionTesting testing;

    @Inject
    public CloudFrontFunctionTestController(CloudFrontFunctionTesting testing) {
        this.testing = testing;
    }

    @POST
    public Response testFunction(@PathParam("Name") String name, @HeaderParam("If-Match") String ifMatch, String body) {
        try {
            Map<String, String> fields = fields(body);
            CloudFrontFunctionRuntime.Result result = testing.test(name, fields.get("Stage"), ifMatch,
                    fields.get("EventObject"));
            return Response.ok(resultXml(result), "application/xml").build();
        } catch (AwsException failure) {
            String xml = new XmlBuilder().start("ErrorResponse", AwsNamespaces.CLOUDFRONT).start("Error")
                    .elem("Type", failure.getHttpStatus() >= 500 ? "Server" : "Client")
                    .elem("Code", failure.getErrorCode()).elem("Message", failure.getMessage()).end("Error")
                    .elem("RequestId", "00000000-0000-0000-0000-000000000000").end("ErrorResponse").build();
            return Response.status(failure.getHttpStatus()).type("application/xml").entity(xml).build();
        }
    }

    private static Map<String, String> fields(String body) {
        if (body == null || body.length() > 60000) {
            throw invalid();
        }
        Map<String, String> values = new LinkedHashMap<>();
        XMLStreamReader reader = null;
        try {
            reader = XmlParser.newStreamReader(body);
            if (reader.nextTag() != XMLStreamConstants.START_ELEMENT
                    || !"TestFunctionRequest".equals(reader.getLocalName())) {
                throw invalid();
            }
            while (reader.nextTag() == XMLStreamConstants.START_ELEMENT) {
                String name = reader.getLocalName();
                if (!("Stage".equals(name) || "EventObject".equals(name)) || values.containsKey(name)) {
                    throw invalid();
                }
                values.put(name, reader.getElementText());
            }
            if (!"TestFunctionRequest".equals(reader.getLocalName())) {
                throw invalid();
            }
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    throw invalid();
                }
            }
            return values;
        } catch (XMLStreamException malformed) {
            throw invalid();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // The request reader owns only this in-memory string.
                }
            }
        }
    }

    private static String resultXml(CloudFrontFunctionRuntime.Result result) {
        CloudFrontFunctionSnapshot function = result.snapshot();
        XmlBuilder xml = new XmlBuilder().start("TestResult", AwsNamespaces.CLOUDFRONT)
                .start("FunctionSummary").elem("Name", function.name()).elem("Status", function.status())
                .start("FunctionConfig").elem("Comment", function.comment()).elem("Runtime", function.runtime());
        if (!function.keyValueStoreArns().isEmpty()) {
            xml.start("KeyValueStoreAssociations").elem("Quantity", function.keyValueStoreArns().size()).start("Items");
            for (String arn : function.keyValueStoreArns()) {
                xml.start("KeyValueStoreAssociation").elem("KeyValueStoreARN", arn).end("KeyValueStoreAssociation");
            }
            xml.end("Items").end("KeyValueStoreAssociations");
        }
        xml.end("FunctionConfig").start("FunctionMetadata")
                .elem("FunctionARN", function.arn()).elem("Stage", function.stage())
                .elem("CreatedTime", function.createdTime().toString())
                .elem("LastModifiedTime", function.lastModifiedTime().toString())
                .end("FunctionMetadata").end("FunctionSummary")
                .start("FunctionExecutionLogs");
        for (String line : result.evaluation().logs()) {
            xml.elem("member", line);
        }
        return xml.end("FunctionExecutionLogs").elem("FunctionErrorMessage", result.evaluation().error())
                .elem("FunctionOutput", result.evaluation().output()).end("TestResult").build();
    }

    private static AwsException invalid() {
        return new AwsException("InvalidArgument", "The function test request is invalid.", 400);
    }
}
