package com.example.starter.translation.domain;

import com.example.starter.translation.api.ApiDtos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版式占位符工具：格式校验、正文提取与译文完整性比对。
 * 占位符形如 {name}，name 以字母开头、仅含字母、数字与下划线。
 * 集合一律按占位符原文升序规范排序，保证响应与快照的稳定顺序。
 */
public final class Placeholders {

    private static final Pattern TOKEN = Pattern.compile("\\{[A-Za-z][A-Za-z0-9_]*\\}");

    private Placeholders() {
    }

    /** 判断声明项是否为合法占位符（整体匹配）。 */
    public static boolean isValid(String placeholder) {
        return placeholder != null && TOKEN.matcher(placeholder).matches();
    }

    /** 提取正文中全部合法占位符（按出现顺序，含重复）。 */
    public static List<String> extract(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /** 统计正文中各占位符出现次数，按占位符原文升序返回。 */
    public static Map<String, Integer> countBy(String text) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String token : extract(text)) {
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 译文完整性比对：required 为源段声明的要求计数（规范排序），
     * 返回全部违规明细（缺失、额外、计数不一致），无违规为空列表。
     */
    public static List<ApiDtos.PlaceholderViolation> validate(Map<String, Integer> required, String content) {
        Map<String, Integer> actual = countBy(content);
        List<ApiDtos.PlaceholderViolation> violations = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            int actualCount = actual.getOrDefault(entry.getKey(), 0);
            if (actualCount == 0) {
                violations.add(new ApiDtos.PlaceholderViolation(
                        entry.getKey(), "MISSING", entry.getValue(), 0));
            } else if (actualCount != entry.getValue()) {
                violations.add(new ApiDtos.PlaceholderViolation(
                        entry.getKey(), "COUNT_MISMATCH", entry.getValue(), actualCount));
            }
        }
        for (Map.Entry<String, Integer> entry : actual.entrySet()) {
            if (!required.containsKey(entry.getKey())) {
                violations.add(new ApiDtos.PlaceholderViolation(
                        entry.getKey(), "EXTRA", 0, entry.getValue()));
            }
        }
        return violations;
    }
}
