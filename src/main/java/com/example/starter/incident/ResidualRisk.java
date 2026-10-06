package com.example.starter.incident;

import java.math.BigInteger;
import java.util.List;

/**
 * 残余风险精确有理数核算：残余 = R × ∏(1 - p/q)，以规范化分数（已约分）表示，
 * 比较全程使用 BigInteger 精确运算，不做任何舍入，也不输出舍入后的数值。
 */
public final class ResidualRisk {

    private ResidualRisk() {
    }

    /** 单个屏障的削减率 p/q（0&lt;=p&lt;=q，q&gt;=1）。 */
    public record Ratio(int p, int q) {
    }

    /** 规范化分数：den &gt; 0，num/den 已按最大公约数约分。 */
    public record Fraction(BigInteger num, BigInteger den) {

        /** 精确分数文本，形如 "20/1"；不输出任何舍入后的小数。 */
        public String asString() {
            return num + "/" + den;
        }
    }

    /**
     * 计算残余分数：R × ∏(q-p) / ∏q，按 gcd 约分。
     * ratios 为当前有效屏障（任务 DONE 且未撤销）的削减率；为空时残余即 R。
     */
    public static Fraction residual(int initialRisk, List<Ratio> ratios) {
        BigInteger num = BigInteger.valueOf(initialRisk);
        BigInteger den = BigInteger.ONE;
        for (Ratio ratio : ratios) {
            num = num.multiply(BigInteger.valueOf(ratio.q() - ratio.p()));
            den = den.multiply(BigInteger.valueOf(ratio.q()));
        }
        BigInteger gcd = num.gcd(den);
        return new Fraction(num.divide(gcd), den.divide(gcd));
    }

    /**
     * 判断残余是否不大于允许上限 L：num/den &lt;= L 当且仅当 num &lt;= L × den。
     */
    public static boolean withinLimit(Fraction residual, long limit) {
        return residual.num().compareTo(BigInteger.valueOf(limit).multiply(residual.den())) <= 0;
    }
}
