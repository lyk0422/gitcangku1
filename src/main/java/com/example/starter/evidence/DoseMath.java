package com.example.starter.evidence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 剂量换算：内部以“毫勒克斯×纳秒”（照度×1000×持续纳秒）精确累计，不做任何舍入；
 * 仅在展示时换算为勒克斯分钟并按 HALF_UP 保留四位，展示值不参与超限判定。
 */
final class DoseMath {

    /**
     * 1 勒克斯分钟对应的内部单位数：1000（毫勒克斯）× 60 × 1e9（纳秒）。
     */
    static final BigDecimal MILLI_LUX_NANOS_PER_LUX_MINUTE = new BigDecimal("60000000000000");

    private DoseMath() {
    }

    /**
     * 单段剂量：照度（最多3位小数）× 精确持续纳秒，结果为整数个内部单位，无舍入。
     */
    static BigDecimal segmentDose(BigDecimal lux, LocalDateTime startAt, LocalDateTime endAt) {
        long nanos = Duration.between(startAt, endAt).toNanos();
        return lux.movePointRight(3).multiply(BigDecimal.valueOf(nanos));
    }

    /**
     * 上限（勒克斯分钟）换算为内部单位，精确。
     */
    static BigDecimal limitToInternal(BigDecimal limitLuxMinutes) {
        return limitLuxMinutes.multiply(MILLI_LUX_NANOS_PER_LUX_MINUTE);
    }

    /**
     * 是否达到上限：累计 ≥ 上限 即视为 DOSE_BLOCKED（精确比较，不使用展示值）。
     */
    static boolean reachesLimit(BigDecimal cumInternal, BigDecimal limitLuxMinutes) {
        return cumInternal.compareTo(limitToInternal(limitLuxMinutes)) >= 0;
    }

    /**
     * 展示用勒克斯分钟：HALF_UP 四位，不参与判定。
     */
    static BigDecimal toDisplayLuxMinutes(BigDecimal internal) {
        return internal.divide(MILLI_LUX_NANOS_PER_LUX_MINUTE, 4, RoundingMode.HALF_UP);
    }
}
