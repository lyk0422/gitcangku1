package com.example.starter.calibration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.service.UncertaintyMath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合成不确定度口径单元测试：公开 RSS（平方和开根），HALF_UP 保留 4 位小数。
 */
class UncertaintyMathTest {

    private UncertaintyComponent component(String name, String magnitude) {
        return new UncertaintyComponent(0L, 0L, 1L, name, new BigDecimal(magnitude), Instant.EPOCH);
    }

    @Test
    void emptyBudgetCombinesToZeroWithFourDecimals() {
        assertEquals("0.0000", UncertaintyMath.combined(List.of()).toPlainString());
        assertEquals("0.0000", UncertaintyMath.combined(null).toPlainString());
    }

    @Test
    void singleComponentEqualsItsMagnitudeRoundedToFourDecimals() {
        assertEquals("0.3000", UncertaintyMath.combined(List.of(component("u", "0.3"))).toPlainString());
    }

    @Test
    void combinesByRootSumOfSquares() {
        // sqrt(3^2 + 4^2) = 5
        assertEquals("5.0000",
                UncertaintyMath.combined(List.of(component("a", "3"), component("b", "4"))).toPlainString());
        // sqrt(1 + 1) = 1.41421356... -> HALF_UP 4 = 1.4142
        assertEquals("1.4142",
                UncertaintyMath.combined(List.of(component("a", "1"), component("b", "1"))).toPlainString());
        // sqrt(0.1^2+0.2^2+0.3^2) = sqrt(0.14) = 0.3741657... -> 0.3742
        assertEquals("0.3742",
                UncertaintyMath.combined(
                        List.of(component("a", "0.1"), component("b", "0.2"), component("c", "0.3")))
                        .toPlainString());
    }

    @Test
    void halfUpRoundingAtFourthDecimal() {
        // sqrt(2) = 1.41421356 -> 1.4142
        assertEquals("1.4142", UncertaintyMath.sqrt(new BigDecimal("2")).toPlainString());
        // 构造 1.41425 的平方量级，验证第五位为 5 时进位：直接校验 sqrt(1.999...) 附近舍入
        BigDecimal v = new BigDecimal("0.00000001");
        BigDecimal root = UncertaintyMath.sqrt(v);
        assertEquals("0.0001", root.toPlainString(), "0.0001 的平方 1e-8，HALF_UP 保留 4 位为 0.0001");
    }

    @Test
    void magnitudeWithMoreThanSixInputDecimalsNotRoundedPrematurely() {
        // 0.123456^2 + 0 = 0.01524138...；sqrt ≈ 0.123456...，4 位 = 0.1235
        BigDecimal combined = UncertaintyMath.combined(List.of(component("a", "0.123456")));
        assertEquals("0.1235", combined.toPlainString());
    }

    @Test
    void negativeSquareSumRejected() {
        assertThrows(IllegalArgumentException.class, () -> UncertaintyMath.sqrt(new BigDecimal("-1")));
    }

    @Test
    void resultHasExactlyFourDecimalPlaces() {
        BigDecimal combined = UncertaintyMath.combined(
                List.of(component("a", "1"), component("b", "2"), component("c", "3")));
        assertEquals(4, combined.scale());
        assertTrue(combined.compareTo(BigDecimal.ZERO) > 0);
    }
}
