package com.example.starter.incident;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 简化网格工具：网格码为 1~16 位字母数字（可含中划线），
 * 规范化为去空白、统一大写、去重并按字典序排序。
 */
public final class Grids {

    private static final Pattern GRID_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,15}");

    private Grids() {
    }

    /**
     * 规范化网格集合：逐项校验并大写化，去重后按字典序排序返回不可变列表。
     *
     * @throws ApiException 400：集合为空或含非法网格码
     */
    public static List<String> normalize(List<String> grids, String field) {
        if (grids == null || grids.isEmpty()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : grids) {
            if (raw == null || raw.isBlank()) {
                throw ApiException.badRequest(field + " 含空网格码");
            }
            String code = raw.strip().toUpperCase(java.util.Locale.ROOT);
            if (!GRID_CODE.matcher(code).matches()) {
                throw ApiException.badRequest(field + " 含非法网格码: " + raw);
            }
            normalized.add(code);
        }
        return normalized.stream().sorted().toList();
    }

    /**
     * 规范化单个网格码（如最终位置）：去空白并大写化，校验格式。
     *
     * @throws ApiException 400：为空或格式非法
     */
    public static String normalizeOne(String grid, String field) {
        if (grid == null || grid.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        String code = grid.strip().toUpperCase(java.util.Locale.ROOT);
        if (!GRID_CODE.matcher(code).matches()) {
            throw ApiException.badRequest(field + " 非法网格码: " + grid);
        }
        return code;
    }

    /**
     * 规范化网格集合的规范串（逗号分隔），用于持久化与指纹计算。
     */
    public static String canonical(List<String> grids) {
        return String.join(",", grids);
    }

    /**
     * 解析持久化的规范串为网格列表；空串/空值返回空列表。
     */
    public static List<String> parse(String canonical) {
        if (canonical == null || canonical.isBlank()) {
            return List.of();
        }
        return List.of(canonical.split(","));
    }

    /**
     * 判断两个规范化网格集合是否相交。
     */
    public static boolean intersects(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        Set<String> set = Set.copyOf(a);
        return b.stream().anyMatch(set::contains);
    }
}
