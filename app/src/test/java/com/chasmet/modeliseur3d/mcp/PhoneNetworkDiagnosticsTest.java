package com.chasmet.modeliseur3d.mcp;

import java.net.InetAddress;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneNetworkDiagnosticsTest {
    @Test public void distinguishesPrivateReservedCgnatAndRoutableAddresses() throws Exception {
        String[][] examples={{"127.0.0.1","loopback"},{"192.168.1.50","ipv4_private"},{"10.1.2.3","ipv4_private"},
                {"172.31.2.1","ipv4_private"},{"100.64.0.1","ipv4_cgnat"},{"100.127.255.254","ipv4_cgnat"},
                {"169.254.2.1","link_local"},{"192.0.2.10","ipv4_special"},{"198.51.100.1","ipv4_special"},
                {"203.0.113.1","ipv4_special"},{"198.19.1.1","ipv4_special"},{"8.8.8.8","ipv4_public"},
                {"::1","loopback"},{"fe80::1","link_local"},{"fd01::1","ipv6_ula"},{"fc00::1","ipv6_ula"},
                {"2001:db8::1","ipv6_special"},{"2001:4860:4860::8888","ipv6_global"},{"ff02::1","multicast"}};
        for(String[] example:examples)assertEquals(example[0],example[1],PhoneNetworkDiagnostics.classify(InetAddress.getByName(example[0])));
        assertFalse(PhoneNetworkDiagnostics.global(InetAddress.getByName("fd01::1")));
        assertFalse(PhoneNetworkDiagnostics.global(InetAddress.getByName("100.100.1.1")));
    }
}
