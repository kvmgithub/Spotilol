package com.project.lol.security;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Strict HTTP/1 framing; ambiguous messages must never reach a pooled connection. */
public final class HttpFraming {
    private HttpFraming() {}
    public static String readLine(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = input.read();
            if (b == -1) {
                if (line.size() == 0) return null;
                throw new EOFException("Truncated HTTP line");
            }
            if (b == '\r') {
                if (input.read() != '\n') throw new IOException("Invalid CRLF");
                return line.toString(StandardCharsets.ISO_8859_1.name());
            }
            if (b == '\n' || b == 0 || line.size() >= maxBytes) throw new IOException("Invalid or oversized HTTP line");
            line.write(b);
        }
    }
    public static long contentLength(List<String> names, List<String> values) throws IOException {
        if (names.size() != values.size()) throw new IOException("Invalid headers");
        Long length = null;
        boolean transferEncoding = false;
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase("Content-Length")) {
                if (length != null || !values.get(i).matches("[0-9]+")) throw new IOException("Invalid or duplicate Content-Length");
                try { length = Long.parseLong(values.get(i)); }
                catch (NumberFormatException e) { throw new IOException("Content-Length overflow", e); }
            }
            if (names.get(i).equalsIgnoreCase("Transfer-Encoding")) {
                if (transferEncoding || !values.get(i).equalsIgnoreCase("chunked")) throw new IOException("Unsupported transfer encoding");
                transferEncoding = true;
            }
        }
        if (length != null && transferEncoding) throw new IOException("Ambiguous HTTP framing");
        return length == null ? -1 : length;
    }
}
