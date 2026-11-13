package io.github.hectorvent.floci.services.codedeploy;

import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.model.BuildResponseItem;

import java.util.concurrent.TimeUnit;

/** Keeps a bounded tail of the stock agent image build for setup failures. */
final class CodeDeployImageBuild extends BuildImageResultCallback {
    static final int OUTPUT_LIMIT = 16 * 1024;
    private final StringBuilder output = new StringBuilder();

    @Override
    public void onNext(BuildResponseItem item) {
        append(item.getStream());
        append(item.getError());
        super.onNext(item);
    }

    private synchronized void append(String text) {
        if (text == null) {
            return;
        }
        if (text.length() >= OUTPUT_LIMIT) {
            output.setLength(0);
            output.append(text, text.length() - OUTPUT_LIMIT, text.length());
        } else {
            int excess = output.length() + text.length() - OUTPUT_LIMIT;
            if (excess > 0) {
                output.delete(0, excess);
            }
            output.append(text);
        }
    }

    private synchronized String outputTail() {
        return output.toString();
    }

    @Override
    public String awaitImageId(long timeout, TimeUnit unit) {
        try {
            return super.awaitImageId(timeout, unit);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Cannot build the CodeDeploy agent image; last "
                    + OUTPUT_LIMIT + " output characters:\n" + outputTail(), failure);
        }
    }
}
