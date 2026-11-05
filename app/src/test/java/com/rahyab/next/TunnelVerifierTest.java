package com.rahyab.next;

import org.junit.Test;
import static org.junit.Assert.*;

public class TunnelVerifierTest {
    @Test public void acceptsPublicIpv4Literal() {
        assertTrue(TunnelVerifier.isPublicAddressLiteral("8.8.8.8"));
    }

    @Test public void rejectsPrivateAndLoopbackIpv4() {
        assertFalse(TunnelVerifier.isPublicAddressLiteral("192.168.1.10"));
        assertFalse(TunnelVerifier.isPublicAddressLiteral("127.0.0.1"));
        assertFalse(TunnelVerifier.isPublicAddressLiteral("10.0.0.1"));
    }

    @Test public void rejectsHostnamesAndMalformedAddresses() {
        assertFalse(TunnelVerifier.isPublicAddressLiteral("example.com"));
        assertFalse(TunnelVerifier.isPublicAddressLiteral("999.999.1.1"));
        assertFalse(TunnelVerifier.isPublicAddressLiteral(""));
    }

    @Test public void acceptsPublicIpv6Literal() {
        assertTrue(TunnelVerifier.isPublicAddressLiteral("2001:4860:4860::8888"));
    }
}