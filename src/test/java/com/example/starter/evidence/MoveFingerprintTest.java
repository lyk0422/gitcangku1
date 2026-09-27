package com.example.starter.evidence;

import com.example.starter.evidence.dto.MoveCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 位置迁移 moveKey 指纹的纯单元测试：
 * 指纹包含操作者、规范化证物集合（去重、字典序）、源/目标库位与 expectedVersion；
 * 集合换序与重复键必须视为同参，操作者/库位/版本任一不同必须产生不同指纹。
 */
class MoveFingerprintTest {

    private final IdempotencyAdvisor advisor = new IdempotencyAdvisor(null, new ObjectMapper());

    private String fingerprint(String actor, MoveCreateRequest request) {
        return advisor.hash(LocationMoveService.OP_MOVE_CREATE,
                actor, request.moveKey(), request);
    }

    private MoveCreateRequest move(String commandKey, String moveKey, List<String> keys,
                                   String source, String target, long version) {
        return new MoveCreateRequest(commandKey, moveKey, keys, source, target, version);
    }

    @Test
    void reorderedSetProducesSameFingerprint() {
        // 控制器在计算指纹前先规范化集合；换序的两次申请规范化后必须得到相同指纹。
        MoveCreateRequest one = move("CMD-1", "MOVE-1",
                LocationMoveService.normalizeKeys(List.of("EV-3", "EV-1", "EV-2")),
                "LOC-A", "LOC-B", 7L);
        MoveCreateRequest reordered = move("CMD-1", "MOVE-1",
                LocationMoveService.normalizeKeys(List.of("EV-2", "EV-3", "EV-1")),
                "LOC-A", "LOC-B", 7L);

        assertThat(fingerprint("alice", reordered))
                .isEqualTo(fingerprint("alice", one));
    }

    @Test
    void duplicateKeysInSetProduceSameFingerprint() {
        MoveCreateRequest base = move("CMD-1", "MOVE-1", List.of("EV-1", "EV-2"),
                "LOC-A", "LOC-B", 7L);
        MoveCreateRequest duplicated = move("CMD-1", "MOVE-1",
                LocationMoveService.normalizeKeys(List.of("EV-2", "EV-1", "EV-2", "EV-1")),
                "LOC-A", "LOC-B", 7L);

        assertThat(fingerprint("alice", duplicated))
                .isEqualTo(fingerprint("alice", base));
    }

    @Test
    void normalizeKeysDeduplicatesAndSortsLexicographically() {
        assertThat(LocationMoveService.normalizeKeys(List.of("EV-9", "EV-1", "EV-1", "EV-3")))
                .containsExactly("EV-1", "EV-3", "EV-9");
    }

    @Test
    void differentActorProducesDifferentFingerprint() {
        MoveCreateRequest request = move("CMD-1", "MOVE-1", List.of("EV-1"),
                "LOC-A", "LOC-B", 7L);

        assertThat(fingerprint("alice", request))
                .isNotEqualTo(fingerprint("bob", request));
    }

    @Test
    void differentLocationsOrVersionProduceDifferentFingerprint() {
        MoveCreateRequest base = move("CMD-1", "MOVE-1", List.of("EV-1"),
                "LOC-A", "LOC-B", 7L);

        assertThat(fingerprint("alice", move("CMD-1", "MOVE-1", List.of("EV-1"),
                "LOC-X", "LOC-B", 7L))).isNotEqualTo(fingerprint("alice", base));
        assertThat(fingerprint("alice", move("CMD-1", "MOVE-1", List.of("EV-1"),
                "LOC-A", "LOC-X", 7L))).isNotEqualTo(fingerprint("alice", base));
        assertThat(fingerprint("alice", move("CMD-1", "MOVE-1", List.of("EV-1"),
                "LOC-A", "LOC-B", 8L))).isNotEqualTo(fingerprint("alice", base));
    }

    @Test
    void operationIsPartOfFingerprint() {
        MoveCreateRequest request = move("CMD-1", "MOVE-1", List.of("EV-1"),
                "LOC-A", "LOC-B", 7L);
        String createHash = advisor.hash(LocationMoveService.OP_MOVE_CREATE, "alice",
                request.moveKey(), request);
        String confirmHash = advisor.hash(LocationMoveService.OP_MOVE_CONFIRM_FIRST, "alice",
                request.moveKey(), request);

        assertThat(createHash).isNotEqualTo(confirmHash);
    }
}
