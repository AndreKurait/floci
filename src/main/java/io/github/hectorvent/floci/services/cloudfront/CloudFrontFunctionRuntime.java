package io.github.hectorvent.floci.services.cloudfront;

import java.util.List;
import java.util.function.Supplier;

interface CloudFrontFunctionRuntime {

    Result evaluate(Supplier<CloudFrontFunctionSnapshot> capture, String event);

    record Evaluation(String output, String error, List<String> logs) {
        public Evaluation {
            logs = List.copyOf(logs);
        }
    }

    record Result(CloudFrontFunctionSnapshot snapshot, Evaluation evaluation) {}
}
