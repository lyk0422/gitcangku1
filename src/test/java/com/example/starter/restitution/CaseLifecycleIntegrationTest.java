package com.example.starter.restitution;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主流程与失败分支：建案、登记重叠主张、证据版本、批准规则、撤回、422 缺项与 422 覆盖集合、
 * 成功裁决冻结、终态拒写、历史查询取冻结快照。
 */
class CaseLifecycleIntegrationTest extends BaseIntegrationTest {

    @Test
    void fullHappyPathFreezesCaseAndRejectsLaterWrites() throws Exception {
        String caseId = createCase("clerk", List.of("Vase", "Scroll"));

        // 两个可重叠主张：c1 覆盖 Vase，c2 覆盖 Scroll+Vase；裁决选 c1+c2 不允许（Vase 重复），
        // 合规组合为 c1(Vase)+c3(Scroll)
        registerClaim("clerk", caseId, "c1", "museum-A", List.of("Vase"));
        registerClaim("clerk", caseId, "c2", "museum-B", List.of("Vase", "Scroll"));
        registerClaim("clerk", caseId, "c3", "museum-C", List.of("Scroll"));

        addEvidence("clerk", caseId, "c1", "ev-a1", "provenance photo");
        addEvidence("clerk", caseId, "c3", "ev-c1", "archive record");

        approve("reviewer-1", caseId, "c1");
        approve("reviewer-2", caseId, "c1");
        approve("reviewer-1", caseId, "c3");
        approve("reviewer-2", caseId, "c3");

        JsonNode before = getJson("/api/cases/" + caseId, 200);
        long versionBeforeDecision = before.get("version").asLong();
        assertThat(before.get("source").asText()).isEqualTo("LIVE");
        assertThat(before.get("claims").size()).isEqualTo(3);

        JsonNode decision = writeJson("POST", "/api/cases/" + caseId + "/decisions",
                "judge", newRequestId(),
                Map.of("expectedVersion", versionBeforeDecision,
                        "claimKeys", List.of("c1", "c3")), 200);
        assertThat(decision.get("status").asText()).isEqualTo("DECIDED");
        assertThat(decision.get("version").asLong()).isEqualTo(versionBeforeDecision + 1);
        assertThat(decision.get("selectedClaimKeys"))
                .extracting(JsonNode::asText)
                .containsExactly("c1", "c3");
        Map<String, String> ownerByItem = java.util.stream.StreamSupport
                .stream(decision.get("frozenItems").spliterator(), false)
                .collect(java.util.stream.Collectors.toMap(
                        n -> n.get("itemNo").asText(), n -> n.get("applicant").asText()));
        assertThat(ownerByItem).containsEntry("Vase", "museum-A")
                .containsEntry("Scroll", "museum-C");

        // 终态拒绝一切新写入
        writeJson("POST", "/api/cases/" + caseId + "/claims", "clerk", newRequestId(),
                Map.of("claimKey", "cx", "applicant", "a", "statement", "s",
                        "items", List.of("Vase")), 409);
        writeJson("POST", "/api/cases/" + caseId + "/claims/c1/evidences", "clerk",
                newRequestId(), Map.of("evidenceKey", "ev-late", "summary", "x"), 409);
        writeOk("DELETE", "/api/cases/" + caseId + "/claims/c3", "clerk", newRequestId(), null, 409);
        writeOk("POST", "/api/cases/" + caseId + "/claims/c1/approvals", "reviewer-1",
                newRequestId(), null, 409);

        // 历史查询不重算：只含中选主张冻结快照与有效证据/批准
        JsonNode after = getJson("/api/cases/" + caseId, 200);
        assertThat(after.get("source").asText()).isEqualTo("FROZEN");
        assertThat(after.get("status").asText()).isEqualTo("DECIDED");
        assertThat(after.get("claims")).extracting(n -> n.get("claimKey").asText())
                .containsExactly("c1", "c3");
        JsonNode c1 = java.util.stream.StreamSupport.stream(after.get("claims").spliterator(), false)
                .filter(n -> "c1".equals(n.get("claimKey").asText())).findFirst().orElseThrow();
        assertThat(c1.get("evidences")).hasSize(1);
        assertThat(c1.get("evidences").get(0).get("evidenceKey").asText()).isEqualTo("ev-a1");
        assertThat(c1.get("approvals")).hasSize(2);
    }

