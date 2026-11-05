package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;

/** Encryption metadata captured when an object or multipart upload is created. */
record S3ObjectEncryption(String algorithm, String kmsKeyId) {

    S3ObjectEncryption {
        kmsKeyId = "aws:kms".equals(algorithm) ? kmsKeyId : null;
    }

    static S3ObjectEncryption resolve(String configuration, String requestedAlgorithm,
                                      String requestedKeyId, boolean customerKeyProvided) {
        if (customerKeyProvided) {
            return new S3ObjectEncryption(null, null);
        }
        if (requestedAlgorithm != null) {
            return new S3ObjectEncryption(requestedAlgorithm, requestedKeyId);
        }
        return fromConfiguration(configuration);
    }

    static S3ObjectEncryption fromConfiguration(String configuration) {
        if (configuration == null) {
            return new S3ObjectEncryption("AES256", null);
        }
        XmlParser.XmlElement root = XmlParser.parseDocumentElementTree(configuration);
        if (root == null || !"ServerSideEncryptionConfiguration".equals(root.name())
                || root.count("Rule") != 1) {
            throw malformedConfiguration();
        }
        XmlParser.XmlElement rule = root.child("Rule");
        if (rule.child("BlockedEncryptionTypes") != null) {
            throw new AwsException("NotImplemented", "BlockedEncryptionTypes is not implemented.", 501);
        }
        if (rule.count("ApplyServerSideEncryptionByDefault") > 1) {
            throw malformedConfiguration();
        }
        XmlParser.XmlElement defaults = rule.child("ApplyServerSideEncryptionByDefault");
        if (defaults == null) {
            return new S3ObjectEncryption("AES256", null);
        }
        if (defaults.count("SSEAlgorithm") != 1 || defaults.count("KMSMasterKeyID") > 1) {
            throw malformedConfiguration();
        }
        String algorithm = S3Service.normalizeServerSideEncryption(defaults.child("SSEAlgorithm").text());
        if (algorithm == null) {
            throw malformedConfiguration();
        }
        XmlParser.XmlElement key = defaults.child("KMSMasterKeyID");
        return new S3ObjectEncryption(algorithm, key == null ? null : key.text().trim());
    }

    private static AwsException malformedConfiguration() {
        return new AwsException("MalformedXML",
                "The XML you provided was not well-formed or did not validate against our published schema.", 400);
    }
}
