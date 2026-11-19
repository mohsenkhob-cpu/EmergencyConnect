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

    @Test public void homepageVerifierRejectsFalsePositives() {
        assertFalse(TunnelVerifier.isUsableHtmlResponse(204, "text/html", 1024));
        assertFalse(TunnelVerifier.isUsableHtmlResponse(200, "text/plain", 1024));
        assertFalse(TunnelVerifier.isUsableHtmlResponse(200, "text/html", 120));
        assertFalse(TunnelVerifier.isUsableHtmlResponse(403, "text/html", 2048));
        assertTrue(TunnelVerifier.isUsableHtmlResponse(200, "text/html; charset=utf-8", 1024));
    }

    @Test public void redirectsMustRemainOnTrustedHttpsMediaHost() throws Exception {
        java.net.URL root = new java.net.URL("https://www.youtube.com/");
        assertTrue(TunnelVerifier.isAllowedMediaRedirect(root, new java.net.URL("https://m.youtube.com/")));
        assertTrue(TunnelVerifier.isAllowedMediaRedirect(root, new java.net.URL("https://consent.youtube.com/")));
        assertFalse(TunnelVerifier.isAllowedMediaRedirect(root, new java.net.URL("http://m.youtube.com/")));
        assertFalse(TunnelVerifier.isAllowedMediaRedirect(root, new java.net.URL("https://youtube.com.evil.test/")));
        assertFalse(TunnelVerifier.isAllowedMediaRedirect(root, new java.net.URL("https://example.com/")));
        assertFalse(TunnelVerifier.isAllowedMediaRedirect(root, new java.net.URL("https://www.youtube.com:444/")));
    }

    @Test public void acceptsPublicIpv6Literal() {
        assertTrue(TunnelVerifier.isPublicAddressLiteral("2001:4860:4860::8888"));
    }
    @Test public void multipleHttpsIpEchoFormatsAreSupported() {
        assertEquals("8.8.8.8", TunnelVerifier.decodePublicIp("{\"ip\":\"8.8.8.8\"}", true));
        assertEquals("2001:4860:4860::8888",
                TunnelVerifier.decodePublicIp("2001:4860:4860::8888\n", false));
    }

    @Test public void ipEchoMustNotAcceptPrivateHostnamesOrHtml() {
        assertNull(TunnelVerifier.decodePublicIp("{\"ip\":\"192.168.1.1\"}", true));
        assertNull(TunnelVerifier.decodePublicIp("{\"ip\":\"google.com\"}", true));
        assertNull(TunnelVerifier.decodePublicIp("200 OK", false));
        assertNull(TunnelVerifier.decodePublicIp("<html>8.8.8.8</html>", false));
        assertNull(TunnelVerifier.decodePublicIp("{bad_json}", true));
    }
}