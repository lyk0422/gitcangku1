package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 更正附录版本链有效值计算的纯单元测试：V0 恒为原始值，逐版叠加差异，旧版本视角不随新附录漂移。
 */
class CorrigendumEffectiveValuesTest {

    private static final Instant T1 = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-01T09:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-01T10:00:00Z");

    private final ObservationSnapshot snapshot =
            new ObservationSnapshot("obs-c", 1, "站点A", "10", "初始备注", false);

    private CorrigendumAppendix appendix(int version, Integer previous, String field,
                                        String oldValue, String newValue, Instant at) {
        return new CorrigendumAppendix("ap-" + version, "obs-c", "req-" + version, version,
                previous, "res-c", 1, field, oldValue, newValue, "recorder-" + version, at);
    }

    @Test
    void versionZeroAlwaysReturnsOriginalSnapshotValues() {
        EffectiveValues v0 = CorrigendumService.effectiveAt(snapshot, List.of(), 0);
        assertThat(v0).isEqualTo(new EffectiveValues("站点A", "10", "初始备注"));

        // 即使已有 V1..V3，V0 视角恒为原值，不随新附录漂移
        List<CorrigendumAppendix> chain = List.of(
                appendix(1, null, "reading", "10", "11", T1),
                appendix(2, 1, "location", "站点A", "站点B", T2),
                appendix(3, 2, "reading", "11", "12", T3));
        assertThat(CorrigendumService.effectiveAt(snapshot, chain, 0))
                .isEqualTo(new EffectiveValues("站点A", "10", "初始备注"));
    }

    @Test
    void versionOneAppliesOnlyFirstCorrection() {
        // 题干场景：簇 C 原值 10，附录 V1 更正为 11；V0 仍为 10
        List<CorrigendumAppendix> chain = List.of(
                appendix(1, null, "reading", "10", "11", T1));
        assertThat(CorrigendumService.effectiveAt(snapshot, chain, 1))
                .isEqualTo(new EffectiveValues("站点A", "11", "初始备注"));
    }

    @Test
    void laterVersionsStackAndOldVersionsDoNotDrift() {
        List<CorrigendumAppendix> chain = List.of(
                appendix(1, null, "reading", "10", "11", T1),
                appendix(2, 1, "location", "站点A", "站点B", T2),
                appendix(3, 2, "note", "初始备注", "复核备注", T3));

        // 各版本视角互不影响：V1 只有读数变化，V2 叠加地点，V3 再叠加备注
        assertThat(CorrigendumService.effectiveAt(snapshot, chain, 1))
                .isEqualTo(new EffectiveValues("站点A", "11", "初始备注"));
        assertThat(CorrigendumService.effectiveAt(snapshot, chain, 2))
                .isEqualTo(new EffectiveValues("站点B", "11", "初始备注"));
        assertThat(CorrigendumService.effectiveAt(snapshot, chain, 3))
                .isEqualTo(new EffectiveValues("站点B", "11", "复核备注"));
    }

    @Test
    void repeatedCorrectionOnSameFieldTakesLatestAtHeadButKeepsHistoricalValue() {
        List<CorrigendumAppendix> chain = List.of(
                appendix(1, null, "reading", "10", "11", T1),
                appendix(2, 1, "reading", "11", "12.5", T2),
                appendix(3, 2, "reading", "12.5", "13", T3));

        assertThat(CorrigendumService.effectiveAt(snapshot, chain, 0).reading()).isEqualTo("10");
        assertThat(CorrigendumService.effectiveAt(snapshot, chain, 1).reading()).isEqualTo("11");
        assertThat(CorrigendumService.effectiveAt(snapshot, chain, 2).reading()).isEqualTo("12.5");
        assertThat(CorrigendumService.effectiveAt(snapshot, chain, 3).reading()).isEqualTo("13");
    }

    @Test
    void versionBeyondChainIsRejected() {
        List<CorrigendumAppendix> chain = List.of(
                appendix(1, null, "reading", "10", "11", T1));
        assertThatThrownBy(() -> CorrigendumService.effectiveAt(snapshot, chain, 2))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(404));
        assertThatThrownBy(() -> CorrigendumService.effectiveAt(snapshot, chain, -1))
                .isInstanceOf(ApiException.class);
    }
}
