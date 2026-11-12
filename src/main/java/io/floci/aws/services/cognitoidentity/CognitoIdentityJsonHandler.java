package io.floci.aws.services.cognitoidentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.services.cognitoidentity.model.CognitoIdentity;
import io.floci.aws.services.cognitoidentity.model.CognitoIdentityProvider;
import io.floci.aws.services.cognitoidentity.model.IdentityPool;
import io.floci.aws.services.cognitoidentity.model.PrincipalTagAttributeMap;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AWS JSON 1.1 identity-pool management and supported identity credential flows. */
@ApplicationScoped
public class CognitoIdentityJsonHandler {

    private final CognitoIdentityService cognitoIdentityService;
    private final ObjectMapper objectMapper;
    private final CognitoIdentityTokens tokens;
    private final CognitoIdentityCredentials credentials;

    @Inject
    public CognitoIdentityJsonHandler(CognitoIdentityService cognitoIdentityService, ObjectMapper objectMapper,
                                     CognitoIdentityTokens tokens, CognitoIdentityCredentials credentials) {
        this.cognitoIdentityService = cognitoIdentityService;
        this.objectMapper = objectMapper;
        this.tokens = tokens;
        this.credentials = credentials;
    }

    /** Unsigned public APIs identify their region through the regional pool or identity ID. */
    public Response handle(String action, JsonNode request, String region, boolean signed) throws Exception {
        if (!signed && ("GetId".equals(action) || "GetCredentialsForIdentity".equals(action)
                || "GetOpenIdToken".equals(action))) {
            String id = text(request, "GetId".equals(action) ? "IdentityPoolId" : "IdentityId");
            if (CognitoIdentityService.isIdentityId(id)) {
                return handle(action, request, id.substring(0, id.indexOf(':')));
            }
        }
        return handle(action, request, region);
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "CreateIdentityPool" -> Response.ok(
                    identityPoolNode(cognitoIdentityService.createIdentityPool(parsePool(request), region))).build();

            case "DescribeIdentityPool" -> Response.ok(identityPoolNode(
                    cognitoIdentityService.describeIdentityPool(text(request, "IdentityPoolId"), region))).build();

            case "UpdateIdentityPool" -> Response.ok(
                    identityPoolNode(cognitoIdentityService.updateIdentityPool(parsePool(request), region))).build();

            case "DeleteIdentityPool" -> {
                cognitoIdentityService.deleteIdentityPool(text(request, "IdentityPoolId"), region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }

            case "ListIdentityPools" -> {
                CognitoIdentityService.Page page = cognitoIdentityService.listIdentityPools(
                        integer(request, "MaxResults"), text(request, "NextToken"), region);
                ObjectNode response = objectMapper.createObjectNode();
                ArrayNode identityPools = response.putArray("IdentityPools");
                for (IdentityPool pool : page.identityPools()) {
                    ObjectNode summary = identityPools.addObject();
                    summary.put("IdentityPoolId", pool.getIdentityPoolId());
                    summary.put("IdentityPoolName", pool.getIdentityPoolName());
                }
                if (page.nextToken() != null) {
                    response.put("NextToken", page.nextToken());
                }
                yield Response.ok(response).build();
            }

            case "SetIdentityPoolRoles" -> {
                cognitoIdentityService.setIdentityPoolRoles(text(request, "IdentityPoolId"),
                        stringMap(request.get("Roles")), request.get("RoleMappings"), region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }

            case "GetIdentityPoolRoles" -> {
                IdentityPool pool = cognitoIdentityService.getIdentityPoolRoles(
                        text(request, "IdentityPoolId"), region);
                ObjectNode response = objectMapper.createObjectNode();
                response.put("IdentityPoolId", pool.getIdentityPoolId());
                response.set("Roles", objectMapper.valueToTree(pool.getRoles()));
                response.set("RoleMappings", pool.getRoleMappings() != null
                        ? pool.getRoleMappings()
                        : objectMapper.createObjectNode());
                yield Response.ok(response).build();
            }

            case "TagResource" -> {
                cognitoIdentityService.tagResource(text(request, "ResourceArn"),
                        stringMap(request.get("Tags")), region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }

            case "UntagResource" -> {
                cognitoIdentityService.untagResource(text(request, "ResourceArn"),
                        stringList(request.get("TagKeys")), region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }

            case "ListTagsForResource" -> {
                Map<String, String> tags = cognitoIdentityService.listTagsForResource(
                        text(request, "ResourceArn"), region);
                ObjectNode response = objectMapper.createObjectNode();
                response.set("Tags", objectMapper.valueToTree(tags));
                yield Response.ok(response).build();
            }

            case "SetPrincipalTagAttributeMap" -> {
                String identityPoolId = text(request, "IdentityPoolId");
                PrincipalTagAttributeMap attributeMap = cognitoIdentityService.setPrincipalTagAttributeMap(
                        identityPoolId, text(request, "IdentityProviderName"),
                        bool(request, "UseDefaults"), stringMap(request.get("PrincipalTags")), region);
                yield Response.ok(principalTagAttributeMapNode(identityPoolId, attributeMap)).build();
            }

            case "GetPrincipalTagAttributeMap" -> {
                String identityPoolId = text(request, "IdentityPoolId");
                PrincipalTagAttributeMap attributeMap = cognitoIdentityService.getPrincipalTagAttributeMap(
                        identityPoolId, text(request, "IdentityProviderName"), region);
                yield Response.ok(principalTagAttributeMapNode(identityPoolId, attributeMap)).build();
            }

            case "GetId" -> {
                CognitoIdentity identity = cognitoIdentityService.getId(text(request, "IdentityPoolId"),
                        text(request, "AccountId"), checkedStringMap(request.get("Logins")), region);
                yield Response.ok(objectMapper.createObjectNode().put("IdentityId", identity.identityId())).build();
            }
            case "DescribeIdentity" -> Response.ok(identityNode(cognitoIdentityService.describeIdentity(
                    text(request, "IdentityId"), region))).build();
            case "ListIdentities" -> {
                CognitoIdentityService.IdentityPage page = cognitoIdentityService.listIdentities(
                        text(request, "IdentityPoolId"), checkedInteger(request, "MaxResults"),
                        text(request, "NextToken"), Boolean.TRUE.equals(bool(request, "HideDisabled")), region);
                ObjectNode response = objectMapper.createObjectNode().put("IdentityPoolId", text(request, "IdentityPoolId"));
                ArrayNode identities = response.putArray("Identities");
                page.identities().forEach(identity -> identities.add(identityNode(identity)));
                if (page.nextToken() != null) {
                    response.put("NextToken", page.nextToken());
                }
                yield Response.ok(response).build();
            }
            case "DeleteIdentities" -> {
                JsonNode values = request.get("IdentityIdsToDelete");
                if (values == null || !values.isArray()) {
                    throw new AwsException("InvalidParameterException", "IdentityIdsToDelete must be a list.", 400);
                }
                List<String> unprocessed = cognitoIdentityService.deleteIdentities(stringList(values), region);
                ObjectNode response = objectMapper.createObjectNode();
                ArrayNode results = response.putArray("UnprocessedIdentityIds");
                unprocessed.forEach(id -> results.addObject().put("IdentityId", id)
                        .put("ErrorCode", "InvalidParameterException"));
                yield Response.ok(response).build();
            }
            case "GetOpenIdToken" -> {
                CognitoIdentity identity = cognitoIdentityService.publicIdentity(text(request, "IdentityId"), region);
                IdentityPool pool = cognitoIdentityService.publicPool(identity.identityPoolId(), region);
                if (!pool.isAllowClassicFlow()) {
                    throw new AwsException("InvalidParameterException", "Basic authentication is disabled.", 400);
                }
                CognitoIdentityService.requireSupportedRoleConfiguration(pool);
                CognitoIdentityService.rejectLogins(checkedStringMap(request.get("Logins")));
                CognitoIdentityService.requireGuest(pool, identity);
                yield Response.ok(tokenNode(identity, tokens.mint(pool, identity, 600))).build();
            }
            case "GetOpenIdTokenForDeveloperIdentity" -> {
                IdentityPool pool = cognitoIdentityService.describeIdentityPool(text(request, "IdentityPoolId"), region);
                CognitoIdentityService.requireSupportedRoleConfiguration(pool);
                if (!checkedStringMap(request.get("PrincipalTags")).isEmpty()) {
                    throw new AwsException("InvalidParameterException", "PrincipalTags are not supported.", 400);
                }
                long duration = 900;
                JsonNode requestedDuration = request.get("TokenDuration");
                if (requestedDuration != null && !requestedDuration.isNull()) {
                    if (!requestedDuration.isIntegralNumber() || !requestedDuration.canConvertToLong()
                            || requestedDuration.asLong() < 1 || requestedDuration.asLong() > 86400) {
                        throw new AwsException("InvalidParameterException", "TokenDuration must be between 1 and 86400.", 400);
                    }
                    duration = requestedDuration.asLong();
                }
                long tokenDuration = duration;
                ObjectNode token = cognitoIdentityService.developerIdentity(pool.getIdentityPoolId(),
                        text(request, "IdentityId"), checkedStringMap(request.get("Logins")), region,
                        identity -> tokenNode(identity, tokens.mint(pool, identity, tokenDuration)));
                yield Response.ok(token).build();
            }
            case "GetCredentialsForIdentity" -> {
                CognitoIdentityCredentials.Credentials issued = credentials.enhanced(text(request, "IdentityId"),
                        checkedStringMap(request.get("Logins")), text(request, "CustomRoleArn"), region);
                ObjectNode response = objectMapper.createObjectNode().put("IdentityId", issued.identityId());
                response.putObject("Credentials").put("AccessKeyId", issued.accessKeyId())
                        .put("SecretKey", issued.secretKey()).put("SessionToken", issued.sessionToken())
                        .put("Expiration", issued.expiration().getEpochSecond());
                yield Response.ok(response).build();
            }
            case "LookupDeveloperIdentity" -> {
                CognitoIdentityService.DeveloperLookup result = cognitoIdentityService.lookupDeveloper(
                        text(request, "IdentityPoolId"), text(request, "IdentityId"),
                        text(request, "DeveloperUserIdentifier"), checkedInteger(request, "MaxResults"),
                        text(request, "NextToken"), region);
                ObjectNode response = objectMapper.createObjectNode().put("IdentityId", result.identityId());
                response.set("DeveloperUserIdentifierList", objectMapper.valueToTree(result.developerUserIdentifiers()));
                if (result.nextToken() != null) {
                    response.put("NextToken", result.nextToken());
                }
                yield Response.ok(response).build();
            }
            case "UnlinkDeveloperIdentity" -> {
                cognitoIdentityService.unlinkDeveloper(text(request, "IdentityPoolId"), text(request, "IdentityId"),
                        text(request, "DeveloperProviderName"), text(request, "DeveloperUserIdentifier"), region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }

            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported by floci", 400);
        };
    }

    private ObjectNode tokenNode(CognitoIdentity identity, String token) {
        return objectMapper.createObjectNode().put("IdentityId", identity.identityId()).put("Token", token);
    }

    private ObjectNode identityNode(CognitoIdentity identity) {
        ObjectNode node = objectMapper.createObjectNode().put("IdentityId", identity.identityId())
                .put("CreationDate", identity.creationDate().getEpochSecond())
                .put("LastModifiedDate", identity.lastModifiedDate().getEpochSecond());
        IdentityPool pool = cognitoIdentityService.publicPool(identity.identityPoolId(), identity.region());
        node.set("Logins", objectMapper.valueToTree(identity.developerUserIdentifiers().isEmpty()
                ? List.of() : List.of(pool.getDeveloperProviderName())));
        return node;
    }

    private Map<String, String> checkedStringMap(JsonNode value) {
        if (value == null || value.isNull()) {
            return Map.of();
        }
        if (!value.isObject()) {
            throw new AwsException("SerializationException", "A string map is required.", 400);
        }
        Map<String, String> values = new LinkedHashMap<>();
        value.fields().forEachRemaining(entry -> {
            if (entry.getKey().isBlank() || !entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                throw new AwsException("InvalidParameterException", "Map names and values must be nonempty strings.", 400);
            }
            values.put(entry.getKey(), entry.getValue().asText());
        });
        return values;
    }

    private Integer checkedInteger(JsonNode request, String name) {
        JsonNode value = request.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new AwsException("SerializationException", name + " must be an integer.", 400);
        }
        return value.asInt();
    }

    private IdentityPool parsePool(JsonNode request) {
        IdentityPool pool = new IdentityPool();
        pool.setIdentityPoolId(text(request, "IdentityPoolId"));
        pool.setIdentityPoolName(text(request, "IdentityPoolName"));
        pool.setAllowUnauthenticatedIdentities(requiredBool(request, "AllowUnauthenticatedIdentities"));
        pool.setAllowClassicFlow(request.path("AllowClassicFlow").asBoolean(false));
        pool.setSupportedLoginProviders(stringMap(request.get("SupportedLoginProviders")));
        pool.setDeveloperProviderName(text(request, "DeveloperProviderName"));
        pool.setOpenIdConnectProviderArns(stringList(request.get("OpenIdConnectProviderARNs")));
        pool.setSamlProviderArns(stringList(request.get("SamlProviderARNs")));
        pool.setIdentityPoolTags(stringMap(request.get("IdentityPoolTags")));

        List<CognitoIdentityProvider> providers = new ArrayList<>();
        JsonNode providersNode = request.get("CognitoIdentityProviders");
        if (providersNode != null && providersNode.isArray()) {
            for (JsonNode node : providersNode) {
                CognitoIdentityProvider provider = new CognitoIdentityProvider();
                provider.setProviderName(text(node, "ProviderName"));
                provider.setClientId(text(node, "ClientId"));
                provider.setServerSideTokenCheck(node.path("ServerSideTokenCheck").asBoolean(false));
                providers.add(provider);
            }
        }
        pool.setCognitoIdentityProviders(providers);
        return pool;
    }

    private ObjectNode identityPoolNode(IdentityPool pool) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("IdentityPoolId", pool.getIdentityPoolId());
        node.put("IdentityPoolName", pool.getIdentityPoolName());
        node.put("AllowUnauthenticatedIdentities", pool.isAllowUnauthenticatedIdentities());
        node.put("AllowClassicFlow", pool.isAllowClassicFlow());
        node.set("SupportedLoginProviders", objectMapper.valueToTree(pool.getSupportedLoginProviders()));
        if (pool.getDeveloperProviderName() != null) {
            node.put("DeveloperProviderName", pool.getDeveloperProviderName());
        }
        node.set("OpenIdConnectProviderARNs", objectMapper.valueToTree(pool.getOpenIdConnectProviderArns()));
        node.set("SamlProviderARNs", objectMapper.valueToTree(pool.getSamlProviderArns()));
        node.set("IdentityPoolTags", objectMapper.valueToTree(pool.getIdentityPoolTags()));

        ArrayNode providers = node.putArray("CognitoIdentityProviders");
        for (CognitoIdentityProvider provider : pool.getCognitoIdentityProviders()) {
            ObjectNode providerNode = providers.addObject();
            providerNode.put("ProviderName", provider.getProviderName());
            providerNode.put("ClientId", provider.getClientId());
            providerNode.put("ServerSideTokenCheck", provider.isServerSideTokenCheck());
        }
        return node;
    }

    private ObjectNode principalTagAttributeMapNode(String identityPoolId, PrincipalTagAttributeMap attributeMap) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("IdentityPoolId", identityPoolId);
        node.put("IdentityProviderName", attributeMap.getIdentityProviderName());
        node.put("UseDefaults", attributeMap.isUseDefaults());
        node.set("PrincipalTags", objectMapper.valueToTree(attributeMap.getPrincipalTags()));
        return node;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        return value.asInt();
    }

    private Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw new AwsException("SerializationException", field + " must be a boolean.", 400);
        }
        return value.asBoolean();
    }

    private boolean requiredBool(JsonNode node, String field) {
        Boolean value = bool(node, field);
        if (value == null) {
            throw SmithyValidationErrors.validationException(
                    SmithyValidationErrors.notNull(Character.toLowerCase(field.charAt(0)) + field.substring(1)));
        }
        return value;
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        }
        return values;
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(element -> values.add(element.asText()));
        }
        return values;
    }
}
