package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 弃用裁决单元测试：左闭右开边界、警告差额与稳定排序。
 */
class DeprecationAdjudicatorTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private static ActiveDeprecation window(String name, int version, int replacement,
                                            String effectiveAt) {
        return new ActiveDeprecation(name, version, replacement,
                Instant.parse(effectiveAt), "note-" + name + version);
    }

    private static Map<NameVersion, ActiveDeprecation> map(ActiveDeprecation... windows) {
        Map<NameVersion, ActiveDeprecation> map = new java.util.HashMap<>();
        for (ActiveDeprecation w : windows) {
            map.put(new NameVersion(w.name(), w.version()), w);
        }
        return map;
    }

    @Test
    void noWindowsNoHits() {
        DeprecationAdjudicator.Adjudication result = DeprecationAdjudicator.adjudicate(
                Map.of("app", 1), Map.of(), NOW);
        assertThat(result.blocked()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void futureWindowProducesWarningWithSecondsDifference() {
        DeprecationAdjudicator.Adjudication result = DeprecationAdjudicator.adjudicate(
                Map.of("lib", 1),
                map(window("lib", 1, 2, "2026-06-01T00:01:00Z")), NOW);
        assertThat(result.blocked()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        DeprecationAdjudicator.WarningHit warning = result.warnings().get(0);
        assertThat(warning.name()).isEqualTo("lib");
        assertThat(warning.version()).isEqualTo(1);
        assertThat(warning.replacementVersion()).isEqualTo(2);
        assertThat(warning.effectiveAt()).isEqualTo(Instant.parse("2026-06-01T00:01:00Z"));
        assertThat(warning.secondsUntilEffective()).isEqualTo(60L);
    }

    @Test
    void effectiveAtExactlyNowBlocksLeftClosed() {
        // 左闭：effectiveAt == now 即已生效。
        DeprecationAdjudicator.Adjudication result = DeprecationAdjudicator.adjudicate(
                Map.of("lib", 1),
                map(window("lib", 1, 2, "2026-06-01T00:00:00Z")), NOW);
        assertThat(result.blocked()).hasSize(1);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void pastWindowBlocks() {
        DeprecationAdjudicator.Adjudication result = DeprecationAdjudicator.adjudicate(
                Map.of("lib", 1),
                map(window("lib", 1, 2, "2026-05-31T23:59:59Z")), NOW);
        assertThat(result.blocked()).hasSize(1);
    }

    @Test
    void windowForDifferentVersionDoesNotHit() {
        DeprecationAdjudicator.Adjudication result = DeprecationAdjudicator.adjudicate(
                Map.of("lib", 2),
                map(window("lib", 1, 2, "2026-05-01T00:00:00Z")), NOW);
        assertThat(result.blocked()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void windowForNameOutsideClosureDoesNotHit() {
        DeprecationAdjudicator.Adjudication result = DeprecationAdjudicator.adjudicate(
                Map.of("app", 1),
                map(window("lib", 1, 2, "2026-05-01T00:00:00Z")), NOW);
        assertThat(result.blocked()).isEmpty();
    }

    @Test
    void mixedBlockedAndWarningsSortedByNameThenVersion() {
        DeprecationAdjudicator.Adjudication result = DeprecationAdjudicator.adjudicate(
                Map.of("zlib", 1, "alib", 2, "mid", 1),
                map(window("zlib", 1, 2, "2026-05-01T00:00:00Z"),
                        window("alib", 2, 3, "2026-07-01T00:00:00Z"),
                        window("mid", 1, 2, "2026-05-15T00:00:00Z")), NOW);
        assertThat(result.blocked()).extracting("name")
                .containsExactly("mid", "zlib");
        assertThat(result.warnings()).extracting("name")
                .containsExactly("alib");
    }

    @Test
    void sameNameDifferentVersionsSortByVersion() {
        DeprecationAdjudicator.Adjudication result = DeprecationAdjudicator.adjudicate(
                Map.of("lib", 1, "lib2", 1),
                map(window("lib2", 1, 2, "2026-05-01T00:00:00Z"),
                        window("lib", 1, 2, "2026-05-01T00:00:00Z")), NOW);
        assertThat(result.blocked()).extracting("name")
                .containsExactly("lib", "lib2");
    }
}
