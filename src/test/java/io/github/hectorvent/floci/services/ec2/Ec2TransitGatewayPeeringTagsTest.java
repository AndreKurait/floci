package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.TransitGatewayPeeringAttachment;
import io.github.hectorvent.floci.testing.Ec2ServiceFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Each end of a transit gateway peering tags the attachment for itself, so what one region was
 * told must not change when the other region reads the same attachment.
 */
class Ec2TransitGatewayPeeringTagsTest {

    private static final String REQUESTER_REGION = "us-east-1";
    private static final String ACCEPTER_REGION = "us-west-2";

    @Test
    void oneSideReadingAPeeringDoesNotChangeTheTagsTheOtherSideWasGiven() {
        Ec2Service service = Ec2ServiceFixtures.mockModeService();
        String requesterGateway = service.createTransitGateway(REQUESTER_REGION, "requester", null, List.of())
                .getTransitGatewayId();
        String accepterGateway = service.createTransitGateway(ACCEPTER_REGION, "accepter", null, List.of())
                .getTransitGatewayId();
        String attachmentId = service.createTransitGatewayPeeringAttachment(REQUESTER_REGION, requesterGateway,
                accepterGateway, Ec2ServiceFixtures.ACCOUNT, ACCEPTER_REGION, null,
                List.of(new Tag("Side", "requester"))).getTransitGatewayAttachmentId();
        service.createTags(ACCEPTER_REGION, List.of(attachmentId), List.of(new Tag("Side", "accepter")));

        TransitGatewayPeeringAttachment requesterView = describe(service, REQUESTER_REGION, attachmentId);
        TransitGatewayPeeringAttachment accepterView = describe(service, ACCEPTER_REGION, attachmentId);

        assertThat(tagValues(accepterView), contains("accepter"));
        assertThat("the requester's answer still holds the requester's tags after the accepter read",
                tagValues(requesterView), contains("requester"));
        assertEquals("pendingAcceptance", requesterView.getState());
    }

    private static TransitGatewayPeeringAttachment describe(Ec2Service service, String region, String attachmentId) {
        return service.describeTransitGatewayPeeringAttachments(region, List.of(attachmentId),
                Map.<String, List<String>>of()).getFirst();
    }

    private static List<String> tagValues(TransitGatewayPeeringAttachment attachment) {
        return attachment.getTags().stream().map(Tag::getValue).toList();
    }
}
