package com.example.starter.water;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 阶梯计费与农户总量校验的纯单元测试：阶梯边界、HALF_UP 到分、非法总量 422。
 */
class SettlementFeeTests {

    @Test
    void tieredFeeAppliesTiersAndHalfUpToCents() {
        // 第一阶梯内：每立方米 2 元
        assertEquals(new BigDecimal("0.00"), WaterService.tieredFee(new BigDecimal("0")));
        assertEquals(new BigDecimal("100.00"), WaterService.tieredFee(new BigDecimal("50")));
        // 恰好 100 立方米：200.00，不触发第二阶梯
        assertEquals(new BigDecimal("200.00"), WaterService.tieredFee(new BigDecimal("100")));
        // 超出部分每立方米 3 元
        assertEquals(new BigDecimal("200.03"), WaterService.tieredFee(new BigDecimal("100.01")));
        assertEquals(new BigDecimal("350.00"), WaterService.tieredFee(new BigDecimal("150")));
        // HALF_UP 到分：0.015 -> 0.02，0.012 -> 0.01
        assertEquals(new BigDecimal("200.02"), WaterService.tieredFee(new BigDecimal("100.005")));
        assertEquals(new BigDecimal("200.01"), WaterService.tieredFee(new BigDecimal("100.004")));
        // 第一阶梯内同样到分：0.002 -> 0.00，0.01 -> 0.01
        assertEquals(new BigDecimal("0.00"), WaterService.tieredFee(new BigDecimal("0.001")));
        assertEquals(new BigDecimal("0.01"), WaterService.tieredFee(new BigDecimal("0.005")));
        // 3 位小数精确水量
        assertEquals(new BigDecimal("0.67"), WaterService.tieredFee(new BigDecimal("0.333")));
    }

    @Test
    void invalidFarmerTotalThrows422() {
        ApiException negative = assertThrows(ApiException.class,
                () -> WaterService.validateFarmerTotal("u1", new BigDecimal("-0.001")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, negative.status());
        assertEquals("INVALID_FARMER_TOTAL", negative.code());

        ApiException tooPrecise = assertThrows(ApiException.class,
                () -> WaterService.validateFarmerTotal("u1", new BigDecimal("1.0001")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, tooPrecise.status());

        ApiException nullTotal = assertThrows(ApiException.class,
                () -> WaterService.validateFarmerTotal("u1", null));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, nullTotal.status());

        // 合法：零、整数、3 位小数、尾零不影响
        WaterService.validateFarmerTotal("u1", BigDecimal.ZERO);
        WaterService.validateFarmerTotal("u1", new BigDecimal("150"));
        WaterService.validateFarmerTotal("u1", new BigDecimal("100.005"));
        WaterService.validateFarmerTotal("u1", new BigDecimal("1.500"));
    }

    @Test
    void fmtMoneyAlwaysRendersTwoDecimals() {
        assertEquals("350.00", WaterService.fmtMoney(new BigDecimal("350")));
        assertEquals("-60.00", WaterService.fmtMoney(new BigDecimal("-60")));
        assertEquals("0.00", WaterService.fmtMoney(BigDecimal.ZERO));
        assertEquals("200.02", WaterService.fmtMoney(new BigDecimal("200.02")));
    }
}