    @Test
    void claimEvidenceAndApprovalVersionRules() throws Exception {
        String caseId = createCase("clerk", List.of("Vase"));
        registerClaim("clerk", caseId, "c1", "museum-A", List.of("Vase"));

        // 无证据不可批准
        writeJson("POST", "/api/cases/" + caseId + "/claims/c1/approvals", "reviewer-1",
                newRequestId(), null, 409);
        // 申请人不可自批
        addEvidence("clerk", caseId, "c1", "ev-1", "summary-1");
        writeJson("POST", "/api/cases/" + caseId + "/claims/c1/approvals", "museum-A",
                newRequestId(), null, 403);

        JsonNode caseAfterEv1 = getJson("/api/cases/" + caseId, 200);
        long vAfterEv1 = caseAfterEv1.get("version").asLong();

        // 首次批准加案件版本
        writeOk("POST", "/api/cases/" + caseId + "/claims/c1/approvals", "reviewer-1",
                newRequestId(), null, 201);
        JsonNode afterFirstApproval = getJson("/api/cases/" + caseId, 200);
        long vAfterFirstApproval = afterFirstApproval.get("version").asLong();
        assertThat(vAfterFirstApproval).isEqualTo(vAfterEv1 + 1);

        // 同一人同版本重复批准不计数不加版本
        writeOk("POST", "/api/cases/" + caseId + "/claims/c1/approvals", "reviewer-1",
                newRequestId(), null, 201);
        assertThat(getJson("/api/cases/" + caseId, 200).get("version").asLong())
                .isEqualTo(vAfterFirstApproval);

        // 追加证据推进证据版本：旧批准失效，当前版本批准归零，须重新批准
        addEvidence("clerk", caseId, "c1", "ev-2", "summary-2");
        JsonNode c1 = claimNode(caseId, "c1");
        assertThat(c1.get("evidenceVersion").asLong()).isEqualTo(2);
        long currentApprovers = java.util.stream.StreamSupport
                .stream(c1.get("approvals").spliterator(), false)
                .filter(n -> n.get("evidenceVersion").asLong() == 2).count();
        assertThat(currentApprovers).isZero();
    }

    @Test
    void evidenceRevokeKeepsHistoryAndInvalidatesCurrentApprovals() throws Exception {
        String caseId = createCase("clerk", List.of("Vase"));
        registerClaim("clerk", caseId, "c1", "museum-A", List.of("Vase"));
        addEvidence("clerk", caseId, "c1", "ev-1", "summary-1");
        approve("reviewer-1", caseId, "c1");
        approve("reviewer-2", caseId, "c1");

        // 撤销证据：证据与案件版本各加一，历史保留
        JsonNode before = getJson("/api/cases/" + caseId, 200);
        long vBefore = before.get("version").asLong();
        writeOk("DELETE", "/api/cases/" + caseId + "/claims/c1/evidences/ev-1", "clerk",
                newRequestId(), null, 200);
        JsonNode after = getJson("/api/cases/" + caseId, 200);
        assertThat(after.get("version").asLong()).isEqualTo(vBefore + 1);
        JsonNode c1 = claimNode(caseId, "c1");
        assertThat(c1.get("evidenceVersion").asLong()).isEqualTo(2);
        assertThat(c1.get("evidences")).hasSize(1);
        assertThat(c1.get("evidences").get(0).get("active").asBoolean()).isFalse();

        // 再次撤销同一证据冲突
        writeOk("DELETE", "/api/cases/" + caseId + "/claims/c1/evidences/ev-1", "clerk",
                newRequestId(), null, 409);

        // 无有效证据不可裁决：缺项 NO_ACTIVE_EVIDENCE
        writeJson("POST", "/api/cases/" + caseId + "/decisions", "judge", newRequestId(),
                Map.of("expectedVersion", after.get("version").asLong(),
                        "claimKeys", List.of("c1")), 422);
    }

    @Test
    void withdrawnClaimCannotBeUsedOrRestored() throws Exception {
        String caseId = createCase("clerk", List.of("Vase"));
        registerClaim("clerk", caseId, "c1", "museum-A", List.of("Vase"));

        JsonNode before = getJson("/api/cases/" + caseId, 200);
        writeOk("DELETE", "/api/cases/" + caseId + "/claims/c1", "clerk",
                newRequestId(), null, 200);
        assertThat(getJson("/api/cases/" + caseId, 200).get("version").asLong())
                .isEqualTo(before.get("version").asLong() + 1);

        // 撤回不可恢复：再次撤回 409；加证据/批准均 409
        writeOk("DELETE", "/api/cases/" + caseId + "/claims/c1", "clerk",
                newRequestId(), null, 409);
        writeJson("POST", "/api/cases/" + caseId + "/claims/c1/evidences", "clerk",
                newRequestId(), Map.of("evidenceKey", "ev-x", "summary", "s"), 409);
        writeOk("POST", "/api/cases/" + caseId + "/claims/c1/approvals", "reviewer-1",
                newRequestId(), null, 409);

        // 裁决选中已撤回主张：422 缺项 WITHDRAWN 且藏品缺失
        JsonNode err = writeJson("POST", "/api/cases/" + caseId + "/decisions", "judge",
                newRequestId(),
                Map.of("expectedVersion", getJson("/api/cases/" + caseId, 200).get("version").asLong(),
                        "claimKeys", List.of("c1")), 422);
        assertThat(err.get("claimDeficiencies").get("c1").get(0).asText()).isEqualTo("WITHDRAWN");
        assertThat(err.get("missingItems")).extracting(JsonNode::asText).containsExactly("Vase");
    }

