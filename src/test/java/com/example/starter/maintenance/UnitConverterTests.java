package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.example.starter.maintenance.domain.MeasurementUnit;
import com.example.starter.maintenance.domain.UnitConverter;

/**
 * 单位换算精度测试：BigDecimal 精确计算，HALF_UP 四舍五入到最近整数分钟；
 * 展示层与存储层使用同一换算规则。
 */
class UnitConverterTests {

    @Test
    void toMinutes_hoursMultipliedBy60() {
        assertEquals(60, UnitConverter.toMinutes(new BigDecimal("1"), MeasurementUnit.HOURS));
        assertEquals(90, UnitConverter.toMinutes(new BigDecimal("1.5"), MeasurementUnit.HOURS));
        assertEquals(0, UnitConverter.toMinutes(new BigDecimal("0"), MeasurementUnit.HOURS));
    }

    @Test
    void toMinutes_roundsHalfUpToNearestMinute() {
        // 0.008 小时 = 0.48 分钟 → 0；0.009 小时 = 0.54 分钟 → 1
        assertEquals(0, UnitConverter.toMinutes(new BigDecimal("0.008"), MeasurementUnit.HOURS));
        assertEquals(1, UnitConverter.toMinutes(new BigDecimal("0.009"), MeasurementUnit.HOURS));
        // 恰好 0.5 分钟（0.008333... 不在 2 位小数域内，用 0.5 分钟直接验证 HALF_UP）
        assertEquals(1, UnitConverter.toMinutes(new BigDecimal("0.5"), MeasurementUnit.MINUTES));
        assertEquals(2, UnitConverter.toMinutes(new BigDecimal("1.5"), MeasurementUnit.MINUTES));
        assertEquals(1, UnitConverter.toMinutes(new BigDecimal("1.49"), MeasurementUnit.MINUTES));
        // 2.675 小时 = 160.5 分钟 → 161（HALF_UP，不用银行家舍入）
        assertEquals(161, UnitConverter.toMinutes(new BigDecimal("2.675"), MeasurementUnit.HOURS));
    }

    @Test
    void convert_hoursToMinutesAndBack() {
        assertEquals(0, new BigDecimal("90").compareTo(
                UnitConverter.convert(new BigDecimal("1.5"), MeasurementUnit.HOURS,
                        MeasurementUnit.MINUTES)));
        assertEquals(0, new BigDecimal("1.5").compareTo(
                UnitConverter.convert(new BigDecimal("90"), MeasurementUnit.MINUTES,
                        MeasurementUnit.HOURS)));
        // 分钟 → 小时保留 2 位小数：1 分钟 = 0.0166... → 0.02
        assertEquals(0, new BigDecimal("0.02").compareTo(
                UnitConverter.convert(new BigDecimal("1"), MeasurementUnit.MINUTES,
                        MeasurementUnit.HOURS)));
    }

    @Test
    void convert_sameUnitNormalizesScale() {
        assertEquals(0, new BigDecimal("1.50").compareTo(
                UnitConverter.convert(new BigDecimal("1.5"), MeasurementUnit.HOURS,
                        MeasurementUnit.HOURS)));
    }

    @Test
    void displayFromMinutes_singleConversionNoAccumulation() {
        // 展示层直接由存储分钟数换算：90 分钟 → 1.5 小时，不经过中间十进制二次转换
        assertEquals(0, new BigDecimal("1.5").compareTo(
                UnitConverter.displayFromMinutes(90, MeasurementUnit.HOURS)));
        assertEquals(0, new BigDecimal("90").compareTo(
                UnitConverter.displayFromMinutes(90, MeasurementUnit.MINUTES)));
    }

    @Test
    void hasValidScale_atMostTwoDecimals() {
        assertTrue(UnitConverter.hasValidScale(new BigDecimal("1.5")));
        assertTrue(UnitConverter.hasValidScale(new BigDecimal("1.55")));
        assertFalse(UnitConverter.hasValidScale(new BigDecimal("1.555")));
        assertFalse(UnitConverter.hasValidScale(new BigDecimal("0.001")));
    }
}
