package io.github.hectorvent.floci.services.cloudformation.provisioners;

/** Whether a change-set preview can determine that a resource must be replaced. */
public enum CfnReplacement {
    NEVER("False"),
    CONDITIONAL("Conditional"),
    ALWAYS("True");

    private final String value;

    CfnReplacement(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Combines independent reasons why a resource may need replacement. */
    public CfnReplacement stronger(CfnReplacement other) {
        return ordinal() >= other.ordinal() ? this : other;
    }

    /** Replacement needs both the property rule and a change to its value. */
    public CfnReplacement weaker(CfnReplacement other) {
        return ordinal() <= other.ordinal() ? this : other;
    }
}
