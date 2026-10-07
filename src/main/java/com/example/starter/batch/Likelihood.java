package com.example.starter.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 合成序贯检验似然比的精确分数运算。
 *
 * <p>初始似然比为 1（分子=分母=1）；每个 GOOD 分子乘 3，每个 BAD 分母乘 3，
 * 分数始终以整数分子/分母精确保存，不引入浮点误差。阈值判定只使用整数交叉相乘：
 * 比值 &gt;= 9 即 {@code numerator >= 9 * denominator}；比值 &lt;= 1/9 即
 * {@code 9 * numerator <= denominator}。{@link #decimal(long, long)} 仅用于展示，
 * 严禁参与阈值或状态判定。
 */
public final class Likelihood {

    /**
     * PASS 阈值：似然比大于等于 9。
     */
    public static final long PASS_NUMERATOR = 9L;
    /**
     * FAIL 阈值：似然比小于等于 1/9。
     */
    public static final long FAIL_NUMERATOR = 1L;
    public static final long FAIL_DENOMINATOR = 9L;

    private Likelihood() {
    }

    /**
     * 初始似然比 1 的分子。
     */
    public static long initialNumerator() {
        return 1L;
    }

    /**
     * 初始似然比 1 的分母。
     */
    public static long initialDenominator() {
        return 1L;
    }

    /**
     * 应用一个样本结果后的新分子：GOOD 乘 3，BAD 不变。
     */
    public static long nextNumerator(long numerator, SequentialSample sample) {
        return sample == SequentialSample.GOOD ? numerator * 3L : numerator;
    }

    /**
     * 应用一个样本结果后的新分母：BAD 乘 3，GOOD 不变。
     */
    public static long nextDenominator(long denominator, SequentialSample sample) {
        return sample == SequentialSample.BAD ? denominator * 3L : denominator;
    }

    /**
     * 精确阈值判定：比值 &gt;= 9 返回 PASS；比值 &lt;= 1/9 返回 FAIL；
     * 已提交样本数达到上限 {@code maxResults} 仍未达阈值返回 INCONCLUSIVE；否则 CONTINUE。
     *
     * @param submittedCount 提交本次结果后已落定的样本总数（1～maxResults）
     */
    public static SequentialConclusion conclude(long numerator, long denominator,
                                                int submittedCount, int maxResults) {
        if (numerator >= PASS_NUMERATOR * denominator) {
            return SequentialConclusion.PASS;
        }
        if (FAIL_DENOMINATOR * numerator <= FAIL_NUMERATOR * denominator) {
            return SequentialConclusion.FAIL;
        }
        return submittedCount >= maxResults
                ? SequentialConclusion.INCONCLUSIVE
                : SequentialConclusion.CONTINUE;
    }

    /**
     * 似然比是否已达到终止结论（PASS/FAIL/INCONCLUSIVE）；仅 CONTINUE 允许下一结果。
     */
    public static boolean isTerminal(SequentialConclusion conclusion) {
        return conclusion != SequentialConclusion.CONTINUE;
    }

    /**
     * 仅用于展示的小数值（6 位小数，四舍五入）；调用方不得据此做阈值或状态判定。
     */
    public static BigDecimal decimal(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }
}
