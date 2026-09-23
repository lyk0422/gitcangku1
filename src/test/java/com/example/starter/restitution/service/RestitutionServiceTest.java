package com.example.starter.restitution.service;

import com.example.starter.restitution.AbstractH2IntegrationTest;
import com.example.starter.restitution.error.ApiException;
import com.example.starter.restitution.web.dto.AddEvidenceRequest;
import com.example.starter.restitution.web.dto.CaseResponse;
import com.example.starter.restitution.web.dto.ClaimResponse;
import com.example.starter.restitution.web.dto.DecideRequest;
import com.example.starter.restitution.web.dto.DecisionResponse;
import com.example.starter.restitution.web.dto.EvidenceResponse;
import com.example.starter.restitution.web.dto.RegisterClaimRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 藏品归还裁决主流程与失败分支的真实 H2 集成测试。
 */
class RestitutionServiceTest extends AbstractH2IntegrationTest {

    @Autowired
    private RestitutionService service;

    private final AtomicLong seq = new AtomicLong();

    private String rid() {
        return "req-" + seq.incrementAndGet();
    }

    private CaseResponse newCase(String... artifacts) {
        return service.createCase("officer", rid(), List.of(artifacts));
    }

    private ClaimResponse registerClaim(CaseResponse caseRow, String claimKey, String applicant,
                                        List<String> artifacts) {
        return service.registerClaim("officer", rid(), caseRow.caseKey(),
                new RegisterClaimRequest(claimKey, applicant, "说明-" + claimKey, artifacts));
    }

    private void addEvidence(CaseResponse caseRow, String claimKey, String evidenceKey, String summary) {
        service.addEvidence("officer", rid(), caseRow.caseKey(), claimKey,
                new AddEvidenceRequest(evidenceKey, summary));
    }

    private void approve(CaseResponse caseRow, String claimKey, String reviewer) {
        service.approve(reviewer, rid(), caseRow.caseKey(), claimKey);
    }

    // ------------------------------------------------------------------
    // 案件
    // ------------------------------------------------------------------

    @Test
    void createCase_startsOpenVersion1WithArtifacts() {
        CaseResponse response = newCase("A", "B", "C");

        assertThat(response.status()).isEqualTo("OPEN");
        assertThat(response.version()).isEqualTo(1L);
        assertThat(response.artifactNos()).containsExactly("A", "B", "C");
        assertThat(response.caseKey()).startsWith("C-");
    }

