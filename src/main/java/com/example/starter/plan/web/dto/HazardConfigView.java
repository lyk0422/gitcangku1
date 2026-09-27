package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.SortedSet;

/**
 * 危险品隔离配置版本视图。separations 按类别对、restrictedSections 按区段排序，排序稳定。
 *
 * @param versionNo         配置版本号
 * @param effectiveFromUtc  生效开始时刻（含），UTC
 * @param effectiveToUtc    生效结束时刻（不含），UTC；null 表示长期有效
 * @param fingerprint       配置内容规范化指纹（SHA-256）
 * @param separations       互斥类别间隔规则（类别对字典序归一化）
 * @param restrictedSections 限制区段开放清单
 */
public record HazardConfigView(int versionNo, Instant effectiveFromUtc, Instant effectiveToUtc,
                               String fingerprint, List<SeparationView> separations,
                               List<SectionOpenListView> restrictedSections) {

    /**
     * 互斥类别间隔规则视图。
     */
    public record SeparationView(String classA, String classB, int minCars) {
    }

    /**
     * 限制区段开放清单视图，开放类别按字典序排列。
     */
    public record SectionOpenListView(String sectionId, SortedSet<String> openHazardClasses) {
    }
}
