package com.example.starter.blind.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 随机无含义盲码生成器；盲码与区组号、席位序号之间不存在可推导关系。
 * 使用去除易混字符的 Crockford 风格字母表，长度固定 12 位；全局唯一性由数据库唯一索引兜底。
 */
@Component
public class BlindCodeGenerator {

    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    public String nextCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
