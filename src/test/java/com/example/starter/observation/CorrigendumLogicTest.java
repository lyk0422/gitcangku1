package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 更正附录纯领域逻辑单元测试：差异规范化、有效值叠加、版本链重算与数值比较，不启动 Spring、不依赖数据库。
 */
class CorrigendumLogicTest {

    private FieldChangeRequest req(String field, String oldValue, String newValue) {
        return new FieldChangeRequest(field, oldValue, newValue);
    }

    private CorrigendumRecord record(int version, int previous, String field,
                                     String oldValue, String newValue) {
        return new CorrigendumRecord(
                "corr-" + version, "obs-1", "req-" + version, version, previous, 3,
                List.of(new FieldChange(field, oldValue, newValue)),
                "recorder", Instant.parse("2026-09-30T0" + version + ":00:00Z"));
    }

    @Test
    void normalizeChangesOrdersByFixedFieldOrder() {
        List<FieldChange> changes = CorrigendumService.normalizeChanges(List.of(
                req("note", "旧备注", "新备注"),
                req("reading", "10", "11"),
                req("location", "站点A", "站点B")));

        assertThat(changes).extracting(FieldChange::field)
                .containsExactly("location", "reading", "note");
    }

    @Test
    void normalizeChangesRejectsBlankNewValueAsFieldDeletion() {
        assertThatThrownBy(() -> CorrigendumService.normalizeChanges(
                List.of(req("note", "旧备注", "  "))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400));
    }

    @Test
    void normalizeChangesRejectsBlankOldValue() {
        assertThatThrownBy(() -> CorrigendumService.normalizeChanges(
                List.of(req("location", "", "站点B"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400));
    }

    @Test
    void normalizeChangesRejectsDuplicateField() {
        assertThatThrownBy(() -> CorrigendumService.normalizeChanges(List.of(
                req("reading", "10", "11"),
                req("reading", "11", "12"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400));
    }

    @Test
    void normalizeChangesRejectsReadingWithMoreThanThreeFractionDigits() {
        assertThatThrownBy(() -> CorrigendumService.normalizeChanges(
                List.of(req("reading", "10", "11.1234"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400));
    }

    @Test
    void applyChangesOnlyTouchesListedFields() {
        EffectiveValues values = new EffectiveValues("站点A", "10", "备注");
        EffectiveValues next = CorrigendumService.applyChanges(values,
                List.of(new FieldChange("reading", "10", "11")));

        assertThat(next).isEqualTo(new EffectiveValues("站点A", "11", "备注"));
    }

    @Test
    void applyChangesAccumulatesAcrossMultipleFields() {
        EffectiveValues values = new EffectiveValues("站点A", "10", "备注");
        EffectiveValues next = CorrigendumService.applyChanges(values, List.of(
                new FieldChange("location", "站点A", "站点B"),
                new FieldChange("note", "备注", "更正备注")));

        assertThat(next).isEqualTo(new EffectiveValues("站点B", "10", "更正备注"));
    }

    @Test
    void effectiveAtReplaysEachVersionWithoutFutureDrift() {
        EffectiveValues base = new EffectiveValues("站点A", "10", "备注");
        List<CorrigendumRecord> chain = List.of(
                record(1, 0, "reading", "10", "11"),
                record(2, 1, "reading", "11", "12"),
                record(3, 2, "note", "备注", "终版备注"));

        assertThat(CorrigendumService.effectiveAt(base, chain, 0))
                .isEqualTo(new EffectiveValues("站点A", "10", "备注"));
        assertThat(CorrigendumService.effectiveAt(base, chain, 1))
                .isEqualTo(new EffectiveValues("站点A", "11", "备注"));
        assertThat(CorrigendumService.effectiveAt(base, chain, 2))
                .isEqualTo(new EffectiveValues("站点A", "12", "备注"));
        assertThat(CorrigendumService.effectiveAt(base, chain, 3))
                .isEqualTo(new EffectiveValues("站点A", "12", "终版备注"));
    }

    @Test
    void readingValuesComparedNumerically() {
        assertThat(CorrigendumService.valueEquals("reading", "10", "10.000")).isTrue();
        assertThat(CorrigendumService.valueEquals("reading", "10", "11")).isFalse();
        assertThat(CorrigendumService.valueEquals("location", "站点A", "站点A")).isTrue();
        assertThat(CorrigendumService.valueEquals("note", "备注", "备注2")).isFalse();
    }
}
