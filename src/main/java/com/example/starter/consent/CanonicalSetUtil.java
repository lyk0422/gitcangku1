package com.example.starter.consent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 对象集合规范化与摘要工具。
 *
 * <p>规范化形式为排序后的 JSON 字符串数组：每个元素先经 JSON 字符串转义，
 * 再按字典序排列。因此集合不能、也不会以未转义分隔符直接拼接——包含逗号、引号、
 * 反斜杠的键不会产生 {@code ["a","b"]} 与 {@code ["a,b"]} 互相碰撞的歧义。
 */
public final class CanonicalSetUtil {

    private CanonicalSetUtil() {
    }

    /**
     * 规范化结果。
     *
     * @param canonicalJson 规范化 JSON 数组原文（如 {@code ["a","b"]}），空集合为 {@code []}
     * @param orderedKeys   去重并按字典序排列后的键列表
     * @param digest        规范化 JSON（UTF-8）的 SHA-256 十六进制摘要
     */
    public record CanonicalSet(String canonicalJson, List<String> orderedKeys, String digest) {
    }

    /**
     * 对对象键集合做规范化与摘要。
     *
     * @param keys         原始对象键
     * @param objectMapper JSON 序列化器
     * @return 规范化集合
     * @throws IllegalArgumentException 当集合中存在重复键时
     */
    public static CanonicalSet canonicalize(List<String> keys, ObjectMapper objectMapper) {
        Set<String> sorted = new TreeSet<>(keys);
        if (sorted.size() != keys.size()) {
            throw new IllegalArgumentException("对象键重复，无法规范化为集合");
        }
        List<String> ordered = List.copyOf(sorted);
        String json = writeJsonArray(ordered, objectMapper);
        return new CanonicalSet(json, ordered, sha256Hex(json.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 序列化为 JSON 数组；元素中的逗号、引号、反斜杠等字符均按 JSON 规则转义。
     */
    public static String writeJsonArray(List<String> orderedKeys, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(orderedKeys);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规范化集合序列化失败", e);
        }
    }

    /**
     * 计算字节的 SHA-256 十六进制摘要（小写，64 字符）。
     */
    public static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
