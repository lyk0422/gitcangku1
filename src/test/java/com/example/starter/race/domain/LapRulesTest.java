package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LapRules} 的纯逻辑单元测试：同圈与跨圈严格递增、乱序收件、非负耗时边界。
 */
class LapRulesTest {

    /** 测试用圈次记录视图。 */
    private record Pass(String bib, int lapNo, LapPoint point, long elapsedMillis)
            implements LapPassView {
    }

    private static Pass pass(int lapNo, LapPoint point, long elapsedMillis) {
        return new Pass("a", lapNo, point, elapsedMillis);
    }

    @Test
    void 同圈必须严格递增() {
        List<Pass> existing = List.of(pass(1, LapPoint.A, 100));
        assertThat(LapRules.validate(existing, 1, LapPoint.B, 200)).isNull();
        assertThat(LapRules.validate(existing, 1, LapPoint.B, 100)).contains("严格大于");
        assertThat(LapRules.validate(existing, 1, LapPoint.B, 50)).contains("严格大于");
    }

    @Test
    void 下一圈A必须严格大于上一圈FINISH() {
        List<Pass> existing = List.of(pass(1, LapPoint.FINISH, 300));
        assertThat(LapRules.validate(existing, 2, LapPoint.A, 301)).isNull();
        assertThat(LapRules.validate(existing, 2, LapPoint.A, 300)).contains("严格大于");
        assertThat(LapRules.validate(existing, 2, LapPoint.A, 299)).contains("严格大于");
    }

    @Test
    void 乱序收件时新记录必须小于已存在的后序点() {
        // 第2圈A已先到，补第1圈FINISH：必须小于第2圈A
        List<Pass> existing = List.of(pass(2, LapPoint.A, 400));
        assertThat(LapRules.validate(existing, 1, LapPoint.FINISH, 350)).isNull();
        assertThat(LapRules.validate(existing, 1, LapPoint.FINISH, 400)).contains("严格小于");
        assertThat(LapRules.validate(existing, 1, LapPoint.FINISH, 450)).contains("严格小于");
    }

    @Test
    void 前序后序同时存在时取最近邻点校验() {
        List<Pass> existing = List.of(
                pass(1, LapPoint.A, 100),
                pass(1, LapPoint.FINISH, 300));
        // B 落在 (100, 300) 之间即可，不要求与更远的点比较
        assertThat(LapRules.validate(existing, 1, LapPoint.B, 200)).isNull();
        assertThat(LapRules.validate(existing, 1, LapPoint.B, 100)).contains("严格大于");
        assertThat(LapRules.validate(existing, 1, LapPoint.B, 300)).contains("严格小于");
    }

    @Test
    void 耗时允许非负整数含零() {
        assertThat(LapRules.validate(List.of(), 1, LapPoint.A, 0)).isNull();
        assertThat(LapRules.validate(List.of(), 1, LapPoint.A, -1)).contains("非负");
        assertThat(LapRules.validate(List.of(), 1, LapPoint.A, 86_400_000L)).isNull();
        assertThat(LapRules.validate(List.of(), 1, LapPoint.A, 86_400_001L)).contains("非负");
    }
}