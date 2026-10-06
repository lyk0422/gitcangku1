package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三源计时共识纯逻辑测试：齐全性、离散上限与中位值裁定，不涉及数据库。
 */
class SensorQuorumTest {

    private record Reading(String sensorId, long elapsedMillis, int sensorRevision)
            implements SensorQuorum.SensorReadingView {
    }

    private static Reading reading(String sensorId, long elapsedMillis) {
        return new Reading(sensorId, elapsedMillis, 1);
    }

    @Test
    void 三条读数达成共识时取中位值() {
        // 题干合成示例：100、110、120 裁定为 110
        SensorQuorum.QuorumResult result = SensorQuorum.adjudicate(List.of(
                reading("s1", 100L), reading("s2", 110L), reading("s3", 120L)));
        assertThat(result.accepted()).isTrue();
        assertThat(result.medianMillis()).isEqualTo(110L);
    }

    @Test
    void 乱序输入仍取排序后的中位值() {
        SensorQuorum.QuorumResult result = SensorQuorum.adjudicate(List.of(
                reading("s3", 120L), reading("s1", 100L), reading("s2", 110L)));
        assertThat(result.accepted()).isTrue();
        assertThat(result.medianMillis()).isEqualTo(110L);
    }

    @Test
    void 离散恰好20毫秒时达成共识() {
        SensorQuorum.QuorumResult result = SensorQuorum.adjudicate(List.of(
                reading("s1", 100L), reading("s2", 105L), reading("s3", 120L)));
        assertThat(result.accepted()).isTrue();
        assertThat(result.medianMillis()).isEqualTo(105L);
    }

    @Test
    void 离散超过20毫秒时拒绝() {
        // 题干示例：120 修订为 131 后，100/110/131 离散31，重新裁定拒绝
        SensorQuorum.QuorumResult result = SensorQuorum.adjudicate(List.of(
                reading("s1", 100L), reading("s2", 110L), reading("s3", 131L)));
        assertThat(result.accepted()).isFalse();
        assertThat(result.violation()).contains("离散");
        assertThat(result.medianMillis()).isNull();
    }

    @Test
    void 读数不齐全或超量时拒绝() {
        SensorQuorum.QuorumResult two = SensorQuorum.adjudicate(List.of(
                reading("s1", 100L), reading("s2", 110L)));
        assertThat(two.accepted()).isFalse();
        assertThat(two.violation()).contains("3");

        SensorQuorum.QuorumResult four = SensorQuorum.adjudicate(List.of(
                reading("s1", 100L), reading("s2", 101L),
                reading("s3", 102L), reading("s4", 103L)));
        assertThat(four.accepted()).isFalse();
    }

    @Test
    void 重复传感器读数拒绝() {
        SensorQuorum.QuorumResult result = SensorQuorum.adjudicate(List.of(
                reading("s1", 100L), reading("s1", 105L), reading("s2", 110L)));
        assertThat(result.accepted()).isFalse();
        assertThat(result.violation()).contains("重复");
    }

    @Test
    void 读数越界时拒绝() {
        SensorQuorum.QuorumResult zero = SensorQuorum.adjudicate(List.of(
                reading("s1", 0L), reading("s2", 100L), reading("s3", 110L)));
        assertThat(zero.accepted()).isFalse();

        SensorQuorum.QuorumResult tooLarge = SensorQuorum.adjudicate(List.of(
                reading("s1", 100L), reading("s2", 110L), reading("s3", 86_400_001L)));
        assertThat(tooLarge.accepted()).isFalse();
    }
}
