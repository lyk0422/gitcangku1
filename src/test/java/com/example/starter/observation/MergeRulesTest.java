package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三方合并纯规则单元测试：候选未改/当前未改/双方同值/冲突，读数按数值比较。
 */
class MergeRulesTest {

    @Test
    void candidateUnchangedKeepsCurrent() {
        List<String> conflicts = new ArrayList<>();
        String merged = MergeRules.mergeField("A", "B", "A", false, "location", conflicts);
        assertThat(merged).isEqualTo("B");
        assertThat(conflicts).isEmpty();
    }

    @Test
    void currentUnchangedAcceptsCandidate() {
        List<String> conflicts = new ArrayList<>();
        String merged = MergeRules.mergeField("A", "A", "B", false, "location", conflicts);
        assertThat(merged).isEqualTo("B");
        assertThat(conflicts).isEmpty();
    }

    @Test
    void bothSidesChangedToSameValueAccepts() {
        List<String> conflicts = new ArrayList<>();
        String merged = MergeRules.mergeField("A", "B", "B", false, "location", conflicts);
        assertThat(merged).isEqualTo("B");
        assertThat(conflicts).isEmpty();
    }

    @Test
    void divergentChangesConflictAndKeepCurrent() {
        List<String> conflicts = new ArrayList<>();
        String merged = MergeRules.mergeField("A", "B", "C", false, "location", conflicts);
        assertThat(merged).isEqualTo("B");
        assertThat(conflicts).containsExactly("location");
    }

    @Test
    void readingComparedNumericallyAcrossRepresentations() {
        assertThat(MergeRules.readingEquals("1.0", "1.00")).isTrue();
        assertThat(MergeRules.readingEquals("1.0", "1.1")).isFalse();
        // 候选 1.00 与基线 1.0 数值相同：视为未改，保留当前 2.0
        List<String> conflicts = new ArrayList<>();
        String merged = MergeRules.mergeField("1.0", "2.0", "1.00", true, "reading", conflicts);
        assertThat(merged).isEqualTo("2.0");
        assertThat(conflicts).isEmpty();
        // 双方各自改成不同数值：冲突
        List<String> conflicts2 = new ArrayList<>();
        String merged2 = MergeRules.mergeField("1.0", "2.0", "3.0", true, "reading", conflicts2);
        assertThat(merged2).isEqualTo("2.0");
        assertThat(conflicts2).containsExactly("reading");
    }

    @Test
    void fieldConflictsMatrix() {
        assertThat(MergeRules.fieldConflicts("A", "B", "A", false)).isFalse();
        assertThat(MergeRules.fieldConflicts("A", "A", "B", false)).isFalse();
        assertThat(MergeRules.fieldConflicts("A", "B", "B", false)).isFalse();
        assertThat(MergeRules.fieldConflicts("A", "B", "C", false)).isTrue();
    }
}
