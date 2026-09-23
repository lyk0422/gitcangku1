package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等请求参数摘要工具：以 US 单元分隔符拼接结构化参数后取 SHA-256。
 * 集合参数必须在调用前排序，保证集合换序同参。
 */
public final class RequestHashes {

    private static final String SEP = "\u001F";

    private RequestHashes() {
    }

    public static String sha256(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(String.join(SEP, parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
