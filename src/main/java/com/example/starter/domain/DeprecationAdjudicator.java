package com.example.starter.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 锁定解析时刻的弃用裁决（纯逻辑，不访问数据库）。
 *
 * <p>所有时间统一 UTC，区间端点按左闭右开解释：解析时刻 {@code now} 满足
 * {@code effectiveAt <= now} 的窗口视为已生效，命中闭包则整次锁定拒绝；
 * {@code effectiveAt > now} 的窗口尚未生效，命中闭包仍允许锁定，但必须产出
 * {@code DEPRECATION_WARNING}，并返回距生效的实际秒数差额。
 */
public final class DeprecationAdjudicator {

    private DeprecationAdjudicator() {
    }

    /**
     * 一条命中闭包的尚未生效弃用警告。
     *
     * @param secondsUntilEffective 解析时刻距生效时刻的秒数差额（正数，向零取整）
     */
    public record WarningHit(
            String name,
            int version,
            int replacementVersion,
            Instant effectiveAt,
            String note,
            long secondsUntilEffective) {
    }

    /**
     * 裁决结果：已生效命中（阻断）与尚未生效命中（警告），均按名称、版本升序。
     */
    public record Adjudication(
            List<ActiveDeprecation> blocked,
            List<WarningHit> warnings) {
    }

    /**
     * 在依赖闭包上按解析时刻裁决弃用窗口。
     *
     * @param closure      锁定解析出的闭包：制品名称 -> 精确版本
     * @param deprecations 全部有效弃用窗口，键为（名称，版本）
     * @param now          可注入时钟给出的解析时刻（UTC）
     */
    public static Adjudication adjudicate(Map<String, Integer> closure,
                                          Map<NameVersion, ActiveDeprecation> deprecations,
                                          Instant now) {
        TreeMap<NameVersion, ActiveDeprecation> blocked = new TreeMap<>();
        TreeMap<NameVersion, WarningHit> warnings = new TreeMap<>();
        for (Map.Entry<String, Integer> chosen : closure.entrySet()) {
            ActiveDeprecation window = deprecations.get(
                    new NameVersion(chosen.getKey(), chosen.getValue()));
            if (window == null) {
                continue;
            }
            if (!window.effectiveAt().isAfter(now)) {
                // 左闭：effectiveAt == now 即已生效。
                blocked.put(new NameVersion(window.name(), window.version()), window);
            } else {
                long seconds = Duration.between(now, window.effectiveAt()).getSeconds();
                warnings.put(new NameVersion(window.name(), window.version()),
                        new WarningHit(window.name(), window.version(),
                                window.replacementVersion(), window.effectiveAt(),
                                window.note(), seconds));
            }
        }
        return new Adjudication(List.copyOf(new ArrayList<>(blocked.values())),
                List.copyOf(new ArrayList<>(warnings.values())));
    }
}
