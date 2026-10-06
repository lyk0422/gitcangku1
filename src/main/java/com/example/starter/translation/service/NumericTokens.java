package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.NumericTokenRow;
import com.example.starter.translation.domain.Rows.TranslationTokenRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数值词元校核：本地化数字在译文中必须保持原值，不作为任意占位符。
 * 目标固定格式：逗号作小数点、点作三位分组符（首组 1~3 位、后组恰 3 位）、小数 1~6 位、
 * 可带负号；禁止指数及混用格式，如 1.234,50。格式非法 400，错值/缺词元/区间问题 422，不自动修复。
 */
public final class NumericTokens {

    /** 目标数值格式：可选负号 + 整数首组 1~3 位 + 任意个三位分组 + 可选 1~6 位小数。 */
    private static final Pattern TARGET_FORMAT = Pattern.compile("-?\\d{1,3}(\\.\\d{3})*(,\\d{1,6})?");

    private NumericTokens() {
    }

    /** 解析目标数值字符串为精确十进制；格式非法返回 null。 */
    public static BigDecimal parseTargetValue(String target) {
        if (target == null || !TARGET_FORMAT.matcher(target).matches()) {
            return null;
        }
        String normalized = target.replace(".", "").replace(',', '.');
        return new BigDecimal(normalized);
    }

    /** 校验源词元登记：最多 5 个、tokenKey 唯一、原值字面量可存储；返回可落库行。 */
    public static List<NumericTokenRow> validateSourceTokens(String segmentId,
                                                             List<ApiDtos.NumericTokenInput> tokens) {
        List<ApiDtos.NumericTokenInput> safe = tokens == null ? List.of() : tokens;
        if (safe.size() > 5) {
            throw ApiException.unprocessable("数值词元最多 5 个，实际 " + safe.size() + " 个");
        }
        Set<String> seen = new HashSet<>();
        List<NumericTokenRow> rows = new ArrayList<>();
        for (ApiDtos.NumericTokenInput token : safe) {
            if (!seen.add(token.tokenKey())) {
                throw ApiException.unprocessable("数值词元 tokenKey 重复: " + token.tokenKey());
            }
            String literal = token.value().toPlainString();
            if (literal.length() > 128) {
                throw ApiException.unprocessable("数值词元原值超出存储长度: " + token.tokenKey());
            }
            rows.add(new NumericTokenRow(segmentId, token.tokenKey(), literal, token.unit()));
        }
        return rows;
    }

    /**
     * 提交侧校核：目标词元集合须与当前源词元同键完整对应；目标字符串格式非法 400；
     * 缺/多词元、错值、单位不符、区间越界/文字不一致/重叠 422。通过后返回可落库行。
     */
    public static List<TranslationTokenRow> validateTargetTokens(
            String segmentId, String language, List<NumericTokenRow> sourceTokens,
            List<ApiDtos.TargetNumericTokenInput> targets, String content) {
        List<ApiDtos.TargetNumericTokenInput> safe = targets == null ? List.of() : targets;
        Map<String, BigDecimal> parsedByKey = new LinkedHashMap<>();
        for (ApiDtos.TargetNumericTokenInput target : safe) {
            BigDecimal value = parseTargetValue(target.target());
            if (value == null) {
                throw ApiException.badRequest("目标数值格式非法: " + target.target());
            }
            if (parsedByKey.put(target.tokenKey(), value) != null) {
                throw ApiException.unprocessable("目标数值词元 tokenKey 重复: " + target.tokenKey());
            }
        }
        Map<String, NumericTokenRow> sourceByKey = sourceTokens.stream()
                .collect(Collectors.toMap(NumericTokenRow::tokenKey, Function.identity()));
        for (NumericTokenRow source : sourceTokens) {
            if (!parsedByKey.containsKey(source.tokenKey())) {
                throw ApiException.unprocessable("缺少目标数值词元: " + source.tokenKey());
            }
        }
        List<TranslationTokenRow> rows = new ArrayList<>();
        for (ApiDtos.TargetNumericTokenInput target : safe) {
            NumericTokenRow source = sourceByKey.get(target.tokenKey());
            if (source == null) {
                throw ApiException.unprocessable("目标数值词元无对应源词元: " + target.tokenKey());
            }
            if (parsedByKey.get(target.tokenKey()).compareTo(new BigDecimal(source.sourceValue())) != 0) {
                throw ApiException.unprocessable("目标数值与源词元原值不一致: " + target.tokenKey());
            }
            if (!source.unit().equals(target.unit())) {
                throw ApiException.unprocessable("目标数值单位与源词元不一致: " + target.tokenKey());
            }
            rows.add(new TranslationTokenRow(segmentId, language, target.tokenKey(), target.target(),
                    target.unit(), target.start(), target.end()));
        }
        validateRanges(rows, content);
        return rows;
    }

    /**
     * 批准/发布侧复核：已存储的译文词元对当前源词元做完整校验（键集合、值、单位、区间），
     * 任何不一致 422；普通无词元段落两侧均为空，直接通过。
     */
    public static void validateStoredTokens(String segmentId, String language,
                                            List<NumericTokenRow> sourceTokens,
                                            List<TranslationTokenRow> storedTokens, String content) {
        Map<String, NumericTokenRow> sourceByKey = sourceTokens.stream()
                .collect(Collectors.toMap(NumericTokenRow::tokenKey, Function.identity()));
        Map<String, TranslationTokenRow> storedByKey = storedTokens.stream()
                .collect(Collectors.toMap(TranslationTokenRow::tokenKey, Function.identity()));
        if (!sourceByKey.keySet().equals(storedByKey.keySet())) {
            throw ApiException.unprocessable(
                    "译文数值词元与当前源词元集合不一致: " + segmentId + "/" + language);
        }
        for (TranslationTokenRow stored : storedTokens) {
            NumericTokenRow source = sourceByKey.get(stored.tokenKey());
            BigDecimal value = parseTargetValue(stored.targetText());
            if (value == null || value.compareTo(new BigDecimal(source.sourceValue())) != 0
                    || !source.unit().equals(stored.unit())) {
                throw ApiException.unprocessable("译文数值词元与源词元不一致: " + stored.tokenKey());
            }
        }
        validateRanges(storedTokens, content);
    }

    /** 区间校核：码点边界合法、区间文字与目标字符串一致、互不重叠；补充字符按 1 个码点计。 */
    private static void validateRanges(List<TranslationTokenRow> tokens, String content) {
        int contentLength = content.codePointCount(0, content.length());
        List<TranslationTokenRow> byStart = new ArrayList<>(tokens);
        byStart.sort(Comparator.comparingInt(TranslationTokenRow::startOffset));
        int previousEnd = 0;
        for (TranslationTokenRow token : byStart) {
            if (token.startOffset() >= token.endOffset() || token.endOffset() > contentLength) {
                throw ApiException.unprocessable("数值词元区间越界: " + token.tokenKey());
            }
            if (token.startOffset() < previousEnd) {
                throw ApiException.unprocessable("数值词元区间重叠: " + token.tokenKey());
            }
            previousEnd = token.endOffset();
            String slice = content.substring(content.offsetByCodePoints(0, token.startOffset()),
                    content.offsetByCodePoints(0, token.endOffset()));
            if (!slice.equals(token.targetText())) {
                throw ApiException.unprocessable(
                        "数值词元区间文字与目标字符串不一致: " + token.tokenKey());
            }
        }
    }
}
