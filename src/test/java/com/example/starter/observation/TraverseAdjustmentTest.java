package com.example.starter.observation;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 闭合导线闭合差与逐边修正纯计算逻辑的单元测试（不启动应用上下文）。
 */
class TraverseAdjustmentTest {

    private static TraverseAdjustment.LegInput leg(String dx, String dy, String w) {
        return new TraverseAdjustment.LegInput(dx, dy, w);
    }

    @Test
    void syntheticTraverseAdjustsToExactClosure() {
        // 题干合成数据：位移 (1,0)、(0,1)、(-0.94,-1)，闭合差 (0.06, 0)
        TraverseAdjustment.Adjustment adjustment = TraverseAdjustment.compute(List.of(
                leg("1", "0", "100"),
                leg("0", "1", "200"),
                leg("-0.94", "-1", "300")));

        assertThat(adjustment.closureDx().toPlainString()).isEqualTo("0.06");
        assertThat(adjustment.closureDy().toPlainString()).isEqualTo("0");
        assertThat(adjustment.totalWeight().toPlainString()).isEqualTo("600");

        // 前 n-1 边：dx_i - 闭合差 * w_i / 总w，HALF_UP 保留 6 位
        assertThat(adjustment.legs()).extracting(TraverseAdjustment.AdjustedLeg::adjustedDx)
                .containsExactly("0.990000", "-0.020000", "-0.970000");
        assertThat(adjustment.legs()).extracting(TraverseAdjustment.AdjustedLeg::adjustedDy)
                .containsExactly("0.000000", "1.000000", "-1.000000");

        // 末边为前边之和的相反数，修正和精确为 0
        BigDecimal sumDx = adjustment.legs().stream()
                .map(l -> new BigDecimal(l.adjustedDx())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumDy = adjustment.legs().stream()
                .map(l -> new BigDecimal(l.adjustedDy())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDx).isEqualByComparingTo("0");
        assertThat(sumDy).isEqualByComparingTo("0");
    }

    @Test
    void closureErrorBeyondLimitRejected() {
        // 题干拒绝用例：末边 (-0.899,-1)，闭合差 (0.101, 0) 越限
        assertThatThrownBy(() -> TraverseAdjustment.compute(List.of(
                leg("1", "0", "100"),
                leg("0", "1", "200"),
                leg("-0.899", "-1", "300"))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void closureErrorOnDyBeyondLimitRejected() {
        // dy 求和 0.101 越限
        assertThatThrownBy(() -> TraverseAdjustment.compute(List.of(
                leg("0", "1", "100"),
                leg("0", "0", "200"),
                leg("0", "-0.899", "300"))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void closureErrorExactlyAtLimitAccepted() {
        // 绝对值恰好 0.1 米：不超过上限，允许生成
        TraverseAdjustment.Adjustment adjustment = TraverseAdjustment.compute(List.of(
                leg("1", "0", "100"),
                leg("0", "0", "200"),
                leg("-0.9", "0", "300")));
        assertThat(adjustment.closureDx().toPlainString()).isEqualTo("0.1");
        assertThat(adjustment.legs()).extracting(TraverseAdjustment.AdjustedLeg::adjustedDx)
                .containsExactly("0.983333", "-0.033333", "-0.950000");
        BigDecimal sumDx = adjustment.legs().stream()
                .map(l -> new BigDecimal(l.adjustedDx())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDx).isEqualByComparingTo("0");
    }

    @Test
    void adjustmentDistributedByWeightWithHalfUpRounding() {
        // 不等权：闭合差按 w_i/总w 比例分配，验证 HALF_UP 六位舍入
        TraverseAdjustment.Adjustment adjustment = TraverseAdjustment.compute(List.of(
                leg("0", "0", "1"),
                leg("0", "0", "2"),
                leg("0.07", "0", "3"),
                leg("0", "0", "4")));
        // 总w=10，闭合差 0.07：各边修正量 0.007、0.014、0.021，末边取反
        assertThat(adjustment.legs()).extracting(TraverseAdjustment.AdjustedLeg::adjustedDx)
                .containsExactly("-0.007000", "-0.014000", "0.049000", "-0.028000");
        BigDecimal sumDx = adjustment.legs().stream()
                .map(l -> new BigDecimal(l.adjustedDx())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDx).isEqualByComparingTo("0");
    }
}
