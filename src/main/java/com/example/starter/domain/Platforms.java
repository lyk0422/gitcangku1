package com.example.starter.domain;

import java.util.List;
import java.util.TreeSet;

/**
 * 目标平台相关常量与校验。
 *
 * <p>平台元素格式固定为 {@code os/arch}（os、arch 均为非空白且不含 '/' 与空格的片段），
 * 每个制品版本最多声明 10 项；也允许只含 {@link #ANY} 表示全平台。
 * {@code ANY} 与具体平台不能混填。本类不依赖 Web 层，非法输入抛
 * {@link IllegalArgumentException}，由服务层转换为 400。
 */
public final class Platforms {

    /** 全平台通配：制品支持任意目标平台。 */
    public static final String ANY = "ANY";

    /** 单个制品版本允许声明的平台数量上限。 */
    public static final int MAX_PLATFORMS = 10;

    private Platforms() {
    }

    /**
     * 校验登记请求中的平台集合，返回不可变副本；null 或空按兼容规则视为 {@code [ANY]}。
     *
     * @throws IllegalArgumentException 格式非法、数量超限或 ANY 与具体平台混填
     */
    public static List<String> normalize(List<String> platforms) {
        if (platforms == null || platforms.isEmpty()) {
            return List.of(ANY);
        }
        if (platforms.size() > MAX_PLATFORMS) {
            throw new IllegalArgumentException(
                    "每个制品版本最多声明 " + MAX_PLATFORMS + " 个平台");
        }
        TreeSet<String> normalized = new TreeSet<>();
        boolean hasAny = false;
        for (String raw : platforms) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("平台不能为空");
            }
            String p = raw.trim();
            if (ANY.equals(p)) {
                hasAny = true;
                normalized.add(p);
                continue;
            }
            validatePlatformLiteral(p);
            normalized.add(p);
        }
        if (hasAny && normalized.size() > 1) {
            throw new IllegalArgumentException("ANY 不能与具体平台混合声明");
        }
        return List.copyOf(normalized);
    }

    /**
     * 校验锁定请求的目标平台：必填，格式为 {@code os/arch}，不允许 ANY。
     *
     * @throws IllegalArgumentException 缺失、为 ANY 或格式非法
     */
    public static String validateTarget(String targetPlatform) {
        if (targetPlatform == null || targetPlatform.isBlank()) {
            throw new IllegalArgumentException("targetPlatform 不能为空");
        }
        String p = targetPlatform.trim();
        if (ANY.equals(p)) {
            throw new IllegalArgumentException(
                    "targetPlatform 必须为具体平台 os/arch，不能为 ANY");
        }
        validatePlatformLiteral(p);
        return p;
    }

    private static void validatePlatformLiteral(String p) {
        String[] parts = p.split("/", -1);
        if (parts.length != 2 || !isFragment(parts[0]) || !isFragment(parts[1])) {
            throw new IllegalArgumentException("平台格式必须为 os/arch: " + p);
        }
    }

    private static boolean isFragment(String part) {
        if (part.isEmpty()) {
            return false;
        }
        for (int i = 0; i < part.length(); i++) {
            if (Character.isWhitespace(part.charAt(i)) || part.charAt(i) == '/') {
                return false;
            }
        }
        return true;
    }
}
