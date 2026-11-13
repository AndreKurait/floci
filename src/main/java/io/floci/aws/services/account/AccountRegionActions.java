package io.floci.aws.services.account;

import java.util.Map;

/** The four Region handlers whose matched methods share Account Region authorization rules. */
public final class AccountRegionActions {
    private static final Map<String, String> METHODS = Map.of(
            "listRegions", "account:ListRegions",
            "getRegionOptStatus", "account:GetRegionOptStatus",
            "enableRegion", "account:EnableRegion",
            "disableRegion", "account:DisableRegion");

    private AccountRegionActions() {}

    public static String forMethod(String method) {
        return METHODS.get(method);
    }

    public static boolean supports(String operation) {
        return operation != null && METHODS.containsValue(operation);
    }
}
