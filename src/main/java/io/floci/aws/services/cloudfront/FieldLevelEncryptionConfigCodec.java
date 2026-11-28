package io.floci.aws.services.cloudfront;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.XmlBuilder;
import io.floci.aws.core.common.XmlParser;
import io.floci.aws.core.common.XmlParser.XmlElement;
import io.floci.aws.services.cloudfront.model.FieldLevelEncryptionConfig;
import io.floci.aws.services.cloudfront.model.FieldLevelEncryptionProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persists the documented configuration shape; this codec does not encrypt viewer requests. */
final class FieldLevelEncryptionConfigCodec {

    private FieldLevelEncryptionConfigCodec() {
    }

    static FieldLevelEncryptionConfig parse(String body) {
        XmlElement root = XmlParser.parseDocumentElementTree(body);
        if (root == null || !"FieldLevelEncryptionConfig".equals(root.name())) {
            throw invalid("A valid FieldLevelEncryptionConfig XML document is required.");
        }
        FieldLevelEncryptionConfig config = new FieldLevelEncryptionConfig();
        config.setCallerReference(text(root, "CallerReference", true));
        config.setComment(text(root, "Comment", false));
        config.setQueryArgProfileConfig(parseBlock(root.child("QueryArgProfileConfig"),
                "ForwardWhenQueryArgProfileIsUnknown", "QueryArgProfiles", "QueryArgProfile",
                List.of("ProfileId", "QueryArg")));
        config.setContentTypeProfileConfig(parseBlock(root.child("ContentTypeProfileConfig"),
                "ForwardWhenContentTypeIsUnknown", "ContentTypeProfiles", "ContentTypeProfile",
                List.of("ContentType", "Format", "ProfileId")));
        return config;
    }

