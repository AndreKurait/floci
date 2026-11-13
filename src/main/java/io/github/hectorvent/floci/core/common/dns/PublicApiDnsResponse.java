package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.netty.util.NetUtil;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Presents the configured public endpoint through its routed address without inventing DNS answers. */
final class PublicApiDnsResponse {
    private final int upstreamAddress;
    private final int publicAddress;

    PublicApiDnsResponse(String upstream) {
        if (!NetUtil.isValidIpV4Address(upstream)) {
            throw new IllegalArgumentException("The public endpoint upstream must be an IPv4 address");
        }
        upstreamAddress = ByteBuffer.wrap(NetUtil.createByteArrayFromIpAddressString(upstream)).getInt();
        publicAddress = ByteBuffer.wrap(NetUtil.createByteArrayFromIpAddressString(
                EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS)).getInt();
    }

    byte[] rewrite(byte[] response) {
        if (response == null || response.length < 12) {
            return response;
        }
        ByteBuffer packet = ByteBuffer.wrap(response);
        int flags = Short.toUnsignedInt(packet.getShort(2));
        // Negative, truncated, non-query and authenticated answers retain their exact wire data.
        if ((flags & 0xfa6f) != 0x8000 || packet.getShort(4) != 1) {
            return response;
        }
        int answers = Short.toUnsignedInt(packet.getShort(6));
        int records = answers + Short.toUnsignedInt(packet.getShort(8))
                + Short.toUnsignedInt(packet.getShort(10));
        packet.position(12);
        List<Integer> offsets = new ArrayList<>();
        try {
            skipName(packet);
            packet.getShort();
            if (packet.getShort() != 1) {
                return response;
            }
            for (int index = 0; index < records; index++) {
                skipName(packet);
                int type = Short.toUnsignedInt(packet.getShort());
                int recordClass = Short.toUnsignedInt(packet.getShort());
                packet.getInt();
                int length = Short.toUnsignedInt(packet.getShort());
                if (length > packet.remaining()) {
                    return response;
                }
                // Do not invalidate SIG, RRSIG or TSIG records by changing their signed message.
                if (type == 24 || type == 46 || type == 250) {
                    return response;
                }
                if (index < answers && type == 1 && recordClass == 1 && length == 4
                        && packet.getInt(packet.position()) == upstreamAddress) {
                    offsets.add(packet.position());
                }
                packet.position(packet.position() + length);
            }
            if (packet.hasRemaining() || offsets.isEmpty()) {
                return response;
            }
        } catch (BufferUnderflowException | IllegalArgumentException ignored) {
            // The upstream owns malformed answers; never return a partially changed packet.
            return response;
        }
        byte[] routed = response.clone();
        ByteBuffer updated = ByteBuffer.wrap(routed);
        for (int offset : offsets) {
            updated.putInt(offset, publicAddress);
        }
        return routed;
    }

    private static void skipName(ByteBuffer packet) {
        int labels = 0;
        while (labels++ < 128) {
            int length = Byte.toUnsignedInt(packet.get());
            if (length == 0) {
                return;
            }
            if ((length & 0xc0) == 0xc0) {
                int target = ((length & 0x3f) << 8) | Byte.toUnsignedInt(packet.get());
                if (target < 12 || target >= packet.position() - 2) {
                    throw new IllegalArgumentException("Invalid DNS compression pointer");
                }
                return;
            }
            if (length > 63 || length > packet.remaining()) {
                throw new IllegalArgumentException("Invalid DNS label");
            }
            packet.position(packet.position() + length);
        }
        throw new IllegalArgumentException("DNS name exceeds the label limit");
    }
}
