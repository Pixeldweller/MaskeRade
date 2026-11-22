package com.pixeldweller.maskerade.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashes {

    private Hashes() {}

    public static String sha256Hex(String s) {
        return HexFormat.of().formatHex(digest("SHA-256", s.getBytes(StandardCharsets.UTF_8)));
    }

    /** Stable 64-bit value of a string, for deterministic choices from word lists. */
    public static long sha256Long(String s) {
        var d = digest("SHA-256", s.getBytes(StandardCharsets.UTF_8));
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (d[i] & 0xFF);
        return v;
    }

    /** Git's object id of a blob: sha1("blob " + length + "\0" + content). */
    public static String gitBlobId(byte[] content) {
        try {
            var md = MessageDigest.getInstance("SHA-1");
            md.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
            md.update(content);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] digest(String algorithm, byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
