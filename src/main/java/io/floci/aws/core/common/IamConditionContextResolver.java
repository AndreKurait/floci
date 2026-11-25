package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.acmpca.AcmPcaService;
import io.floci.aws.services.amazonmq.AmazonMqConfigurationService;
import io.floci.aws.services.apigateway.ApiGatewayDocumentationIamConditions;
import io.floci.aws.services.athena.AthenaBatchQueryService;
import io.floci.aws.services.athena.AthenaPreparedStatementService;
import io.floci.aws.services.backup.BackupFrameworkResources;
import io.floci.aws.services.backup.BackupFrameworkService;
import io.floci.aws.services.batch.BatchSchedulingPolicyService;
import io.floci.aws.services.batch.BatchSchedulingPolicyTarget;
import io.floci.aws.services.cloudformation.CloudFormationDriftService;
import io.floci.aws.services.cloudformation.CloudFormationDriftSnapshot;
import io.floci.aws.services.cloudfront.CloudFrontFunctionTestRoute;
import io.floci.aws.services.cloudfront.CloudFrontFunctionTesting;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsDeliveryService;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import io.floci.aws.services.cloudwatch.logs.LogGroupTagTarget;
import io.floci.aws.services.cloudwatch.logs.LogTransformerTarget;
import io.floci.aws.services.dynamodb.DynamoDbConditionKeys;
import io.floci.aws.services.dynamodb.DynamoDbFacade;
import io.floci.aws.services.dynamodb.DynamoDbPartiQLParser;
import io.floci.aws.services.dynamodb.DynamoDbTableNames;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import io.floci.aws.services.ec2.Ec2PlacementGroupService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Ec2PlacementGroup;
import io.floci.aws.services.ec2.model.LaunchTemplate;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.ecr.EcrService;
import io.floci.aws.services.eks.EksAccessEntryUpdateConditions;
import io.floci.aws.services.elbv2.ElbV2ConditionKeys;
import io.floci.aws.services.iam.IamActionRegistry;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.ResourceArnBuilder;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.IamUser;
import io.floci.aws.services.kinesis.KinesisService;
import io.floci.aws.services.kms.KmsGrantConditions;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.kms.model.KmsAlias;
import io.floci.aws.services.lambda.LambdaRuntimeManagementService;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambdaweb.LambdaWebService;
import io.floci.aws.services.lambdaweb.model.WebFunctionEndpoint;
import io.floci.aws.services.memorydb.MemoryDbParameterGroupService;
import io.floci.aws.services.memorydb.MemoryDbParameterGroupTarget;
import io.floci.aws.services.s3.S3ConditionKeys;
import io.floci.aws.services.s3.S3ControlTagConditionKeys;
import io.floci.aws.services.s3.S3ObjectActions;
import io.floci.aws.services.s3.S3ObjectKeys;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.S3SignatureConditions;
import io.floci.aws.services.ssm.SsmService;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Builds the IAM request context: the condition keys a policy's Condition block can match,
 * for the request currently being enforced.
 *
 * <p>Two keys are generic across services and populated the same way wherever they apply:
 * <ul>
 *   <li>{@code aws:RequestTag/<key>}, a tag the caller asks to attach, read from the request
 *       before the operation is applied.</li>
 *   <li>{@code aws:ResourceTag/<key>}, a tag already on the target resource, read from current
 *       state. {@link #resolve} carries the first target's tags and
 *       {@link #resolveRemainingTargets} one context per further target, so the filter can
 *       require the policy to hold for every resource the request names, as AWS does.</li>
 * </ul>
 */
@ApplicationScoped
public class IamConditionContextResolver {

    private static final Logger LOG = Logger.getLogger(IamConditionContextResolver.class);
    /** Reads a resource EC2 holds as its fields, java.time ones included. */
    private static final ObjectMapper RESOURCE_MAPPER = new ObjectMapper().findAndRegisterModules();

    /** Set by {@code ResourceArnBuilder.readJsonBody}, which runs earlier in the same filter pass. */
    static final String BUFFERED_JSON_BODY = "floci.bufferedJsonBody";
    private static final String BUFFERED_FORM_BODY = "floci.bufferedFormBody";
    /** Set on a request that carries none of its operation's members, see {@link #readNoMembers}. */
    private static final String NO_MEMBERS = "floci.noRequestMembers";
    /** Prefixes the action whose numbered EC2 targets {@link #heldTargets} looked up for the request. */
    private static final String HELD_TARGETS = "floci.heldTargets.";
    private static final String REQUEST_TAG_PREFIX = "aws:RequestTag/";
    private static final String RESOURCE_TAG_PREFIX = "aws:ResourceTag/";
    /**
     * The services that set {@code aws:SourceOwner} when they publish to an SNS topic, a deprecated key
     * SNS keeps for the integrations that had it (SNS Developer Guide, "aws:SourceAccount versus
     * aws:SourceOwner"). The guide lists more; these are the ones that call through
     * {@link ServiceCallAuthorizer}.
     */
    private static final Set<String> SNS_SOURCE_OWNER_SERVICES = Set.of("ses");
    public static final String EXISTING_OBJECT_TAG_PREFIX = "s3:ExistingObjectTag/";
    public static final String REQUEST_OBJECT_TAG_PREFIX = "s3:RequestObjectTag/";
    public static final String REQUEST_OBJECT_TAG_KEYS = "s3:RequestObjectTagKeys";
    public static final String VERSION_ID = "s3:versionid";
    public static final String OBJECT_LOCK_MODE = "s3:object-lock-mode";
    public static final String OBJECT_LOCK_RETAIN_UNTIL_DATE = "s3:object-lock-retain-until-date";
    public static final String OBJECT_LOCK_REMAINING_RETENTION_DAYS = "s3:object-lock-remaining-retention-days";
    public static final String OBJECT_LOCK_LEGAL_HOLD = "s3:object-lock-legal-hold";
    public static final String SERVER_SIDE_ENCRYPTION = "s3:x-amz-server-side-encryption";
    public static final String SERVER_SIDE_ENCRYPTION_KMS_KEY_ID = "s3:x-amz-server-side-encryption-aws-kms-key-id";
    static final String REQUEST_AUTH_TYPE = "lambda:Request/WebFunctionAuthType";
    static final String RESOURCE_AUTH_TYPE = "lambda:Resource/WebFunctionAuthType";

    private final Instance<AmazonMqConfigurationService> mqConfigurations;

    @Inject
    Instance<BackupFrameworkService> backupFrameworks;

    @Inject
    Instance<AcmPcaService> privateAuthorities;

    @Inject
    Instance<ApiGatewayDocumentationIamConditions> documentationConditions;

    @Inject
    Instance<CloudFormationDriftService> driftService;

    /** Resolved lazily: KMS's own condition keys need the aliases of the key a request names. */
    @Inject
    Instance<KmsService> kmsService;

    private final Instance<DynamoDbFacade> dynamoDbFacade;
    private final Instance<Ec2Service> ec2Service;
    /** Lazy to keep the existing IAM construction graph independent of EC2 storage creation. */
    @Inject
    Instance<Ec2PlacementGroupService> placementGroupService;
    private final Instance<EcrService> ecrService;
    private final Instance<S3Service> s3Service;
    private final Instance<IamService> iamService;
    private final Instance<LambdaWebService> lambdaWebService;
    private final Instance<RequestConditionSpelling> requestConditionSpellings;
    private final Instance<StoredResourceArns> storedResourceArns;
    private final RequestContext requestContext;
    private final RegionResolver regionResolver;
    private final Instance<LambdaService> lambdaService;
    private final Instance<KinesisService> kinesisService;
    private final Instance<LambdaRuntimeManagementService> runtimeManagement;
    private final Instance<SsmService> ssmService;
    private final Instance<ElbV2ConditionKeys> elbV2ConditionKeys;
    private final Instance<AthenaPreparedStatementService> athenaPreparedStatements;
    private final EmulatorConfig config;
    private final CurrentVertxRequest currentVertxRequest;
    private final Clock clock;
    private final Instance<AthenaBatchQueryService> athenaBatchQueryService;
    private final Instance<EksAccessEntryUpdateConditions> eksAccessEntryUpdates;
    private final Instance<CloudFrontFunctionTesting> cloudFrontFunctionTesting;
    private final ResolvedServiceCatalog serviceCatalog;
    private final Instance<BatchSchedulingPolicyService> batchSchedulingPolicies;

    private final Instance<MemoryDbParameterGroupService> memoryDbParameterGroups;

    private final Instance<CloudWatchLogsService> logsService;

    @Inject
    public IamConditionContextResolver(Instance<DynamoDbFacade> dynamoDbFacade,
            Instance<Ec2Service> ec2Service,
            Instance<S3Service> s3Service,
            Instance<CloudWatchLogsService> logsService,
            Instance<IamService> iamService,
            Instance<LambdaWebService> lambdaWebService,
            RequestContext requestContext,
            EmulatorConfig config,
            CurrentVertxRequest currentVertxRequest,
            ResolvedServiceCatalog serviceCatalog,
            Instance<SsmService> ssmService,
            Instance<ElbV2ConditionKeys> elbV2ConditionKeys,
            Instance<AmazonMqConfigurationService> mqConfigurations,
            Instance<LambdaService> lambdaService,
            Instance<AthenaPreparedStatementService> athenaPreparedStatements,
            Instance<KinesisService> kinesisService,
            Instance<AthenaBatchQueryService> athenaBatchQueryService,
            Instance<EksAccessEntryUpdateConditions> eksAccessEntryUpdates,
            Instance<LambdaRuntimeManagementService> runtimeManagement,
            Instance<EcrService> ecrService,
            Instance<CloudFrontFunctionTesting> cloudFrontFunctionTesting,
            Instance<MemoryDbParameterGroupService> memoryDbParameterGroups,
            Instance<BatchSchedulingPolicyService> batchSchedulingPolicies,
            Instance<RequestConditionSpelling> requestConditionSpellings,
            Instance<StoredResourceArns> storedResourceArns,
            RegionResolver regionResolver) {
        this(dynamoDbFacade, ec2Service, s3Service, logsService, iamService, lambdaWebService, requestContext,
                config, currentVertxRequest, serviceCatalog, ssmService, elbV2ConditionKeys, mqConfigurations,
                lambdaService, athenaPreparedStatements, kinesisService, athenaBatchQueryService,
                eksAccessEntryUpdates, runtimeManagement, ecrService, cloudFrontFunctionTesting,
                memoryDbParameterGroups, batchSchedulingPolicies, requestConditionSpellings, storedResourceArns,
                regionResolver, Clock.systemUTC());
    }

    public IamConditionContextResolver(Instance<DynamoDbFacade> dynamoDbFacade,
            Instance<Ec2Service> ec2Service,
            Instance<S3Service> s3Service,
            Instance<IamService> iamService,
            Instance<LambdaWebService> lambdaWebService,
            Instance<RequestConditionSpelling> requestConditionSpellings,
            Instance<StoredResourceArns> storedResourceArns,
            RequestContext requestContext,
            RegionResolver regionResolver,
            EmulatorConfig config,
            CurrentVertxRequest currentVertxRequest,
            ResolvedServiceCatalog serviceCatalog) {
        this(dynamoDbFacade, ec2Service, s3Service, null, iamService, lambdaWebService, requestContext, config,
                currentVertxRequest, serviceCatalog, null, null, null, null, null, null, null, null, null, null,
                null, null, null, requestConditionSpellings, storedResourceArns, regionResolver,
                Clock.systemUTC());
    }

    IamConditionContextResolver(Instance<DynamoDbFacade> dynamoDbFacade,
            Instance<Ec2Service> ec2Service,
            Instance<S3Service> s3Service,
            Instance<IamService> iamService,
            Instance<LambdaWebService> lambdaWebService,
            Instance<RequestConditionSpelling> requestConditionSpellings,
            Instance<StoredResourceArns> storedResourceArns,
            RequestContext requestContext,
            RegionResolver regionResolver,
            EmulatorConfig config,
            CurrentVertxRequest currentVertxRequest,
            ResolvedServiceCatalog serviceCatalog,
            Clock clock) {
        this(dynamoDbFacade, ec2Service, s3Service, null, iamService, lambdaWebService, requestContext, config,
                currentVertxRequest, serviceCatalog, null, null, null, null, null, null, null, null, null, null,
                null, null, null, requestConditionSpellings, storedResourceArns, regionResolver, clock);
    }

    public IamConditionContextResolver(Instance<DynamoDbFacade> dynamoDbFacade,
            Instance<Ec2Service> ec2Service,
            Instance<S3Service> s3Service,
            Instance<CloudWatchLogsService> logsService,
            Instance<IamService> iamService,
            Instance<LambdaWebService> lambdaWebService,
            RequestContext requestContext,
            EmulatorConfig config,
            CurrentVertxRequest currentVertxRequest,
            ResolvedServiceCatalog serviceCatalog,
            Instance<SsmService> ssmService,
            Instance<ElbV2ConditionKeys> elbV2ConditionKeys,
            Instance<AmazonMqConfigurationService> mqConfigurations,
            Instance<LambdaService> lambdaService,
            Instance<AthenaPreparedStatementService> athenaPreparedStatements,
            Instance<KinesisService> kinesisService,
            Instance<AthenaBatchQueryService> athenaBatchQueryService,
            Instance<EksAccessEntryUpdateConditions> eksAccessEntryUpdates,
            Instance<LambdaRuntimeManagementService> runtimeManagement,
            Instance<EcrService> ecrService,
            Instance<CloudFrontFunctionTesting> cloudFrontFunctionTesting,
            Instance<MemoryDbParameterGroupService> memoryDbParameterGroups,
            Instance<BatchSchedulingPolicyService> batchSchedulingPolicies) {
        this(dynamoDbFacade, ec2Service, s3Service, logsService, iamService, lambdaWebService, requestContext,
                config, currentVertxRequest, serviceCatalog, ssmService, elbV2ConditionKeys, mqConfigurations,
                lambdaService, athenaPreparedStatements, kinesisService, athenaBatchQueryService,
                eksAccessEntryUpdates, runtimeManagement, ecrService, cloudFrontFunctionTesting,
                memoryDbParameterGroups, batchSchedulingPolicies, null, null, null, Clock.systemUTC());
    }

    public IamConditionContextResolver(Instance<DynamoDbFacade> dynamoDbFacade,
            Instance<Ec2Service> ec2Service,
            Instance<S3Service> s3Service,
            Instance<CloudWatchLogsService> logsService,
            Instance<IamService> iamService,
            Instance<LambdaWebService> lambdaWebService,
            RequestContext requestContext,
            EmulatorConfig config,
            CurrentVertxRequest currentVertxRequest,
            ResolvedServiceCatalog serviceCatalog,
            Instance<SsmService> ssmService,
            Instance<ElbV2ConditionKeys> elbV2ConditionKeys) {
        this(dynamoDbFacade, ec2Service, s3Service, logsService, iamService, lambdaWebService, requestContext,
                config, currentVertxRequest, serviceCatalog, ssmService, elbV2ConditionKeys, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, Clock.systemUTC());
    }

    public IamConditionContextResolver(Instance<DynamoDbFacade> dynamoDbFacade,
            Instance<Ec2Service> ec2Service,
            Instance<S3Service> s3Service,
            Instance<CloudWatchLogsService> logsService,
            Instance<IamService> iamService,
            Instance<LambdaWebService> lambdaWebService,
            RequestContext requestContext,
            EmulatorConfig config,
            CurrentVertxRequest currentVertxRequest,
            ResolvedServiceCatalog serviceCatalog) {
        this(dynamoDbFacade, ec2Service, s3Service, logsService, iamService, lambdaWebService, requestContext,
                config, currentVertxRequest, serviceCatalog, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, Clock.systemUTC());
    }

    IamConditionContextResolver(Instance<DynamoDbFacade> dynamoDbFacade,
            Instance<Ec2Service> ec2Service,
            Instance<S3Service> s3Service,
            Instance<CloudWatchLogsService> logsService,
            Instance<IamService> iamService,
            Instance<LambdaWebService> lambdaWebService,
            RequestContext requestContext,
            EmulatorConfig config,
            CurrentVertxRequest currentVertxRequest,
            ResolvedServiceCatalog serviceCatalog,
            Clock clock) {
        this(dynamoDbFacade, ec2Service, s3Service, logsService, iamService, lambdaWebService, requestContext,
                config, currentVertxRequest, serviceCatalog, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, clock);
    }

    private IamConditionContextResolver(Instance<DynamoDbFacade> dynamoDbFacade,
            Instance<Ec2Service> ec2Service,
            Instance<S3Service> s3Service,
            Instance<CloudWatchLogsService> logsService,
            Instance<IamService> iamService,
            Instance<LambdaWebService> lambdaWebService,
            RequestContext requestContext,
            EmulatorConfig config,
            CurrentVertxRequest currentVertxRequest,
            ResolvedServiceCatalog serviceCatalog,
            Clock clock,
            Instance<SsmService> ssmService,
            Instance<ElbV2ConditionKeys> elbV2ConditionKeys,
            Instance<AmazonMqConfigurationService> mqConfigurations,
            Instance<LambdaService> lambdaService,
            Instance<AthenaPreparedStatementService> athenaPreparedStatements,
            Instance<KinesisService> kinesisService,
            Instance<AthenaBatchQueryService> athenaBatchQueryService,
            Instance<EksAccessEntryUpdateConditions> eksAccessEntryUpdates,
            Instance<LambdaRuntimeManagementService> runtimeManagement,
            Instance<EcrService> ecrService,
            Instance<CloudFrontFunctionTesting> cloudFrontFunctionTesting,
            Instance<MemoryDbParameterGroupService> memoryDbParameterGroups,
            Instance<BatchSchedulingPolicyService> batchSchedulingPolicies) {
        this(dynamoDbFacade, ec2Service, s3Service, logsService, iamService, lambdaWebService, requestContext,
                config, currentVertxRequest, serviceCatalog, ssmService, elbV2ConditionKeys, mqConfigurations,
                lambdaService, athenaPreparedStatements, kinesisService, athenaBatchQueryService,
                eksAccessEntryUpdates, runtimeManagement, ecrService, cloudFrontFunctionTesting,
                memoryDbParameterGroups, batchSchedulingPolicies, null, null, null, clock);
    }

    private IamConditionContextResolver(Instance<DynamoDbFacade> dynamoDbFacade,
            Instance<Ec2Service> ec2Service,
            Instance<S3Service> s3Service,
            Instance<CloudWatchLogsService> logsService,
            Instance<IamService> iamService,
            Instance<LambdaWebService> lambdaWebService,
            RequestContext requestContext,
            EmulatorConfig config,
            CurrentVertxRequest currentVertxRequest,
            ResolvedServiceCatalog serviceCatalog,
            Instance<SsmService> ssmService,
            Instance<ElbV2ConditionKeys> elbV2ConditionKeys,
            Instance<AmazonMqConfigurationService> mqConfigurations,
            Instance<LambdaService> lambdaService,
            Instance<AthenaPreparedStatementService> athenaPreparedStatements,
            Instance<KinesisService> kinesisService,
            Instance<AthenaBatchQueryService> athenaBatchQueryService,
            Instance<EksAccessEntryUpdateConditions> eksAccessEntryUpdates,
            Instance<LambdaRuntimeManagementService> runtimeManagement,
            Instance<EcrService> ecrService,
            Instance<CloudFrontFunctionTesting> cloudFrontFunctionTesting,
            Instance<MemoryDbParameterGroupService> memoryDbParameterGroups,
            Instance<BatchSchedulingPolicyService> batchSchedulingPolicies,
            Instance<RequestConditionSpelling> requestConditionSpellings,
            Instance<StoredResourceArns> storedResourceArns,
            RegionResolver regionResolver,
            Clock clock) {
        this.dynamoDbFacade = dynamoDbFacade;
        this.ec2Service = ec2Service;
        this.s3Service = s3Service;
        this.logsService = logsService;
        this.iamService = iamService;
        this.lambdaWebService = lambdaWebService;
        this.requestContext = requestContext;
        this.config = config;
        this.currentVertxRequest = currentVertxRequest;
        this.serviceCatalog = serviceCatalog;
        this.ssmService = ssmService;
        this.elbV2ConditionKeys = elbV2ConditionKeys;
        this.mqConfigurations = mqConfigurations;
        this.lambdaService = lambdaService;
        this.athenaPreparedStatements = athenaPreparedStatements;
        this.kinesisService = kinesisService;
        this.athenaBatchQueryService = athenaBatchQueryService;
        this.eksAccessEntryUpdates = eksAccessEntryUpdates;
        this.runtimeManagement = runtimeManagement;
        this.ecrService = ecrService;
        this.cloudFrontFunctionTesting = cloudFrontFunctionTesting;
        this.memoryDbParameterGroups = memoryDbParameterGroups;
        this.batchSchedulingPolicies = batchSchedulingPolicies;
        this.requestConditionSpellings = requestConditionSpellings;
        this.storedResourceArns = storedResourceArns;
        this.regionResolver = regionResolver != null ? regionResolver
                : new RegionResolver(config.defaultRegion(), config.defaultAccountId(),
                        config.partitions() == null ? AwsRegions.partitionFor(config.defaultRegion())
                                : RegionResolver.effectivePartition(config.defaultRegion(), config.partitions().id()));
        this.clock = clock;
    }

    private Map<String, List<String>> athenaPreparedStatementConditions(String action, ContainerRequestContext ctx) {
        if (!AthenaPreparedStatementService.supportsAction(action)
                || athenaPreparedStatements == null || !athenaPreparedStatements.isResolvable()) {
            return null;
        }
        JsonNode body = bufferedJsonBody(ctx);
        if (body == null || !body.path("WorkGroup").isTextual()) {
            return null;
        }
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        try {
            return athenaPreparedStatements.get().resourceTagConditions(region, body.path("WorkGroup").textValue());
        } catch (AwsException expected) {
            // The handler validates the same selector; an absent workgroup has no resource tags.
            return null;
        }
    }

    private Map<String, List<String>> kinesisConditionContext(String action, ContainerRequestContext ctx) {
        if (kinesisService == null || !kinesisService.isResolvable() || !Set.of("kinesis:GetResourcePolicy",
                "kinesis:PutResourcePolicy", "kinesis:DeleteResourcePolicy").contains(action)) {
            return null;
        }
        JsonNode body = bufferedJsonBody(ctx);
        if (body == null || !body.path("ResourceARN").isTextual()) {
            return null;
        }
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        Map<String, List<String>> context = new LinkedHashMap<>();
        try {
            kinesisService.get().resourcePolicyTags(body.path("ResourceARN").textValue(), region)
                    .forEach((key, value) -> context.put(RESOURCE_TAG_PREFIX + key, List.of(value)));
        } catch (AwsException expected) {
            // Missing or foreign resources provide no tags; the handler returns its resource error if authorized.
        }
        return context;
    }

    private Map<String, List<String>> cloudFrontTestConditionContext(String action, ContainerRequestContext ctx) {
        if (!"cloudfront:TestFunction".equals(action) || cloudFrontFunctionTesting == null
                || !cloudFrontFunctionTesting.isResolvable()) {
            return null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        cloudFrontFunctionTesting.get().currentTags(CloudFrontFunctionTestRoute.name(ctx))
                .forEach((key, value) -> conditions.put(RESOURCE_TAG_PREFIX + key, List.of(value)));
        return conditions.isEmpty() ? null : conditions;
    }

    /**
     * The condition keys the tags of the resource {@code resource} names give: each resource-tag key the
     * request-resources table gives the ARN's type ({@code aws:ResourceTag/<key>},
     * {@code secretsmanager:ResourceTag/<key>}) for each tag the service of the ARN's namespace holds on it
     * ({@link StoredResourceArns#storedTags}), whatever the namespace; null when the table gives the type no such keys or
     * the service holds no tags there. Tag keys differing only in case are one condition key, as IAM's keys are,
     * holding every value, so a set operator sees each. Read for each ARN on its own, never borrowed from another
     * target in the same request; {@link #withResourceTags} puts them in that ARN's context.
     */
    public Map<String, List<String>> resourceTags(String resource) {
        return resourceTags(null, resource);
    }

    /** The resource's stored tags under its own and the caller's keys, excluding keys the action does not read. */
    public Map<String, List<String>> resourceTags(String action, String resource) {
        if (!AwsArnUtils.isArn(resource)) {
            return null;
        }
        String namespace = AwsArnUtils.parse(resource).service();
        Map<String, List<String>> types = RequestResourceMembers.table().resourceTagKeys()
                .getOrDefault(namespace, Map.of());
        StoredResourceArns stored = storedResourceArns(namespace);
        Map<String, String> tags = stored == null ? Map.of() : stored.storedTags(resource);
        Set<String> prefixes = new LinkedHashSet<>(resourceTagPrefixes(namespace, resource, types));
        if (action != null && action.contains(":")) {
            String callerNamespace = action.substring(0, action.indexOf(':'));
            Map<String, List<String>> callerTypes = RequestResourceMembers.table().resourceTagKeys()
                    .getOrDefault(callerNamespace, Map.of());
            prefixes.addAll(resourceTagPrefixes(callerNamespace, resource, callerTypes));
        }
        if (tags.isEmpty() || prefixes.isEmpty()) {
            return null;
        }
        Map<String, List<String>> conditions = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        tags.forEach((key, value) -> prefixes.forEach(prefix ->
                conditions.computeIfAbsent(prefix + key, ignored -> new ArrayList<>()).add(value)));
        Set<String> unread = IamConditionKeys.unreadTagPrefixes(action);
        if (unread.isEmpty()) {
            return conditions;
        }
        Map<String, List<String>> read = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        conditions.forEach((key, values) -> {
            if (unread.stream().noneMatch(prefix -> key.regionMatches(true, 0, prefix, 0, prefix.length()))) {
                read.put(key, values);
            }
        });
        return read.isEmpty() ? null : read;
    }

    /**
     * The condition keys {@code tags}, the tags of the resource {@code resource} names, give it: each resource-tag key
     * the request-resources table gives the ARN's type for each tag, as {@link #resourceTags} gives them for the tags a
     * service holds, for a caller that holds the resource itself (the role a trust policy belongs to). Null when the
     * table gives the type no such keys or there are no tags.
     */
    public static Map<String, List<String>> resourceTagKeys(String resource, Map<String, String> tags) {
        if (!AwsArnUtils.isArn(resource) || tags == null || tags.isEmpty()) {
            return null;
        }
        String namespace = AwsArnUtils.parse(resource).service();
        Map<String, List<String>> types = RequestResourceMembers.table().resourceTagKeys()
                .getOrDefault(namespace, Map.of());
        List<String> prefixes = types.isEmpty() ? List.of() : resourceTagPrefixes(namespace, resource, types);
        if (prefixes.isEmpty()) {
            return null;
        }
        Map<String, List<String>> conditions = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        tags.forEach((key, value) -> prefixes.forEach(prefix ->
                conditions.computeIfAbsent(prefix + key, ignored -> new ArrayList<>()).add(value)));
        return conditions;
    }

    /**
     * The condition keys the table reads from the record of the resource {@code resource} names for
     * {@code action}: what the service holding that resource keeps in the member the table asks for
     * ({@link StoredResourceArns#recorded}), by key. Null when the action takes none, the ARN is of no type the
     * table gives such a key, or the record holds nothing there, which is every resource a request names before
     * the value is set on it. Read for each ARN on its own, as the stored tags are, and
     * {@link #withResourceTags} puts them in that ARN's context.
     */
    public Map<String, List<String>> resourceKeys(String action, String resource) {
        Map<String, IamConditionKeys.Resource> keys = IamConditionKeys.resources(action);
        if (keys.isEmpty() || !AwsArnUtils.isArn(resource)) {
            return null;
        }
        StoredResourceArns stored = storedResourceArns(AwsArnUtils.parse(resource).service());
        if (stored == null) {
            return null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        keys.forEach((key, held) -> {
            if (held.arn().matcher(resource).matches()) {
                stored.recorded(resource, held.member()).ifPresent(value -> conditions.put(key, List.of(value)));
            }
        });
        return conditions.isEmpty() ? null : conditions;
    }

    /**
     * {@code context} with the condition keys read from a resource itself, its stored tags through
     * {@link #resourceTags} or its record through {@link #resourceKeys}, each in place of
     * any key of the same name, case aside, the context held; {@code context} itself when there are none.
     */
    public static Map<String, List<String>> withResourceTags(Map<String, List<String>> context,
                                                             Map<String, List<String>> resourceTags) {
        if (resourceTags == null || resourceTags.isEmpty()) {
            return context;
        }
        Map<String, List<String>> result = context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context);
        resourceTags.forEach((key, values) -> {
            result.keySet().removeIf(key::equalsIgnoreCase);
            result.put(key, List.copyOf(values));
        });
        return result;
    }

    /**
     * The resource-tag key prefixes {@code types} gives the namespace's type whose ARN format {@code resource} fits,
     * the longest format where several do (a type whose ARN extends another's is its own); none when it fits none.
     */
    private static List<String> resourceTagPrefixes(String namespace, String resource,
                                                    Map<String, List<String>> types) {
        Map<String, RequestResourceMembers.ArnFormat> formats = RequestResourceMembers.table().formats()
                .getOrDefault(namespace, Map.of());
        RequestResourceMembers.ArnFormat fitted = null;
        List<String> prefixes = List.of();
        for (Map.Entry<String, List<String>> type : types.entrySet()) {
            RequestResourceMembers.ArnFormat format = formats.get(type.getKey());
            if (format != null && format.pattern().matcher(resource).matches()
                    && (fitted == null || format.format().length() > fitted.format().length())) {
                fitted = format;
                prefixes = type.getValue();
            }
        }
        return prefixes;
    }

    /** The registered finder of the namespace's stored resources; null when there is none. */
    private StoredResourceArns storedResourceArns(String namespace) {
        if (storedResourceArns == null || storedResourceArns.isUnsatisfied()) {
            return null;
        }
        for (StoredResourceArns stored : storedResourceArns) {
            if (stored.namespace().equals(namespace)) {
                return stored;
            }
        }
        return null;
    }

    public Map<String, List<String>> resolve(String credentialScope, String action,
                                             ContainerRequestContext ctx) {
        Map<String, List<String>> serviceConditions = switch (credentialScope) {
            case "backup" -> backupFrameworkConditions(action, ctx);
            case "apigateway" -> documentationConditions != null && documentationConditions.isResolvable()
                    ? documentationConditions.get().resolve(action, ctx) : null;
            case "cloudformation" -> driftConditionContext(action, ctx);
            case "batch" -> batchSchedulingPolicyConditions(action, ctx);
            case "account" -> accountRegionConditionContext(action, ctx);
            case "s3" -> s3ConditionContext(action, ctx);
            case "kms" -> kmsConditionContext(action, ctx);
            case "cloudfront" -> cloudFrontTestConditionContext(action, ctx);
            case "ec2" -> ec2ConditionContext(action, ctx);
            case "ecr" -> ecrPreviewConditionContext(action, ctx);
            case "elasticloadbalancing" -> elbV2Contexts(action, ctx).stream().findFirst().orElse(null);
            case "dynamodb" -> dynamoDbConditionContext(action, ctx);
            case "iam" -> iamConditionContext(action, ctx);
            case "acm-pca" -> privateCaConditionContext(action, ctx);
            case "logs" -> logsConditionContext(action, ctx);
            case "mq" -> mqConfigurationConditions(action, ctx);
            case "lambda" -> lambdaConditionContext(action, ctx);
            case "ssm" -> ssmSyncConditionContext(action, ctx);
            case "athena" -> athenaPreparedStatementConditions(action, ctx);
            case "kinesis" -> kinesisConditionContext(action, ctx);
            case "eks" -> "eks:UpdateAccessEntry".equals(action) && eksAccessEntryUpdates != null
                    && eksAccessEntryUpdates.isResolvable() ? eksAccessEntryUpdates.get().resolve(ctx) : null;
            case "memorydb" -> memoryDbParameterGroupConditions(action, ctx);
            case "redshift" -> redshiftConditionContext(action, ctx);
            case "states" -> stepFunctionsConditionContext(action, ctx);
            default -> null;
        };
        if (Set.of("s3", "ec2", "dynamodb", "iam", "acm-pca", "logs", "lambda").contains(credentialScope)) {
            return serviceConditions;
        }
        Map<String, List<String>> modeled = requestMemberConditions(action, ctx);
        if (modeled == null || modeled.isEmpty()) {
            return serviceConditions;
        }
        Map<String, List<String>> combined = new LinkedHashMap<>(modeled);
        if (serviceConditions != null) {
            combined.putAll(serviceConditions);
        }
        return combined;
    }

    /**
     * KMS's own condition keys: the request's encryption context; kms:ResourceAliases, every alias of
     * the key its KeyId names (by id, ARN, alias name or alias ARN); kms:RequestAlias, the alias name
     * when the KeyId is an alias name or alias ARN; and on CreateGrant kms:GrantOperations, the
     * operations it grants. A KeyId naming no key the account holds adds no alias, so the handler
     * still answers its own NotFoundException.
     */
    private Map<String, List<String>> kmsConditionContext(String action, ContainerRequestContext ctx) {
        JsonNode body = bufferedJsonBody(ctx);
        if (body == null || !body.isObject()) {
            return null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        String keyId = body.path("KeyId").isTextual() ? body.path("KeyId").textValue() : null;
        if (keyId != null && !keyId.isBlank() && kmsService != null && kmsService.isResolvable()) {
            String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
            try {
                List<String> aliases = kmsService.get().listAliases(keyId, region).stream()
                        .map(KmsAlias::getAliasName).filter(name -> name != null && !name.isBlank()).sorted().toList();
                if (!aliases.isEmpty()) {
                    conditions.put("kms:ResourceAliases", aliases);
                }
            } catch (AwsException expected) {
                // No such key: the handler answers NotFoundException.
            }
        }
        String requestAlias = kmsRequestAlias(keyId);
        if (requestAlias != null) {
            conditions.put("kms:RequestAlias", List.of(requestAlias));
        }
        if ("kms:CreateGrant".equals(action) && body.path("Operations").isArray()) {
            List<String> operations = new ArrayList<>();
            body.path("Operations").forEach(operation -> operations.add(operation.asText()));
            conditions.put("kms:GrantOperations", operations);
        }
        if (!body.path("EncryptionContext").isObject()) {
            return conditions.isEmpty() ? null : conditions;
        }
        List<String> keys = new ArrayList<>();
        body.path("EncryptionContext").properties().forEach(entry -> {
            if (entry.getValue().isTextual()) {
                keys.add(entry.getKey());
                conditions.put("kms:EncryptionContext:" + entry.getKey(), List.of(entry.getValue().textValue()));
                conditions.put(KmsGrantConditions.encryptionContextKey(entry.getKey()),
                        List.of(entry.getValue().textValue()));
            }
        });
        if (!keys.isEmpty()) {
            conditions.put("kms:EncryptionContextKeys", keys);
        }
        return conditions;
    }

    /** The alias name ({@code alias/...}) a KeyId names by alias name or alias ARN, or null for a key id or key ARN. */
    static String kmsRequestAlias(String keyId) {
        if (keyId == null) {
            return null;
        }
        if (keyId.startsWith("alias/")) {
            return keyId;
        }
        if (!AwsArnUtils.isArn(keyId)) {
            return null;
        }
        String resource = AwsArnUtils.parse(keyId).resource();
        return resource.startsWith("alias/") ? resource : null;
    }

    private Map<String, List<String>> memoryDbParameterGroupConditions(String action, ContainerRequestContext ctx) {
        JsonNode body = bufferedJsonBody(ctx);
        if (body == null || !body.isObject() || !MemoryDbParameterGroupTarget.claims(action, body)) {
            return null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        if (("memorydb:CreateParameterGroup".equals(action) || "memorydb:TagResource".equals(action))
                && body.path("Tags").isArray()) {
            Set<String> keys = new LinkedHashSet<>();
            for (JsonNode tag : body.path("Tags")) {
                if (tag.path("Key").isTextual()
                        && (tag.path("Value").isTextual() || tag.path("Value").isNull() || !tag.has("Value"))) {
                    String key = tag.path("Key").textValue();
                    keys.add(key);
                    conditions.put(REQUEST_TAG_PREFIX + key, List.of(tag.path("Value").isTextual()
                            ? tag.path("Value").textValue() : ""));
                }
            }
            if (!keys.isEmpty()) {
                conditions.put("aws:TagKeys", List.copyOf(keys));
            }
        } else if ("memorydb:UntagResource".equals(action) && body.path("TagKeys").isArray()) {
            List<String> keys = new ArrayList<>();
            body.path("TagKeys").forEach(key -> {
                if (key.isTextual()) {
                    keys.add(key.textValue());
                }
            });
            if (!keys.isEmpty()) {
                conditions.put("aws:TagKeys", keys);
            }
        }
        if (!"memorydb:CreateParameterGroup".equals(action)
                && memoryDbParameterGroups != null && memoryDbParameterGroups.isResolvable()) {
            String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
            String account = requestContext.getAccountId() == null ? config.defaultAccountId() : requestContext.getAccountId();
            String partition = requestContext.getPartition() == null
                    ? AwsRegions.partitionFor(region) : requestContext.getPartition();
            String arn = MemoryDbParameterGroupTarget.resourceArn(action, body, partition, region, account);
            memoryDbParameterGroups.get().iamTags(arn, partition, region, account).forEach((key, value) ->
                    conditions.put(RESOURCE_TAG_PREFIX + key, List.of(value)));
        }
        return conditions.isEmpty() ? null : conditions;
    }

    private Map<String, List<String>> batchSchedulingPolicyConditions(String action, ContainerRequestContext ctx) {
        boolean create = "batch:CreateSchedulingPolicy".equals(action);
        boolean selected = "batch:UpdateSchedulingPolicy".equals(action) || "batch:DeleteSchedulingPolicy".equals(action);
        boolean tag = "batch:TagResource".equals(action);
        boolean untag = "batch:UntagResource".equals(action);
        boolean listTags = "batch:ListTagsForResource".equals(action);
        String arn = BatchSchedulingPolicyTarget.taggedArn(ctx);
        if (!create && !selected && (!(tag || untag || listTags) || !BatchSchedulingPolicyTarget.isPolicySelector(arn))) {
            return null;
        }
        JsonNode body = create || selected || tag ? PassedRoles.jsonBody(ctx) : null;
        if (selected) {
            arn = body != null && body.path("arn").isTextual() ? body.path("arn").textValue() : null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        if ((create || tag) && body != null && body.path("tags").isObject()) {
            List<String> keys = new ArrayList<>();
            body.path("tags").properties().forEach(entry -> {
                if (entry.getValue().isTextual()) {
                    keys.add(entry.getKey());
                    conditions.put(REQUEST_TAG_PREFIX + entry.getKey(), List.of(entry.getValue().textValue()));
                }
            });
            if (!keys.isEmpty()) {
                conditions.put("aws:TagKeys", keys);
            }
        }
        if (untag) {
            List<String> keys = ctx.getUriInfo().getQueryParameters().get("tagKeys");
            if (keys != null && !keys.isEmpty()) {
                conditions.put("aws:TagKeys", List.copyOf(keys));
            }
        }
        if (!create && batchSchedulingPolicies != null && batchSchedulingPolicies.isResolvable()) {
            String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
            String account = requestContext.getAccountId() == null ? config.defaultAccountId() : requestContext.getAccountId();
            String partition = requestContext.getPartition() == null
                    ? RegionResolver.effectivePartition(config.defaultRegion(), config.partitions().id())
                    : requestContext.getPartition();
            batchSchedulingPolicies.get().iamTags(arn, partition, region, account).forEach((key, value) ->
                    conditions.put(RESOURCE_TAG_PREFIX + key, List.of(value)));
        }
        return conditions.isEmpty() ? null : conditions;
    }

    private List<Map<String, List<String>>> elbV2Contexts(String action, ContainerRequestContext ctx) {
        return elbV2ConditionKeys != null && elbV2ConditionKeys.isResolvable()
                ? elbV2ConditionKeys.get().resourceContexts(action, formParameters(ctx)) : List.of();
    }

    /**
     * These calls authorize the base state-machine ARN. A version or alias in the
     * request is a qualifier condition, including when an alias selects a version.
     */
    private Map<String, List<String>> stepFunctionsConditionContext(String action, ContainerRequestContext ctx) {
        if (!("states:DescribeStateMachine".equals(action) || "states:StartExecution".equals(action))) {
            return null;
        }
        JsonNode body = bufferedJsonBody(ctx);
        String value = body == null ? null : body.path("stateMachineArn").asText(null);
        if (value == null) {
            return null;
        }
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(value);
            String[] resource = arn.resource().split(":", -1);
            if ("states".equals(arn.service()) && resource.length == 3
                    && "stateMachine".equals(resource[0]) && !resource[1].isBlank() && !resource[2].isBlank()) {
                return Map.of("states:StateMachineQualifier", List.of(resource[2]));
            }
        } catch (IllegalArgumentException expected) {
            // Leave malformed selectors to the ordinary authorization and API validation.
        }
        return null;
    }

    /** Run Command checks each document and target with that resource's own tags. */
    public Map<String, List<String>> ssmCommandResourceContext(String resource,
                                                              Map<String, List<String>> base,
                                                              String region, String accountId) {
        Map<String, List<String>> conditions = base == null ? new LinkedHashMap<>() : new LinkedHashMap<>(base);
        conditions.keySet().removeIf(key -> key.startsWith(RESOURCE_TAG_PREFIX)
                || key.startsWith("ssm:resourceTag/"));
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resource);
        String partition = requestContext.getPartition() == null
                ? AwsRegions.partitionFor(region) : requestContext.getPartition();
        if (!region.equals(arn.region()) || !accountId.equals(arn.accountId())
                || !partition.equals(arn.partition())) {
            return conditions;
        }
        if ("ec2".equals(arn.service()) && arn.resource().startsWith("instance/")) {
            String instance = arn.resource().substring("instance/".length());
            addResourceTags(conditions, instance);
            new LinkedHashMap<>(conditions).forEach((key, value) -> {
                if (key.startsWith(RESOURCE_TAG_PREFIX)) {
                    conditions.put("ssm:resourceTag/" + key.substring(RESOURCE_TAG_PREFIX.length()), value);
                }
            });
        } else if ("ssm".equals(arn.service()) && arn.resource().startsWith("document/")
                && ssmService != null && ssmService.isResolvable()) {
            try {
                ssmService.get().listDocumentTags(arn.resource().substring("document/".length()), region)
                        .forEach((key, value) -> conditions.put(RESOURCE_TAG_PREFIX + key, List.of(value)));
            } catch (AwsException expected) {
                if (!"InvalidResourceId".equals(expected.getErrorCode())) {
                    throw expected;
                }
                // Missing documents have no tags; the handler retains its normal not-found response.
            }
        }
        return conditions;
    }

    private Map<String, List<String>> backupFrameworkConditions(String action, ContainerRequestContext ctx) {
        if (!BackupFrameworkResources.supports(action) || backupFrameworks == null || !backupFrameworks.isResolvable()) {
            return null;
        }
        JsonNode body = bufferedJsonBody(ctx);
        if (!BackupFrameworkResources.matches(action, ctx, body)) {
            return null;
        }
        String target = BackupFrameworkResources.target(action, ctx, body);
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        Map<String, List<String>> conditions = new LinkedHashMap<>(
                backupFrameworks.get().resourceConditions(region, target));
        if ("backup:TagResource".equals(action) && body != null && body.path("Tags").isObject()) {
            List<String> keys = new ArrayList<>();
            body.path("Tags").properties().forEach(entry -> {
                keys.add(entry.getKey());
                conditions.put("aws:RequestTag/" + entry.getKey(), List.of(entry.getValue().asText()));
            });
            conditions.put("aws:TagKeys", keys);
        } else if ("backup:UntagResource".equals(action) && body != null && body.path("TagKeyList").isArray()) {
            List<String> keys = new ArrayList<>();
            body.path("TagKeyList").forEach(key -> keys.add(key.asText()));
            conditions.put("aws:TagKeys", keys);
        }
        return conditions;
    }

    /** Group mutations and stream access use the handler's group and request scope. */
    private Map<String, List<String>> mqConfigurationConditions(String action, ContainerRequestContext ctx) {
        if ("mq:CreateConfiguration".equals(action)) {
            JsonNode body = PassedRoles.jsonBody(ctx);
            return body == null ? null : AmazonMqConfigurationService.requestTagConditions(
                    AmazonMqConfigurationService.requestTags(body.get("tags")));
        }
        if (mqConfigurations == null || !mqConfigurations.isResolvable() || action == null || !switch (action) {
            case "mq:DescribeConfiguration", "mq:UpdateConfiguration", "mq:DeleteConfiguration",
                    "mq:DescribeConfigurationRevision", "mq:ListConfigurationRevisions" -> true;
            default -> false;
        }) {
            return null;
        }
        String id = ctx.getUriInfo().getPathParameters().getFirst("configurationId");
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        mqConfigurations.get().tagsForAuthorization(id).forEach((key, value) ->
                conditions.put(RESOURCE_TAG_PREFIX + key, List.of(value)));
        return conditions;
    }

    private Map<String, List<String>> ecrPreviewConditionContext(String action, ContainerRequestContext ctx) {
        if (!("ecr:StartLifecyclePolicyPreview".equals(action) || "ecr:GetLifecyclePolicyPreview".equals(action))
                || ecrService == null || !ecrService.isResolvable()) {
            return null;
        }
        JsonNode body = bufferedJsonBody(ctx);
        if (body == null) {
            return null;
        }
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        Map<String, String> tags = ecrService.get().lifecyclePreviewTags(body.path("repositoryName").asText(null),
                body.path("registryId").asText(null), region);
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        tags.forEach((key, value) -> {
            if (key != null && value != null) {
                conditions.put("aws:ResourceTag/" + key, List.of(value));
                conditions.put("ecr:ResourceTag/" + key, List.of(value));
            }
        });
        return conditions;
    }

    private Map<String, List<String>> driftConditionContext(String action, ContainerRequestContext ctx) {
        if (!CloudFormationDriftService.handles(action)
                || "cloudformation:DescribeStackDriftDetectionStatus".equals(action)
                || driftService == null || !driftService.isResolvable()) {
            return null;
        }
        String account = requestContext.getAccountId() == null ? config.defaultAccountId() : requestContext.getAccountId();
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        CloudFormationDriftSnapshot stack = driftService.get().authorizationTarget(
                formParameters(ctx).get("StackName"), region, account);
        Map<String, List<String>> context = new LinkedHashMap<>();
        stack.tags().forEach((key, value) -> context.put("aws:ResourceTag/" + key, List.of(value)));
        return context;
    }

    private Map<String, List<String>> accountRegionConditionContext(String action, ContainerRequestContext ctx) {
        if (!("account:EnableRegion".equals(action) || "account:DisableRegion".equals(action)
                || "account:GetRegionOptStatus".equals(action))) {
            return null;
        }
        JsonNode body = PassedRoles.jsonBody(ctx);
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        for (String key : RequestConditionKeys.requestKeys(action)) {
            for (String member : RequestConditionKeys.members(action, key)) {
                JsonNode value = body == null ? null : body.get(member);
                if (value != null && value.isTextual()) {
                    putIfPresent(conditions, key, value.textValue());
                }
            }
        }
        return conditions.isEmpty() ? null : conditions;
    }


    /**
     * The tags a CloudWatch Logs call asks to attach or remove, read as its handler reads them: a tagged
     * {@code CreateLogGroup}'s and a {@code TagResource}'s {@code tags} as {@code aws:RequestTag/<key>} and
     * {@code aws:TagKeys}, an {@code UntagResource}'s {@code tagKeys} as {@code aws:TagKeys}. The tags of what the
     * request names come from Logs' {@link StoredResourceArns}, as for every namespace the table reads.
     */
    private Map<String, List<String>> logsConditionContext(String action, ContainerRequestContext ctx) {
        // Stream creation/deletion has no resource tags; the generated table owns those checks.
        if (!("logs:PutLogEvents".equals(action)
                || LogGroupTagTarget.supports(action) || LogTransformerTarget.supports(action)
                || "logs:CreateLogGroup".equals(action) || "logs:DeleteLogGroup".equals(action)
                || "logs:PutRetentionPolicy".equals(action) || "logs:DeleteRetentionPolicy".equals(action)
                || "logs:CreateExportTask".equals(action))) {
            return null;
        }
        JsonNode body = bufferedJsonBody(ctx);
        if (body == null) {
            return null;
        }
        if ("logs:CreateLogGroup".equals(action)) {
            JsonNode tags = body.path("tags");
            if (!tags.isObject() || tags.isEmpty()) {
                return null;
            }
            Map<String, List<String>> conditions = new LinkedHashMap<>();
            List<String> keys = new ArrayList<>();
            tags.properties().forEach(entry -> {
                keys.add(entry.getKey());
                conditions.put("aws:RequestTag/" + entry.getKey(), List.of(entry.getValue().asText()));
            });
            conditions.put("aws:TagKeys", keys);
            return conditions;
        }
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        boolean direct = LogGroupTagTarget.supports(action);
        if ("logs:TagResource".equals(action) && body.path("tags").isObject()) {
            List<String> keys = new ArrayList<>();
            body.path("tags").fields().forEachRemaining(entry -> {
                keys.add(entry.getKey());
                conditions.put(REQUEST_TAG_PREFIX + entry.getKey(), List.of(entry.getValue().asText()));
            });
            if (!keys.isEmpty()) {
                conditions.put("aws:TagKeys", keys);
            }
        } else if ("logs:UntagResource".equals(action)) {
            List<String> keys = new ArrayList<>();
            body.path("tagKeys").forEach(key -> keys.add(key.asText()));
            if (!keys.isEmpty()) {
                conditions.put("aws:TagKeys", keys);
            }
        }
        if (logsService == null || !logsService.isResolvable()) {
            return conditions.isEmpty() ? null : conditions;
        }
        String taggedArn = body.path("resourceArn").asText();
        if (direct && (CloudWatchLogsDeliveryService.isDeliveryResourceArn(taggedArn)
                || AwsArnUtils.isArnFor(taggedArn, "logs")
                && AwsArnUtils.parse(taggedArn).resource().startsWith("destination:"))) {
            // A destination or delivery resource is not a log group; its own table-backed tags apply.
            return conditions.isEmpty() ? null : conditions;
        }
        String group = direct ? logsService.get().resolveTagTarget(body.path("resourceArn").asText(), region).name()
                : body.path("logGroupName").asText();
        if (LogTransformerTarget.supports(action)) {
            group = logsService.get().resolveTransformerTarget(body.path("logGroupIdentifier").asText(), region).name();
        }
        Map<String, String> tags;
        try {
            tags = "logs:CreateExportTask".equals(action)
                        ? logsService.get().readLogExportTags(requestContext.getAccountId() == null
                                ? config.defaultAccountId() : requestContext.getAccountId(), region, group)
                        : logsService.get().listTagsLogGroup(group, region);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            // Leave the handler to report a missing group; unexpected lookup errors must not hide a deny.
            return conditions.isEmpty() ? null : conditions;
        }
        if (tags == null || tags.isEmpty()) {
            return conditions.isEmpty() ? null : conditions;
        }
        tags.forEach((key, value) -> conditions.put(RESOURCE_TAG_PREFIX + key,
                List.of(value == null ? "" : value)));
        return conditions;
    }

    /** The condition key whose value a delegation can decide rather than the request. */
    private static final String PERMISSIONS_BOUNDARY_KEY = "iam:PermissionsBoundary";

    /** The boundary the caller's delegation registered, or empty for any other caller. */
    private Optional<String> delegatedBoundary() {
        if (iamService.isUnsatisfied() || requestContext == null) {
            return Optional.empty();
        }
        return iamService.get().delegatedRoleBoundaryArn(requestContext.getAccessKeyId());
    }

    private Map<String, List<String>> privateCaConditionContext(String action, ContainerRequestContext ctx) {
        JsonNode body = bufferedJsonBody(ctx);
        if (body == null) {
            return null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        if (privateAuthorities != null && privateAuthorities.isResolvable()
                && body.path("CertificateAuthorityArn").isTextual()) {
            privateAuthorities.get().storedTags(body.path("CertificateAuthorityArn").textValue())
                    .forEach((key, value) -> conditions.put(RESOURCE_TAG_PREFIX + key, List.of(value)));
        }
        if ("acm-pca:IssueCertificate".equals(action)) {
            JsonNode template = body.path("TemplateArn");
            if (template.isMissingNode() || template.isNull() || template.isTextual()) {
                String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
                String value = template.isTextual() ? template.textValue()
                        : AwsArnUtils.Arn.global(AwsPartitions.forRegion(region).map(AwsPartition::id)
                                .orElseGet(() -> RegionResolver.effectivePartition(
                                        config.defaultRegion(), config.partitions().id())), "acm-pca", "",
                                "template/EndEntityCertificate/V1").toString();
                conditions.put("acm-pca:TemplateArn", List.of(value));
            }
        }
        return conditions.isEmpty() ? null : conditions;
    }

    private Map<String, List<String>> iamConditionContext(String action, ContainerRequestContext ctx) {
        Map<String, String> members = IamConditionKeys.members(action);
        Map<String, String> targets = IamConditionKeys.targets(action);
        Map<String, IamConditionKeys.RequestTags> requestTags = IamConditionKeys.requestTags(action);
        if (members.isEmpty() && targets.isEmpty() && requestTags.isEmpty()) {
            return null;
        }
        Map<String, String> form = formParameters(ctx);
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        members.forEach((key, member) -> {
            String value = form.get(member);
            if (value != null) {
                conditions.put(key, List.of(value));
            }
        });
        // iam:PermissionsBoundary names the boundary the request will leave on the principal, so a
        // role created under a delegation publishes the cap that delegation registered rather than
        // whatever the call asked for: the write applies the cap, and a policy conditioned on the
        // boundary has to be told the same value the role ends up with.
        if (members.containsKey(PERMISSIONS_BOUNDARY_KEY) && "iam:CreateRole".equals(action)) {
            delegatedBoundary().ifPresent(arn -> conditions.put(PERMISSIONS_BOUNDARY_KEY, List.of(arn)));
        }
        targets.forEach((key, target) -> {
            if (key.contains("${TagKey}")) {
                Map<String, String> tags = storedTargetValue(target, form.get(target), IamRole::getTags, IamUser::getTags);
                if (tags != null) {
                    tags.forEach((tag, value) -> conditions.put(key.replace("${TagKey}", tag), List.of(value)));
                }
            } else {
                String value = storedTargetValue(target, form.get(target),
                        IamRole::getPermissionsBoundaryArn, IamUser::getPermissionsBoundaryArn);
                if (value != null) {
                    conditions.put(key, List.of(value));
                }
            }
        });
        if (!requestTags.isEmpty()) {
            addIamRequestTags(conditions, form, requestTags);
        }
        return conditions.isEmpty() ? null : conditions;
    }

    private static void addIamRequestTags(Map<String, List<String>> conditions, Map<String, String> form,
                                          Map<String, IamConditionKeys.RequestTags> requestTags) {
        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        form.forEach(parameters::putSingle);
        requestTags.forEach((key, fields) -> {
            List<String> keys = new ArrayList<>();
            for (AwsQueryLists.IndexedMember member : AwsQueryLists.members(parameters, fields.member(), null)) {
                String tag = form.get(member.prefix() + (fields.keyMember() == null ? "" : "." + fields.keyMember()));
                if (tag == null) {
                    continue;
                }
                if ("aws:TagKeys".equals(key)) {
                    if (!keys.contains(tag)) {
                        keys.add(tag);
                    }
                } else {
                    conditions.put(key.replace("${TagKey}", tag),
                            List.of(form.getOrDefault(member.prefix() + "." + fields.valueMember(), "")));
                }
            }
            if (!keys.isEmpty()) {
                conditions.put(key, keys);
            }
        });
    }

    private <T> T storedTargetValue(String target, String name,
                                    Function<IamRole, T> roleValue, Function<IamUser, T> userValue) {
        if (name == null || !iamService.isResolvable()) {
            return null;
        }
        String account = requestContext.getAccountId();
        return switch (target) {
            case "RoleName" -> iamService.get().findRole(account, name).map(roleValue).orElse(null);
            case "UserName" -> iamService.get().findUser(account, name).map(userValue).orElse(null);
            default -> throw new IllegalStateException("Unknown IAM condition key target: " + target);
        };
    }

    /**
     * The keys a request for {@code action} carries in its own members, such as an OpenTunnel remotePort, read
     * through {@code parameter} from the table in {@code request-condition-keys.json}; null when it carries none.
     */
    static Map<String, List<String>> requestConditions(String action, Function<String, String> parameter) {
        return requestConditions(action, member -> {
            String value = parameter.apply(member);
            return value == null ? List.of() : List.of(value);
        }, null);
    }

    /**
     * The keys a request for {@code action} carries in its own members, such as a Redshift DbUser, the record names
     * of a Route 53 change batch or an EKS cluster's endpoint access, read as its namespace's protocol carries them
     * (a Query form, an XML or JSON body), from the table in {@code request-condition-keys.json}; null when it
     * carries none, or is sent in a protocol no reader here reads yet. A member left out, or given a JSON null, names
     * nothing, so an update takes no value from what the resource already has.
     */
    private Map<String, List<String>> requestMemberConditions(String action, ContainerRequestContext ctx) {
        List<String> keys = RequestConditionKeys.requestKeys(action);
        if (keys.isEmpty()) {
            return null;
        }
        String namespace = action.substring(0, action.indexOf(':'));
        String protocol = RequestConditionKeys.protocol(namespace);
        Function<String, List<String>> values;
        if ("query".equals(protocol) || "ec2".equals(protocol)) {
            Map<String, String> form = formParameters(ctx);
            values = member -> form.containsKey(member) ? List.of(form.get(member)) : List.of();
        } else if ("rest-xml".equals(protocol)) {
            List<String> members = keys.stream()
                    .flatMap(key -> RequestConditionKeys.members(action, key).stream()).toList();
            Map<String, List<String>> read = XmlParser.valuesAt(bodyText(ctx), members);
            if (read == null) {
                // The handler refuses a body it cannot read the same elements from, so nothing is checked on it.
                return null;
            }
            values = member -> read.getOrDefault(member, List.of());
        } else if ("json".equals(protocol) || "rest-json".equals(protocol)) {
            JsonNode body = PassedRoles.jsonBody(ctx);
            if (body == null) {
                return null;
            }
            values = member -> jsonValues(body, member.split("\\."), 0, new ArrayList<>());
        } else {
            return null;
        }
        return requestConditions(action, values, requestConditionSpelling(namespace));
    }

    /**
     * The scalar values at {@code path} in a JSON body, a member path as the table writes it, {@code *} stepping into
     * every element of a list or map; a JSON null, a structure or a list where the path ends names nothing.
     */
    private static List<String> jsonValues(JsonNode node, String[] path, int step, List<String> found) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return found;
        }
        if (step == path.length) {
            if (node.isValueNode()) {
                found.add(node.asText());
            }
            return found;
        }
        if ("*".equals(path[step])) {
            node.forEach(element -> jsonValues(element, path, step + 1, found));
            return found;
        }
        return jsonValues(node.get(path[step]), path, step + 1, found);
    }

    /**
     * Every value {@code values} reads for each member the table names for each key {@code action} takes, spelled by
     * {@code spelling} as the namespace's service spells the member's shape; null when there are none.
     */
    private static Map<String, List<String>> requestConditions(String action, Function<String, List<String>> values,
                                                               RequestConditionSpelling spelling) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        for (String key : RequestConditionKeys.requestKeys(action)) {
            for (String member : RequestConditionKeys.members(action, key)) {
                String shape = RequestConditionKeys.shape(action, member);
                for (String value : values.apply(member)) {
                    if (value != null && !value.isBlank()) {
                        conditions.computeIfAbsent(key, ignored -> new ArrayList<>())
                                .add(spelling == null ? value : spelling.spell(shape, value));
                    }
                }
            }
        }
        return conditions.isEmpty() ? null : conditions;
    }

    /** The registered spelling of the namespace's request members; null when there is none. */
    private RequestConditionSpelling requestConditionSpelling(String namespace) {
        if (requestConditionSpellings == null || requestConditionSpellings.isUnsatisfied()) {
            return null;
        }
        for (RequestConditionSpelling spelling : requestConditionSpellings) {
            if (spelling.namespace().equals(namespace)) {
                return spelling;
            }
        }
        return null;
    }

    /** The same destination default the sync handler uses for omitted create/list/delete types. */
    private Map<String, List<String>> ssmSyncConditionContext(String action, ContainerRequestContext ctx) {
        if (!RequestConditionKeys.takes(action, "ssm:SyncType")) {
            return null;
        }
        JsonNode body = bufferedJsonBody(ctx);
        if (body == null || !body.isObject()) {
            return null;
        }
        for (String member : RequestConditionKeys.members(action, "ssm:SyncType")) {
            JsonNode value = body.get(member);
            if (value != null && !value.isNull()) {
                return value.isTextual() ? Map.of("ssm:SyncType", List.of(value.asText())) : null;
            }
        }
        return "ssm:UpdateResourceDataSync".equals(action)
                ? null : Map.of("ssm:SyncType", List.of("SyncToDestination"));
    }

    public static Map<String, List<String>> withGlobalContext(Map<String, List<String>> serviceContext,
                                                        String resourceArn, String region,
                                                        String accountId, String resourceOwnerAccountId) {
        return withGlobalContext(serviceContext, resourceArn, region, accountId, resourceOwnerAccountId, null);
    }

    public static Map<String, List<String>> withGlobalContext(Map<String, List<String>> serviceContext,
                                                        String resourceArn, String region,
                                                        String accountId, String resourceOwnerAccountId, String action) {
        Map<String, List<String>> conditions = serviceContext == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(serviceContext);
        putResourceAccount(conditions, resourceArn, resourceOwnerAccountId, action);
        putIfPresent(conditions, "aws:PrincipalAccount", accountId);
        putIfPresent(conditions, "aws:RequestedRegion", region);
        conditions.putIfAbsent("aws:ViaAWSService", List.of("false"));
        // This key exists for signed AWS credentials, not anonymous resource-policy callers.
        if ("anonymous".equals(accountId)) {
            conditions.remove("aws:PrincipalIsAWSService");
        } else {
            conditions.put("aws:PrincipalIsAWSService", List.of("false"));
        }
        putRequestTime(conditions);
        return conditions;
    }

    /**
     * The time of the request, which the IAM User Guide says "is always included in the request
     * context" under two keys: {@code aws:CurrentTime}, a Date, and {@code aws:EpochTime}, "the
     * date and time of the request in epoch or Unix time", a Date and a Numeric, so whole seconds.
     * Every context a request is evaluated against carries them, including those built outside
     * {@link #withGlobalContext}, such as S3's own bucket-policy check.
     */
    public static void putRequestTime(Map<String, List<String>> conditions) {
        Instant now = Instant.now();
        conditions.put("aws:CurrentTime", List.of(now.toString()));
        conditions.put("aws:EpochTime", List.of(Long.toString(now.getEpochSecond())));
    }

    private static void putResourceAccount(Map<String, List<String>> conditions, String resourceArn,
                                           String resourceOwnerAccountId, String action) {
        // S3 ARNs omit the owner account. Both condition keys must use the stored bucket owner,
        // never the caller; leave them absent when neither the ARN nor service state knows it.
        String account = AwsArnUtils.accountOrDefault(resourceArn, null);
        if (account == null || account.isBlank()) {
            account = resourceOwnerAccountId;
        }
        putIfPresent(conditions, "aws:ResourceAccount", account);
        if (S3ControlTagConditionKeys.takes(action, "s3:ResourceAccount")) {
            putIfPresent(conditions, "s3:ResourceAccount", account);
        }
    }

    /**
     * The request context of a call an AWS service makes on its own behalf, such as SES publishing to
     * a topic, from the IAM User Guide's global condition keys: {@code aws:PrincipalIsAWSService} is
     * {@code true}; {@code aws:PrincipalServiceName} and {@code aws:PrincipalServiceNamesList} name the
     * service; {@code aws:SourceArn} and {@code aws:SourceAccount} name the resource whose
     * configuration triggered the call, when it has them; and {@code aws:ResourceAccount} and
     * {@code aws:RequestedRegion} describe the target; {@code aws:CurrentTime} and
     * {@code aws:EpochTime} carry the time of the request, as on every request. The keys that describe an IAM principal,
     * {@code aws:PrincipalArn} and {@code aws:PrincipalAccount} among them, are left out: no IAM
     * principal makes the call. A call to an SNS topic from a service SNS lists as setting it also
     * carries {@code aws:SourceOwner}, the source account, which is what the default topic policy
     * checks.
     */
    public static Map<String, List<String>> serviceCallContext(String servicePrincipal, String resourceArn,
                                                               String resourceOwnerAccountId,
                                                               String sourceArn, String sourceAccount) {
        return serviceCallContext(servicePrincipal, resourceArn, resourceOwnerAccountId, sourceArn, sourceAccount, null);
    }

    public static Map<String, List<String>> serviceCallContext(String servicePrincipal, String resourceArn,
                                                               String resourceOwnerAccountId,
                                                               String sourceArn, String sourceAccount, String action) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        conditions.put("aws:PrincipalIsAWSService", List.of("true"));
        conditions.put("aws:ViaAWSService", List.of("false"));
        conditions.put("aws:PrincipalServiceName", List.of(servicePrincipal));
        conditions.put("aws:PrincipalServiceNamesList", List.of(servicePrincipal));
        putIfPresent(conditions, "aws:SourceArn", sourceArn);
        putIfPresent(conditions, "aws:SourceAccount", sourceAccount);
        if (SNS_SOURCE_OWNER_SERVICES.contains(ServicePrincipals.serviceName(servicePrincipal))
                && AwsArnUtils.isArnFor(resourceArn, "sns")) {
            putIfPresent(conditions, "aws:SourceOwner", sourceAccount);
        }
        putResourceAccount(conditions, resourceArn, resourceOwnerAccountId, action);
        putIfPresent(conditions, "aws:RequestedRegion", AwsArnUtils.regionOrDefault(resourceArn, null));
        putRequestTime(conditions);
        return conditions;
    }

    private static void putIfPresent(Map<String, List<String>> conditions, String key, String value) {
        if (value != null && !value.isBlank()) {
            conditions.put(key, List.of(value));
        }
    }

    // ── Lambda ──────────────────────────────────────────────────────────────────

    /** The authType a web function endpoint request names, and the one the endpoint has before it. */
    private Map<String, List<String>> lambdaConditionContext(String action, ContainerRequestContext ctx) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        if (("lambda:GetFunctionRecursionConfig".equals(action) || "lambda:PutFunctionRecursionConfig".equals(action))
                && lambdaService != null && lambdaService.isResolvable()) {
            String path = ctx.getUriInfo().getPath();
            String prefix = "2024-08-31/functions/";
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (path.startsWith(prefix) && path.endsWith("/recursion-config")) {
                String reference = path.substring(prefix.length(), path.length() - "/recursion-config".length());
                String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
                try {
                    lambdaService.get().recursionResourceTags(region, reference)
                            .forEach((key, value) -> {
                                if (key != null && value != null) {
                                    conditions.put(RESOURCE_TAG_PREFIX + key, List.of(value));
                                }
                            });
                } catch (AwsException expected) {
                    // Invalid or missing targets have no resource tags; the handler rejects them.
                }
            }
        }
        if (("lambda:GetRuntimeManagementConfig".equals(action)
                || "lambda:PutRuntimeManagementConfig".equals(action))
                && runtimeManagement != null && runtimeManagement.isResolvable()) {
            String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
            String account = requestContext.getAccountId() == null ? config.defaultAccountId()
                    : requestContext.getAccountId();
            runtimeManagement.get().authorizationTags(region, account,
                    ctx.getUriInfo().getPathParameters().getFirst("functionName"),
                    ctx.getUriInfo().getQueryParameters().getFirst("Qualifier"))
                    .forEach((key, value) -> conditions.put(RESOURCE_TAG_PREFIX + key, List.of(value)));
        }
        for (String member : RequestConditionKeys.members(action, REQUEST_AUTH_TYPE)) {
            PassedRoles.values(PassedRoles.jsonBody(ctx), member.split("\\."))
                    .forEach(value -> putIfPresent(conditions, REQUEST_AUTH_TYPE, value));
        }
        if (RequestConditionKeys.takes(action, RESOURCE_AUTH_TYPE)) {
            putIfPresent(conditions, RESOURCE_AUTH_TYPE, storedAuthType(action, ctx));
        }
        return conditions.isEmpty() ? null : conditions;
    }

    /** The authType of the web function endpoint the request names, as the request resources read it. */
    private String storedAuthType(String action, ContainerRequestContext ctx) {
        if (!lambdaWebService.isResolvable()) {
            return null;
        }
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        String endpointArn = RequestResources.of(action, ctx, regionResolver.partitionForRegion(region), region,
                        requestContext.getAccountId(), null, IamActionRegistry.pathLabels("lambda", ctx)).stream()
                .filter(arn -> arn.contains("/endpoint/")).findFirst().orElse("");
        int child = endpointArn.lastIndexOf("/endpoint/");
        if (child < 0) {
            return null;
        }
        try {
            WebFunctionEndpoint endpoint = lambdaWebService.get().getEndpoint(region, endpointArn.substring(0, child),
                    endpointArn.substring(child + "/endpoint/".length()));
            return endpoint == null ? null : endpoint.authType;
        } catch (AwsException expected) {
            // The handler refuses the same names, so the key is only left out, as for a missing endpoint on AWS.
            return null;
        }
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────

    private Map<String, List<String>> s3ConditionContext(String action, ContainerRequestContext ctx) {
        // S3 Control's request tag keys come from the generated table; S3's own below were measured
        // against AWS, and do not all match the Service Reference.
        if (IamActionRegistry.isS3ControlRequest("s3", ctx)) {
            return S3ControlTagConditionKeys.conditions(action, () -> bodyText(ctx),
                    ctx.getUriInfo().getQueryParameters());
        }
        Map<String, List<String>> conditions = s3ActionConditionContext(action, ctx);
        Map<String, List<String>> signature = S3SignatureConditions.request(ctx.getHeaderString("Authorization"),
                ctx.getUriInfo() == null ? null : ctx.getUriInfo().getQueryParameters());
        if (!signature.isEmpty()) {
            conditions = merge(conditions, signature);
        }
        // A version action is checked only for a request naming a version, and carries its id.
        if (S3ObjectActions.isVersionAction(action) && ctx.getUriInfo() != null) {
            String versionId = ctx.getUriInfo().getQueryParameters().getFirst("versionId");
            if (versionId != null) {
                conditions = merge(conditions, Map.of(VERSION_ID, List.of(versionId)));
            }
        }
        return conditions;
    }

    private Map<String, List<String>> s3ActionConditionContext(String action, ContainerRequestContext ctx) {
        return switch (action) {
            case "s3:ListBucket" -> s3BucketListConditionContext(ctx.getUriInfo().getQueryParameters());
            // DeleteBucketTagging is checked as s3:PutBucketTagging, and sends no tags of its own:
            // what it has is the bucket's current tags, as GetBucketTagging does.
            case "s3:PutBucketTagging" -> "DELETE".equalsIgnoreCase(ctx.getMethod())
                    ? s3BucketResourceTagConditionContext(ctx)
                    : s3PutBucketTaggingConditionContext(ctx);
            case "s3:GetBucketTagging", "s3:DeleteBucket" -> s3BucketResourceTagConditionContext(ctx);
            // Object tags. Which action is given which key is what real AWS was measured to do,
            // not what reads naturally: s3:DeleteObject is given neither key, and s3:PutObject is
            // given the tags of the REQUEST only, never those of an object it is about to
            // overwrite. See S3ObjectTagConditionEnforcementIntegrationTest.
            //
            // The version actions IamActionRegistry checks for a request naming a versionId carry
            // the key as their base actions do, per the Service Reference, as do the annotation
            // actions, and the versionId picks whose tags are read.
            case "s3:GetObject", "s3:GetObjectVersion", "s3:GetObjectTagging",
                 "s3:GetObjectVersionTagging", "s3:GetObjectAcl", "s3:GetObjectVersionAcl",
                 "s3:DeleteObjectTagging",
                 "s3:DeleteObjectVersionTagging", "s3:GetObjectAnnotation", "s3:GetObjectVersionAnnotation",
                 "s3:ListObjectAnnotations", "s3:ListObjectVersionAnnotations", "s3:PutObjectAnnotation",
                 "s3:PutObjectVersionAnnotation", "s3:DeleteObjectAnnotation",
                 "s3:DeleteObjectVersionAnnotation" -> s3ExistingObjectTagConditionContext(ctx);
            case "s3:PutObjectAcl", "s3:PutObjectVersionAcl" -> merge(
                    s3ExistingObjectTagConditionContext(ctx),
                    S3ObjectActions.cannedAclConditions(ctx::getHeaderString));
            // The Object Lock keys come from what the request sets: an object creation's headers, the
            // retention and legal-hold bodies. A part or a completion creates nothing from its own
            // headers, and is judged by the encryption its upload recorded.
            case "s3:PutObject" -> isPartOrCompletion(ctx)
                    ? s3UploadEncryptionConditionContext(ctx)
                    : S3ObjectActions.objectCreationConditions(ctx::getHeaderString,
                            s3RequestTagging(ctx), S3ObjectActions.isCopy(ctx.getMethod(), ctx::getHeaderString),
                            clock.instant());
            case "s3:PutObjectRetention" -> S3ObjectActions.retentionBodyConditions(bodyText(ctx), clock.instant());
            case "s3:PutObjectLegalHold" -> S3ObjectActions.legalHoldBodyConditions(bodyText(ctx), clock.instant());
            case "s3:PutObjectTagging", "s3:PutObjectVersionTagging" -> merge(
                    s3ExistingObjectTagConditionContext(ctx), S3ObjectActions.taggingBodyConditions(bodyText(ctx)));
            default -> null;
        };
    }

    /**
     * The encryption keys of a part or a completion: what its upload recorded when it was created, as
     * measured on S3, since a part sends neither header. An upload not held offers neither.
     */
    private Map<String, List<String>> s3UploadEncryptionConditionContext(ContainerRequestContext ctx) {
        if (!s3Service.isResolvable()) {
            return null;
        }
        String bucket = s3BucketName(ctx.getUriInfo().getPath());
        try {
            return s3Service.get().multipartEncryptionConditions(bucket, s3RequestObjectKey(ctx, bucket),
                    ctx.getUriInfo().getQueryParameters().getFirst("uploadId"));
        } catch (AwsException expected) {
            // The handler answers an upload not held as NoSuchUpload; here it offers no keys.
            return null;
        }
    }

    /**
     * A part or a completion as S3Controller routes them: a PUT naming an upload and a part number, or a
     * POST naming an upload and not asking for a new one. Any other PUT or POST naming an {@code uploadId}
     * creates an object or an upload, whatever that parameter says, and is judged by its own headers.
     */
    private static boolean isPartOrCompletion(ContainerRequestContext ctx) {
        MultivaluedMap<String, String> params = ctx.getUriInfo().getQueryParameters();
        if (!params.containsKey("uploadId")) {
            return false;
        }
        return "PUT".equalsIgnoreCase(ctx.getMethod())
                ? params.containsKey("partNumber")
                : "POST".equalsIgnoreCase(ctx.getMethod()) && !params.containsKey("uploads");
    }

    /**
     * Whether a request checked as {@code s3:PutObject} is a part or a completion of an upload S3 does
     * not hold. There is no encryption to judge it by, and S3 answers it {@code NoSuchUpload} under a
     * policy on the encryption keys, as measured.
     */
    boolean namesUploadNotHeld(String action, ContainerRequestContext ctx) {
        return "s3:PutObject".equals(action) && isPartOrCompletion(ctx)
                && ctx.getHeaderString("x-amz-copy-source") == null
                && s3UploadEncryptionConditionContext(ctx) == null && s3Service.isResolvable();
    }

    private static String bodyText(ContainerRequestContext ctx) {
        byte[] body = bufferEntity(ctx);
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Whether an S3 delete or retention change that sends {@code x-amz-bypass-governance-retention}
     * overrides governance-mode retention, which is when AWS requires {@code s3:BypassGovernanceRetention}
     * for it: a delete that removes a version under a governance lock, or a retention change that
     * shortens or removes one (see {@link S3Service#deleteOverridesGovernanceRetention} and
     * {@link S3Service#retentionChangeOverridesGovernance}). A retention date that does not parse
     * overrides nothing, since the change is refused as malformed.
     */
    boolean s3OverridesGovernanceRetention(String action, ContainerRequestContext ctx) {
        if (ctx.getUriInfo() == null || !s3Service.isResolvable()) {
            return false;
        }
        String bucket = s3BucketName(ctx.getUriInfo().getPath());
        String key = s3RequestObjectKey(ctx, bucket);
        if (key == null) {
            return false;
        }
        String versionId = ctx.getUriInfo().getQueryParameters().getFirst("versionId");
        if (!"s3:PutObjectRetention".equals(action)) {
            return s3Service.get().deleteOverridesGovernanceRetention(bucket, key, versionId);
        }
        String date = XmlParser.extractFirst(bodyText(ctx), "RetainUntilDate", null);
        Instant retainUntil;
        try {
            retainUntil = date == null ? null : Instant.parse(date);
        } catch (DateTimeParseException e) {
            LOG.debugv("Retention date {0} does not parse, so the change overrides no lock", date);
            return false;
        }
        return s3Service.get().retentionChangeOverridesGovernance(bucket, key, versionId, retainUntil);
    }

    /**
     * {@code s3:ExistingObjectTag/<key>} from the tags of the version this request targets. An
     * object that does not exist, or has no tags, offers no keys, so a {@code StringEquals} on one
     * does not match and an allow conditioned on it does not apply.
     *
     * <p>The {@code versionId} query parameter decides which version is read. Falling back to the
     * current version would authorize a read of an older version against tags it does not carry,
     * which lets a request through that the policy refuses.
     */
    private Map<String, List<String>> s3ExistingObjectTagConditionContext(ContainerRequestContext ctx) {
        if (ctx.getUriInfo() == null || !s3Service.isResolvable()) {
            return null;
        }
        String bucket = s3BucketName(ctx.getUriInfo().getPath());
        String key = s3RequestObjectKey(ctx, bucket);
        if (key == null) {
            return null;
        }
        String versionId = ctx.getUriInfo().getQueryParameters().getFirst("versionId");
        Map<String, String> tags;
        try {
            tags = s3Service.get().getObjectTagging(bucket, key, versionId);
        } catch (RuntimeException e) {
            LOG.debugv(e, "Could not read object tags for the IAM condition context: {0}/{1} version {2}",
                    bucket, key, versionId);
            return null;
        }
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        tags.forEach((k, v) -> conditions.put(EXISTING_OBJECT_TAG_PREFIX + k, List.of(v)));
        return conditions;
    }

    /**
     * A PutObject's {@code x-amz-tagging}: the header, or the query parameter where a presigner may
     * place it. The header takes precedence, including an empty header, matching the source the S3
     * controller applies to the object.
     */
    private static String s3RequestTagging(ContainerRequestContext ctx) {
        String header = ctx.getHeaderString("x-amz-tagging");
        if (header == null && ctx.getUriInfo() != null) {
            header = ctx.getUriInfo().getQueryParameters().getFirst("x-amz-tagging");
        }
        return header;
    }

    private static Map<String, List<String>> merge(Map<String, List<String>> a, Map<String, List<String>> b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        Map<String, List<String>> merged = new LinkedHashMap<>(a);
        merged.putAll(b);
        return merged;
    }

    /**
     * The object key an S3 request names, null for a bucket-level request. It is read from the path
     * as it arrived, as {@code S3Controller} reads it, since the normalized path drops a key's
     * leading slash and so names another object. Without a current Vert.x request, as in a unit
     * test, the request URI's raw path stands in for it.
     */
    private String s3RequestObjectKey(ContainerRequestContext ctx, String bucket) {
        if (bucket == null) {
            return null;
        }
        URI requestUri = ctx.getUriInfo().getRequestUri();
        RoutingContext current = currentVertxRequest == null ? null : currentVertxRequest.getCurrent();
        String sentPath = current != null && current.request() != null ? current.request().path()
                : requestUri == null ? null : requestUri.getRawPath();
        return S3ObjectKeys.objectKey(sentPath, requestUri == null ? null : requestUri.getPath(),
                ctx.getUriInfo().getPath(), bucket);
    }

    Map<String, List<String>> s3BucketListConditionContext(MultivaluedMap<String, String> queryParameters) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        addQueryCondition(conditions, S3ConditionKeys.PREFIX, queryParameters, "prefix");
        addQueryCondition(conditions, "s3:delimiter", queryParameters, "delimiter");
        addQueryCondition(conditions, "s3:max-keys", queryParameters, "max-keys");
        return conditions.isEmpty() ? null : conditions;
    }

    private static void addQueryCondition(Map<String, List<String>> conditions, String conditionKey,
                                          MultivaluedMap<String, String> queryParameters, String queryParameter) {
        String value = queryParameters.getFirst(queryParameter);
        if (value != null) {
            conditions.put(conditionKey, List.of(value));
        }
    }

    /** {@code aws:RequestTag/<key>} from the {@code <Tagging><TagSet>} body of PutBucketTagging. */
    private Map<String, List<String>> s3PutBucketTaggingConditionContext(ContainerRequestContext ctx) {
        byte[] body = bufferEntity(ctx);
        if (body == null || body.length == 0) {
            return null;
        }
        Map<String, String> requested = XmlParser.extractPairs(
                new String(body, StandardCharsets.UTF_8), "Tag", "Key", "Value");
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        requested.forEach((key, value) -> conditions.put(REQUEST_TAG_PREFIX + key, List.of(value)));
        if (!requested.isEmpty()) {
            conditions.put("aws:TagKeys", List.copyOf(requested.keySet()));
        }
        return conditions.isEmpty() ? null : conditions;
    }

    /** {@code aws:ResourceTag/<key>} from the target bucket's current tags. */
    private Map<String, List<String>> s3BucketResourceTagConditionContext(ContainerRequestContext ctx) {
        String bucket = s3BucketName(ctx.getUriInfo().getPath());
        if (bucket == null || !s3Service.isResolvable()) {
            return null;
        }
        Map<String, String> existing;
        try {
            existing = s3Service.get().getBucketTagging(bucket);
        } catch (RuntimeException e) {
            // A bucket that does not exist has no tags to offer; the request fails on its own later.
            LOG.debugv(e, "Could not read bucket tags for the IAM condition context: {0}", bucket);
            return null;
        }
        if (existing == null || existing.isEmpty()) {
            return null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        existing.forEach((key, value) -> conditions.put(RESOURCE_TAG_PREFIX + key, List.of(value)));
        return conditions;
    }

    /** Path-style bucket, the same reading {@code ResourceArnBuilder} applies to S3 paths. */
    private static String s3BucketName(String path) {
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        if (stripped.isEmpty()) {
            return null;
        }
        int slash = stripped.indexOf('/');
        return slash < 0 ? stripped : stripped.substring(0, slash);
    }

    // ── EC2 ─────────────────────────────────────────────────────────────────────

    private Map<String, List<String>> ec2ConditionContext(String action, ContainerRequestContext ctx) {
        if ("ec2:CreatePlacementGroup".equals(action) || "ec2:DeletePlacementGroup".equals(action)
                || "ec2:DescribePlacementGroups".equals(action)) {
            return placementGroupConditionContext(action, ctx);
        }
        if ("ec2:RunInstances".equals(action)) {
            return ec2RunInstancesConditionContext(ctx);
        }
        RequestResourceMembers.Targets targets = RequestResourceMembers.table().targets().get(action);
        if (targets == null) {
            return switch (action) {
                case "ec2:CreateTags" -> ec2CreateTagsConditionContext(ctx);
                case "ec2:DeleteTags" -> ec2ResourceTagConditionContext(ctx, "ResourceId.1");
                case "ec2:TerminateInstances" ->
                        ec2ResourceTagConditionContext(ctx, "InstanceId.1");
                case "ec2:DeleteSecurityGroup", "ec2:AuthorizeSecurityGroupIngress", "ec2:AuthorizeSecurityGroupEgress",
                        "ec2:RevokeSecurityGroupIngress", "ec2:RevokeSecurityGroupEgress" ->
                        ec2ResourceTagConditionContext(ctx, "GroupId");
                default -> null;
            };
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        Map<String, String> form = formParameters(ctx);
        if ("ec2:CreateTags".equals(action)) {
            addRequestTags(conditions, form, "Tag.");
        }
        if (targets != null) {
            addResourceTags(conditions, form.get(targets.query() + ".1"), targets);
            addResourceKeys(conditions, form.get(targets.query() + ".1"), targets,
                    heldTargets(action, ctx, form, targets));
        }
        return conditions.isEmpty() ? null : conditions;
    }

    private Map<String, List<String>> placementGroupConditionContext(String action, ContainerRequestContext ctx) {
        Map<String, String> form = formParameters(ctx);
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        conditions.put("ec2:Region", List.of(region));
        if ("ec2:DescribePlacementGroups".equals(action)) {
            return conditions;
        }
        String name = form.get("GroupName");
        if (name != null) {
            conditions.put("ec2:PlacementGroupName", List.of(name));
        }
        if ("ec2:CreatePlacementGroup".equals(action)) {
            String strategy = form.get("Strategy");
            if (strategy != null) {
                conditions.put("ec2:PlacementGroupStrategy", List.of(strategy));
            }
            for (String prefix : List.of("TagSpecification.", "TagSpecifications.")) {
                for (int spec = 1; form.containsKey(prefix + spec + ".ResourceType"); spec++) {
                    if ("placement-group".equals(form.get(prefix + spec + ".ResourceType"))) {
                        addRequestTags(conditions, form, prefix + spec + ".Tag.");
                    }
                }
            }
        } else if (placementGroupService != null && placementGroupService.isResolvable()) {
            Ec2PlacementGroup group = placementGroupService.get().authorizationTarget(region, name);
            if (group != null) {
                conditions.put("ec2:PlacementGroupStrategy", List.of(group.strategy()));
                group.tags().forEach((key, value) -> {
                    conditions.put("aws:ResourceTag/" + key, List.of(value));
                    conditions.put("ec2:ResourceTag/" + key, List.of(value));
                });
            }
        }
        return conditions;
    }

    /**
     * {@code aws:RequestTag/<key>} from every {@code TagSpecification.N.Tag.M} pair. The instance
     * does not exist yet, so there is no resource tag to offer.
     */
    private Map<String, List<String>> ec2RunInstancesConditionContext(ContainerRequestContext ctx) {
        Map<String, String> form = formParameters(ctx);
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        for (int spec = 1; form.containsKey("TagSpecification." + spec + ".ResourceType"); spec++) {
            addRequestTags(conditions, form, "TagSpecification." + spec + ".Tag.");
        }
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        LaunchTemplate template = Ec2LaunchAuthorization.template(ctx,
                ec2Service.isResolvable() ? ec2Service.get() : null, region);
        if (template != null) {
            String account = requestContext.getAccountId() == null ? config.defaultAccountId() : requestContext.getAccountId();
            String partition = requestContext.getPartition() == null
                    ? AwsRegions.partitionFor(region) : requestContext.getPartition();
            conditions.put("ec2:LaunchTemplate", List.of(new AwsArnUtils.Arn(
                    partition, "ec2", region, account, "launch-template/" + template.getLaunchTemplateId()).toString()));
        }
        return conditions.isEmpty() ? null : conditions;
    }

    /** EC2 resource types whose ARN names the resource by the id the EC2 tag store keys its tags by. */
    static final Set<String> TAG_STORE_ID_TYPES = Set.of("vpc-endpoint", "network-interface", "subnet",
            "security-group", "vpc", "route-table", "image", "snapshot", "instance", "volume");

    /**
     * {@code aws:ResourceTag/<key>} for one EC2 resource being authorized, from that resource's own
     * stored tags, as AWS evaluates each resource a request acts on with its own tags: present (an
     * empty map for an untagged resource, which leaves every key absent so a condition on one does not
     * match) for an EC2 ARN naming one resource of a type whose ARN carries the id the EC2 tag store
     * keys its tags by ({@link #TAG_STORE_ID_TYPES}); empty for anything else (a type wildcard such as
     * {@code vpc-endpoint/*}, a type whose ARN names the resource another way, such as a placement
     * group by name, a non-EC2 ARN, {@code *}), whose context is left as the request built it. An ARN
     * without an account (an image, a snapshot) is read in the caller's account, the ARN's region
     * otherwise the request's.
     */
    public Optional<Map<String, List<String>>> ec2ResourceTags(String resource) {
        if (!AwsArnUtils.isArnFor(resource, "ec2") || !ec2Service.isResolvable()) {
            return Optional.empty();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resource);
        int slash = arn.resource().indexOf('/');
        String type = slash < 0 ? arn.resource() : arn.resource().substring(0, slash);
        String id = slash < 0 ? "" : arn.resource().substring(slash + 1);
        if (!TAG_STORE_ID_TYPES.contains(type) || id.isEmpty() || id.contains("*") || id.contains("?")) {
            return Optional.empty();
        }
        String region = !arn.region().isEmpty() ? arn.region()
                : requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        String account = !arn.accountId().isEmpty() ? arn.accountId()
                : requestContext.getAccountId() == null ? config.defaultAccountId() : requestContext.getAccountId();
        Map<String, List<String>> tags = new LinkedHashMap<>();
        for (Tag tag : ec2Service.get().resourceTags(account, region, id)) {
            tags.put(RESOURCE_TAG_PREFIX + tag.getKey(), List.of(tag.getValue() == null ? "" : tag.getValue()));
        }
        return Optional.of(tags);
    }

    /** A termination batch pairs each instance ARN with that instance's own tags. */
    public Map<String, List<String>> ec2TerminationContext(String resource, Map<String, List<String>> base) {
        Map<String, List<String>> conditions = base == null ? new LinkedHashMap<>() : new LinkedHashMap<>(base);
        conditions.keySet().removeIf(key -> key.startsWith(RESOURCE_TAG_PREFIX));
        if (AwsArnUtils.isArn(resource)) {
            String id = AwsArnUtils.parse(resource).resource();
            if (id.startsWith("instance/")) {
                addResourceTags(conditions, id.substring("instance/".length()));
            }
        }
        return conditions;
    }

    /**
     * One condition context per target after the first, for the EC2 actions that accept several
     * resource ids in one request. An untagged target yields an empty context rather than none,
     * so a policy scoped through {@code aws:ResourceTag} fails to match it. Requests naming a
     * single target get an empty list. Single-item DynamoDB writes also use separate contexts
     * for distinct return modes, so every requested output must be authorized on the same resource.
     */
    public List<Map<String, List<String>>> resolveRemainingTargets(String credentialScope, String action,
                                                                   ContainerRequestContext ctx) {
        if ("elasticloadbalancing".equals(credentialScope)) {
            List<Map<String, List<String>>> contexts = elbV2Contexts(action, ctx);
            return contexts.size() < 2 ? List.of() : contexts.subList(1, contexts.size());
        }
        if ("dynamodb".equals(credentialScope)) {
            List<String> modes = singleItemReturnValues(action, bufferedJsonBody(ctx));
            if (modes.size() < 2) {
                return List.of();
            }
            Map<String, List<String>> primary = dynamoDbConditionContext(action, ctx);
            List<Map<String, List<String>>> contexts = new ArrayList<>();
            for (int i = 1; i < modes.size(); i++) {
                Map<String, List<String>> context = new LinkedHashMap<>(primary);
                context.put("dynamodb:ReturnValues", List.of(modes.get(i)));
                contexts.add(context);
            }
            return contexts;
        }
        if (!"ec2".equals(credentialScope)) {
            return List.of();
        }
        RequestResourceMembers.Targets generatedTargets = RequestResourceMembers.table().targets().get(action);
        if (generatedTargets == null) {
            return List.of();
        }
        String idParameter = generatedTargets.query() + ".";
        Map<String, String> form = formParameters(ctx);
        List<Map<String, List<String>>> targets = new ArrayList<>();
        if (!form.containsKey(idParameter + 1)) {
            // The handlers read numbered members from 1 up to the first gap, and so does this.
            return targets;
        }
        Map<String, Object> held = heldTargets(action, ctx, form, generatedTargets);
        for (int i = 2; form.containsKey(idParameter + i); i++) {
            Map<String, List<String>> conditions = new LinkedHashMap<>();
            if ("ec2:CreateTags".equals(action)) {
                addRequestTags(conditions, form, "Tag.");
            }
            addResourceTags(conditions, form.get(idParameter + i), generatedTargets);
            addResourceKeys(conditions, form.get(idParameter + i), generatedTargets, held);
            targets.add(conditions);
        }
        return targets;
    }

    /**
     * What EC2 holds under each numbered target of an {@code action} request whose type has generated resource keys,
     * keyed by id. The targets are looked up together, once per request and action, so the first target's context
     * and the remaining targets' contexts share a single pass over EC2's stores however many the request names.
     */
    private Map<String, Object> heldTargets(String action, ContainerRequestContext ctx, Map<String, String> form,
                                            RequestResourceMembers.Targets targets) {
        String property = HELD_TARGETS + action;
        if (ctx.getProperty(property) instanceof HeldTargets cached) {
            return cached.byId();
        }
        Map<String, Object> held = new LinkedHashMap<>();
        if (ec2Service.isResolvable()) {
            Map<String, Set<String>> idsByType = new LinkedHashMap<>();
            for (int i = 1; form.containsKey(targets.query() + "." + i); i++) {
                String id = form.get(targets.query() + "." + i);
                String type = targetType(id, targets);
                if (type != null && !ec2ResourceKeys(type).isEmpty()) {
                    idsByType.computeIfAbsent(type, ignored -> new LinkedHashSet<>()).add(id);
                }
            }
            idsByType.forEach((type, ids) -> held.putAll(ec2Service.get().heldResources(requestRegion(), type, ids)));
        }
        ctx.setProperty(property, new HeldTargets(held));
        return held;
    }

    /** The resources {@link #heldTargets} found for a request, kept on it under the action they were read for. */
    private record HeldTargets(Map<String, Object> byId) {
    }

    private static void addRequestTags(Map<String, List<String>> conditions, Map<String, String> form,
                                       String prefix) {
        for (int i = 1; form.containsKey(prefix + i + ".Key"); i++) {
            String value = form.get(prefix + i + ".Value");
            String key = form.get(prefix + i + ".Key");
            List<String> keys = conditions.computeIfAbsent("aws:TagKeys", ignored -> new ArrayList<>());
            if (!keys.contains(key)) {
                keys.add(key);
            }
            conditions.put(REQUEST_TAG_PREFIX + key,
                    List.of(value == null ? "" : value));
        }
    }

    /**
     * The generated record keys of one EC2 ARN (see {@link #putResourceKeys}), retaining their caller-scoped
     * lookup. Clear earlier generated tag keys before the shared {@link #resourceTags} path reads this ARN.
     */
    public Map<String, List<String>> withEc2ResourceContext(String scope, String action, String resource,
                                                           String region, String accountId,
                                                           Map<String, List<String>> context) {
        if (!"ec2".equals(scope) || RequestResources.hasIndividualTargets(action)
                || !AwsArnUtils.isArn(resource) || !ec2Service.isResolvable()) {
            return context;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resource);
        if (!"ec2".equals(arn.service())) {
            return context;
        }
        String resourceRegion = arn.region().isEmpty() ? region : arn.region();
        String resourceAccount = arn.accountId().isEmpty() ? accountId : arn.accountId();
        String[] parts = arn.resource().split("/", 2);
        if (parts.length != 2 || parts[1].contains("*") || parts[1].contains("?")) {
            return context;
        }
        List<String> prefixes = RequestResourceMembers.table().resourceTagKeys()
                .getOrDefault("ec2", Map.of()).getOrDefault(parts[0], List.of());
        boolean callerResource = region.equals(resourceRegion) && accountId.equals(resourceAccount);
        if (callerResource && "ec2:DeletePlacementGroup".equals(action) && "placement-group".equals(parts[0])) {
            // Native placement groups keep their name-scoped tags and strategy in their own store.
            return context;
        }
        Map<String, RequestResourceMembers.ResourceKey> keys = callerResource ? ec2ResourceKeys(parts[0]) : Map.of();
        if (prefixes.isEmpty() && keys.isEmpty()) {
            return context;
        }
        Map<String, List<String>> result = context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context);
        result.keySet().removeIf(key -> prefixes.stream().anyMatch(key::startsWith) || keys.containsKey(key));
        if (!keys.isEmpty()) {
            putResourceKeys(result, region, parts[0],
                    ec2Service.get().heldResources(region, parts[0], List.of(parts[1])).get(parts[1]));
        }
        return result;
    }

    private void addResourceTags(Map<String, List<String>> conditions, String resourceId,
                                 RequestResourceMembers.Targets targets) {
        if (resourceId == null || resourceId.isBlank() || !ec2Service.isResolvable()) {
            return;
        }
        String type = targetType(resourceId, targets);
        List<String> prefixes = type == null ? List.of(RESOURCE_TAG_PREFIX)
                : RequestResourceMembers.table().resourceTagKeys().getOrDefault("ec2", Map.of())
                .getOrDefault(type, List.of());
        putStoredTags(conditions, requestRegion(), resourceId, prefixes);
    }

    /**
     * The generated resource keys of the type whose id prefix {@code resourceId} carries, from what {@code held} says
     * EC2 holds under it (see {@link #putResourceKeys}).
     */
    private void addResourceKeys(Map<String, List<String>> conditions, String resourceId,
                                 RequestResourceMembers.Targets targets, Map<String, Object> held) {
        String type = targetType(resourceId, targets);
        if (type != null) {
            putResourceKeys(conditions, requestRegion(), type, held.get(resourceId));
        }
    }

    /** The generated EC2 resource type whose id prefix {@code resourceId} carries, or null when none does. */
    private static String targetType(String resourceId, RequestResourceMembers.Targets targets) {
        if (resourceId == null || resourceId.isBlank()) {
            return null;
        }
        for (Map.Entry<String, String> type : targets.prefixes().entrySet()) {
            if (resourceId.startsWith(type.getValue())) {
                return type.getKey();
            }
        }
        return null;
    }

    /** The condition keys the request-resources table reads from a member of EC2's resource {@code type}. */
    private static Map<String, RequestResourceMembers.ResourceKey> ec2ResourceKeys(String type) {
        return RequestResourceMembers.table().resourceKeys().getOrDefault("ec2", Map.of())
                .getOrDefault(type, Map.of());
    }

    private String requestRegion() {
        return requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
    }

    /**
     * Each condition key the request-resources table gives EC2's resource {@code type} as a member of its botocore
     * shape, valued from that member of {@code held}, the resource EC2 holds under the target's id, as the ARN of the
     * key's type where the Service Reference types the key as one. A member with no value, or no resource held,
     * leaves the key absent.
     */
    private void putResourceKeys(Map<String, List<String>> conditions, String region, String type, Object held) {
        Map<String, RequestResourceMembers.ResourceKey> keys = ec2ResourceKeys(type);
        if (keys.isEmpty() || held == null) {
            return;
        }
        JsonNode resource = RESOURCE_MAPPER.valueToTree(held);
        String account = requestContext.getAccountId();
        String partition = regionResolver.partitionForRegion(region);
        keys.forEach((key, spec) -> {
            String value = memberValue(resource, spec.member());
            if (value != null && !value.isBlank()) {
                conditions.put(key, List.of(spec.arn() == null ? value
                        : RequestResources.idArn("ec2", spec.arn(), value, partition, region, account)));
            }
        });
    }

    /** The scalar the resource holds in the botocore member {@code member}, its field of that name, case aside. */
    private static String memberValue(JsonNode resource, String member) {
        for (Map.Entry<String, JsonNode> field : resource.properties()) {
            if (field.getKey().equalsIgnoreCase(member) && field.getValue().isValueNode()
                    && !field.getValue().isNull()) {
                return field.getValue().asText();
            }
        }
        return null;
    }

    private void putStoredTags(Map<String, List<String>> conditions, String region, String resourceId,
                               List<String> prefixes) {
        for (Tag tag : ec2Service.get().resourceTags(region, resourceId)) {
            for (String prefix : prefixes) {
                conditions.put(prefix + tag.getKey(), List.of(tag.getValue() == null ? "" : tag.getValue()));
            }
        }
    }

    // ── Request body access ─────────────────────────────────────────────────────

    /**
     * Has every reader of the request's members read none from it: its JSON body reads as one naming nothing, and its
     * form, its raw body and a Query request's parameters as absent. A request a route AWS documents outside botocore
     * takes is another API's, so a member its body gives was added by the caller, naming a resource or giving a
     * condition key a value its handler never acts on.
     */
    static void readNoMembers(ContainerRequestContext ctx) {
        ctx.setProperty(NO_MEMBERS, Boolean.TRUE);
        ctx.setProperty(BUFFERED_JSON_BODY, JsonNodeFactory.instance.objectNode());
    }

    private static boolean carriesNoMembers(ContainerRequestContext ctx) {
        return Boolean.TRUE.equals(ctx.getProperty(NO_MEMBERS));
    }

    /**
     * The decoded form parameters of a Query-protocol request, buffered once per request and
     * restored for the controller that reads the form next. A repeated key keeps its first
     * value, which is the value the Query handlers read through {@code getFirst}, so the
     * resource this context describes is the resource the handler acts on.
     */
    @SuppressWarnings("unchecked")
    static Map<String, String> formParameters(ContainerRequestContext ctx) {
        if (carriesNoMembers(ctx)) {
            return Map.of();
        }
        if ("GET".equalsIgnoreCase(ctx.getMethod()) && IamActionRegistry.isQueryRequest(ctx)) {
            Map<String, String> parameters = new LinkedHashMap<>();
            ctx.getUriInfo().getQueryParameters().forEach((key, values) -> {
                if (!values.isEmpty()) {
                    parameters.put(key, values.getFirst());
                }
            });
            return parameters;
        }
        Object cached = ctx.getProperty(BUFFERED_FORM_BODY);
        if (cached instanceof Map<?, ?> map) {
            return (Map<String, String>) map;
        }
        MediaType mediaType = ctx.getMediaType();
        if (mediaType == null
                || !"application".equalsIgnoreCase(mediaType.getType())
                || !"x-www-form-urlencoded".equalsIgnoreCase(mediaType.getSubtype())) {
            return Map.of();
        }
        byte[] body = bufferEntity(ctx);
        if (body == null || body.length == 0) {
            return Map.of();
        }
        Charset charset = charsetOf(mediaType);
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : new String(body, charset).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            parameters.putIfAbsent(QueryFormDecoder.decode(key, charset, ctx),
                    QueryFormDecoder.decode(value, charset, ctx));
        }
        ctx.setProperty(BUFFERED_FORM_BODY, parameters);
        return parameters;
    }

    /**
     * Reads the entity fully and puts an equivalent stream back for downstream readers; none for a request that
     * carries no members, whose entity is left to its handler.
     */
    static byte[] bufferEntity(ContainerRequestContext ctx) {
        if (carriesNoMembers(ctx)) {
            return null;
        }
        InputStream in = ctx.getEntityStream();
        if (in == null) {
            return null;
        }
        byte[] body;
        try {
            body = in.readAllBytes();
        } catch (IOException e) {
            LOG.debugv(e, "Failed to buffer the request body for the IAM condition context");
            ctx.setEntityStream(new ByteArrayInputStream(new byte[0]));
            return null;
        }
        ctx.setEntityStream(new ByteArrayInputStream(body));
        return body;
    }

    private static Charset charsetOf(MediaType mediaType) {
        String name = mediaType.getParameters().get("charset");
        if (name == null || name.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    // ── DynamoDB ────────────────────────────────────────────────────────────────

    /**
     * Populates {@code dynamodb:LeadingKeys}, {@code dynamodb:Attributes} and
     * {@code dynamodb:Select} and single-item write {@code dynamodb:ReturnValues} from the buffered request body.
     *
     * <p>A key that cannot be resolved (unknown table, a Key that omits the partition
     * attribute, an unparseable KeyConditionExpression) is omitted rather than guessed.
     * A {@code ForAllValues} condition matches an absent key; policies must pair it with
     * {@code Null:false} to require a resolved key.
     */
    Map<String, List<String>> dynamoDbConditionContext(String action, ContainerRequestContext ctx) {
        JsonNode body = bufferedJsonBody(ctx);
        if (body == null || !body.isObject()) {
            return null;
        }
        if (isDynamoDbBatch(action)) {
            return dynamoDbBatchConditionContext(action, ctx, null);
        }
        TableDefinition table = describeTargetTable(targetTableName(body));
        DynamoDbConditionKeys.Result keys = dynamoDbKeys(action, body, table, ctx);

        Map<String, List<String>> conditions = new LinkedHashMap<>();
        putKeyConditions(conditions, keys);
        List<String> returnValues = singleItemReturnValues(action, body);
        if (!returnValues.isEmpty()) {
            conditions.put("dynamodb:ReturnValues", List.of(returnValues.getFirst()));
        }
        return conditions.isEmpty() ? null : conditions;
    }

    /** Adds the request-derived {@code LeadingKeys}, {@code Attributes} and {@code Select} that were resolved. */
    private static void putKeyConditions(Map<String, List<String>> conditions, DynamoDbConditionKeys.Result keys) {
        if (!keys.leadingKeys().isEmpty()) {
            conditions.put("dynamodb:LeadingKeys", keys.leadingKeys());
        }
        if (!keys.attributes().isEmpty()) {
            conditions.put("dynamodb:Attributes", keys.attributes());
        }
        if (keys.select() != null) {
            conditions.put("dynamodb:Select", List.of(keys.select()));
        }
    }

    private static List<String> singleItemReturnValues(String action, JsonNode body) {
        if (!("dynamodb:PutItem".equals(action) || "dynamodb:UpdateItem".equals(action)
                || "dynamodb:DeleteItem".equals(action)) || body == null || !body.isObject()) {
            return List.of();
        }
        Set<String> modes = new LinkedHashSet<>();
        for (String field : List.of("ReturnValues", "ReturnValuesOnConditionCheckFailure")) {
            String mode = body.path(field).asText("NONE");
            if (!"NONE".equals(mode)) {
                modes.add(mode);
            }
        }
        return modes.isEmpty() ? List.of("NONE") : List.copyOf(modes);
    }

    record AthenaQueryTarget(String resource, Map<String, List<String>> context) {
    }

    static boolean athenaQueryAction(String action) {
        if (action == null) {
            return false;
        }
        return switch (action) {
            case "athena:BatchGetQueryExecution", "athena:GetQueryExecution", "athena:GetQueryResults",
                    "athena:StopQueryExecution", "athena:ListQueryExecutions" -> true;
            default -> false;
        };
    }

    List<AthenaQueryTarget> athenaQueryTargets(String action, JsonNode body,
                                              String region, String account) {
        if (!athenaQueryAction(action) || athenaBatchQueryService == null
                || !athenaBatchQueryService.isResolvable()) {
            return List.of();
        }
        return athenaBatchQueryService.get().authorizationTargets(action, region, account, body).stream()
                .map(target -> new AthenaQueryTarget(target.resource(), target.context())).toList();
    }

    /** One transaction item keeps its action, table and condition keys together. */
    record DynamoDbTransactionItem(String action, String resource, Map<String, List<String>> context) {
    }

    /**
     * The condition keys of one check of an operation whose request's parts each take an action (see
     * {@link RequestResources#targets}): those {@link #resolve} gives the request for {@code action} when the check
     * is of the request itself ({@code part} null), and otherwise those of the part: a DynamoDB transaction item's
     * own keys with {@code dynamodb:EnclosingOperation} naming the transaction, or a batch's or transaction's PartiQL
     * statement's, with {@code dynamodb:EnclosingOperation} for a transaction's. Null when it gives none.
     */
    public Map<String, List<String>> resolvePart(String credentialScope, String action, String operation,
                                                 ContainerRequestContext ctx, JsonNode part, String region) {
        if (part == null || !"dynamodb".equals(credentialScope) || operation == null) {
            return resolve(credentialScope, action, ctx);
        }
        String enclosing = operation.substring(operation.indexOf(':') + 1);
        Map<String, List<String>> context = new LinkedHashMap<>();
        if (part.has("Statement")) {
            String statement = DynamoDbConditionKeys.partiQLStatement(part);
            if ("ExecuteTransaction".equals(enclosing)) {
                context.put("dynamodb:EnclosingOperation", List.of(enclosing));
            }
            putKeyConditions(context, DynamoDbConditionKeys.extract(action, part,
                    describeTargetTable(DynamoDbPartiQLParser.extractTable(statement))));
            return context;
        }
        boolean read = "TransactGetItems".equals(enclosing);
        String tableName = targetTableName(part);
        TableDefinition table;
        try {
            table = describeTargetTable(tableName == null ? null
                    : DynamoDbTableNames.resolveWithRegion(tableName, region).name());
        } catch (AwsException e) {
            table = null;
        }
        context.put("dynamodb:EnclosingOperation", List.of(enclosing));
        putKeyConditions(context, read ? DynamoDbConditionKeys.extractTransactionGet(part, table)
                : DynamoDbConditionKeys.extract(action, part, table));
        if (!read && part.hasNonNull("ReturnValuesOnConditionCheckFailure")) {
            context.put("dynamodb:ReturnValues", List.of(part.path("ReturnValuesOnConditionCheckFailure").asText()));
        }
        return context;
    }

    /** Request-wide context for callers that do not yet have a resolved resource. */
    Map<String, List<String>> dynamoDbBatchConditionContext(String action, ContainerRequestContext ctx,
                                                            String resourceArn) {
        List<Map<String, List<String>>> entries = dynamoDbBatchConditionContexts(action, ctx, resourceArn);
        if (entries == null) {
            return null;
        }
        Map<String, List<String>> combined = new LinkedHashMap<>();
        for (Map<String, List<String>> entry : entries) {
            entry.forEach((key, values) -> combined.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(values));
        }
        return combined;
    }

    /** Each table reference retains its own scalar selection, even when name and ARN resolve alike. */
    List<Map<String, List<String>>> dynamoDbBatchConditionContexts(String action, ContainerRequestContext ctx,
                                                                  String resourceArn) {
        if (!isDynamoDbBatch(action)) {
            return null;
        }
        List<Map<String, List<String>>> contexts = new ArrayList<>();
        JsonNode body = bufferedJsonBody(ctx);
        JsonNode items = body == null ? null : body.get("RequestItems");
        if (items == null || !items.isObject()) {
            return List.of(Map.of());
        }
        items.properties().forEach(entry -> {
            String reference = entry.getKey().trim();
            if (resourceArn != null && !matchesTableResource(reference, resourceArn)) {
                return;
            }
            ObjectNode single = ((ObjectNode) body).objectNode();
            single.setAll((ObjectNode) body);
            single.putObject("RequestItems").set(entry.getKey(), entry.getValue());
            DynamoDbConditionKeys.Result keys = dynamoDbKeys(
                    action, single, describeTargetTable(reference), ctx);
            Map<String, List<String>> conditions = new LinkedHashMap<>();
            putKeyConditions(conditions, keys);
            contexts.add(conditions);
        });
        return contexts.isEmpty() ? List.of(Map.of()) : contexts;
    }

    private static boolean isDynamoDbBatch(String action) {
        return "dynamodb:BatchGetItem".equals(action) || "dynamodb:BatchWriteItem".equals(action);
    }

    private static boolean matchesTableResource(String reference, String resourceArn) {
        if (AwsArnUtils.isArn(reference)) {
            return reference.equals(resourceArn);
        }
        return AwsArnUtils.parse(resourceArn).resource().equals("table/" + reference);
    }

    private JsonNode bufferedJsonBody(ContainerRequestContext ctx) {
        Object cached = ctx.getProperty(BUFFERED_JSON_BODY);
        return cached instanceof JsonNode node ? node : null;
    }

    private DynamoDbConditionKeys.Result dynamoDbKeys(
            String action, JsonNode body, TableDefinition table, ContainerRequestContext ctx) {
        String target = ctx.getHeaderString("X-Amz-Target");
        boolean legacy = action != null && action.startsWith("dynamodb:")
                && serviceCatalog.matchTarget(target)
                    .filter(ServiceCatalog.TargetMatch::jsonOnly)
                    .filter(match -> "dynamodb".equals(match.descriptor().externalKey()))
                    .filter(match -> match.action().equals(action.substring("dynamodb:".length())))
                    .isPresent()
                && ctx.getMediaType() != null
                && ctx.getMediaType().isCompatible(MediaType.valueOf("application/x-amz-json-1.0"));
        return legacy ? DynamoDbConditionKeys.extractLegacy(action, body, table)
                : DynamoDbConditionKeys.extract(action, body, table);
    }

    /**
     * Looks up the table whose key schema names the partition key. Resolved lazily through
     * Instance so core.common keeps no hard dependency on the DynamoDB facade, and via
     * {@code findTable} rather than {@code describeTable} so the O(items) item-count refresh
     * never runs on the enforcement hot path.
     */
    private TableDefinition describeTargetTable(String tableName) {
        if (tableName == null || !dynamoDbFacade.isResolvable()) {
            return null;
        }
        String region = requestContext.getRegion() == null
                ? config.defaultRegion() : requestContext.getRegion();
        DynamoDbFacade dynamoDb = dynamoDbFacade.get();
        return dynamoDb.tables().findTable(dynamoDb.scope(region), tableName).orElse(null);
    }

    /** The table targeted by a non-batch request. */
    private String targetTableName(JsonNode body) {
        if (body.hasNonNull("TableName")) {
            String tableName = body.get("TableName").asText().trim();
            if (!tableName.isEmpty()) {
                return tableName;
            }
        }
        return null;
    }

    private Map<String, List<String>> redshiftConditionContext(String action, ContainerRequestContext ctx) {
        List<String> keys = RequestConditionKeys.requestKeys(action);
        if (keys.isEmpty()) {
            return null;
        }
        Map<String, String> form = formParameters(ctx);
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        for (String key : keys) {
            for (String member : RequestConditionKeys.members(action, key)) {
                putIfPresent(conditions, key, form.get(member));
            }
        }
        return conditions.isEmpty() ? null : conditions;
    }

    private Map<String, List<String>> ec2CreateTagsConditionContext(ContainerRequestContext ctx) {
        Map<String, String> form = formParameters(ctx);
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        addRequestTags(conditions, form, "Tag.");
        addResourceTags(conditions, form.get("ResourceId.1"));
        return conditions.isEmpty() ? null : conditions;
    }

    private Map<String, List<String>> ec2ResourceTagConditionContext(ContainerRequestContext ctx,
                                                                     String idParameter) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        addResourceTags(conditions, formParameters(ctx).get(idParameter));
        return conditions.isEmpty() ? null : conditions;
    }

    private void addResourceTags(Map<String, List<String>> conditions, String resourceId) {
        if (resourceId == null || resourceId.isBlank() || ec2Service == null || !ec2Service.isResolvable()) {
            return;
        }
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        for (Tag tag : ec2Service.get().resourceTags(region, resourceId)) {
            conditions.put(RESOURCE_TAG_PREFIX + tag.getKey(),
                    List.of(tag.getValue() == null ? "" : tag.getValue()));
        }
    }
}
