package com.example.starter.firmware.domain;

import com.example.starter.firmware.error.ApiException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 发布单目标标签策略（创建时固化的不可变值对象）。
 * 三类条件：includeAll 必须全部包含；excludeAny 不得包含任一项；includeAny 至少包含一项。
 * 列表均为规范化、去重、升序；空列表表示该条件未启用。
 *
 * @param includeAll 必须包含的标签
 * @param excludeAny 不得包含的标签
 * @param includeAny 至少包含一项的标签
 */
public record TargetPolicy(List<String> includeAll, List<String> excludeAny, List<String> includeAny) {

    /**
     * 由原始请求列表构建并校验策略：
     * 空条件（已给出但为空列表）与全空策略 422；标签重复 422；同标签同时要求包含与排除 422。
     */
    public static TargetPolicy of(List<String> includeAllRaw, List<String> excludeAnyRaw,
                                  List<String> includeAnyRaw) {
        if (includeAllRaw == null && excludeAnyRaw == null && includeAnyRaw == null) {
            throw ApiException.unprocessable("EMPTY_POLICY", "目标策略至少需要一个条件");
        }
        List<String> includeAll = normalizeCondition("includeAll", includeAllRaw);
        List<String> excludeAny = normalizeCondition("excludeAny", excludeAnyRaw);
        List<String> includeAny = normalizeCondition("includeAny", includeAnyRaw);
        Set<String> includes = new HashSet<>(includeAll);
        includes.addAll(includeAny);
        for (String excluded : excludeAny) {
            if (includes.contains(excluded)) {
                throw ApiException.unprocessable("TAG_INCLUDE_EXCLUDE_CONFLICT",
                        "标签同时要求包含和排除: " + excluded);
            }
        }
        return new TargetPolicy(includeAll, excludeAny, includeAny);
    }

    private static List<String> normalizeCondition(String name, List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw.isEmpty()) {
            throw ApiException.unprocessable("EMPTY_CONDITION", "条件 " + name + " 不能为空列表");
        }
        return Tags.normalizeAll(raw);
    }

    /**
     * 判断设备规范化标签集合是否命中本策略。
     */
    public boolean matches(Set<String> deviceTags) {
        return deviceTags.containsAll(includeAll)
                && excludeAny.stream().noneMatch(deviceTags::contains)
                && (includeAny.isEmpty() || includeAny.stream().anyMatch(deviceTags::contains));
    }
}
