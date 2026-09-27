package com.example.starter.translation.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * 版式占位符领域规则（无框架依赖，可直接单元测试）。
 *
 * <p>占位符词法为 {@code {名称}}：左右花括号包裹 1~62 个大小写英文字母、数字或下划线，
 * 例如 {@code {name}}、{@code {0}}。源段显式声明的占位符集合经去重并按 Unicode 字典序规范排序；
 * 源文与译文中的出现次数按非重叠扫描计数。译文的占位符多重集合必须与源文完全一致：
 * 不允许缺失、额外或重复计数不一致。
 */
public final class Placeholders {

    /** 占位符最大声明数量，防御异常超长请求。 */
    public static final int MAX_DECLARED = 50;

    /** 名称最长 62 个字符，整体最长 64 个字符，与段落字段长度约束匹配。 */
    public static final Pattern TOKEN = Pattern.compile("\\{[A-Za-z0-9_]{1,62}}");

    private Placeholders() {
    }

    /**
     * 校验并规范化源段声明的占位符集合：逐条校验词法、去重、按字典序排序。
     *
     * @param raw 原始声明列表，null 视为空
     * @return 去重并排序后的不可变列表
     * @throws IllegalArgumentException 词法非法、元素为空或声明重复时抛出，消息可直接作为 422 原因
     */
    public static List<String> normalizeDeclaration(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_DECLARED) {
            throw new IllegalArgumentException("占位符数量超过上限 " + MAX_DECLARED + " 个");
        }
        java.util.TreeSet<String> sorted = new java.util.TreeSet<>();
        for (String token : raw) {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("占位符不能为空");
            }
            if (!TOKEN.matcher(token).matches()) {
                throw new IllegalArgumentException("占位符格式非法: " + token);
            }
            if (!sorted.add(token)) {
                throw new IllegalArgumentException("占位符声明重复: " + token);
            }
        }
        return List.copyOf(sorted);
    }

    /** 判断单个字符串是否为合法占位符词法。 */
    public static boolean isLegalToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /**
     * 检测文本中花括号形式的非法占位符：未闭合的 {@code {name}、不含合法名称的 {@code {}}、
     * {@code {a b}} 等均视为格式非法。纯文本中出现孤立花括号同样按非法处理，
     * 避免把版式标记误当普通字符。
     *
     * @throws IllegalArgumentException 发现非法花括号片段时抛出，消息含具体片段
     */
    public static void assertNoMalformedBraces(CharSequence text) {
        if (text == null) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '{') {
                continue;
            }
            int close = -1;
            for (int j = i + 1; j < text.length(); j++) {
                if (text.charAt(j) == '}') {
                    close = j;
                    break;
                }
            }
            if (close < 0) {
                throw new IllegalArgumentException("占位符未闭合: " + text.subSequence(i, Math.min(i + 64, text.length())));
            }
            String candidate = text.subSequence(i, close + 1).toString();
            if (!TOKEN.matcher(candidate).matches()) {
                throw new IllegalArgumentException("占位符格式非法: " + candidate);
            }
            i = close;
        }
    }

    /**
     * 非重叠扫描文本中的全部占位符并按字典序统计出现次数。
     *
     * @return 键按字典序排列的计数表，值为不小于 1 的出现次数
     */
    public static LinkedHashMap<String, Integer> counts(CharSequence text) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        if (text == null) {
            return counts;
        }
        TOKEN.matcher(text).results().map(MatchResult::group)
                .sorted()
                .forEach(token -> counts.merge(token, 1, Integer::sum));
        return counts;
    }

    /** 将计数表展开为按字典序排列的多重集合列表（重复占位符出现多次）。 */
    public static List<String> expand(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(e -> java.util.stream.Stream.generate(e::getKey).limit(e.getValue()))
                .toList();
    }
}
