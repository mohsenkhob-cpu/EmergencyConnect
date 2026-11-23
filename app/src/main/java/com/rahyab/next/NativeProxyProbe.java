package com.rahyab.next;

import android.os.SystemClock;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.net.SocketTimeoutException;
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

    static boolean youtubeReachable(int port) {
        String host = "www.youtube.com";
        try (Socket socket = new Socket(); SSLSocket tls = openSocksTls(socket, port, host)) {
            tls.setSoTimeout(3000);
            sendRequest(tls, host, "/");
            String status = readLine(new BufferedInputStream(tls.getInputStream()), 160);
            return status.matches("HTTP/1\\.[01] (?:200|30[12378]|429)(?: .*|$)");
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    private static boolean httpsOverSocks(int port, String host, String path, int expected) {
        try (Socket socket = new Socket(); SSLSocket tls = openSocksTls(socket, port, host)) {
            sendRequest(tls, host, path);
            return hasStatus(new BufferedInputStream(tls.getInputStream()), expected);
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    static long sampleDownloadKbps(int port) {
        final int limit = 128 * 1024;
        final String host = "speed.cloudflare.com";
        long started = 0L;
        int bytes = 0;
        try (Socket socket = new Socket(); SSLSocket tls = openSocksTls(socket, port, host)) {
            tls.setSoTimeout(1800);
            sendRequest(tls, host, "/__down?bytes=" + limit);
            InputStream input = new BufferedInputStream(tls.getInputStream());
            if (!hasStatus(input, 200)) return -1;
            for (int header = 0; ; header++) {
                if (header == 80) return -1;
                String line = readLine(input, 1024);
                if (line.isEmpty()) break;
            }
            started = SystemClock.elapsedRealtime();
            byte[] buffer = new byte[8192];
            while (bytes < limit && SystemClock.elapsedRealtime() - started < 4000L
                    && !Thread.currentThread().isInterrupted()) {
                int count = input.read(buffer, 0, Math.min(buffer.length, limit - bytes));
                if (count < 0) break;
                bytes += count;
            }
        } catch (SocketTimeoutException ignored) {
            // A partial response body can still indicate slow transfer.
        } catch (IOException | RuntimeException error) {
            return -1;
        }
        return bytes > 0 && !Thread.currentThread().isInterrupted()
                ? Math.max(1L, bytes * 8L / Math.max(1L, SystemClock.elapsedRealtime() - started))
                : -1;
    }

    private static SSLSocket openSocksTls(Socket socket, int port, String host) throws IOException {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 2500);
        socket.setSoTimeout(5000);
        OutputStream output = socket.getOutputStream();
        output.write(new byte[] {5, 1, 0});
        output.flush();
        DataInputStream data = new DataInputStream(socket.getInputStream());
        if (data.readUnsignedByte() != 5 || data.readUnsignedByte() != 0)
            throw new IOException("SOCKS authentication negotiation failed");

        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        byte[] request = new byte[7 + name.length];
        request[0] = 5; request[1] = 1; request[3] = 3;
        request[4] = (byte) name.length;
        System.arraycopy(name, 0, request, 5, name.length);
        request[5 + name.length] = 1;
        request[6 + name.length] = (byte) 187;
        output.write(request);
        output.flush();
        if (data.readUnsignedByte() != 5 || data.readUnsignedByte() != 0)
            throw new IOException("SOCKS connection failed");
        data.readUnsignedByte();
        int addressSize = switch (data.readUnsignedByte()) {
            case 1 -> 4;
            case 4 -> 16;
            case 3 -> data.readUnsignedByte();
            default -> throw new IOException("Unsupported SOCKS address type");
        };
        data.readFully(new byte[addressSize + 2]);

        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket tls = (SSLSocket) factory.createSocket(socket, host, 443, true);
        try {
            tls.setSoTimeout(5000);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            return tls;
        } catch (IOException | RuntimeException error) {
            try { tls.close(); } catch (IOException ignored) { }
            throw error;
        }
    }

    private static void sendRequest(SSLSocket tls, String host, String path) throws IOException {
        OutputStream output = tls.getOutputStream();
        output.write(("GET " + path + " HTTP/1.1\r\nHost: " + host
                + "\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static boolean hasStatus(InputStream input, int expected) throws IOException {
        return readLine(input, 160).matches("HTTP/1\\.[01] " + expected + "(?: .*|$)");
    }

    private static String readLine(InputStream input, int limit) throws IOException {
        byte[] line = new byte[limit];
        int n = 0;
        while (n < limit) {
            int next = input.read();
            if (next == '\n') return new String(line, 0, n, StandardCharsets.US_ASCII);
            if (next < 0) throw new IOException("Incomplete HTTP response");
            if (next != '\r') line[n++] = (byte) next;
        }
        throw new IOException("HTTP response line exceeds limit");
    }
}
