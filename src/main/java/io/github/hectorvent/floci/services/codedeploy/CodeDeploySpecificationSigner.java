package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.ECKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/** Attached signatures consumed by the ordinary CodeDeploy agent without developer mode. */
@ApplicationScoped
public class CodeDeploySpecificationSigner {

    private final CertificateGenerator.Issuer issuer;

    @Inject
    public CodeDeploySpecificationSigner(FlociCertificateAuthority authority) {
        this(authority.issuer());
    }

    CodeDeploySpecificationSigner(CertificateGenerator.Issuer issuer) {
        this.issuer = issuer;
    }

    public String sign(String specification) {
        try {
            PrivateKey signingKey = issuer.key();
            boolean ecKey = signingKey instanceof ECKey;
            if (ecKey && !"EC".equals(signingKey.getAlgorithm())) {
                signingKey = KeyFactory.getInstance("EC")
                        .generatePrivate(new PKCS8EncodedKeySpec(signingKey.getEncoded()));
            }
            String algorithm = ecKey ? "SHA256withECDSA" : switch (signingKey.getAlgorithm()) {
                case "RSA" -> "SHA256withRSA";
                default -> throw new IllegalStateException("Unsupported CodeDeploy signing key algorithm");
            };
            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
            generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(
                    new JcaDigestCalculatorProviderBuilder().build()).build(
                    new JcaContentSignerBuilder(algorithm).build(signingKey),
                    issuer.certificate()));
            generator.addCertificates(new JcaCertStore(List.of(issuer.certificate())));
            byte[] signed = generator.generate(new CMSProcessableByteArray(
                    specification.getBytes(StandardCharsets.UTF_8)), true).getEncoded();
            return "-----BEGIN PKCS7-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(signed)
                    + "\n-----END PKCS7-----\n";
        } catch (Exception failure) {
            throw new IllegalStateException("Could not sign the CodeDeploy deployment specification", failure);
        }
    }
}
