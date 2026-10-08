package com.example.starter.observation;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

/**
 * 断面成果：对应 section_result 表的一行，创建时冻结完整点列、水面高程 H 与精确面积，
 * 不可变、永不更新；源观测后续变更仅在查询时标记 sourceUpdated，不重算旧成果。
 *
 * @param resultId         断面成果唯一标识
 * @param requestId        生成该成果的请求标识
 * @param sectionNo        断面号（全部源点一致）
 * @param waterLevelMm     整数水面高程 H，单位毫米
 * @param points           冻结的完整点列（横向严格递增）
 * @param areaNumerator    精确面积最简分数分子，单位平方毫米
 * @param areaDenominator  精确面积最简分数分母，单位平方毫米
 * @param operator         生成成果的操作者标识
 * @param createdAtUtc     成果生成时刻（UTC）
 */
public record SectionResult(
        String resultId,
        String requestId,
        String sectionNo,
        long waterLevelMm,
        List<FrozenSectionPoint> points,
        BigInteger areaNumerator,
        BigInteger areaDenominator,
        String operator,
        Instant createdAtUtc) {
}