    @Test
    void decision422ReportsDeficienciesDuplicatesAndMissing() throws Exception {
        String caseId = createCase("clerk", List.of("Vase", "Scroll", "Seal"));
        registerClaim("clerk", caseId, "c1", "museum-A", List.of("Vase"));
        registerClaim("clerk", caseId, "c2", "museum-B", List.of("Vase", "Scroll"));
        addEvidence("clerk", caseId, "c2", "ev-2", "s");
        approve("reviewer-1", caseId, "c2");

        JsonNode err = writeJson("POST", "/api/cases/" + caseId + "/decisions", "judge",
                newRequestId(),
                Map.of("expectedVersion", getJson("/api/cases/" + caseId, 200).get("version").asLong(),
                        "claimKeys", List.of("c1", "c2", "c-missing")), 422);
        assertThat(err.get("claimDeficiencies").get("c1").toString()).contains("NO_ACTIVE_EVIDENCE");
        assertThat(err.get("claimDeficiencies").get("c2").toString())
                .contains("INSUFFICIENT_APPROVALS");
        assertThat(err.get("claimDeficiencies").get("c-missing").toString())
                .contains("CLAIM_NOT_FOUND");
        assertThat(err.get("duplicateItems")).extracting(JsonNode::asText).containsExactly("Vase");
        assertThat(err.get("missingItems")).extracting(JsonNode::asText).containsExactly("Seal");
        // 失败不留部分裁决
        assertThat(getJson("/api/cases/" + caseId, 200).get("status").asText()).isEqualTo("OPEN");
        Integer frozenClaims = jdbc.queryForObject(
                "SELECT COUNT(*) FROM frozen_claim WHERE case_id = ?", Integer.class, caseId);
        assertThat(frozenClaims).isZero();
    }

    @Test
    void invalidParamsNotFoundAndDuplicateKeys() throws Exception {
        // 参数非法 400：空藏品列表
        writeJson("POST", "/api/cases", "clerk", newRequestId(),
                Map.of("items", List.of()), 400);
        // 参数非法 400：11 个藏品
        writeJson("POST", "/api/cases", "clerk", newRequestId(),
                Map.of("items", List.of("i1", "i2", "i3", "i4", "i5", "i6", "i7", "i8",
                        "i9", "i10", "i11")), 400);
        // 参数非法 400：藏品编号重复
        writeJson("POST", "/api/cases", "clerk", newRequestId(),
                Map.of("items", List.of("a", "a")), 400);

        String caseId = createCase("clerk", List.of("Vase"));
        // 案件不存在 404
        getJson("/api/cases/no-such-case", 404);
        writeJson("POST", "/api/cases/no-such-case/claims", "clerk", newRequestId(),
                Map.of("claimKey", "c1", "applicant", "a", "statement", "s",
                        "items", List.of("Vase")), 404);

        registerClaim("clerk", caseId, "c1", "museum-A", List.of("Vase"));
        // claimKey 案内重复 409
        writeJson("POST", "/api/cases/" + caseId + "/claims", "clerk", newRequestId(),
                Map.of("claimKey", "c1", "applicant", "museum-B", "statement", "s",
                        "items", List.of("Vase")), 409);
        // 主张子集超出案件藏品 400
        writeJson("POST", "/api/cases/" + caseId + "/claims", "clerk", newRequestId(),
                Map.of("claimKey", "c9", "applicant", "museum-X", "statement", "s",
                        "items", List.of("Ghost")), 400);
        // 主张不存在 404
        writeJson("POST", "/api/cases/" + caseId + "/claims/nope/evidences", "clerk",
                newRequestId(), Map.of("evidenceKey", "e", "summary", "s"), 404);

        addEvidence("clerk", caseId, "c1", "ev-1", "s1");
        // evidenceKey 案内重复 409（跨主张也不行）
        registerClaim("clerk", caseId, "c2", "museum-B", List.of("Vase"));
        writeJson("POST", "/api/cases/" + caseId + "/claims/c2/evidences", "clerk",
                newRequestId(), Map.of("evidenceKey", "ev-1", "summary", "s2"), 409);
        // 撤销不存在的证据 404
        writeOk("DELETE", "/api/cases/" + caseId + "/claims/c1/evidences/nope", "clerk",
                newRequestId(), null, 404);
    }

    @Test
    void evidenceLimit20Enforced() throws Exception {
        String caseId = createCase("clerk", List.of("Vase"));
        registerClaim("clerk", caseId, "c1", "museum-A", List.of("Vase"));
        for (int i = 1; i <= 20; i++) {
            addEvidence("clerk", caseId, "c1", "ev-" + i, "summary-" + i);
        }
        JsonNode err = writeJson("POST", "/api/cases/" + caseId + "/claims/c1/evidences",
                "clerk", newRequestId(),
                Map.of("evidenceKey", "ev-21", "summary", "overflow"), 409);
        assertThat(err.get("error").asText()).isEqualTo("EVIDENCE_LIMIT_EXCEEDED");
        assertThat(claimNode(caseId, "c1").get("evidences")).hasSize(20);
    }

    private JsonNode claimNode(String caseId, String claimKey) throws Exception {
        JsonNode detail = getJson("/api/cases/" + caseId, 200);
        return java.util.stream.StreamSupport.stream(detail.get("claims").spliterator(), false)
                .filter(n -> claimKey.equals(n.get("claimKey").asText()))
                .findFirst().orElseThrow();
    }
}
