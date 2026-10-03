package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.domain.PressureSample;
import com.example.starter.service.SwitchConditionEvaluator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 切换条件纯函数评估的单元测试（不依赖数据库）。
 */
class SwitchConditionEvaluatorTest {

    private static final Instant BASE = Instant.parse("2026-10-04T08:00:00Z");

    private static List<PressureSample> samples(String... pressuresAndMinutes) {
        List<PressureSample> samples = new ArrayList<>();
        for (int i = 0; i < pressuresAndMinutes.length; i += 2) {
            PressureSample sample = new PressureSample();
            sample.setId((long) (i / 2 + 1));
            sample.setPressureMpa(new BigDecimal(pressuresAndMinutes[i]));
            sample.setSampledAt(BASE.minus(Long.parseLong(pressuresAndMinutes[i + 1]), ChronoUnit.MINUTES));
            samples.add(sample);
        }
        return samples;
    }

    @Test
    void fourInRangeSamplesPass() {
        Optional<String> reason = SwitchConditionEvaluator.evaluate(
                samples("10.0", "12", "10.5", "9", "11.0", "6", "10.2", "3"));
        assertThat(reason).isEmpty();
    }

    @Test
    void boundaryValuesEightAndTwelvePass() {
        Optional<String> reason = SwitchConditionEvaluator.evaluate(
                samples("8.0", "12", "12.0", "9", "8.0", "6", "12.0", "3"));
        assertThat(reason).isEmpty();
    }

    @Test
    void singleOutOfRangeReadingFails() {
        Optional<String> reason = SwitchConditionEvaluator.evaluate(
                samples("10.0", "12", "12.1", "9", "10.5", "6", "11.0", "3"));
        assertThat(reason).hasValueSatisfying(message -> assertThat(message).contains("12.1"));
    }

    @Test
    void fewerThanFourSamplesFails() {
        Optional<String> reason = SwitchConditionEvaluator.evaluate(
                samples("10.0", "12", "10.5", "9", "11.0", "6"));
        assertThat(reason).hasValueSatisfying(message -> assertThat(message).contains("不足"));
    }

    @Test
    void gapOfExactlyFiveMinutesPasses() {
        Optional<String> reason = SwitchConditionEvaluator.evaluate(
                samples("10.0", "15", "10.5", "10", "11.0", "5", "10.2", "0"));
        assertThat(reason).isEmpty();
    }

    @Test
    void gapOverFiveMinutesFails() {
        Optional<String> reason = SwitchConditionEvaluator.evaluate(
                samples("10.0", "14", "10.5", "10", "11.0", "4", "10.2", "0"));
        assertThat(reason).hasValueSatisfying(message -> assertThat(message).contains("间隔"));
    }
}