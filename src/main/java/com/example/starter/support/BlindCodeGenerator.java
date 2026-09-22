package com.example.starter.support;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 随机无含义盲码生成器。盲码为均匀随机的十六进制串，
 * 不编码实验、区组、席位序号或处理代码，也不按时间或顺序生成。
 */
@Component
public class BlindCodeGenerator {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int RANDOM_BYTES = 12;

    private final SecureRandom random = new SecureRandom();

    /** 生成一个 24 位随机十六进制盲码；全局唯一性由调用方结合数据库重试保证。 */
    public String next() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(chars);
    }
}
