package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版式占位符工具：占位符形如 <code>{name}</code>，name 为 1~32 位 Unicode 字母、数字或下划线。
 * 源段声明的集合按 Unicode 字典序规范化并保留重复出现次数（多重集合）；
 * 译文中的占位符以同一规则扫描连续出现位置。比对时给出缺失/额外条目的要求次数、
 * 实际次数与差额（要求次数 - 实际次数），供 422 错误信息与诊断接口定位原因。
 */
public final class Placeholders {

    /** 合法占位符：左花括号 + 1~32 位 Unicode 字母/数字/下划线 + 右花括号。 */
    public static final Pattern PATTERN = Pattern.compile("\\{[\\p{L}\\p{N}_]{1,32}}");

    private Placeholders() {
    }

    /**
     * 规范化源段声明：逐元素校验格式（非法 422），返回按 Unicode 字典序排序的列表，重复元素保留重复次数。
     * 入参为 null（接口未声明）时按空集合处理。
     */
    public static List<String> canonical(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> sorted = new ArrayList<>(raw.size());
        for (String token : raw) {
            if (token == null || !PATTERN.matcher(token).matches()) {
                throw ApiException.unprocessable("占位符格式非法: " + token);
            }
            sorted.add(token);
        }
        sorted.sort(String::compareTo);
        return List.copyOf(sorted);
    }

    /** 扫描正文中的全部占位符出现位置（按出现顺序），再按字典序排序，重复出现全部保留。 */
    public static List<String> extract(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PATTERN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        found.sort(String::compareTo);
        return List.copyOf(found);
    }

    /** 比对要求集合与实际集合；完全一致返回 null，否则返回缺失/额外明细（含要求、实际与差额次数）。 */
    public static Mismatch compare(List<String> required, List<String> actual) {
        Map<String, Integer> requiredCounts = counts(required);
        Map<String, Integer> actualCounts = counts(actual);
        List<Diff> missing = new ArrayList<>();
        List<Diff> extra = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : requiredCounts.entrySet()) {
            int requiredCount = entry.getValue();
            int actualCount = actualCounts.getOrDefault(entry.getKey(), 0);
            if (actualCount < requiredCount) {
                missing.add(new Diff(entry.getKey(), requiredCount, actualCount, requiredCount - actualCount));
            }
        }
        for (Map.Entry<String, Integer> entry : actualCounts.entrySet()) {
            int actualCount = entry.getValue();
            int requiredCount = requiredCounts.getOrDefault(entry.getKey(), 0);
            if (actualCount > requiredCount) {
                extra.add(new Diff(entry.getKey(), requiredCount, actualCount, actualCount - requiredCount));
            }
        }
        if (missing.isEmpty() && extra.isEmpty()) {
            return null;
        }
        return new Mismatch(List.copyOf(required), List.copyOf(actual), List.copyOf(missing), List.copyOf(extra));
    }

    /** 组装 422 错误信息：逐条给出占位符、要求次数、实际次数与差额。 */
    public static String describe(String prefix, Mismatch mismatch) {
        StringBuilder message = new StringBuilder(prefix);
        for (Diff diff : mismatch.missing()) {
            message.append("；缺失 ").append(diff.placeholder())
                    .append(" 要求 ").append(diff.requiredCount())
                    .append(" 次实际 ").append(diff.actualCount())
                    .append(" 次差额 ").append(diff.difference()).append(" 次");
        }
        for (Diff diff : mismatch.extra()) {
            message.append("；额外 ").append(diff.placeholder())
                    .append(" 要求 ").append(diff.requiredCount())
                    .append(" 次实际 ").append(diff.actualCount())
                    .append(" 次差额 ").append(diff.difference()).append(" 次");
        }
        return message.toString();
    }

    private static Map<String, Integer> counts(List<String> tokens) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String token : tokens) {
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 单个占位符的计数差额。
     *
     * @param placeholder    占位符原文
     * @param requiredCount  源段要求的出现次数
     * @param actualCount    译文实际出现次数
     * @param difference     差额绝对值；缺失为要求-实际，额外为实际-要求
     */
    public record Diff(String placeholder, int requiredCount, int actualCount, int difference) {
    }

    /**
     * 占位符集合不一致明细。
     *
     * @param required 源段要求的规范化集合（含重复）
     * @param actual   译文实际提取的规范化集合（含重复）
     * @param missing  缺失条目（实际次数少于要求）
     * @param extra    额外条目（实际次数多于要求，含源段未声明的占位符）
     */
    public record Mismatch(List<String> required, List<String> actual, List<Diff> missing, List<Diff> extra) {
    }
}
