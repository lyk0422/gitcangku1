package com.example.starter.evidence;

import com.example.starter.error.ApiException;

import java.util.Base64;

/**
 * 分块字节的 Base64 工具：身份比较一律以解码字节为准，
 * 等价 Base64 写法（换行等空白、省略填充）规范化后不形成不同内容。
 */
final class ChunkEncoding {

    private ChunkEncoding() {
    }

    /**
     * 宽松解码标准 Base64：允许空白（含 MIME 换行）并补齐省略的填充；非法字符抛 400。
     */
    static byte[] decode(String dataBase64) {
        String compact = dataBase64.replaceAll("\\s", "");
        int remainder = compact.length() % 4;
        if (remainder != 0) {
            compact = compact + "=".repeat(4 - remainder);
        }
        try {
            return Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("dataBase64 不是合法的标准 Base64");
        }
    }

    /**
     * 解码后再用标准编码器输出唯一规范形式，用于幂等指纹，保证等价编码同参。
     */
    static String canonical(String dataBase64) {
        return Base64.getEncoder().encodeToString(decode(dataBase64));
    }
}
