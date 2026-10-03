package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.service.ColorDifference;
import org.junit.jupiter.api.Test;

/**
 * 色差计算单元测试：三维欧氏距离，比较前不四舍五入。
 */
class ColorDifferenceTest {

    @Test
    void syntheticPassingSample() {
        // 合成样例：目标 (50,0,0)，布卷 (51,1,1)，距离约 1.732，可放行
        double deltaE = ColorDifference.deltaE(51, 1, 1, 50, 0, 0);
        assertEquals(Math.sqrt(3), deltaE, 1e-12);
        assertTrue(ColorDifference.qualified(deltaE));
    }

    @Test
    void syntheticRejectedSample() {
        // 合成样例：布卷 (53,0,0)，距离 3.0，拒绝
        double deltaE = ColorDifference.deltaE(53, 0, 0, 50, 0, 0);
        assertEquals(3.0, deltaE, 1e-12);
        assertFalse(ColorDifference.qualified(deltaE));
    }

    @Test
    void boundaryIsQualified() {
        // 恰好 2.0 合格（含边界）
        double deltaE = ColorDifference.deltaE(52, 0, 0, 50, 0, 0);
        assertEquals(2.0, deltaE, 1e-12);
        assertTrue(ColorDifference.qualified(deltaE));
    }

    @Test
    void noRoundingBeforeComparison() {
        // 2.0000000001 不得四舍五入为 2.0，应判不合格
        double deltaE = ColorDifference.deltaE(52.0000000001, 0, 0, 50, 0, 0);
        assertTrue(deltaE > 2.0);
        assertFalse(ColorDifference.qualified(deltaE));
    }

    @Test
    void threeDimensionalDistance() {
        // 三轴联合：sqrt(1+4+4)=3
        double deltaE = ColorDifference.deltaE(51, 2, 2, 50, 0, 0);
        assertEquals(3.0, deltaE, 1e-12);
    }
}