package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 引文锚点纯规则：引用标识规范化、字符区间校验、交叉检测、
 * 引用文本保留与旧锚点到新锚点的一一映射校验。
 * 区间一律采用左闭右开、按 Java UTF-16 代码单元（字符偏移）计算。
 */
public final class AnchorSupport {

    private AnchorSupport() {
    }

    /** 锚点区间：左闭右开 [start, end)。 */
    public record Range(int start, int end) {
        public int length() {
            return end - start;
        }
    }

    /** 旧锚点视图：引用标识与左闭右开区间。 */
    public record OldAnchor(String citationKey, int start, int end) {
    }

    /** 迁移映射：旧锚点引用标识到新区间；迁移中引用标识保持不变。 */
    public record Migration(String citationKey, Range newRange) {
    }

    /** 规范化引用标识：去首尾空白、转小写（语言无关）；空标识非法。 */
    public static String normalizeCitationKey(String citationKey) {
        if (citationKey == null) {
            throw ApiException.unprocessable("引用标识不能为空");
        }
        String normalized = citationKey.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw ApiException.unprocessable("引用标识不能为空");
        }
        return normalized;
    }

    /** 校验单个区间：start 非负、end 不越界、长度为正。返回可读问题，合法返回 null。 */
    public static String validateRange(int start, int end, int contentLength) {
        if (start < 0) {
            return "区间起点不能为负数: " + start;
        }
        if (end > contentLength) {
            return "区间终点 " + end + " 超出当前译文长度 " + contentLength;
        }
        if (start >= end) {
            return "区间必须非空且左闭右开: [" + start + ", " + end + ")";
        }
        return null;
    }

    /** 校验一批区间互不交叉（端点相接允许）；违规时抛出 422。 */
    public static void assertNoOverlap(List<Range> ranges) {
        List<Range> sorted = new ArrayList<>(ranges);
        sorted.sort((a, b) -> a.start() == b.start() ? Integer.compare(a.end(), b.end())
                : Integer.compare(a.start(), b.start()));
        for (int i = 1; i < sorted.size(); i++) {
            Range previous = sorted.get(i - 1);
            Range current = sorted.get(i);
            if (current.start() < previous.end()) {
                throw ApiException.unprocessable("引文锚点区间交叉: [" + previous.start() + ", "
                        + previous.end() + ") 与 [" + current.start() + ", " + current.end() + ")");
            }
        }
    }

    /** 截取区间内文本（左闭右开）。 */
    public static String slice(String content, int start, int end) {
        return content.substring(start, end);
    }

    /**
     * 校验译文修订后全部生效锚点的引用文本逐字保留，并返回每个旧锚点在新译文中的最终区间（按入参顺序）。
     * 映射按引用标识一一对应：重复映射、映射指向不存在的锚点、新区间越界或文本不一致均抛 422；
     * 未提供映射的锚点沿用旧区间（旧区间在新文本中越界或文本变化同样 422）。
     */
    public static List<Range> resolveAndAssert(String oldContent, String newContent,
                                               List<OldAnchor> oldAnchors, List<Migration> mappings) {
        Map<String, Range> mapped = new LinkedHashMap<>();
        Set<String> duplicateGuard = new HashSet<>();
        List<Migration> safeMappings = mappings == null ? List.<Migration>of() : mappings;
        for (Migration migration : safeMappings) {
            String key = normalizeCitationKey(migration.citationKey());
            if (!duplicateGuard.add(key)) {
                throw ApiException.unprocessable("锚点映射重复: citationKey=" + key);
            }
            mapped.put(key, migration.newRange());
        }
        Set<String> knownKeys = new HashSet<>();
        for (OldAnchor anchor : oldAnchors) {
            knownKeys.add(anchor.citationKey());
        }
        for (String mappedKey : mapped.keySet()) {
            if (!knownKeys.contains(mappedKey)) {
                throw ApiException.unprocessable("锚点映射指向该段落不存在的生效锚点: citationKey=" + mappedKey);
            }
        }
        List<Range> resolved = new ArrayList<>(oldAnchors.size());
        for (OldAnchor anchor : oldAnchors) {
            String expected = slice(oldContent, anchor.start(), anchor.end());
            Range target = mapped.getOrDefault(anchor.citationKey(), new Range(anchor.start(), anchor.end()));
            String problem = validateRange(target.start(), target.end(), newContent.length());
            if (problem != null) {
                throw ApiException.unprocessable("锚点 " + anchor.citationKey() + " 的新" + problem);
            }
            String actual = slice(newContent, target.start(), target.end());
            if (!actual.equals(expected)) {
                throw ApiException.unprocessable("锚点 " + anchor.citationKey() + " 引用文本不一致：要求保留 \""
                        + expected + "\"，新区间 [" + target.start() + ", " + target.end()
                        + ") 实际为 \"" + actual + "\"，差额 " + (target.end() - target.start())
                        + " 对要求 " + expected.length() + " 字符");
            }
            resolved.add(target);
        }
        assertNoOverlap(resolved);
        return resolved;
    }

    /** 时间截断到微秒，保证 TIMESTAMP(6) 存储与比较精度稳定。 */
    public static Instant truncateToMicros(Instant instant) {
        return Instant.ofEpochSecond(instant.getEpochSecond(), (instant.getNano() / 1000) * 1000);
    }
}
