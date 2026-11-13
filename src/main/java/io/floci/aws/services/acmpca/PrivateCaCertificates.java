package io.floci.aws.services.acmpca;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Pem;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERPrintableString;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** RootCACertificate/V1 and EndEntityCertificate/V1, with actual CSR proof of possession. */
final class PrivateCaCertificates {

    private static final Map<String, ASN1ObjectIdentifier> SUBJECT_FIELDS = subjectFields();

    private PrivateCaCertificates() {}

    record KeyMaterial(String privateKeyPem, String csrPem) {}

    static Set<String> subjectFieldNames() {
        return SUBJECT_FIELDS.keySet();
    }

    static KeyMaterial create(Map<String, String> subject) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            ExtensionsGenerator extensions = new ExtensionsGenerator();
            extensions.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign));
            PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(
                    subject(subject), pair.getPublic())
                    .addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate())
                    .build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate()));
            return new KeyMaterial(pem(pair.getPrivate()), pem(csr));
        } catch (Exception failure) {
            throw new AwsException("RequestFailedException", "Unable to generate the private CA key and CSR", 400);
        }
    }

    static PKCS10CertificationRequest csr(String text) {
        try (PEMParser parser = new PEMParser(new StringReader(text))) {
            Object object = parser.readObject();
            if (!(object instanceof PKCS10CertificationRequest request) || parser.readObject() != null) {
                throw new IllegalArgumentException("Expected exactly one PKCS#10 CSR");
            }
            PublicKey key = new JcaPKCS10CertificationRequest(request).getPublicKey();
            if (!(key instanceof RSAPublicKey rsa)
                    || !Set.of(2048, 3072, 4096).contains(rsa.getModulus().bitLength())) {
                throw new AwsException("InvalidArgsException", "Only RSA 2048, 3072 and 4096 bit CSRs are supported", 400);
            }
            if (!request.isSignatureValid(new JcaContentVerifierProviderBuilder().build(key))) {
                throw new IllegalArgumentException("Invalid CSR signature");
            }
            GeneralNames names = subjectAlternativeNames(request);
            if (request.getSubject().getRDNs().length == 0 && (names == null || names.getNames().length == 0)) {
                throw new IllegalArgumentException("A CSR must contain a subject or subject alternative name");
            }
            return request;
        } catch (AwsException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AwsException("MalformedCSRException", "The certificate signing request is malformed", 400);
        }
    }

    static void requireOwnCsr(PKCS10CertificationRequest csr, String ownCsr) {
        PKCS10CertificationRequest own = csr(ownCsr);
        if (!own.getSubject().equals(csr.getSubject())
                || !own.getSubjectPublicKeyInfo().equals(csr.getSubjectPublicKeyInfo())) {
            throw new AwsException("InvalidArgsException", "The root certificate must use this CA's subject and key", 400);
        }
    }

    static X509Certificate issue(PKCS10CertificationRequest csr, String privateKeyPem,
                                 X509Certificate parent, Instant notBefore, Instant notAfter) {
        try {
            boolean root = parent == null;
            PrivateKey key = Pem.parsePrivateKey(privateKeyPem);
            X500Name issuer = root ? csr.getSubject()
                    : X500Name.getInstance(parent.getSubjectX500Principal().getEncoded());
            BigInteger serial = new BigInteger(159, new SecureRandom()).add(BigInteger.ONE);
            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    issuer, serial, Date.from(notBefore), Date.from(notAfter),
                    csr.getSubject(), csr.getSubjectPublicKeyInfo());
            JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.basicConstraints, root, new BasicConstraints(root));
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                    extensions.createSubjectKeyIdentifier(csr.getSubjectPublicKeyInfo()));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(root
                    ? KeyUsage.digitalSignature | KeyUsage.keyCertSign | KeyUsage.cRLSign
                    : KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            if (!root) {
                builder.addExtension(Extension.authorityKeyIdentifier, false,
                        extensions.createAuthorityKeyIdentifier(parent));
                builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(
                        new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
            }
            GeneralNames names = subjectAlternativeNames(csr);
            if (names != null) {
                builder.addExtension(Extension.subjectAlternativeName, csr.getSubject().getRDNs().length == 0, names);
            }
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(key)));
            certificate.verify(root ? new JcaPKCS10CertificationRequest(csr).getPublicKey() : parent.getPublicKey());
            return certificate;
        } catch (Exception failure) {
            throw new AwsException("RequestFailedException", "Unable to sign the certificate", 400);
        }
    }

    static X509Certificate certificate(String text) {
        try (PEMParser parser = new PEMParser(new StringReader(text))) {
            Object object = parser.readObject();
            if (!(object instanceof X509CertificateHolder holder) || parser.readObject() != null) {
                throw new IllegalArgumentException("Expected exactly one X.509 certificate");
            }
            return new JcaX509CertificateConverter().getCertificate(holder);
        } catch (Exception failure) {
            throw new AwsException("MalformedCertificateException", "The certificate is malformed", 400);
        }
    }

    static X509Certificate importedRoot(String text, String ownCsr, Instant now) {
        X509Certificate certificate = certificate(text);
        try {
            PKCS10CertificationRequest request = csr(ownCsr);
            if (!Arrays.equals(certificate.getPublicKey().getEncoded(), request.getSubjectPublicKeyInfo().getEncoded())
                    || !X500Name.getInstance(certificate.getSubjectX500Principal().getEncoded())
                    .equals(request.getSubject())) {
                throw new AwsException("CertificateMismatchException", "The certificate does not match this CA's CSR", 400);
            }
            if (!certificate.getIssuerX500Principal().equals(certificate.getSubjectX500Principal())) {
                throw new IllegalArgumentException("A root certificate must be self-signed");
            }
            certificate.verify(certificate.getPublicKey());
            certificate.checkValidity(Date.from(now));
            Set<String> critical = certificate.getCriticalExtensionOIDs();
            boolean[] usage = certificate.getKeyUsage();
            if (certificate.getBasicConstraints() < 0 || critical == null
                    || !critical.contains(Extension.basicConstraints.getId())
                    || usage == null || usage.length <= 5 || !usage[5]) {
                throw new IllegalArgumentException("A CA certificate needs critical CA constraints and keyCertSign");
            }
            // This bounded issuer does not implement imported name/policy constraints.
            if (!Set.of(Extension.basicConstraints.getId(), Extension.keyUsage.getId(),
                    Extension.subjectAlternativeName.getId()).containsAll(critical)
                    || certificate.getExtensionValue(Extension.nameConstraints.getId()) != null
                    || certificate.getExtensionValue(Extension.policyConstraints.getId()) != null
                    || certificate.getExtensionValue(Extension.policyMappings.getId()) != null
                    || certificate.getExtensionValue(Extension.inhibitAnyPolicy.getId()) != null
                    || certificate.getExtendedKeyUsage() != null) {
                throw new AwsException("InvalidRequestException",
                        "Imported certificate constraints outside the root CA profile are not supported", 400);
            }
            return certificate;
        } catch (AwsException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AwsException("MalformedCertificateException",
                    "A currently valid self-signed CA certificate with keyCertSign is required", 400);
        }
    }

    static String pem(Object object) {
        try (StringWriter text = new StringWriter(); JcaPEMWriter writer = new JcaPEMWriter(text)) {
            writer.writeObject(object);
            writer.flush();
            return text.toString();
        } catch (Exception failure) {
            throw new AwsException("RequestFailedException", "Unable to encode certificate material", 400);
        }
    }

    private static GeneralNames subjectAlternativeNames(PKCS10CertificationRequest csr) {
        Attribute[] attributes = csr.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest);
        if (attributes.length > 1 || attributes.length == 1 && attributes[0].getAttrValues().size() != 1) {
            throw new IllegalArgumentException("Ambiguous CSR extension request");
        }
        if (attributes.length == 0) {
            return null;
        }
        Extensions requested = Extensions.getInstance(attributes[0].getAttrValues().getObjectAt(0));
        Extension names = requested.getExtension(Extension.subjectAlternativeName);
        return names == null ? null : GeneralNames.getInstance(names.getParsedValue());
    }

    private static X500Name subject(Map<String, String> subject) {
        X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
        SUBJECT_FIELDS.forEach((name, oid) -> {
            if (subject.containsKey(name)) {
                String value = subject.get(name);
                // API attributes are literal strings; DN parsing changes prefixes and counts CN UTF-16 units.
                builder.addRDN(oid, BCStyle.C.equals(oid) || BCStyle.SERIALNUMBER.equals(oid)
                        ? new DERPrintableString(value, true) : new DERUTF8String(value));
            }
        });
        return builder.build();
    }

    private static Map<String, ASN1ObjectIdentifier> subjectFields() {
        Map<String, ASN1ObjectIdentifier> fields = new LinkedHashMap<>();
        fields.put("Country", BCStyle.C);
        fields.put("State", BCStyle.ST);
        fields.put("Locality", BCStyle.L);
        fields.put("Organization", BCStyle.O);
        fields.put("OrganizationalUnit", BCStyle.OU);
        fields.put("CommonName", BCStyle.CN);
        fields.put("SerialNumber", BCStyle.SERIALNUMBER);
        return fields;
    }
}
