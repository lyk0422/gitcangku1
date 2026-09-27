package com.example.starter.firmware.domain;

import com.example.starter.firmware.error.ApiException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 标签目标表达式（不可变）。三类条件均按规范化标签匹配：
 * <ul>
 *   <li>mustInclude：设备必须包含全部标签；</li>
 *   <li>mustExclude：设备不得包含其中任一标签；</li>
 *   <li>includeAny：设备至少包含其中一项标签；为空表示不增加“至少一项”约束。</li>
 * </ul>
 * 表达式规范化后固化在发布单快照中：标签 trim、去重并按字典序排序。
 * 非法表达式（空条件、组内重复标签、同标签同时要求包含与排除）返回 422。
 *
 * @param mustInclude 必须全部包含的标签（已规范化排序）
 * @param mustExclude 不得包含任一的标签（已规范化排序）
 * @param includeAny  至少包含一项的标签（已规范化排序）；空列表表示不约束
 */
public record LabelExpression(List<String> mustInclude, List<String> mustExclude, List<String> includeAny) {

    public LabelExpression {
        mustInclude = List.copyOf(mustInclude);
        mustExclude = List.copyOf(mustExclude);
        includeAny = List.copyOf(includeAny);
    }

    /**
     * 规范化并校验原始入参。标签大小写敏感；trim 后为空串视为非法。
     */
    public static LabelExpression of(Collection<String> rawInclude, Collection<String> rawExclude,
                                     Collection<String> rawAny) {
        List<String> include = normalizeGroup(rawInclude, "mustInclude");
        List<String> exclude = normalizeGroup(rawExclude, "mustExclude");
        List<String> any = normalizeGroup(rawAny == null ? List.of() : rawAny, "includeAny");
        // 空条件：三类条件均为空时表达式不选择任何设备，按题意返回 422
        if (include.isEmpty() && exclude.isEmpty() && any.isEmpty()) {
            throw ApiException.unprocessable("EMPTY_LABEL_CONDITION", "标签表达式为空：必须包含、不得包含、至少包含一项三类条件至少提供一类");
        }
        Set<String> conflict = new TreeSet<>(include);
        conflict.retainAll(exclude);
        if (!conflict.isEmpty()) {
            throw ApiException.unprocessable("LABEL_INCLUDE_EXCLUDE_CONFLICT",
                    "同一标签不能同时要求包含与排除: " + String.join(",", conflict));
        }
        return new LabelExpression(include, exclude, any);
    }

    private static List<String> normalizeGroup(Collection<String> raw, String group) {
        Collection<String> source = raw == null ? List.of() : raw;
        Set<String> normalized = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (String item : source) {
            if (item == null) {
                throw ApiException.unprocessable("LABEL_INVALID", group + " 包含空标签");
            }
            String tag = item.trim();
            if (tag.isEmpty()) {
                throw ApiException.unprocessable("LABEL_INVALID", group + " 包含空标签");
            }
            if (!normalized.add(tag)) {
                duplicates.add(tag);
            }
        }
        if (!duplicates.isEmpty()) {
            throw ApiException.unprocessable("LABEL_DUPLICATED",
                    group + " 存在重复标签: " + String.join(",", new TreeSet<>(duplicates)));
        }
        return List.copyOf(new TreeSet<>(normalized));
    }

    /**
     * 设备标签集合是否满足表达式。
     */
    public boolean matches(Collection<String> deviceLabels) {
        Set<String> labels = deviceLabels instanceof Set ? (Set<String>) deviceLabels : Set.copyOf(deviceLabels);
        if (!labels.containsAll(mustInclude)) {
            return false;
        }
        for (String excluded : mustExclude) {
            if (labels.contains(excluded)) {
                return false;
            }
        }
        if (!includeAny.isEmpty()) {
            boolean anyHit = false;
            for (String candidate : includeAny) {
                if (labels.contains(candidate)) {
                    anyHit = true;
                    break;
                }
            }
            if (!anyHit) {
                return false;
            }
        }
        return true;
    }

    /**
     * 规范化单个标签集合：trim、去空白标签拒绝、去重并按字典序排序。供设备标签使用。
     */
    public static List<String> normalizeTags(Collection<String> raw) {
        Collection<String> source = raw == null ? List.of() : raw;
        Set<String> normalized = new TreeSet<>();
        for (String item : source) {
            if (item == null) {
                throw ApiException.unprocessable("LABEL_INVALID", "标签不能为 null");
            }
            String tag = item.trim();
            if (tag.isEmpty()) {
                throw ApiException.unprocessable("LABEL_INVALID", "标签不能为空");
            }
            normalized.add(tag);
        }
        return List.copyOf(normalized);
    }
}