    private static Map<String, Object> parseBlock(XmlElement block, String forward, String collection,
                                                   String itemName, List<String> fields) {
        if (block == null) {
            return null;
        }
        String value = text(block, forward, true);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw invalid(forward + " must be a boolean.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(forward, Boolean.valueOf(value));
        XmlElement list = block.child(collection);
        if (list == null) {
            return result;
        }
        List<Map<String, String>> items = new ArrayList<>();
        XmlElement container = list.child("Items");
        if (container != null) {
            for (XmlElement entry : container.children()) {
                if (!itemName.equals(entry.name())) {
                    throw invalid("Unexpected element in " + collection + ".");
                }
                Map<String, String> item = new LinkedHashMap<>();
                for (String field : fields) {
                    boolean required = !("ContentTypeProfile".equals(itemName) && "ProfileId".equals(field));
                    String fieldValue = text(entry, field, required);
                    if (fieldValue != null) {
                        item.put(field, fieldValue);
                    }
                }
                if (item.containsKey("Format") && !"URLEncoded".equals(item.get("Format"))) {
                    throw invalid("The content type profile format must be URLEncoded.");
                }
                items.add(item);
            }
        }
        int quantity = quantity(list, items.size());
        Map<String, Object> listValue = new LinkedHashMap<>();
        listValue.put("Quantity", quantity);
        if (container != null) {
            listValue.put("Items", items);
        }
        result.put(collection, listValue);
        return result;
    }

    static void serialize(XmlBuilder xml, FieldLevelEncryptionConfig config) {
        xml.elem("CallerReference", config.getCallerReference());
        if (config.getComment() != null) {
            xml.elem("Comment", config.getComment());
        }
        serializeBlock(xml, "QueryArgProfileConfig", config.getQueryArgProfileConfig(),
                "ForwardWhenQueryArgProfileIsUnknown", "QueryArgProfiles", "QueryArgProfile");
        serializeBlock(xml, "ContentTypeProfileConfig", config.getContentTypeProfileConfig(),
                "ForwardWhenContentTypeIsUnknown", "ContentTypeProfiles", "ContentTypeProfile");
    }

    @SuppressWarnings("unchecked")
    private static void serializeBlock(XmlBuilder xml, String name, Map<String, Object> config,
                                       String forward, String collection, String itemName) {
        if (config == null) {
            return;
        }
        xml.start(name).elem(forward, String.valueOf(config.get(forward)));
        Map<String, Object> list = (Map<String, Object>) config.get(collection);
        if (list != null) {
            xml.start(collection).elem("Quantity", String.valueOf(list.get("Quantity")));
            List<Map<String, String>> items = (List<Map<String, String>>) list.get("Items");
            if (items != null) {
                xml.start("Items");
                for (Map<String, String> item : items) {
                    xml.start(itemName);
                    item.forEach(xml::elem);
                    xml.end(itemName);
                }
                xml.end("Items");
            }
            xml.end(collection);
        }
        xml.end(name);
    }

    static Set<String> profileIds(FieldLevelEncryptionConfig config) {
        Set<String> ids = new LinkedHashSet<>();
        collectProfileIds(ids, config.getQueryArgProfileConfig(), "QueryArgProfiles");
        collectProfileIds(ids, config.getContentTypeProfileConfig(), "ContentTypeProfiles");
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static void collectProfileIds(Set<String> ids, Map<String, Object> block, String collection) {
        if (block == null || !(block.get(collection) instanceof Map<?, ?> list)) {
            return;
        }
        List<Map<String, String>> items = (List<Map<String, String>>) list.get("Items");
        if (items != null) {
            for (Map<String, String> item : items) {
                String id = item.get("ProfileId");
                if (id != null && !id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
    }

    static FieldLevelEncryptionProfile parseProfile(String body) {
        XmlElement root = XmlParser.parseDocumentElementTree(body);
        if (root == null || !"FieldLevelEncryptionProfileConfig".equals(root.name())) {
            throw invalid("A valid FieldLevelEncryptionProfileConfig XML document is required.");
        }
        FieldLevelEncryptionProfile profile = new FieldLevelEncryptionProfile();
        profile.setName(text(root, "Name", true));
        profile.setCallerReference(text(root, "CallerReference", true));
        profile.setComment(text(root, "Comment", false));
        XmlElement entities = root.child("EncryptionEntities");
        if (entities == null) {
            throw invalid("EncryptionEntities is required.");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> allPatterns = new ArrayList<>();
        XmlElement container = entities.child("Items");
        if (container != null) {
            for (XmlElement entry : container.children()) {
                if (!"EncryptionEntity".equals(entry.name())) {
                    throw invalid("Unexpected element in EncryptionEntities.");
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("PublicKeyId", text(entry, "PublicKeyId", true));
                item.put("ProviderId", text(entry, "ProviderId", true));
                XmlElement patterns = entry.child("FieldPatterns");
                if (patterns == null) {
                    throw invalid("FieldPatterns is required.");
                }
                List<String> values = new ArrayList<>();
                XmlElement patternItems = patterns.child("Items");
                if (patternItems != null) {
                    for (XmlElement pattern : patternItems.children()) {
                        if (!"FieldPattern".equals(pattern.name()) || !pattern.children().isEmpty()) {
                            throw invalid("FieldPatterns Items must contain scalar FieldPattern values.");
                        }
                        String value = pattern.rawText();
                        if (value.isEmpty() || value.indexOf('*') >= 0 && value.indexOf('*') != value.length() - 1) {
                            throw invalid("Field patterns must be a field name or a prefix followed by '*'.");
                        }
                        if (allPatterns.stream().anyMatch(existing -> overlaps(existing, value))) {
                            throw invalid("Field patterns must not overlap.");
                        }
                        values.add(value);
                        allPatterns.add(value);
                    }
                }
                Map<String, Object> patternValue = new LinkedHashMap<>();
                patternValue.put("Quantity", quantity(patterns, values.size()));
                if (patternItems != null) {
                    patternValue.put("Items", values);
                }
                item.put("FieldPatterns", patternValue);
                items.add(item);
            }
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("Quantity", quantity(entities, items.size()));
        if (container != null) {
            value.put("Items", items);
        }
        profile.setEncryptionEntities(value);
        return profile;
    }

    private static boolean overlaps(String first, String second) {
        return first.equals(second) || first.endsWith("*") && second.startsWith(first.substring(0, first.length() - 1))
                || second.endsWith("*") && first.startsWith(second.substring(0, second.length() - 1));
    }

    @SuppressWarnings("unchecked")
    static void serializeProfile(XmlBuilder xml, FieldLevelEncryptionProfile profile) {
        xml.elem("Name", profile.getName()).elem("CallerReference", profile.getCallerReference());
        if (profile.getComment() != null) {
            xml.elem("Comment", profile.getComment());
        }
        Map<String, Object> entities = profile.getEncryptionEntities();
        if (entities == null) {
            return;
        }
        xml.start("EncryptionEntities").elem("Quantity", String.valueOf(entities.get("Quantity")));
        List<Map<String, Object>> items = (List<Map<String, Object>>) entities.get("Items");
        if (items != null) {
            xml.start("Items");
            for (Map<String, Object> item : items) {
                xml.start("EncryptionEntity").elem("PublicKeyId", (String) item.get("PublicKeyId"))
                        .elem("ProviderId", (String) item.get("ProviderId"));
                Map<String, Object> patterns = (Map<String, Object>) item.get("FieldPatterns");
                xml.start("FieldPatterns").elem("Quantity", String.valueOf(patterns.get("Quantity")));
                List<String> values = (List<String>) patterns.get("Items");
                if (values != null) {
                    xml.start("Items");
                    values.forEach(value -> xml.elem("FieldPattern", value));
                    xml.end("Items");
                }
                xml.end("FieldPatterns").end("EncryptionEntity");
            }
            xml.end("Items");
        }
        xml.end("EncryptionEntities");
    }

    @SuppressWarnings("unchecked")
    static Set<String> publicKeyIds(FieldLevelEncryptionProfile profile) {
        Set<String> ids = new LinkedHashSet<>();
        Map<String, Object> entities = profile.getEncryptionEntities();
        if (entities != null && entities.get("Items") instanceof List<?> items) {
            for (Object entry : items) {
                ids.add((String) ((Map<String, Object>) entry).get("PublicKeyId"));
            }
        }
        return ids;
    }

    private static int quantity(XmlElement list, int size) {
        int quantity;
        try {
            quantity = Integer.parseInt(text(list, "Quantity", true).trim());
        } catch (NumberFormatException exception) {
            throw invalid(list.name() + " Quantity must be an integer.");
        }
        if (quantity < 0 || quantity != size) {
            throw new AwsException("InconsistentQuantities",
                    "The value of Quantity and the size of Items do not match.", 400);
        }
        return quantity;
    }

    private static String text(XmlElement parent, String field, boolean required) {
        XmlElement child = parent.child(field);
        if (child == null) {
            if (required) {
                throw invalid(field + " is required.");
            }
            return null;
        }
        if (!child.children().isEmpty()) {
            throw invalid(field + " must be a scalar value.");
        }
        return child.rawText();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidArgument", message, 400);
    }
}
