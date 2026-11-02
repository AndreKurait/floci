package io.github.hectorvent.floci.services.ec2;

import java.util.List;

public record ResolvedAmiImage(String dockerImage, String guestRuntime, boolean cloudInit,
                               String dockerPlatform, List<Ec2ImageCatalog.VolumeMount> volumeMounts) {
    public static final String DEFAULT_RUNTIME = "minimal";
    public static final String SYSTEMD_RUNTIME = "systemd";
    public static final String IMAGE_RUNTIME = "image";

    public ResolvedAmiImage {
        volumeMounts = volumeMounts == null ? List.of() : List.copyOf(volumeMounts);
    }

    public ResolvedAmiImage(String dockerImage, String guestRuntime, boolean cloudInit, String dockerPlatform) {
        this(dockerImage, guestRuntime, cloudInit, dockerPlatform, List.of());
    }

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
