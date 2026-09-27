package com.example.starter.firmware.service;

import com.example.starter.firmware.api.TargetExpressionRequest;
import com.example.starter.firmware.domain.TagExpression;
import com.example.starter.firmware.error.ApiException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 标签规范化与目标策略匹配（纯逻辑，无 IO，便于单元测试）。
 * 规范化规则：trim 两端空白；长度 1~64；仅允许字母、数字与 . _ : -；大小写敏感原样保留。
 * 组内重复标签、三类条件全空、同标签同时出现在必须包含与不得包含中均为 422。
 */
@Component
public class TagPolicy {

    /**
     * 规范化标签允许字符：ASCII 字母/数字或 . _ : -。
     */
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:=-]+");

    public static final int MAX_TAG_LENGTH = 64;

    /**
     * 单个标签规范化：trim 后校验长度与字符集；非法时抛 422。
     */
    public String normalizeTag(String raw) {
        if (raw == null) {
            throw ApiException.unprocessable("TAG_INVALID", "标签不能为空");
        }
        String tag = raw.trim();
        if (tag.isEmpty() || tag.length() > MAX_TAG_LENGTH) {
            throw ApiException.unprocessable("TAG_INVALID",
                    "标签长度必须在1~%d之间: '%s'".formatted(MAX_TAG_LENGTH, raw));
        }
        if (!ALLOWED.matcher(tag).matches()) {
            throw ApiException.unprocessable("TAG_INVALID", "标签包含非法字符（仅允许字母数字._:-）: " + tag);
        }
        return tag;
    }

    /**
     * 规范化一组标签：逐标签规范化，拒绝组内重复，结果按字典序排序。
     *
     * @param groupName 条件组名（include/exclude/anyOf），用于可区分的错误信息
     */
    public List<String> normalizeGroup(List<String> rawTags, String groupName) {
        List<String> source = rawTags == null ? List.of() : rawTags;
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : source) {
            String tag = normalizeTag(raw);
            if (!normalized.add(tag)) {
                throw ApiException.unprocessable("TAG_DUPLICATE",
                        "条件组 " + groupName + " 中标签重复: " + tag);
            }
        }
        return new ArrayList<>(new TreeSet<>(normalized));
    }

    /**
     * 规范化并校验目标表达式。
     * 空条件（三组都为空）与“同标签同时必须包含、不得包含”均返回 422。
     */
    public TagExpression normalizeExpression(TargetExpressionRequest request) {
        TargetExpressionRequest source = request == null
                ? new TargetExpressionRequest(null, null, null) : request;
        List<String> include = normalizeGroup(source.include(), TagExpression.INCLUDE);
        List<String> exclude = normalizeGroup(source.exclude(), TagExpression.EXCLUDE);
        List<String> anyOf = normalizeGroup(source.anyOf(), TagExpression.ANY_OF);
        if (include.isEmpty() && exclude.isEmpty() && anyOf.isEmpty()) {
            throw ApiException.unprocessable("TARGET_EXPRESSION_EMPTY",
                    "目标标签表达式为空：include/exclude/anyOf 至少需要一个非空条件");
        }
        for (String tag : include) {
            if (exclude.contains(tag)) {
                throw ApiException.unprocessable("TAG_INCLUDE_EXCLUDE_CONFLICT",
                        "标签 " + tag + " 同时出现在必须包含与不得包含条件中");
            }
        }
        return new TagExpression(include, exclude, anyOf);
    }

    /**
     * 设备标签集合是否命中表达式：必须全部包含 include、不得命中任一 exclude、
     * anyOf 非空时至少命中一项。
     */
    public boolean matches(Set<String> deviceTags, TagExpression expression) {
        if (!deviceTags.containsAll(expression.include())) {
            return false;
        }
        for (String tag : expression.exclude()) {
            if (deviceTags.contains(tag)) {
                return false;
            }
        }
        if (!expression.anyOf().isEmpty()) {
            boolean hitAny = false;
            for (String tag : expression.anyOf()) {
                if (deviceTags.contains(tag)) {
                    hitAny = true;
                    break;
                }
            }
            if (!hitAny) {
                return false;
            }
        }
        return true;
    }
}
