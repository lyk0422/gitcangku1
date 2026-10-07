package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PursuitMath} 与 {@link Rational} 的纯单元测试：
 * 合成样例、精确有理数、公共区间边界、禁止外推。
 */
class PursuitMathTest {

    private static List<PursuitMath.SamplePoint> samples(long... elapsedThenProgress) {
        if (elapsedThenProgress.length % 2 != 0) {
            throw new IllegalArgumentException("参数必须为 (elapsedMs, progressMm) 对");
        }
        java.util.List<PursuitMath.SamplePoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < elapsedThenProgress.length; i += 2) {
            points.add(new PursuitMath.SamplePoint(
                    elapsedThenProgress[i], elapsedThenProgress[i + 1]));
        }
        return points;
    }

    @Test
    void 合成样例_A起始0前进3000_B起始1000前进2000_最早交会10秒() {
        Optional<Rational> catchTime = PursuitMath.earliestCatchTime(
                samples(0, 0, 10_000, 3_000), 0,
                samples(0, 0, 10_000, 2_000), 1_000);
        assertThat(catchTime).isPresent();
        assertThat(catchTime.get().num()).isEqualTo(BigInteger.valueOf(10_000));
        assertThat(catchTime.get().den()).isEqualTo(BigInteger.ONE);
    }

    @Test
    void 合成样例_A仅前进2999_公共区间内未交会() {
        Optional<Rational> catchTime = PursuitMath.earliestCatchTime(
                samples(0, 0, 10_000, 2_999), 0,
                samples(0, 0, 10_000, 2_000), 1_000);
        assertThat(catchTime).isEmpty();
    }

    @Test
    void 交会时刻为分数时保存精确有理数不取整() {
        // 追赶者速度1mm/ms，被追者速度1/3mm/ms、起始领先1mm：t - 1 - t/3 = 0 -> t = 3/2。
        Optional<Rational> catchTime = PursuitMath.earliestCatchTime(
                samples(0, 0, 300, 300), 0,
                samples(0, 0, 300, 100), 1);
        assertThat(catchTime).isPresent();
        assertThat(catchTime.get().num()).isEqualTo(BigInteger.valueOf(3));
        assertThat(catchTime.get().den()).isEqualTo(BigInteger.valueOf(2));
    }

    @Test
    void 交会恰好发生在公共区间末端时计入() {
        // 追赶者速度2/3，被追者速度1/2、起始领先500：t/6 = 500 -> t = 3000 = 公共区间末端。
        Optional<Rational> catchTime = PursuitMath.earliestCatchTime(
                samples(0, 0, 3_000, 2_000), 0,
                samples(0, 0, 4_000, 2_000), 500);
        assertThat(catchTime).isPresent();
        assertThat(catchTime.get().num()).isEqualTo(BigInteger.valueOf(3_000));
        assertThat(catchTime.get().den()).isEqualTo(BigInteger.ONE);
    }

    @Test
    void 禁止向公共时间区间之外外推() {
        // 追赶者数据止于1000ms；交会点在3000ms，超出公共区间 [0,1000]，不得外推。
        Optional<Rational> catchTime = PursuitMath.earliestCatchTime(
                samples(0, 0, 1_000, 1_000), 0,
                samples(0, 0, 2_000, 1_000), 1_500);
        assertThat(catchTime).isEmpty();
    }

    @Test
    void 多段轨迹在折点间求最早交会() {
        // 追赶者前1000ms原地不动再冲刺3mm/ms；被追者匀速1/2mm/ms、起始领先500。
        // 窗口[1000,2000]内：3(t-1000) - 500 - t/2 = 0 -> t = 1400。
        Optional<Rational> catchTime = PursuitMath.earliestCatchTime(
                samples(0, 0, 1_000, 0, 2_000, 3_000), 0,
                samples(0, 0, 2_000, 1_000), 500);
        assertThat(catchTime).isPresent();
        assertThat(catchTime.get().num()).isEqualTo(BigInteger.valueOf(1_400));
        assertThat(catchTime.get().den()).isEqualTo(BigInteger.ONE);
    }

    @Test
    void 被追者折点落在追赶者段内时按双方折点切分窗口() {
        // 追赶者匀速2mm/ms；被追者起始领先100，前500ms静止、之后1mm/ms。
        // 窗口[0,500]：2t - 100 = 0 -> t = 50。
        Optional<Rational> catchTime = PursuitMath.earliestCatchTime(
                samples(0, 0, 1_000, 2_000), 0,
                samples(0, 0, 500, 0, 1_000, 500), 100);
        assertThat(catchTime).isPresent();
        assertThat(catchTime.get().num()).isEqualTo(BigInteger.valueOf(50));
        assertThat(catchTime.get().den()).isEqualTo(BigInteger.ONE);
    }

    @Test
    void 单点轨迹无法交会时返回空() {
        Optional<Rational> catchTime = PursuitMath.earliestCatchTime(
                samples(0, 0), 0,
                samples(0, 0, 1_000, 1_000), 100);
        assertThat(catchTime).isEmpty();
    }

    @Test
    void 位置差始终为负时返回空() {
        Optional<Rational> catchTime = PursuitMath.earliestCatchTime(
                samples(0, 0, 1_000, 100), 0,
                samples(0, 0, 1_000, 2_000), 1_000);
        assertThat(catchTime).isEmpty();
    }

    @Test
    void 有理数构造时约分并规范分母符号() {
        Rational half = Rational.of(BigInteger.valueOf(2), BigInteger.valueOf(4));
        assertThat(half.num()).isEqualTo(BigInteger.ONE);
        assertThat(half.den()).isEqualTo(BigInteger.valueOf(2));

        Rational negative = Rational.of(BigInteger.valueOf(3), BigInteger.valueOf(-6));
        assertThat(negative.num()).isEqualTo(BigInteger.valueOf(-1));
        assertThat(negative.den()).isEqualTo(BigInteger.valueOf(2));

        assertThatThrownBy(() -> Rational.of(BigInteger.ONE, BigInteger.ZERO))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void 有理数四则运算保持精确() {
        Rational third = Rational.of(BigInteger.ONE, BigInteger.valueOf(3));
        Rational sixth = Rational.of(BigInteger.ONE, BigInteger.valueOf(6));
        Rational sum = third.add(sixth);
        assertThat(sum.num()).isEqualTo(BigInteger.ONE);
        assertThat(sum.den()).isEqualTo(BigInteger.valueOf(2));
        assertThat(third.subtract(sixth)).isEqualTo(sixth);
        assertThat(third.multiply(sixth).num()).isEqualTo(BigInteger.ONE);
        assertThat(third.multiply(sixth).den()).isEqualTo(BigInteger.valueOf(18));
        assertThat(third.divide(sixth).num()).isEqualTo(BigInteger.valueOf(2));
        assertThat(third.compareTo(sixth)).isPositive();
    }
}
