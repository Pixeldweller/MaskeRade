package com.pixeldweller.maskerade.util;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Byte-exact text handling: BOM, encoding and line endings survive decode → encode unchanged.
 * Line endings are never touched (CRLF stays CRLF), the BOM is kept aside, and files that are not valid UTF-8
 * are read as ISO-8859-1, which maps every byte to exactly one char and back.
 */
public final class TextCodec {

    private TextCodec() {}

    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] BOM_UTF16LE = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] BOM_UTF16BE = {(byte) 0xFE, (byte) 0xFF};

    /** Extensions that are binary even if the first bytes happen to contain no NUL. */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "tif", "tiff", "psd", "svgz",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf",
            "jar", "war", "ear", "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "class", "so", "dll", "exe", "dylib",
            "mp3", "mp4", "wav", "ogg", "avi", "mov", "ttf", "otf", "woff", "woff2", "eot",
            "keystore", "jks", "p12", "pfx", "der", "db", "sqlite", "bin", "dat");

    public record Decoded(String text, Charset charset, byte[] bom) {
        public byte[] encode(String newText) throws CharacterCodingException {
            return TextCodec.encode(newText, charset, bom);
        }
    }

    /** Binary = known binary extension, or a NUL byte in the first 8 KB (unless it starts with a UTF-16 BOM). */
    public static boolean isBinary(String fileName, byte[] bytes) {
        var dot = fileName.lastIndexOf('.');
        if (dot >= 0 && BINARY_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT))) return true;
        if (startsWith(bytes, BOM_UTF16LE) || startsWith(bytes, BOM_UTF16BE)) return false;
        int n = Math.min(bytes.length, 8192);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    public static Decoded decode(byte[] bytes) {
        if (startsWith(bytes, BOM_UTF8)) {
            var text = strict(bytes, 3, StandardCharsets.UTF_8);
            if (text != null) return new Decoded(text, StandardCharsets.UTF_8, BOM_UTF8);
        }
        if (startsWith(bytes, BOM_UTF16LE)) {
            var text = strict(bytes, 2, StandardCharsets.UTF_16LE);
            if (text != null) return new Decoded(text, StandardCharsets.UTF_16LE, BOM_UTF16LE);
        }
        if (startsWith(bytes, BOM_UTF16BE)) {
            var text = strict(bytes, 2, StandardCharsets.UTF_16BE);
            if (text != null) return new Decoded(text, StandardCharsets.UTF_16BE, BOM_UTF16BE);
        }
        var utf8 = strict(bytes, 0, StandardCharsets.UTF_8);
        if (utf8 != null) return new Decoded(utf8, StandardCharsets.UTF_8, new byte[0]);
        return new Decoded(new String(bytes, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1, new byte[0]);
    }

    /** Encodes strictly: a char the charset cannot represent is an error, never a silent '?'. */
    public static byte[] encode(String text, Charset charset, byte[] bom) throws CharacterCodingException {
        var encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer buffer = encoder.encode(CharBuffer.wrap(text));
        var out = new byte[bom.length + buffer.remaining()];
        System.arraycopy(bom, 0, out, 0, bom.length);
        buffer.get(out, bom.length, buffer.remaining());
        return out;
    }

    private static String strict(byte[] bytes, int offset, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    /** 1-based line of a char index. */
    public static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** The match plus some context on its line, for reports. */
    public static String snippet(String text, int start, int end) {
        int from = start, to = end;
        while (from > 0 && start - from < 30 && text.charAt(from - 1) != '\n' && text.charAt(from - 1) != '\r') from--;
        while (to < text.length() && to - end < 30 && text.charAt(to) != '\n' && text.charAt(to) != '\r') to++;
        return text.substring(from, to).strip();
    }
}
