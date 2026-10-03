package com.example.gitcangku1.domain;

import java.time.Instant;

/**
 * 放行冻结明细（交付数据，不可修改）。
 * adoptedSource 为采纳依据；deltaE 为未四舍五入的三维欧氏距离；measuredAt 为 UTC。
 */
public record ReleaseItem(
        long id,
        long releaseId,
        int rollNo,
        MeasureType adoptedSource,
        double l,
        double a,
        double b,
        double deltaE,
        Instant measuredAt) {
}