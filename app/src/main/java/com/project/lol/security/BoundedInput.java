package com.project.lol.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class BoundedInput {
    private BoundedInput() {}
    public static String readUtf8(InputStream input, int maxBytes) throws IOException {
        if (maxBytes < 0) throw new IllegalArgumentException("negative byte limit");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int count;
        while ((count = input.read(buf)) != -1) {
            if (count > maxBytes - out.size()) throw new IOException("Response exceeds byte limit");
            out.write(buf, 0, count);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
