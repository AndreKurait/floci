package io.github.hectorvent.floci.services.ec2;

public record ResolvedAmiImage(String dockerImage, String guestRuntime, boolean cloudInit,
                               String dockerPlatform) {
    public static final String DEFAULT_RUNTIME = "minimal";
    public static final String SYSTEMD_RUNTIME = "systemd";
    public static final String IMAGE_RUNTIME = "image";

    public static ResolvedAmiImage minimal(String dockerImage) {
        return new ResolvedAmiImage(dockerImage, DEFAULT_RUNTIME, false, null);
    }

    public boolean systemd() {
        return SYSTEMD_RUNTIME.equals(guestRuntime);
    }

    public boolean imageRuntime() {
        return IMAGE_RUNTIME.equals(guestRuntime);
    }
}
