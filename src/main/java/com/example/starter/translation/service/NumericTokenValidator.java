package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.NumericTokenRow;
import com.example.starter.translation.domain.Rows.TranslationNumericTokenRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数值词元校核：本地化数字须保持原值，不当作任意占位符。
 * 源段登记 0~5 个数值词元（tokenKey 唯一、精确十进制原值、单位）；
 * 译稿按同键回传目标数值字符串与正文码点区间，区间文字须与字符串一致且互不重叠。
 * 目标格式固定：逗号小数点、点三位分组符（如 1.234,50），整数首组 1~3 位、后组恰 3 位，
 * 小数 1~6 位，可带负号，禁止指数及混用格式。解析后精确值与单位均须等于源词元；
 * 错值或缺词元 422、格式非法 400，不自动修复。码点区间从 0 起左闭右开，补充字符计 1 个码点。
 */
public final class NumericTokenValidator {

    /** 源值：精确十进制原值文本，无指数、无分组符。 */
    private static final Pattern SOURCE_VALUE_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");

    /** 目标格式：可带负号；整数首组 1~3 位、后组恰 3 位（点前缀）；小数逗号后 1~6 位。 */
    private static final Pattern TARGET_VALUE_PATTERN =
            Pattern.compile("-?\\d{1,3}(\\.\\d{3})*(,\\d{1,6})?");

    private NumericTokenValidator() {
    }

    /**
     * 校核源段登记的词元集：tokenKey 唯一（重复 422）、原值为合法十进制文本（非法 400）。
     * 返回按 tokenKey 排序的不可变行记录，null 输入视为空集（普通无词元源段）。
     */
    public static List<NumericTokenRow> validateSourceTokens(List<ApiDtos.NumericTokenInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<NumericTokenRow> rows = new ArrayList<>();
        for (ApiDtos.NumericTokenInput input : inputs) {
            if (!seen.add(input.tokenKey())) {
                throw ApiException.unprocessable("数值词元 tokenKey 重复: " + input.tokenKey());
            }
            if (!SOURCE_VALUE_PATTERN.matcher(input.value()).matches()) {
                throw ApiException.badRequest("数值词元原值不是合法十进制: " + input.tokenKey());
            }
            rows.add(new NumericTokenRow(input.tokenKey(), input.value(), input.unit()));
        }
        rows.sort(Comparator.comparing(NumericTokenRow::tokenKey));
        return List.copyOf(rows);
    }

    /**
     * 校核译稿回传的词元集：键集须与源词元完全一致（缺词元或多键 422）；
     * 目标字符串格式非法 400；区间越界、区间文字与字符串不一致、区间重叠、
     * 解析值或单位不等于源词元均 422。返回按 tokenKey 排序的不可变行记录。
     */
    public static List<TranslationNumericTokenRow> validateTargetTokens(List<NumericTokenRow> sourceTokens,
                                                                        String content,
                                                                        List<ApiDtos.TargetNumericTokenInput> inputs) {
        List<ApiDtos.TargetNumericTokenInput> safeInputs = inputs == null ? List.of() : inputs;
        Map<String, NumericTokenRow> sourceByKey = new HashMap<>();
        for (NumericTokenRow source : sourceTokens) {
            sourceByKey.put(source.tokenKey(), source);
        }
        Set<String> seen = new HashSet<>();
        for (ApiDtos.TargetNumericTokenInput input : safeInputs) {
            if (!sourceByKey.containsKey(input.tokenKey())) {
                throw ApiException.unprocessable("译文数值词元键不存在于源段: " + input.tokenKey());
            }
            if (!seen.add(input.tokenKey())) {
                throw ApiException.unprocessable("译文数值词元 tokenKey 重复: " + input.tokenKey());
            }
        }
        for (String key : sourceByKey.keySet()) {
            if (!seen.contains(key)) {
                throw ApiException.unprocessable("译文缺少数值词元: " + key);
            }
        }
        int contentLength = content.codePointCount(0, content.length());
        List<TranslationNumericTokenRow> rows = new ArrayList<>();
        for (ApiDtos.TargetNumericTokenInput input : safeInputs) {
            if (!TARGET_VALUE_PATTERN.matcher(input.targetValue()).matches()) {
                throw ApiException.badRequest("目标数值格式非法: " + input.targetValue());
            }
            if (input.end() <= input.start() || input.end() > contentLength) {
                throw ApiException.unprocessable("数值词元区间越界: " + input.tokenKey()
                        + " [" + input.start() + ", " + input.end() + ")");
            }
            String slice = sliceByCodePoints(content, input.start(), input.end());
            if (!slice.equals(input.targetValue())) {
                throw ApiException.unprocessable("数值词元区间文字与目标字符串不一致: " + input.tokenKey());
            }
            NumericTokenRow source = sourceByKey.get(input.tokenKey());
            if (!source.unit().equals(input.unit())) {
                throw ApiException.unprocessable("数值词元单位不等于源词元: " + input.tokenKey());
            }
            BigDecimal targetValue = parseTargetValue(input.targetValue());
            if (targetValue.compareTo(new BigDecimal(source.sourceValue())) != 0) {
                throw ApiException.unprocessable("数值词元解析值不等于源词元原值: " + input.tokenKey());
            }
            rows.add(new TranslationNumericTokenRow(input.tokenKey(), input.targetValue(), input.unit(),
                    input.start(), input.end()));
        }
        assertNoOverlap(rows);
        rows.sort(Comparator.comparing(TranslationNumericTokenRow::tokenKey));
        return List.copyOf(rows);
    }

