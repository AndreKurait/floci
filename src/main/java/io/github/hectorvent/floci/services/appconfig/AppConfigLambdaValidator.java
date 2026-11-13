package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersion;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Owns a bounded Lambda invocation independently from AppConfig's shorter response deadline. */
@ApplicationScoped
public class AppConfigLambdaValidator implements Resettable {
    private static final Logger LOG = Logger.getLogger(AppConfigLambdaValidator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration VALIDATION_BUDGET = Duration.ofSeconds(15);
    private static final int MAX_PENDING = 32;

    private final LambdaService lambda;
    private final ServiceCallAuthorizer authorizer;
    private final RegionResolver regions;
    private final ExecutorService workers;
    private final LongSupplier nanoTime;
    private final Duration budget;
    private final int capacity;
    private final Set<Job> jobs = new HashSet<>();
    private boolean paused;
    private boolean closed;

    @Inject
    public AppConfigLambdaValidator(LambdaService lambda, ServiceCallAuthorizer authorizer,
                                    RegionResolver regions) {
        this(lambda, authorizer, regions, Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("appconfig-validator-", 0).factory()),
                System::nanoTime, VALIDATION_BUDGET, MAX_PENDING);
    }

    AppConfigLambdaValidator(LambdaService lambda, ServiceCallAuthorizer authorizer, RegionResolver regions,
                             ExecutorService workers, LongSupplier nanoTime, Duration budget, int capacity) {
        this.lambda = lambda;
        this.authorizer = authorizer;
        this.regions = regions;
        this.workers = workers;
        this.nanoTime = nanoTime;
        this.budget = budget;
        this.capacity = capacity;
    }

