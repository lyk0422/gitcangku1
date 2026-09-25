package com.example.starter.incident;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 简化网格集合工具：网格标识去空白、去重并按字典序规范化；
 * 另提供疏散区域指纹（含事件版本、规范化网格、窗口、等级、操作者）的 SHA-256 计算。
 */
public final class GridSets {

    private static final String SEP = "\u001F";

    private GridSets() {
    }

    /**
     * 规范化网格集合：元素去首尾空白、丢弃空白项、去重、字典序排序。
     * 输入为空或全部为空白时返回空集合（由调用方给出 400）。
     */
    public static List<String> normalize(List<String> grids) {
        if (grids == null) {
            return List.of();
        }
        Set<String> sorted = new TreeSet<>();
        for (String grid : grids) {
            if (grid == null) {
                continue;
            }
            String value = grid.strip();
            if (!value.isEmpty()) {
                sorted.add(value);
            }
        }
        return List.copyOf(sorted);
    }

    /**
     * 两个规范化网格集合是否存在交集（用于区域窗口网格重叠与任务命中判定）。
     */
    public static boolean intersects(List<String> a, List<String> b) {
        Set<String> other = Set.copyOf(b);
        for (String grid : a) {
            if (other.contains(grid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算区域指纹：事件版本（事件 updated_at 的微秒级 UTC）+ 规范化网格 +
     * 左闭右开窗口 + 风险等级 + 操作者，各段以单元分隔符拼接后取 SHA-256 十六进制。
     */
    public static String fingerprint(String eventVersion, List<String> normalizedGrids,
                                     String effectiveFrom, String effectiveTo,
                                     String riskLevel, String operator) {
        String payload = String.join(SEP,
                eventVersion,
                String.join(",", normalizedGrids),
                effectiveFrom,
                effectiveTo,
                riskLevel,
                operator);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
