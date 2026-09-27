package com.example.starter.evidence;

import com.example.starter.evidence.dto.CaseTransferInitiateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等指纹规范化单元测试（不依赖数据库）：
 * 仅证物顺序不同、或请求去重排序后内容一致的请求指纹必须相同；任一字段变化指纹必须不同。
 * 规范化逻辑与线上控制器共用 {@link CaseTransferController#canonicalize}。
 */
class CaseTransferFingerprintTest {

    private final IdempotencyAdvisor advisor = new IdempotencyAdvisor(null, new ObjectMapper());

    private CaseTransferInitiateRequest request(String commandKey, String from, String to,
                                                String orderVersion, String fromCust,
                                                String toCust, List<String> keys) {
        return new CaseTransferInitiateRequest(commandKey, from, to, "ORDER-1", orderVersion,
                fromCust, toCust, keys);
    }

    private String fingerprint(String actor, CaseTransferInitiateRequest raw) {
        CaseTransferInitiateRequest canonical = CaseTransferController.canonicalize(raw);
        return advisor.hash(EvidenceService.OP_CASE_TRANSFER_INITIATE, actor,
                canonical.fromCaseKey() + ">" + canonical.toCaseKey(), canonical);
    }

    @Test
    void differentEvidenceOrderProducesSameFingerprint() {
        CaseTransferInitiateRequest a = request("cmd-1", "CASE-A", "CASE-B", "v1",
                "alice", "bob", List.of("EV-1", "EV-2", "EV-3"));
        CaseTransferInitiateRequest b = request("cmd-1", "CASE-A", "CASE-B", "v1",
                "alice", "bob", List.of("EV-3", "EV-1", "EV-2"));
        assertThat(fingerprint("alice", a)).isEqualTo(fingerprint("alice", b));
    }

    @Test
    void duplicateKeysDoNotChangeCanonicalFingerprint() {
        CaseTransferInitiateRequest once = request("cmd-1", "CASE-A", "CASE-B", "v1",
                "alice", "bob", List.of("EV-1", "EV-2"));
        CaseTransferInitiateRequest duplicated = request("cmd-1", "CASE-A", "CASE-B", "v1",
                "alice", "bob", List.of("EV-2", "EV-1", "EV-2"));
        assertThat(fingerprint("alice", once)).isEqualTo(fingerprint("alice", duplicated));
    }

    @Test
    void changedCaseOrderVersionOrActorChangesFingerprint() {
        CaseTransferInitiateRequest base = request("cmd-1", "CASE-A", "CASE-B", "v1",
                "alice", "bob", List.of("EV-1", "EV-2"));
        String baseHash = fingerprint("alice", base);

        assertThat(fingerprint("alice", request("cmd-1", "CASE-A", "CASE-C", "v1",
                "alice", "bob", List.of("EV-1", "EV-2")))).isNotEqualTo(baseHash);
        assertThat(fingerprint("alice", request("cmd-1", "CASE-A", "CASE-B", "v2",
                "alice", "bob", List.of("EV-1", "EV-2")))).isNotEqualTo(baseHash);
        assertThat(fingerprint("alice", request("cmd-1", "CASE-A", "CASE-B", "v1",
                "alice", "carol", List.of("EV-1", "EV-2")))).isNotEqualTo(baseHash);
        assertThat(fingerprint("mallory", request("cmd-1", "CASE-A", "CASE-B", "v1",
                "alice", "bob", List.of("EV-1", "EV-2")))).isNotEqualTo(baseHash);
        assertThat(fingerprint("alice", request("cmd-1", "CASE-A", "CASE-B", "v1",
                "alice", "bob", List.of("EV-1")))).isNotEqualTo(baseHash);
    }

    @Test
    void fingerprintIsStableSha256Hex() {
        String hash = fingerprint("alice", request("cmd-1", "CASE-A", "CASE-B", "v1",
                "alice", "bob", List.of("EV-1")));
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hash).isEqualTo(fingerprint("alice", request("cmd-1", "CASE-A", "CASE-B", "v1",
                "alice", "bob", List.of("EV-1"))));
    }
}
