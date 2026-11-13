package io.github.hectorvent.floci.services.kms;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * KMS GrantListEntry: "When a grant is created with the GranteePrincipal field, the ListGrants response
 * usually contains the user or role designated as the grantee principal in the grant. However, if the
 * grantee principal is an AWS service, the GranteePrincipal field contains an AWS service principal,
 * which might correspond to several different grantee principals, such as an IAM user, IAM role, or AWS
 * account." The setting names which accounts' principals are listed as which service principal.
 */
class KmsListedGranteeServicePrincipalsTest {

    private static final String LISTED = "111122223333";

    @Test
    void unsetListsEveryPrincipalAsStored() {
        KmsListedGranteeServicePrincipals none = new KmsListedGranteeServicePrincipals(List.of());
        for (String principal : List.of("arn:aws:iam::" + LISTED + ":role/worker", "arn:aws:iam::" + LISTED + ":root")) {
            assertEquals(principal, none.listed(principal));
        }
    }

    @Test
    void principalsOfAListedAccountAreListedAsItsServicePrincipal() {
        KmsListedGranteeServicePrincipals listed = new KmsListedGranteeServicePrincipals(
                List.of(LISTED + "=example.us-east-1.amazonaws.com", " "));
        for (String principal : List.of(
                "arn:aws:iam::" + LISTED + ":user/worker",
                "arn:aws:iam::" + LISTED + ":role/path/worker",
                "arn:aws:sts::" + LISTED + ":assumed-role/worker/session",
                "arn:aws:iam::" + LISTED + ":root")) {
            assertEquals("example.us-east-1.amazonaws.com", listed.listed(principal), principal);
        }
        for (String principal : List.of("arn:aws:iam::444455556666:role/worker", "example.amazonaws.com",
                "arn:aws:kms:us-east-1:" + LISTED + ":key/k", "")) {
            assertEquals(principal, listed.listed(principal), principal);
        }
        assertEquals(null, listed.listed(null));
    }

    @Test
    void malformedEntriesAreRefused() {
        for (String entry : List.of("111122223333", "11112222333=example.us-east-1.amazonaws.com",
                "111122223333=example", "111122223333=arn:aws:iam::1:root", "=example.us-east-1.amazonaws.com")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new KmsListedGranteeServicePrincipals(List.of(entry)), entry);
        }
        assertThrows(IllegalArgumentException.class, () -> new KmsListedGranteeServicePrincipals(
                List.of(LISTED + "=a.us-east-1.amazonaws.com", LISTED + "=b.us-east-1.amazonaws.com")));
    }
}
