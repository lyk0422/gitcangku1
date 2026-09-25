package com.example.starter.batch;

import com.example.starter.batch.dto.CompositionInput;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 过敏原集合规范化与校验工具：集合换序视为同参——统一去空白、去重并按字典序升序。
 */
public final class Allergens {

    private Allergens() {
    }

    /**
     * 规范化过敏原代码集合：元素去首尾空白、丢弃空白项、去重、字典序升序。
     * 入参为 null 时按空集合处理（空集合是合法成分，允许）。
     */
    public static List<String> normalize(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String code : codes) {
            if (code != null && !code.isBlank()) {
                sorted.add(code.trim());
            }
        }
        return List.copyOf(sorted);
    }

    /**
     * 校验成分输入：显式提供 composition 时隔离级别不允许为 null（空级别 422）；
     * input 为 null 表示调用方选择默认成分（空集合 + NONE），返回空列表，级别由调用方补默认值。
     * 过敏原代码必须全部在字典内，未知代码返回 422，错误信息携带具体代码以便区分原因。
     */
    public static List<String> validate(CompositionInput input, Set<String> catalog) {
        if (input == null) {
            return List.of();
        }
        if (input.segregationLevel() == null) {
            throw ApiException.unprocessable("segregationLevel 不能为空，取值 NONE/LOW/MEDIUM/HIGH");
        }
        List<String> normalized = normalize(input.allergenCodes());
        for (String code : normalized) {
            if (!catalog.contains(code)) {
                throw ApiException.unprocessable("未知过敏原代码: " + code);
            }
        }
        return normalized;
    }

    /**
     * 规范化列表转存储串：逗号连接，空集合为空串。
     */
    public static String toStored(List<String> normalizedCodes) {
        return String.join(",", normalizedCodes);
    }

    /**
     * 存储串转规范化列表：空串为空列表。
     */
    public static List<String> fromStored(String stored) {
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        return List.of(stored.split(","));
    }
}