    @Test
    void createCase_rejectsEmptyTooManyOrDuplicateArtifacts() {
        assertThatThrownBy(() -> service.createCase("officer", rid(), List.of()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(400);

        assertThatThrownBy(() -> service.createCase("officer", rid(),
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(400);

        assertThatThrownBy(() -> service.createCase("officer", rid(), List.of("A", "A")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(400);
    }

    @Test
    void getCase_unknownReturns404() {
        assertThatThrownBy(() -> service.getCase("C-nope"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // 主张
    // ------------------------------------------------------------------

    @Test
    void registerClaim_overlappingSubsetsAllowedAndBumpsVersion() {
        CaseResponse caseRow = newCase("A", "B", "C");
        ClaimResponse first = registerClaim(caseRow, "CL-1", "alice", List.of("A", "B"));
        ClaimResponse second = registerClaim(caseRow, "CL-2", "bob", List.of("B", "C"));

        assertThat(first.status()).isEqualTo("REGISTERED");
        assertThat(first.evidenceVersion()).isZero();
        assertThat(first.artifactNos()).containsExactly("A", "B");
        assertThat(second.artifactNos()).containsExactly("B", "C");

        assertThat(service.getCase(caseRow.caseKey()).version()).isEqualTo(3L);
    }

    @Test
    void registerClaim_duplicateKeyUnknownArtifactRejected() {
        CaseResponse caseRow = newCase("A", "B");
        registerClaim(caseRow, "CL-1", "alice", List.of("A"));

        assertThatThrownBy(() -> registerClaim(caseRow, "CL-1", "bob", List.of("B")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);

        assertThatThrownBy(() -> registerClaim(caseRow, "CL-2", "bob", List.of("ZZZ")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(400);
    }

    @Test
    void withdrawClaim_bumpsVersionAndBlocksFurtherWritesAndIsNotRecoverable() {
        CaseResponse caseRow = newCase("A");
        registerClaim(caseRow, "CL-1", "alice", List.of("A"));
        long versionAfterRegister = service.getCase(caseRow.caseKey()).version();

        service.withdrawClaim("officer", rid(), caseRow.caseKey(), "CL-1");

        assertThat(service.getCase(caseRow.caseKey()).version()).isEqualTo(versionAfterRegister + 1);
        ClaimResponse withdrawn = service.listClaims(caseRow.caseKey()).get(0);
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");

        // 撤回不可恢复：重复撤回 409。
        assertThatThrownBy(() -> service.withdrawClaim("officer", rid(), caseRow.caseKey(), "CL-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);

        // 撤回后内容不可再写：证据追加、批准均拒绝。
        assertThatThrownBy(() -> addEvidence(caseRow, "CL-1", "E-1", "摘要"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);

        assertThatThrownBy(() -> approve(caseRow, "CL-1", "r1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);
    }

    // ------------------------------------------------------------------
    // 证据
    // ------------------------------------------------------------------

    @Test
    void addAndRevokeEvidence_bumpVersionsAndRetainHistory() {
        CaseResponse caseRow = newCase("A");
        registerClaim(caseRow, "CL-1", "alice", List.of("A"));
        long versionAfterRegister = service.getCase(caseRow.caseKey()).version();

        addEvidence(caseRow, "CL-1", "E-1", "来源说明一");
        ClaimResponse afterFirst = service.listClaims(caseRow.caseKey()).get(0);
        assertThat(afterFirst.evidenceVersion()).isEqualTo(1L);
        assertThat(service.getCase(caseRow.caseKey()).version()).isEqualTo(versionAfterRegister + 1);

        addEvidence(caseRow, "CL-1", "E-2", "来源说明二");
        assertThat(service.listClaims(caseRow.caseKey()).get(0).evidenceVersion()).isEqualTo(2L);

        service.revokeEvidence("officer", rid(), caseRow.caseKey(), "E-1");
        ClaimResponse afterRevoke = service.listClaims(caseRow.caseKey()).get(0);
        assertThat(afterRevoke.evidenceVersion()).isEqualTo(3L);
        assertThat(service.getCase(caseRow.caseKey()).version()).isEqualTo(versionAfterRegister + 3);

        // 历史保留：撤销行仍可查询。
        List<EvidenceResponse> history = service.listEvidence(caseRow.caseKey(), "CL-1");
        assertThat(history).hasSize(2);
        assertThat(history).extracting(EvidenceResponse::evidenceKey, EvidenceResponse::status)
                .containsExactly(tuple("E-1", "REVOKED"), tuple("E-2", "ACTIVE"));

        // 重复撤销 409。
        assertThatThrownBy(() -> service.revokeEvidence("officer", rid(), caseRow.caseKey(), "E-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);
    }

    @Test
    void evidenceKeyIsUniqueWithinCase() {
        CaseResponse caseRow = newCase("A", "B");
        registerClaim(caseRow, "CL-1", "alice", List.of("A"));
        registerClaim(caseRow, "CL-2", "bob", List.of("B"));
        addEvidence(caseRow, "CL-1", "E-SHARED", "摘要一");

        assertThatThrownBy(() -> addEvidence(caseRow, "CL-2", "E-SHARED", "摘要二"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);
    }

    @Test
    void evidenceLimitIs20IncludingRevoked() {
        CaseResponse caseRow = newCase("A");
        registerClaim(caseRow, "CL-1", "alice", List.of("A"));
        for (int i = 1; i <= 20; i++) {
            addEvidence(caseRow, "CL-1", "E-" + i, "摘要" + i);
        }
        // 撤销一份后累计仍达 20，第 21 份仍被拒绝。
        service.revokeEvidence("officer", rid(), caseRow.caseKey(), "E-1");
        assertThatThrownBy(() -> addEvidence(caseRow, "CL-1", "E-21", "摘要21"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);
    }

    @Test
    void evidenceValidationAndMissingResources() {
        CaseResponse caseRow = newCase("A");
        registerClaim(caseRow, "CL-1", "alice", List.of("A"));

        assertThatThrownBy(() -> service.addEvidence("officer", rid(), caseRow.caseKey(), "CL-1",
                new AddEvidenceRequest("E-1", "  ")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(400);

        assertThatThrownBy(() -> service.addEvidence("officer", rid(), caseRow.caseKey(), "NOPE",
                new AddEvidenceRequest("E-1", "摘要")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(404);

        assertThatThrownBy(() -> service.revokeEvidence("officer", rid(), caseRow.caseKey(), "NOPE"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(404);
    }
}
