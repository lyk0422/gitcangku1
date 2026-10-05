package com.example.starter.domain;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一次成功锁定的完整解：精确版本、实际保留边与路径排除处置记录。
 *
 * @param versions   名称 -> 精确版本（名称升序）
 * @param edges      实际保留的依赖边（未被任何路径排除），按 fromName/fromVersion/toName 升序
 * @param exclusions 解中全部已声明排除的处置记录（APPLIED=实际忽略了边，MISS=未命中）
 */
public record LockSolution(
        Map<String, Integer> versions,
        List<RetainedEdge> edges,
        List<ExclusionRecord> exclusions) {

    public LockSolution {
        versions = Collections.unmodifiableMap(new TreeMap<>(versions));
        edges = List.copyOf(edges);
        exclusions = List.copyOf(exclusions);
    }

    /** 实际保留的依赖边：fromName:fromVersion 声明的、指向 toName 的区间约束。 */
    public record RetainedEdge(String fromName, int fromVersion, String toName,
                               int minimumVersion, int maximumVersion) {
    }

    /**
     * 路径排除处置记录。
     *
     * @param fromName     声明排除的边所属制品名称
     * @param fromVersion  声明排除的边所属制品精确版本
     * @param toName       声明排除的边目标制品名称
     * @param excludedName 被排除的制品名称
     * @param outcome      APPLIED=至少实际忽略了一条同名边；MISS=未命中（允许排除未知名称）
     * @param detail       人读理由：APPLIED 时列出被忽略的边，MISS 时为“未命中”
     */
    public record ExclusionRecord(String fromName, int fromVersion, String toName,
                                  String excludedName, String outcome, String detail) {
    }
}
