package com.example.starter.blind.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 代次令牌随机编号生成器；编号不携带实验、角色或盲底信息，仅作不透明凭据。
 * 使用去除易混字符的 Crockford 风格字母表，长度固定 32 位；全局唯一性由主键兜底。
 */
@Component
public class AccessTokenGenerator {

    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int TOKEN_LENGTH = 32;

    private final SecureRandom random = new SecureRandom();

    public String nextToken() {
        StringBuilder builder = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
