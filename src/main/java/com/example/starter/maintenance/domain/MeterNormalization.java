package com.example.starter.maintenance.domain;

import com.example.starter.maintenance.api.ApiException;

/**
 * 量程回绕归一化：归一累计分钟 = wrapCount * M + raw。
 * 只接受显式申报的回绕次数，不根据数值下降猜测回绕。
 * 非法输入（raw 越界、wrapCount 为负）与 long 溢出均抛出 400 业务异常。
 */
public final class MeterNormalization {

    private MeterNormalization() {
    }

    /**
     * 校验并归一原始读数。
     *
     * @param meterRangeMinutes 量程 M（正整数）
     * @param raw               量程内原始读数，须满足 0 <= raw < M
     * @param wrapCount         回绕次数，须非负
     * @return 归一累计分钟 wrapCount * M + raw
     * @throws ApiException 非法输入或归一结果 long 溢出（HTTP 400）
     */
    public static long normalize(long meterRangeMinutes, long raw, long wrapCount) {
        if (raw < 0 || raw >= meterRangeMinutes) {
            throw ApiException.badRequest("RAW_OUT_OF_RANGE",
                    "原始读数 raw 须满足 0 <= raw < M（M=" + meterRangeMinutes + "），实际：" + raw);
        }
        if (wrapCount < 0) {
            throw ApiException.badRequest("WRAP_COUNT_NEGATIVE",
                    "回绕次数 wrapCount 须为非负整数，实际：" + wrapCount);
        }
        try {
            return Math.addExact(Math.multiplyExact(wrapCount, meterRangeMinutes), raw);
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("CUMULATIVE_OVERFLOW",
                    "归一累计分钟 wrapCount * M + raw 超出 long 表示范围");
        }
    }
}
