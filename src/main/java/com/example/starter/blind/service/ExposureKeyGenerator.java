package com.example.starter.blind.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 泄露登记凭据 exposureKey 生成器；凭据无含义、不可由操作者或参与者推导，
 * 仅在揭盲批准时颁发给申请人本人。全局唯一性由数据库唯一索引兜底。
 */
@Component
public class ExposureKeyGenerator {

    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int KEY_LENGTH = 24;
    private static final String PREFIX = "EK-";

    private final SecureRandom random = new SecureRandom();

    public String nextKey() {
        StringBuilder builder = new StringBuilder(PREFIX);
        for (int i = 0; i < KEY_LENGTH; i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
