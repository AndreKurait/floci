package io.floci.aws.services.apigateway.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
public record TestInvokeMethodResult(int status, String body, Map<String, String> headers,
                                     Map<String, List<String>> multiValueHeaders, String log, int latency) {
}
