package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.calibration.api.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LinearFit 纯单元测试：精确有理数拟合、系数可表示性、重复 x 与点数校验、残差计算。
 */
class LinearFitTest {

    private static LinearFit.Point p(String x, String y) {
        return new LinearFit.Point(new BigDecimal(x), new BigDecimal(y));
    }

    @Test
    void fitsExactLineThroughSyntheticPoints() {
        // 题干合成数据：(0,1)、(1,3)、(2,5) → a=2、b=1，残差全 0
        LinearFit.Result result = LinearFit.fit(List.of(p("0", "1"), p("1", "3"), p("2", "5")));
        assertEquals(0, new BigDecimal("2").compareTo(result.a()));
        assertEquals(0, new BigDecimal("1").compareTo(result.b()));
        assertTrue(result.residuals().stream().allMatch(r -> r.signum() == 0));
        assertEquals(0, result.maxAbsResidual().signum());
    }

    @Test
    void computesExactResiduals() {
        // a=1.15、b=-0.1；残差 0.1、-0.05、-0.2、0.15，最大绝对残差 0.2
        LinearFit.Result result = LinearFit.fit(List.of(
                p("0", "0"), p("1", "1"), p("2", "2"), p("3", "3.5")));
        assertEquals(0, new BigDecimal("1.15").compareTo(result.a()));
        assertEquals(0, new BigDecimal("-0.1").compareTo(result.b()));
        assertEquals(0, new BigDecimal("0.2").compareTo(result.maxAbsResidual()));
        assertEquals(0, new BigDecimal("-0.2").compareTo(result.residuals().get(2)));
    }

    @Test
    void rejectsNonRepresentableSlope() {
        // a = 5/14，不能精确表示为 6 位小数 → 422
        ApiException ex = assertThrows(ApiException.class, () ->
                LinearFit.fit(List.of(p("0", "0"), p("1", "0"), p("3", "1"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("COEFFICIENT_NOT_REPRESENTABLE", ex.getCode());
    }

    @Test
    void rejectsNonRepresentableIntercept() {
        // a = 1.5 可表示，但 b = -1/6 不可表示 → 422
        ApiException ex = assertThrows(ApiException.class, () ->
                LinearFit.fit(List.of(p("0", "0"), p("1", "1"), p("2", "3"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("COEFFICIENT_NOT_REPRESENTABLE", ex.getCode());
    }

    @Test
    void rejectsDuplicateXAndInvalidPointCount() {
        ApiException dup = assertThrows(ApiException.class, () ->
                LinearFit.fit(List.of(p("1", "1"), p("1.0", "2"), p("2", "3"))));
        assertEquals(HttpStatus.BAD_REQUEST, dup.getStatus());

        assertThrows(ApiException.class, () ->
                LinearFit.fit(List.of(p("0", "1"), p("1", "3"))));
        assertThrows(ApiException.class, () ->
                LinearFit.fit(List.of(p("0", "0"), p("1", "0"), p("2", "0"), p("3", "0"),
                        p("4", "0"), p("5", "0"), p("6", "0"), p("7", "0"), p("8", "0"))));
        assertThrows(ApiException.class, () -> LinearFit.fit(null));
    }
}
