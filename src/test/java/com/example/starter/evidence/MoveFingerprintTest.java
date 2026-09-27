package com.example.starter.evidence;

import com.example.starter.evidence.dto.MoveApplyRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * moveKey 指纹与证物集合规范化的纯单元测试（不启动 Spring、不使用数据库）：
 * 集合换序/重复视为同参；操作者、集合内容、源/目标库位、版本任一变化指纹不同。
 */
class MoveFingerprintTest {

    private final IdempotencyAdvisor advisor =
            new IdempotencyAdvisor(null, new ObjectMapper());

    private String fingerprint(String actor, String moveKey, List<String> keys,
                               String source, String target, long version) {
        MoveApplyRequest canonical = new MoveApplyRequest(moveKey,
                LocationMoveService.normalizeKeys(keys), source, target, version);
        return advisor.hash(EvidenceService.OP_MOVE_APPLY, actor, moveKey, canonical);
    }

    @Test
    void normalizeKeysDeduplicatesAndSorts() {
        assertThat(LocationMoveService.normalizeKeys(List.of("ev3", "ev1", "ev2", "ev1")))
                .containsExactly("ev1", "ev2", "ev3");
    }

    @Test
    void reorderedAndDuplicatedSetProducesSameFingerprint() {
        List<String> one = List.of("ev1", "ev2", "ev3");
        List<String> shuffled = List.of("ev3", "ev1", "ev2");
        List<String> duplicated = List.of("ev2", "ev1", "ev3", "ev3");

        String base = fingerprint("alice", "MOVE-1", one, "SRC", "TGT", 1);
        assertThat(fingerprint("alice", "MOVE-1", shuffled, "SRC", "TGT", 1)).isEqualTo(base);
        assertThat(fingerprint("alice", "MOVE-1", duplicated, "SRC", "TGT", 1)).isEqualTo(base);
    }

    @Test
    void differentActorSetLocationsOrVersionChangesFingerprint() {
        String base = fingerprint("alice", "MOVE-1", List.of("ev1", "ev2"), "SRC", "TGT", 1);

        assertThat(fingerprint("bob", "MOVE-1", List.of("ev1", "ev2"), "SRC", "TGT", 1))
                .isNotEqualTo(base);
        assertThat(fingerprint("alice", "MOVE-1", List.of("ev1", "ev3"), "SRC", "TGT", 1))
                .isNotEqualTo(base);
        assertThat(fingerprint("alice", "MOVE-1", List.of("ev1"), "SRC", "TGT", 1))
                .isNotEqualTo(base);
        assertThat(fingerprint("alice", "MOVE-1", List.of("ev1", "ev2"), "OTHER", "TGT", 1))
                .isNotEqualTo(base);
        assertThat(fingerprint("alice", "MOVE-1", List.of("ev1", "ev2"), "SRC", "OTHER", 1))
                .isNotEqualTo(base);
        assertThat(fingerprint("alice", "MOVE-1", List.of("ev1", "ev2"), "SRC", "TGT", 2))
                .isNotEqualTo(base);
    }

    @Test
    void differentMoveKeyHasDifferentFingerprintInputSpace() {
        assertThat(fingerprint("alice", "MOVE-1", List.of("ev1"), "SRC", "TGT", 1))
                .isNotEqualTo(fingerprint("alice", "MOVE-2", List.of("ev1"), "SRC", "TGT", 1));
    }
}
