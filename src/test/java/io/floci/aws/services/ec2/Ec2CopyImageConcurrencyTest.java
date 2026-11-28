package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.Image;
import io.floci.aws.testing.StartGate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.floci.aws.testing.Ec2ServiceFixtures.mockModeService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2CopyImageConcurrencyTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentCopiesKeepEveryImageAndUseDistinctIdentifiers(boolean sameName) throws InterruptedException {
        Ec2Service service = mockModeService();
        Image source = service.registerImage("us-east-1", "copy-source", null, "x86_64", "/dev/xvda", null);
        int copies = 24;

        StartGate.Results<Image> results = StartGate.runConcurrently(copies, index ->
                service.copyImage("us-west-2", "us-east-1", source.getImageId(),
                        sameName ? "copy-target" : "copy-target-" + index, null, false, List.of()));

        assertTrue(results.errors().isEmpty(), () -> "Copy workers failed: " + results.errors());
        Set<String> ids = results.values().stream().map(Image::getImageId).collect(Collectors.toSet());
        assertEquals(copies, ids.size());
        List<Image> stored = service.describeImages("us-west-2", List.of(), List.of("self"));
        assertEquals(ids, stored.stream().map(Image::getImageId).collect(Collectors.toSet()));
        assertEquals(sameName ? 1 : copies, stored.stream().map(Image::getName).distinct().count());
        assertTrue(stored.stream().allMatch(image -> "available".equals(image.getState())));
        assertEquals(List.of(source.getImageId()), service.describeImages("us-east-1", List.of(), List.of("self"))
                .stream().map(Image::getImageId).toList());
    }
}
