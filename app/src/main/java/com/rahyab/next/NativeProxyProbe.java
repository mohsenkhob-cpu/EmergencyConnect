package com.rahyab.next;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Probe real, trusted HTTPS through a local SOCKS5 engine (never over direct
 * internet). A listening TCP port or a WireGuard handshake alone is NOT proof
 * that users can reach the internet.
 */
final class NativeProxyProbe {
    private NativeProxyProbe() { }

    static boolean verify(int localPort) {
        if (Thread.currentThread().isInterrupted()) return false;
        return httpsOverSocks(localPort, "cp.cloudflare.com", "/generate_204", 204)
                || (!Thread.currentThread().isInterrupted()
                    && httpsOverSocks(localPort, "api.ipify.org", "/", 200));
    }

    private static boolean httpsOverSocks(int port, String host, String path, int expected) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2500);
            socket.setSoTimeout(5000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            output.write(new byte[] {5, 1, 0});
            output.flush();
            DataInputStream data = new DataInputStream(input);
            if (data.readUnsignedByte() != 5 || data.readUnsignedByte() != 0) return false;

            byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            if (name.length > 255) return false;
            byte[] request = new byte[7 + name.length];
            request[0] = 5; request[1] = 1; request[2] = 0; request[3] = 3;
            request[4] = (byte) name.length;
            System.arraycopy(name, 0, request, 5, name.length);
            request[5 + name.length] = 1; // HTTPS / 443
            request[6 + name.length] = (byte) 187;
            output.write(request);
            output.flush();
            if (data.readUnsignedByte() != 5 || data.readUnsignedByte() != 0) return false;
            data.readUnsignedByte(); // RSV
            int atyp = data.readUnsignedByte();
            if (atyp == 1) data.skipNBytes(4);
            else if (atyp == 4) data.skipNBytes(16);
            else if (atyp == 3) data.skipNBytes(data.readUnsignedByte());
            else return false;
            data.skipNBytes(2);

            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket tls = (SSLSocket) factory.createSocket(socket, host, 443, false)) {
                tls.setSoTimeout(5000);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake(); // trusted CA *and* hostname verified
                tls.getOutputStream().write((
                    "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Accept: */*\r\nConnection: close\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                tls.getOutputStream().flush();
                byte[] line = new byte[160];
                int n = 0;
                while (n < line.length) {
                    int b = tls.getInputStream().read();
                    if (b == -1 || b == '\n') break;
                    if (b != '\r') line[n++] = (byte) b;
                }
                String status = new String(line, 0, n, StandardCharsets.US_ASCII);
                return status.matches("HTTP/1\\.[01] " + expected + "(?: .*|$)");
            }
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }
}
