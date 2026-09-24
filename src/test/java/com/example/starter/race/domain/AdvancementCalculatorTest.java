package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdvancementCalculator} 的纯逻辑测试：组内 DIRECT、跨组 WILDCARD、
 * 并列跨边界超额、未晋级清单与同名次跳号。
 */
class AdvancementCalculatorTest {

    private AdvancementCalculator.Candidate candidate(
            String bib, String groupCode, long totalTimeMs) {
        return new AdvancementCalculator.Candidate(
                bib, groupCode, totalTimeMs, 0L, totalTimeMs);
    }

    private AdvancementCalculator.Candidate candidateWithPenalty(
            String bib, String groupCode, long finishTimeMs, long penaltyMs) {
        return new AdvancementCalculator.Candidate(
                bib, groupCode, finishTimeMs, penaltyMs, finishTimeMs + penaltyMs);
    }

    @Test
    void 每组前Q名直接晋级_其余全局取前W名补位() {
        List<AdvancementCalculator.Candidate> candidates = List.of(
                candidate("a1", "A", 1000),
                candidate("a2", "A", 1100),
                candidate("a3", "A", 1200),
                candidate("b1", "B", 1050),
                candidate("b2", "B", 1150),
                candidate("b3", "B", 1500));

        AdvancementCalculator.Selection selection =
                AdvancementCalculator.select(List.of("A", "B"), candidates, 1, 1);

        assertThat(selection.direct()).extracting(r -> r.candidate().bib())
                .containsExactly("a1", "b1");
        assertThat(selection.direct()).extracting(AdvancementCalculator.Ranked::rank)
                .containsExactly(1, 1);
        // 未直接晋级者 a2(1100)、b2(1150)、a3(1200)、b3(1500) 全局取最快 a2。
        assertThat(selection.wildcard()).extracting(r -> r.candidate().bib())
                .containsExactly("a2");
        assertThat(selection.wildcard().getFirst().rank()).isEqualTo(1);
        // 未晋级按分组顺序（A 后 B）、组内成绩排列：A 组仅剩 a3。
        assertThat(selection.nonAdvanced()).extracting(r -> r.candidate().bib())
                .containsExactly("a3", "b2", "b3");
        assertThat(selection.overQuotaReasons()).isEmpty();
    }

    @Test
    void 组内第Q名并列时同名次全部纳入并给出超额原因() {
        List<AdvancementCalculator.Candidate> candidates = List.of(
                candidate("a1", "A", 1000),
                candidate("a2", "A", 1000),
                candidate("a3", "A", 1000),
                candidate("b1", "B", 2000),
                candidate("b2", "B", 2100));

        AdvancementCalculator.Selection selection =
                AdvancementCalculator.select(List.of("A", "B"), candidates, 2, 0);

        // A 组前两名次为 1、1、1，三人同名次跨过 Q=2 边界，全部 DIRECT。
        assertThat(selection.direct()).extracting(r -> r.candidate().bib())
                .containsExactly("a1", "a2", "a3", "b1", "b2");
        assertThat(selection.direct()).extracting(AdvancementCalculator.Ranked::rank)
                .containsExactly(1, 1, 1, 1, 2);
        assertThat(selection.overQuotaReasons()).hasSize(1);
        AdvancementCalculator.OverQuota reason = selection.overQuotaReasons().getFirst();
        assertThat(reason.boundary()).isEqualTo(AdvancementEntryType.DIRECT);
        assertThat(reason.groupCode()).isEqualTo("A");
        assertThat(reason.quota()).isEqualTo(2);
        assertThat(reason.tiedTimeMs()).isEqualTo(1000L);
        assertThat(reason.bibs()).containsExactly("a1", "a2", "a3");
    }

    @Test
    void 补位第W名并列时同名次全部纳入() {
        List<AdvancementCalculator.Candidate> candidates = List.of(
                candidate("a1", "A", 1000),
                candidate("a2", "A", 1200),
                candidate("b1", "B", 1000),
                candidate("b2", "B", 1200),
                candidate("c1", "C", 1000),
                candidate("c2", "C", 1200));

        AdvancementCalculator.Selection selection =
                AdvancementCalculator.select(List.of("A", "B", "C"), candidates, 1, 2);

        assertThat(selection.direct()).extracting(r -> r.candidate().bib())
                .containsExactly("a1", "b1", "c1");
        // 补位池 a2、b2、c2 同为1200，W=2 边界被并列跨越，三人全部 WILDCARD。
        assertThat(selection.wildcard()).extracting(r -> r.candidate().bib())
                .containsExactly("a2", "b2", "c2");
        assertThat(selection.wildcard()).extracting(AdvancementCalculator.Ranked::rank)
                .containsExactly(1, 1, 1);
        assertThat(selection.overQuotaReasons()).hasSize(1);
        AdvancementCalculator.OverQuota reason = selection.overQuotaReasons().getFirst();
        assertThat(reason.boundary()).isEqualTo(AdvancementEntryType.WILDCARD);
        assertThat(reason.groupCode()).isNull();
        assertThat(reason.quota()).isEqualTo(2);
        assertThat(reason.bibs()).containsExactly("a2", "b2", "c2");
        assertThat(selection.nonAdvanced()).isEmpty();
    }

    @Test
    void 加时处罚计入排名_有效选手不足组不产生选择() {
        // 纯逻辑层只接收候选人，不足判定由服务层负责；这里验证加时被纳入总耗时。
        List<AdvancementCalculator.Candidate> candidates = List.of(
                candidateWithPenalty("a1", "A", 1000, 500),
                candidate("a2", "A", 1400));

        AdvancementCalculator.Selection selection =
                AdvancementCalculator.select(List.of("A"), candidates, 1, 0);

        // a1 总耗时1500 > a2 的1400，a2 取得 DIRECT。
        assertThat(selection.direct()).extracting(r -> r.candidate().bib())
                .containsExactly("a2");
        assertThat(selection.nonAdvanced()).extracting(r -> r.candidate().bib())
                .containsExactly("a1");
        assertThat(selection.direct().getFirst().candidate().totalTimeMs()).isEqualTo(1400L);
    }

    @Test
    void 补位名额为零时没有补位选手() {
        List<AdvancementCalculator.Candidate> candidates = List.of(
                candidate("a1", "A", 1000),
                candidate("a2", "A", 1100),
                candidate("b1", "B", 1050),
                candidate("b2", "B", 1150));

        AdvancementCalculator.Selection selection =
                AdvancementCalculator.select(List.of("A", "B"), candidates, 1, 0);

        assertThat(selection.wildcard()).isEmpty();
        // 未晋级按分组顺序（A 后 B）、组内成绩排列。
        assertThat(selection.nonAdvanced()).extracting(r -> r.candidate().bib())
                .containsExactly("a2", "b2");
    }
}
