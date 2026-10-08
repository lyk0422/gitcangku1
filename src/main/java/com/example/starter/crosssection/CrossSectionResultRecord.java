package com.example.starter.crosssection;

import java.time.Instant;

/**
 * 断面水域面积成果记录：对应 cross_section_result 的一行，创建时冻结水面高程与精确面积，不可变。
 *
 * @param resultId     全局唯一成果标识
 * @param requestId    生成该成果的请求标识
 * @param fingerprint  成果请求全参数指纹
 * @param sectionNo    成果所属断面号（取自已核验的同源点）
 * @param waterLevelMm 整数水面高程 H（毫米）
 * @param areaNumerator   精确面积分子（平方毫米，约分后）
 * @param areaDenominator 精确面积分母（恒正且与分子互质）
 * @param areaText     面积展示值：HALF_UP 三位小数原文
 * @param pointCount   冻结点列点数
 * @param operator     创建成果的操作者标识
 * @param createdAtUtc 成果创建时刻（UTC）
 */
public record CrossSectionResultRecord(
        String resultId,
        String requestId,
        String fingerprint,
        String sectionNo,
        int waterLevelMm,
        String areaNumerator,
        String areaDenominator,
        String areaText,
        int pointCount,
        String operator,
        Instant createdAtUtc) {
}
