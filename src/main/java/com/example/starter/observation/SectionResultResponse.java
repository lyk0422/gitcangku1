package com.example.starter.observation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * 断面成果响应：冻结的完整点列、水面高程 H 与精确面积；
 * sourceUpdated 表示任一源观测当前版本已不同于成果冻结版本（历史成果不重算）。
 *
 * @param resultId      断面成果唯一标识
 * @param sectionNo     断面号
 * @param waterLevelMm  整数水面高程 H，单位毫米
 * @param points        冻结的完整点列（横向严格递增）
 * @param areaExact     精确面积（最简分数文本，分母为 1 时为整数文本），单位平方毫米
 * @param areaDisplay   展示面积，HALF_UP 保留三位小数，单位平方毫米
 * @param sourceUpdated 源观测是否在成果创建后发生过变更
 * @param operator      生成成果的操作者标识
 * @param createdAtUtc  成果生成时刻（UTC，ISO-8601）
 */
public record SectionResultResponse(
        String resultId,
        String sectionNo,
        long waterLevelMm,
        List<FrozenSectionPoint> points,
        String areaExact,
        String areaDisplay,
        boolean sourceUpdated,
        String operator,
        Instant createdAtUtc) {

    /**
     * 由成果实体构造响应；精确面积渲染为最简分数文本，展示值按 HALF_UP 三位小数。
     */
    public static SectionResultResponse of(SectionResult result, boolean sourceUpdated) {
        String exact = result.areaDenominator().equals(BigInteger.ONE)
                ? result.areaNumerator().toString()
                : result.areaNumerator() + "/" + result.areaDenominator();
        String display = new BigDecimal(result.areaNumerator())
                .divide(new BigDecimal(result.areaDenominator()), 3, RoundingMode.HALF_UP)
                .toPlainString();
        return new SectionResultResponse(
                result.resultId(),
                result.sectionNo(),
                result.waterLevelMm(),
                List.copyOf(result.points()),
                exact,
                display,
                sourceUpdated,
                result.operator(),
                result.createdAtUtc());
    }
}
