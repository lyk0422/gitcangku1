package com.example.starter.batch;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合成序贯似然比精确分数规则单元测试：阈值边界用整数交叉相乘判定，展示小数不参与判定。
 */
class LikelihoodTest {

    @Test
    void initialRatioIsOne() {
        assertEquals(1L, Likelihood.initialNumerator());
        assertEquals(1L, Likelihood.initialDenominator());
        assertEquals(0, BigDecimal.ONE.compareTo(
                Likelihood.decimal(Likelihood.initialNumerator(), Likelihood.initialDenominator())));
    }

    @Test
    void goodMultipliesNumeratorByThree_badMultipliesDenominatorByThree() {
        long num = Likelihood.initialNumerator();
        long den = Likelihood.initialDenominator();
        num = Likelihood.nextNumerator(num, SequentialSample.GOOD);
        den = Likelihood.nextDenominator(den, SequentialSample.GOOD);
        assertEquals(3L, num);
        assertEquals(1L, den);
        // GOOD 一次后比值 3，未达 9，继续
        assertEquals(SequentialConclusion.CONTINUE,
                Likelihood.conclude(num, den, 1, 10));

        num = Likelihood.nextNumerator(num, SequentialSample.GOOD);
        den = Likelihood.nextDenominator(den, SequentialSample.GOOD);
        assertEquals(9L, num);
        assertEquals(1L, den);
        // 比值恰好 9（含等于）→ PASS
        assertEquals(SequentialConclusion.PASS, Likelihood.conclude(num, den, 2, 10));
        assertTrue(Likelihood.isTerminal(SequentialConclusion.PASS));
    }

    @Test
    void twoBadsReachOneNinth_andFail() {
        long num = Likelihood.initialNumerator();
        long den = Likelihood.initialDenominator();
        num = Likelihood.nextNumerator(num, SequentialSample.BAD);
        den = Likelihood.nextDenominator(den, SequentialSample.BAD);
        assertEquals(1L, num);
        assertEquals(3L, den);
        assertEquals(SequentialConclusion.CONTINUE, Likelihood.conclude(num, den, 1, 10));

        num = Likelihood.nextNumerator(num, SequentialSample.BAD);
        den = Likelihood.nextDenominator(den, SequentialSample.BAD);
        assertEquals(1L, num);
        assertEquals(9L, den);
        // 比值恰好 1/9（含等于）→ FAIL
        assertEquals(SequentialConclusion.FAIL, Likelihood.conclude(num, den, 2, 10));
        assertTrue(Likelihood.isTerminal(SequentialConclusion.FAIL));
    }

    @Test
    void alternatingGoodAndBadReturnsExactlyToOne_andContinues() {
        long num = Likelihood.initialNumerator();
        long den = Likelihood.initialDenominator();
        for (int i = 1; i <= 8; i++) {
            SequentialSample sample = (i % 2 == 1) ? SequentialSample.GOOD : SequentialSample.BAD;
            num = Likelihood.nextNumerator(num, sample);
            den = Likelihood.nextDenominator(den, sample);
            assertEquals(SequentialConclusion.CONTINUE, Likelihood.conclude(num, den, i, 10));
        }
        // 4 个 GOOD 与 4 个 BAD 后精确回到 1/1，而不是浮点近似值
        assertEquals(81L, num);
        assertEquals(81L, den);
        assertEquals(0, BigDecimal.ONE.compareTo(Likelihood.decimal(num, den)));
        assertFalse(Likelihood.isTerminal(SequentialConclusion.CONTINUE));
    }

    @Test
    void tenResultsWithoutThreshold_isInconclusive() {
        long num = Likelihood.initialNumerator();
        long den = Likelihood.initialDenominator();
        // 5 GOOD + 5 BAD 任意顺序最终为 1，始终不触阈值；第 10 次落定 INCONCLUSIVE
        for (int i = 1; i <= 10; i++) {
            SequentialSample sample = (i % 2 == 1) ? SequentialSample.GOOD : SequentialSample.BAD;
            num = Likelihood.nextNumerator(num, sample);
            den = Likelihood.nextDenominator(den, sample);
        }
        assertEquals(243L, num);
        assertEquals(243L, den);
        SequentialConclusion conclusion = Likelihood.conclude(num, den, 10, 10);
        assertEquals(SequentialConclusion.INCONCLUSIVE, conclusion);
        assertTrue(Likelihood.isTerminal(conclusion));
        // 第 9 次时尚未到上限，仍 CONTINUE
        assertEquals(SequentialConclusion.CONTINUE, Likelihood.conclude(81L, 81L, 9, 10));
    }

    @Test
    void decimalIsDisplayOnlyAndStable() {
        assertEquals(new BigDecimal("0.333333"), Likelihood.decimal(1L, 3L));
        assertEquals(new BigDecimal("9.000000"), Likelihood.decimal(9L, 1L));
        // 3^10 以内的整数分数均可精确表示，无溢出
        assertEquals(new BigDecimal("59049.000000"), Likelihood.decimal(59049L, 1L));
    }
}
