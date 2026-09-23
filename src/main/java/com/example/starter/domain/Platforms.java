package com.example.starter.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 目标平台规则：平台元素固定为 {@code os/arch}，{@code ANY} 表示与任意平台兼容。
 */
public final class Platforms {

    /** 通配平台：制品声明仅含 ANY 时支持全部目标平台。 */
    public static final String ANY = "ANY";

    /** 单个制品版本最多声明的平台数量。 */
    public static final int MAX_PLATFORMS = 10;

    /** os/arch 各段允许的字符。 */
    private static final Pattern OS_ARCH = Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    private Platforms() {
    }

    /** 判断是否为合法的 {@code os/arch}（不接受 ANY）。 */
    public static boolean isOsArch(String value) {
        return value != null && OS_ARCH.matcher(value).matches();
    }

    /**
     * 校验登记请求中的平台集合，返回去重后的不可变副本。
     *
     * <p>规则：非空、最多 10 项；每项为合法 os/arch；若含 ANY 则只能单独出现。
     *
     * @throws IllegalArgumentException 规则不满足时
     */
    public static List<String> normalize(List<String> raw) {
        List<String> source = raw == null ? List.of(ANY) : raw;
        if (source.isEmpty()) {
            throw new IllegalArgumentException("平台集合不能为空；不区分平台请使用 ANY");
        }
        if (source.size() > MAX_PLATFORMS) {
            throw new IllegalArgumentException("平台数量不能超过 " + MAX_PLATFORMS + " 项");
        }
        Set<String> seen = new LinkedHashSet<>();
        boolean any = false;
        for (String item : source) {
            if (item == null || item.isBlank()) {
                throw new IllegalArgumentException("平台元素不能为空");
            }
            String p = item.trim();
            if (ANY.equals(p)) {
                any = true;
            } else if (!isOsArch(p)) {
                throw new IllegalArgumentException("平台元素格式必须为 os/arch: " + p);
            }
            if (!seen.add(p)) {
                throw new IllegalArgumentException("平台元素重复: " + p);
            }
        }
        if (any && seen.size() > 1) {
            throw new IllegalArgumentException("ANY 不能与具体平台同时声明");
        }
        return List.copyOf(new ArrayList<>(seen));
    }
}