    /**
     * 复核已冻结的译文词元（批准与发布时调用）：键集、格式、区间、值与单位全部重新校核，
     * 任一不满足抛 422/400，与提交时口径一致。
     */
    public static void assertStoredTokensConsistent(List<NumericTokenRow> sourceTokens, String content,
                                                    List<TranslationNumericTokenRow> stored) {
        Map<String, NumericTokenRow> sourceByKey = new HashMap<>();
        for (NumericTokenRow source : sourceTokens) {
            sourceByKey.put(source.tokenKey(), source);
        }
        if (sourceByKey.size() != stored.size()) {
            throw ApiException.unprocessable("译文数值词元集与源词元不完整");
        }
        int contentLength = content.codePointCount(0, content.length());
        Set<String> seen = new HashSet<>();
        for (TranslationNumericTokenRow token : stored) {
            NumericTokenRow source = sourceByKey.get(token.tokenKey());
            if (source == null || !seen.add(token.tokenKey())) {
                throw ApiException.unprocessable("译文数值词元集与源词元不完整");
            }
            if (!TARGET_VALUE_PATTERN.matcher(token.targetValue()).matches()) {
                throw ApiException.unprocessable("已存目标数值格式非法: " + token.tokenKey());
            }
            if (token.endOffset() <= token.startOffset() || token.endOffset() > contentLength
                    || !sliceByCodePoints(content, token.startOffset(), token.endOffset())
                            .equals(token.targetValue())) {
                throw ApiException.unprocessable("已存数值词元区间与正文不一致: " + token.tokenKey());
            }
            if (!source.unit().equals(token.unit())) {
                throw ApiException.unprocessable("已存数值词元单位不等于源词元: " + token.tokenKey());
            }
            if (parseTargetValue(token.targetValue())
                    .compareTo(new BigDecimal(source.sourceValue())) != 0) {
                throw ApiException.unprocessable("已存数值词元解析值不等于源词元原值: " + token.tokenKey());
            }
        }
        assertNoOverlap(stored);
    }

    /** 解析目标数值字符串为精确十进制：去分组点、逗号换小数点。调用前须已通过格式校验。 */
    public static BigDecimal parseTargetValue(String targetValue) {
        return new BigDecimal(targetValue.replace(".", "").replace(',', '.'));
    }

    /** 按 Unicode 码点区间截取正文，从 0 起左闭右开，补充字符计 1 个码点。 */
    public static String sliceByCodePoints(String content, int start, int end) {
        int startIndex = content.offsetByCodePoints(0, start);
        int endIndex = content.offsetByCodePoints(startIndex, end - start);
        return content.substring(startIndex, endIndex);
    }

    private static void assertNoOverlap(List<TranslationNumericTokenRow> rows) {
        List<TranslationNumericTokenRow> byRange = new ArrayList<>(rows);
        byRange.sort(Comparator.comparingInt(TranslationNumericTokenRow::startOffset));
        int previousEnd = -1;
        for (TranslationNumericTokenRow row : byRange) {
            if (row.startOffset() < previousEnd) {
                throw ApiException.unprocessable("数值词元区间重叠: " + row.tokenKey());
            }
            previousEnd = row.endOffset();
        }
    }
}
