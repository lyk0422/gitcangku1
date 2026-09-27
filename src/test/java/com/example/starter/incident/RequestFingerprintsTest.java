package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 请求指纹规范化纯单元测试：不启动 Spring、不连数据库。
 * 验证操作者/操作类型/参数任一变化指纹即变，集合经 JSON 数组编码而非分隔符拼接，
 * ["OPS","DB"] 与 ["OPS,DB"] 不碰撞，含 JSON 特殊字符的元素被正确转义。
 */
class RequestFingerprintsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Map<String, Object> params(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void fingerprint_coversActorOperationAndAllParams() {
        Map<String, Object> p = params("proposalKey", "P-1", "roleCode", "OPS",
                "choice", "SUPPORT");
        String base = RequestFingerprints.sha256(objectMapper, "decision_vote", "u1", p);

        assertThat(RequestFingerprints.sha256(objectMapper, "decision_vote", "u1", p))
                .isEqualTo(base);
        assertThat(RequestFingerprints.sha256(objectMapper, "decision_vote", "u2", p))
                .isNotEqualTo(base);
        assertThat(RequestFingerprints.sha256(objectMapper, "decision_create", "u1", p))
                .isNotEqualTo(base);
        assertThat(RequestFingerprints.sha256(objectMapper, "decision_vote", "u1",
                params("proposalKey", "P-2", "roleCode", "OPS", "choice", "SUPPORT")))
                .isNotEqualTo(base);
        assertThat(RequestFingerprints.sha256(objectMapper, "decision_vote", "u1",
                params("proposalKey", "P-1", "roleCode", "DB", "choice", "SUPPORT")))
                .isNotEqualTo(base);
        assertThat(RequestFingerprints.sha256(objectMapper, "decision_vote", "u1",
                params("proposalKey", "P-1", "roleCode", "OPS", "choice", "OPPOSE")))
                .isNotEqualTo(base);
    }

    @Test
    void fingerprint_setsAreJsonEncoded_notDelimiterJoined() {
        Map<String, Object> twoRoles = params("requiredRoles", List.of("OPS", "DB"));
        Map<String, Object> oneRoleWithComma = params("requiredRoles", List.of("OPS,DB"));
        // 原始未转义分隔符拼接会得到 "OPS,DB" 而碰撞；JSON 数组编码必须不碰撞
        assertThat(RequestFingerprints.sha256(objectMapper, "decision_create", "alice",
                twoRoles)).isNotEqualTo(RequestFingerprints.sha256(objectMapper,
                        "decision_create", "alice", oneRoleWithComma));
        // 规范化文本直接体现 JSON 数组结构与转义
        assertThat(RequestFingerprints.canonical(objectMapper, "decision_create", "alice",
                twoRoles)).contains("\"requiredRoles\":[\"OPS\",\"DB\"]");
        assertThat(RequestFingerprints.canonical(objectMapper, "decision_create", "alice",
                oneRoleWithComma)).contains("\"requiredRoles\":[\"OPS,DB\"]");
    }

    @Test
    void fingerprint_jsonEscapesSpecialCharactersInSetElements() {
        Map<String, Object> tricky = params("requiredRoles",
                List.of("ROLE\",\"HACKED"));
        String canonical = RequestFingerprints.canonical(objectMapper,
                "decision_create", "alice", tricky);
        assertThat(canonical).contains("ROLE\\\",\\\"HACKED");
        // 转义后与伪造出的两元素数组不碰撞
        Map<String, Object> forged = params("requiredRoles", List.of("ROLE", "HACKED"));
        assertThat(RequestFingerprints.sha256(objectMapper, "decision_create", "alice",
                tricky)).isNotEqualTo(RequestFingerprints.sha256(objectMapper,
                        "decision_create", "alice", forged));
    }

    @Test
    void fingerprint_is64HexSha256() {
        String fp = RequestFingerprints.sha256(objectMapper, "decision_vote", "u1",
                params("proposalKey", "P-1"));
        assertThat(fp).hasSize(64).matches("[0-9a-f]{64}");
    }
}
