package com.example.starter.domain;

import java.util.List;
import java.util.Map;

/**
 * 锁定解析结果：精确版本组合、实际保留（生效）的依赖边、路径排除命中记录。
 *
 * @param versions   名称 -> 精确版本（名称升序）
 * @param edges      最终解中实际保留的依赖边（排序稳定）
 * @param exclusions 生效边上声明的路径排除及其命中情况（排序稳定）
 */
public record LockResolution(
        Map<String, Integer> versions,
        List<KeptEdge> edges,
        List<ExclusionRecord> exclusions) {

    /**
     * 最终解中实际保留的一条依赖边（未被任何路径排除忽略的边）。
     */
    public record KeptEdge(String fromName, int fromVersion, String toName, int toVersion) {
    }

    /**
     * 一条生效边上声明的路径排除。
     *
     * @param hit true=子图中确实存在同名边被该排除命中；false=未命中（含未知名称）
     */
    public record ExclusionRecord(String fromName, int fromVersion, String toName,
                                  String excludedName, boolean hit) {
    }
}