    void validate(String functionArn, ConfigurationProfile profile, HostedConfigurationVersion version) {
        long deadline = nanoTime.getAsLong() + budget.toNanos();
        if (!AwsArnUtils.isArn(functionArn)
                || !"lambda".equals(AwsArnUtils.parse(functionArn).service())
                || !AwsArnUtils.parse(functionArn).resource().startsWith("function:")) {
            throw invalid("A Lambda validator must name a Lambda function ARN.");
        }
        String sourceAccount = regions.getAccountId();
        String sourceRegion = regions.getRegion();
        String sourceArn = new AwsArnUtils.Arn(regions.getPartition(), "appconfig", sourceRegion, sourceAccount,
                "application/" + profile.getApplicationId() + "/configurationprofile/" + profile.getId()).toString();
        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(Map.of(
                    "applicationId", profile.getApplicationId(),
                    "configurationProfileId", profile.getId(),
                    "configurationVersion", Integer.toString(version.getVersionNumber()),
                    "content", Base64.getEncoder().encodeToString(version.getContent()),
                    "uri", "hosted"));
        } catch (JsonProcessingException e) {
            throw invalid("The Lambda validator event could not be encoded.");
        }
        Job job = new Job(functionArn, sourceArn, sourceAccount, sourceRegion, payload, deadline);
        synchronized (this) {
            if (paused || closed) {
                throw invalid("Lambda validation is unavailable during reset or shutdown.");
            }
            if (jobs.size() >= capacity) {
                throw invalid("Lambda validator execution capacity is exhausted.");
            }
            jobs.add(job);
            try {
                workers.execute(() -> run(job));
            } catch (RejectedExecutionException e) {
                jobs.remove(job);
                throw invalid("Lambda validator execution is unavailable.");
            }
        }
        Outcome outcome;
        try {
            long remaining = deadline - nanoTime.getAsLong();
            if (remaining <= 0) {
                throw new TimeoutException();
            }
            outcome = job.result.get(remaining, TimeUnit.NANOSECONDS);
            if (outcome.completedAt() - deadline >= 0) {
                throw new TimeoutException();
            }
        } catch (TimeoutException e) {
            abandon(job);
            throw timedOut();
        } catch (InterruptedException e) {
            abandon(job);
            Thread.currentThread().interrupt();
            throw invalid("Lambda validator waiting was interrupted.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AwsException failure && "BadRequestException".equals(failure.getErrorCode())) {
                throw failure;
            }
            throw invalid("Lambda validator invocation failed: " + cause.getMessage());
        }
        InvokeResult result = outcome.result();
        if (result.getFunctionError() != null && !result.getFunctionError().isEmpty()) {
            String detail = result.getPayload() == null ? result.getFunctionError()
                    : new String(result.getPayload(), StandardCharsets.UTF_8);
            throw invalid("Lambda Function Error: " + detail);
        }
        if (result.getStatusCode() < 200 || result.getStatusCode() >= 300) {
            throw invalid("Lambda validator invocation did not succeed.");
        }
    }

    private void run(Job job) {
        try {
            synchronized (this) {
                requireAdmitted(job);
            }
            InvokeResult result = RequestScopes.callAs(job.account, job.region, () -> {
                if (authorizer.authorize("appconfig", "lambda:InvokeFunction", job.functionArn,
                        job.sourceArn, job.account) != Decision.ALLOW) {
                    throw invalid("AppConfig is not authorized to invoke the Lambda validator.");
                }
                synchronized (this) {
                    requireAdmitted(job);
                    job.invoking = true;
                }
                return lambda.invokeArn(job.functionArn, job.payload, InvocationType.RequestResponse);
            });
            job.result.complete(new Outcome(result, nanoTime.getAsLong()));
        } catch (RuntimeException | Error failure) {
            job.result.completeExceptionally(failure);
            if (failure instanceof Error error) {
                throw error;
            }
        } finally {
            synchronized (this) {
                jobs.remove(job);
                notifyAll();
            }
        }
    }

    private void requireAdmitted(Job job) {
        if (job.abandoned || closed || paused || nanoTime.getAsLong() - job.deadline >= 0) {
            throw timedOut();
        }
    }

    private synchronized void abandon(Job job) {
        // The worker acknowledges this before invocation. An already running Lambda keeps its owner
        // and capacity until run() exits; cancelling a Future here would not prove it had stopped.
        if (!job.invoking) {
            job.abandoned = true;
        }
    }

    @Override
    public synchronized void checkReset() {
        if (!jobs.isEmpty()) {
            throw new AwsException("ConflictException", "Lambda validator invocations are still active.", 409);
        }
    }

    @Override
    public synchronized void beforeReset() {
        checkReset();
        paused = true;
    }

    @Override
    public synchronized void clear() {
        checkReset();
    }

    @Override
    public synchronized void afterReset() {
        paused = false;
    }

    @PreDestroy
    public void shutdown() {
        synchronized (this) {
            closed = true;
            paused = true;
            jobs.forEach(job -> job.abandoned = true);
        }
        // Returned tasks never started. Acknowledge their closed admission so their owners
        // are released too; running workers remain owned until their actual finally block.
        workers.shutdownNow().forEach(Runnable::run);
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (!jobs.isEmpty()) {
                LOG.warnv("Shutdown has {0} undrained Lambda validator invocation owners.", jobs.size());
            }
        }
    }

    synchronized int pendingInvocations() {
        return jobs.size();
    }

    private AwsException timedOut() {
        return invalid("Lambda validator timed out after " + budget.toMillis() + " milliseconds.");
    }

    private static AwsException invalid(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    private record Outcome(InvokeResult result, long completedAt) {}

    private static final class Job {
        private final String functionArn;
        private final String sourceArn;
        private final String account;
        private final String region;
        private final byte[] payload;
        private final long deadline;
        private final CompletableFuture<Outcome> result = new CompletableFuture<>();
        private boolean invoking;
        private boolean abandoned;

        private Job(String functionArn, String sourceArn, String account, String region, byte[] payload,
                    long deadline) {
            this.functionArn = functionArn;
            this.sourceArn = sourceArn;
            this.account = account;
            this.region = region;
            this.payload = payload;
            this.deadline = deadline;
        }
    }
}
