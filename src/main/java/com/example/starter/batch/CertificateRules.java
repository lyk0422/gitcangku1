package com.example.starter.batch;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 合格证声明匹配的纯规则，不依赖数据库与时钟，便于单元测试边界语义。
 *
 * <p>有效区间按左闭右开解释：validFrom &lt;= at &lt; validTo，全部为 UTC；
 * 因此 at == validFrom 有效，at == validTo 已失效。
 */
public final class CertificateRules {

    private CertificateRules() {
    }

    /**
     * 区间是否非法：起点或终点为空、或终点不严格晚于起点。
     */
    public static boolean isInvalidInterval(Instant validFrom, Instant validTo) {
        return validFrom == null || validTo == null || !validTo.isAfter(validFrom);
    }

    /**
     * 指定 UTC 时点是否落在左闭右开区间 [validFrom, validTo) 内。
     */
    public static boolean isActiveAt(Instant validFrom, Instant validTo, Instant at) {
        return !at.isBefore(validFrom) && at.isBefore(validTo);
    }

    /**
     * 批号是否命中任一前缀（前缀为字符串开头匹配，区分大小写）。
     */
    public static boolean matchesAnyPrefix(String batchNo, Collection<String> prefixes) {
        return matchingPrefix(batchNo, prefixes) != null;
    }

    /**
     * 返回实际命中的前缀；命中多个时按前缀长度降序、再按字典序返回最确定的一个，
     * 保证同批批号在多声明场景下匹配结果稳定。无命中返回 null。
     */
    public static String matchingPrefix(String batchNo, Collection<String> prefixes) {
        return prefixes.stream()
                .filter(batchNo::startsWith)
                .max(java.util.Comparator.comparingInt(String::length)
                        .thenComparing(java.util.Comparator.naturalOrder()))
                .orElse(null);
    }

    /**
     * 请求内前缀是否重复。
     */
    public static boolean hasDuplicate(Collection<String> prefixes) {
        return new HashSet<>(prefixes).size() != prefixes.size();
    }

    /**
     * 与同物料、同优先级、ACTIVE 的既有声明之间是否存在前缀重叠。
     *
     * <p>两个前缀覆盖的批号集合相交即判重叠：前缀字符串相等，或一个前缀是另一个前缀的前缀
     * （如 "LOT" 与 "LOT-A" 都会匹配批号 "LOT-A-1"，同优先级下无法裁决）。
     * 不相干的前缀（如 "LOT-1" 与 "LOT-2"）不重叠；包含关系须以不同优先级发布由高优先级裁决。
     *
     * @param newPrefixes       本次发布的前缀集合
     * @param existingSameLevel 同优先级既有 ACTIVE 声明的前缀集合列表
     * @return 第一个发生重叠的（新前缀，既有前缀）；无重叠返回 null
     */
    public static String[] findSamePriorityOverlap(Set<String> newPrefixes,
                                                   List<Set<String>> existingSameLevel) {
        for (Set<String> existing : existingSameLevel) {
            for (String fresh : newPrefixes) {
                for (String old : existing) {
                    if (overlap(fresh, old)) {
                        return new String[]{fresh, old};
                    }
                }
            }
        }
        return null;
    }

    /**
     * 两个批号前缀的覆盖范围是否相交：相等或互为前缀。
     */
    public static boolean overlap(String a, String b) {
        return a.equals(b) || a.startsWith(b) || b.startsWith(a);
    }
}
