package io.github.hectorvent.floci.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * With {@code floci.services.ec2.guest-tls-endpoint}, an EC2 guest that brings its own resolver is given
 * Floci's https endpoint by address, so the self-signed server certificate must name this host's interface
 * addresses; without it the certificate keeps its configured names only.
 */
class TlsConfigSourceGuestAddressTest {

    private static final int IP_ADDRESS = 7;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("floci.tls.enabled");
        System.clearProperty("floci.tls.self-signed");
        System.clearProperty("floci.storage.persistent-path");
        System.clearProperty("floci.services.ec2.guest-tls-endpoint");
    }

    @Test
    void guestTlsEndpointPutsThisHostsInterfaceAddressesOnTheServerCertificate() throws Exception {
        List<String> addresses = interfaceIpv4Addresses();
        assumeFalse(addresses.isEmpty(), "needs a non-loopback IPv4 interface address");
        System.setProperty("floci.services.ec2.guest-tls-endpoint", "true");

        new TlsConfigSource();

        List<String> ipSans = ipSans(tempDir.resolve("tls/floci-server.crt"));
        assertTrue(ipSans.containsAll(addresses), "certificate IP SANs " + ipSans + " lack " + addresses);
        assertTrue(ipSans.contains("127.0.0.1"), "the default IP SANs stay");
    }

    @Test
    void withoutGuestTlsEndpointTheServerCertificateNamesNoInterfaceAddress() throws Exception {
        List<String> addresses = interfaceIpv4Addresses();
        assumeFalse(addresses.isEmpty(), "needs a non-loopback IPv4 interface address");

        new TlsConfigSource();

        List<String> ipSans = ipSans(tempDir.resolve("tls/floci-server.crt"));
        assertTrue(Collections.disjoint(ipSans, addresses), "certificate IP SANs " + ipSans);
    }

    private static List<String> interfaceIpv4Addresses() throws Exception {
        List<String> addresses = new ArrayList<>();
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    addresses.add(address.getHostAddress());
                }
            }
        }
        return addresses;
    }

    private static List<String> ipSans(Path certFile) throws Exception {
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(Files.readString(certFile).getBytes(StandardCharsets.UTF_8)));
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        List<String> ips = new ArrayList<>();
        if (sans != null) {
            for (List<?> san : sans) {
                if (san.size() >= 2 && Integer.valueOf(IP_ADDRESS).equals(san.get(0))) {
                    ips.add(san.get(1).toString());
                }
            }
        }
        return ips;
    }
}
