package com.example.starter.batch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 过敏原集合规范化工具。
 *
 * <p>规范化规则：逐项去除首尾空白、丢弃空项、去重、按 Unicode 代码点数值稳定排序
 * （代码点顺序，而非拼接字符串的字典序）。任何集合相等判断都基于规范化后的列表逐个比较，
 * 禁止用未转义字符串拼接（如 String.join）比较集合，避免 "a,b"/"a" + "b" 之类碰撞
 * 以及重复项、顺序差异造成的误判。
 */
public final class Allergens {

    private Allergens() {
    }

    /**
     * 将请求中的过敏原输入规范化为不可变列表：trim、去空、去重、按代码点升序稳定排序。
     *
     * @param raw 原始输入，null 视为空集合
     * @return 规范化后的不可变过敏原列表；无过敏原则返回空列表
     */
    public static List<String> normalize(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        // TreeSet 按代码点数值（UTF-16 码元数值对 BMP 与代码点顺序一致；增补平面以代理对参与比较，
        // 仍满足"稳定且确定"的全序）排序并去重。
        Set<String> sorted = new TreeSet<>(Allergens::compareByCodePoint);
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                sorted.add(trimmed);
            }
        }
        return List.copyOf(sorted);
    }

    /**
     * 逐元素比较两个已规范化集合是否完全相同；不使用字符串拼接。
     */
    public static boolean same(List<String> a, List<String> b) {
        return a.equals(b);
    }

    /**
     * 规范化指纹片段：长度 + 逐项值，以 NUL 分隔并加长度前缀，杜绝拼接碰撞；
     * 仅用于幂等指纹，集合相等判断仍使用 {@link #same}。
     */
    public static List<String> fingerprintParts(List<String> normalized) {
        List<String> parts = new ArrayList<>(normalized.size() + 1);
        parts.add("allergen-count=" + normalized.size());
        for (String allergen : normalized) {
            parts.add("allergen=" + allergen);
        }
        return parts;
    }

    /**
     * 代码点数值顺序的字符串比较器，供其它需要同样稳定排序的集合（如必做检验项并集）复用。
     */
    public static Comparator<String> codePointComparator() {
        return Allergens::compareByCodePoint;
    }

    /**
     * 按 Unicode 代码点数值比较两个字符串：先逐代码点比较，前缀相同时短者在前。
     */
    private static int compareByCodePoint(String a, String b) {
        int ia = 0;
        int ib = 0;
        while (ia < a.length() && ib < b.length()) {
            int ca = a.codePointAt(ia);
            int cb = b.codePointAt(ib);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            ia += Character.charCount(ca);
            ib += Character.charCount(cb);
        }
        return Integer.compare(a.length() - ia, b.length() - ib);
    }
}
