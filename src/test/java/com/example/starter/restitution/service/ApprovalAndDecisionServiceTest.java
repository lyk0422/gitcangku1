package com.example.starter.restitution.service;

import com.example.starter.restitution.AbstractH2IntegrationTest;
import com.example.starter.restitution.error.ApiException;
import com.example.starter.restitution.web.dto.AddEvidenceRequest;
import com.example.starter.restitution.web.dto.CaseResponse;
import com.example.starter.restitution.web.dto.ClaimResponse;
import com.example.starter.restitution.web.dto.DecideRequest;
import com.example.starter.restitution.web.dto.DecisionResponse;
import com.example.starter.restitution.web.dto.FrozenArtifactView;
import com.example.starter.restitution.web.dto.RegisterClaimRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 评审批准与整案裁决规则的真实 H2 集成测试。
 */
class ApprovalAndDecisionServiceTest extends AbstractH2IntegrationTest {

    @Autowired
    private RestitutionService service;

    private final AtomicLong seq = new AtomicLong();

    private String rid() {
        return "req-" + seq.incrementAndGet();
    }

    private CaseResponse newCase(String... artifacts) {
        return service.createCase("officer", rid(), List.of(artifacts));
    }

    private void registerClaim(CaseResponse c, String key, String applicant, List<String> artifacts) {
        service.registerClaim("officer", rid(), c.caseKey(),
                new RegisterClaimRequest(key, applicant, "说明-" + key, artifacts));
    }

    private void addEvidence(CaseResponse c, String claimKey, String evidenceKey, String summary) {
        service.addEvidence("officer", rid(), c.caseKey(), claimKey,
                new AddEvidenceRequest(evidenceKey, summary));
    }

    private void approve(CaseResponse c, String claimKey, String reviewer) {
        service.approve(reviewer, rid(), c.caseKey(), claimKey);
    }

    private long currentVersion(CaseResponse c) {
        return service.getCase(c.caseKey()).version();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> detailsOf(ApiException api) {
        return (java.util.Map<String, Object>) api.getDetails();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> mapAt(java.util.Map<String, Object> details, String key) {
        return (java.util.Map<String, Object>) details.get(key);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> listAt(java.util.Map<String, Object> details, String key) {
        return (java.util.List<String>) details.get(key);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> listIn(java.util.Map<String, Object> map, String key) {
        return (java.util.List<String>) map.get(key);
    }

    // ------------------------------------------------------------------
    // 批准规则
    // ------------------------------------------------------------------

    @Test
    void selfApprovalIsForbidden() {
        CaseResponse c = newCase("A");
        registerClaim(c, "CL-1", "alice", List.of("A"));
        addEvidence(c, "CL-1", "E-1", "摘要");

        assertThatThrownBy(() -> service.approve("alice", rid(), c.caseKey(), "CL-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(403);
    }

    @Test
    void approvalRequiresAtLeastOneActiveEvidence() {
        CaseResponse c = newCase("A");
        registerClaim(c, "CL-1", "alice", List.of("A"));

        assertThatThrownBy(() -> approve(c, "CL-1", "r1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);
    }

    @Test
    void firstApprovalBumpsVersionButSameReviewerSameVersionDoesNotRecount() {
        CaseResponse c = newCase("A");
        registerClaim(c, "CL-1", "alice", List.of("A"));
        addEvidence(c, "CL-1", "E-1", "摘要");
        long beforeApproval = currentVersion(c);

        approve(c, "CL-1", "r1");
        assertThat(currentVersion(c)).isEqualTo(beforeApproval + 1);

        // 同一人同版本重复批准：不重复计数，不再加版本。
        approve(c, "CL-1", "r1");
        assertThat(currentVersion(c)).isEqualTo(beforeApproval + 1);

        ClaimResponse claim = service.listClaims(c.caseKey()).get(0);
        assertThat(claim.currentApprovers()).containsExactly("r1");
    }

    @Test
    void approvalIsOnlyValidForEvidenceVersionAndMustBeRenewedAfterEvidenceChange() {
        CaseResponse c = newCase("A");
        registerClaim(c, "CL-1", "alice", List.of("A"));
        addEvidence(c, "CL-1", "E-1", "摘要一");
        approve(c, "CL-1", "r1");
        approve(c, "CL-1", "r2");
        // 两名评审人在证据版本 1 上批准完成；此时裁决仍因藏品校验等另行覆盖。
        ClaimResponse ready = service.listClaims(c.caseKey()).get(0);
        assertThat(ready.currentApprovers()).containsExactly("r1", "r2");

        // 追加证据使证据版本升到 2：旧批准失效，需重新批准。
        addEvidence(c, "CL-1", "E-2", "摘要二");
        ClaimResponse stale = service.listClaims(c.caseKey()).get(0);
        assertThat(stale.currentApprovers()).isEmpty();

        // 撤销证据同样升版本，旧批准仍然失效。
        service.revokeEvidence("r1", rid(), c.caseKey(), "E-2");
        assertThat(service.listClaims(c.caseKey()).get(0).currentApprovers()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 裁决失败分支 422
    // ------------------------------------------------------------------

    @Test
    void decide422ListsMissingApprovalsAndMissingArtifacts() {
        CaseResponse c = newCase("A", "B");
        registerClaim(c, "CL-1", "alice", List.of("A"));
        addEvidence(c, "CL-1", "E-1", "摘要");
        approve(c, "CL-1", "r1"); // 只有一名评审人，且藏品 B 无人覆盖
        long version = currentVersion(c);

        assertThatThrownBy(() -> service.decide("judge", rid(), c.caseKey(),
                new DecideRequest(version, List.of("CL-1"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(422);
                    java.util.Map<String, Object> details = detailsOf(api);
                    assertThat(mapAt(details, "claimDeficiencies")).containsKey("CL-1");
                    assertThat(listAt(details, "missingArtifacts")).containsExactly("B");
                    assertThat(listAt(details, "duplicateArtifacts")).isEmpty();
                });

        // 422 失败不留任何裁决痕迹，案件仍 OPEN 且版本不变。
        assertThat(service.getCase(c.caseKey()).status()).isEqualTo("OPEN");
        assertThat(currentVersion(c)).isEqualTo(version);
    }

    @Test
    void decide422ListsWithdrawnClaimAndExcludesItFromCoverage() {
        CaseResponse c = newCase("A", "B");
        registerClaim(c, "CL-1", "alice", List.of("A", "B"));
        registerClaim(c, "CL-2", "bob", List.of("A", "B"));
        addEvidence(c, "CL-1", "E-1", "摘要");
        addEvidence(c, "CL-2", "E-2", "摘要");
        approve(c, "CL-1", "r1");
        approve(c, "CL-1", "r2");
        approve(c, "CL-2", "r1");
        approve(c, "CL-2", "r2");

        service.withdrawClaim("bob", rid(), c.caseKey(), "CL-2");
        long version = currentVersion(c);

        assertThatThrownBy(() -> service.decide("judge", rid(), c.caseKey(),
                new DecideRequest(version, List.of("CL-1", "CL-2"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(422);
                    java.util.Map<String, Object> details = detailsOf(api);
                    // CL-2 已撤回列入缺项；撤回主张不参与覆盖，CL-1 恰好覆盖 A/B，故无重无漏。
                    assertThat(listIn(mapAt(details, "claimDeficiencies"), "CL-2"))
                            .contains("withdrawn");
                    assertThat(listAt(details, "missingArtifacts")).isEmpty();
                    assertThat(listAt(details, "duplicateArtifacts")).isEmpty();
                });

        // 失败不留部分裁决。
        assertThat(service.getCase(c.caseKey()).status()).isEqualTo("OPEN");
    }

    @Test
    void decide422DuplicateArtifactsWhenActiveClaimsOverlap() {
        CaseResponse c = newCase("A");
        registerClaim(c, "CL-1", "alice", List.of("A"));
        registerClaim(c, "CL-2", "bob", List.of("A"));
        for (String claim : List.of("CL-1", "CL-2")) {
            addEvidence(c, claim, "E-" + claim, "摘要");
            approve(c, claim, "r1");
            approve(c, claim, "r2");
        }
        long version = currentVersion(c);

        assertThatThrownBy(() -> service.decide("judge", rid(), c.caseKey(),
                new DecideRequest(version, List.of("CL-1", "CL-2"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(422);
                    assertThat(listAt(detailsOf(api), "duplicateArtifacts")).containsExactly("A");
                });
    }

    @Test
    void decide422UnknownClaimKey() {
        CaseResponse c = newCase("A");
        long version = currentVersion(c);
        assertThatThrownBy(() -> service.decide("judge", rid(), c.caseKey(),
                new DecideRequest(version, List.of("GHOST"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(422);
                    java.util.Map<String, Object> details = detailsOf(api);
                    assertThat(mapAt(details, "claimDeficiencies")).containsKey("GHOST");
                    assertThat(listAt(details, "missingArtifacts")).containsExactly("A");
                });
    }

    @Test
    void decideRejectsInvalidSelectionAs400() {
        CaseResponse c = newCase("A");
        assertThatThrownBy(() -> service.decide("judge", rid(), c.caseKey(),
                new DecideRequest(1L, List.of("CL-1", "CL-1"))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(400);

        assertThatThrownBy(() -> service.decide("judge", rid(), c.caseKey(),
                new DecideRequest(0L, List.of("CL-1"))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(400);
    }

    @Test
    void decide409OnVersionConflict() {
        CaseResponse c = newCase("A");
        registerClaim(c, "CL-1", "alice", List.of("A"));
        addEvidence(c, "CL-1", "E-1", "摘要");
        approve(c, "CL-1", "r1");
        approve(c, "CL-1", "r2");
        long staleVersion = currentVersion(c) - 1;

        assertThatThrownBy(() -> service.decide("judge", rid(), c.caseKey(),
                new DecideRequest(staleVersion, List.of("CL-1"))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);
    }

    // ------------------------------------------------------------------
    // 裁决成功与终态
    // ------------------------------------------------------------------

    @Test
    void successfulDecisionFreezesEverythingAndRejectsFurtherWrites() {
        CaseResponse c = newCase("A", "B");
        registerClaim(c, "CL-1", "alice", List.of("A"));
        registerClaim(c, "CL-2", "bob", List.of("B"));
        addEvidence(c, "CL-1", "E-1", "alice 证据");
        addEvidence(c, "CL-2", "E-2", "bob 证据");
        approve(c, "CL-1", "r1");
        approve(c, "CL-1", "r2");
        approve(c, "CL-2", "r1");
        approve(c, "CL-2", "r3");
        long expectedVersion = currentVersion(c);

        DecisionResponse decision = service.decide("judge", rid(), c.caseKey(),
                new DecideRequest(expectedVersion, List.of("CL-1", "CL-2")));

        assertThat(decision.status()).isEqualTo("DECIDED");
        assertThat(decision.version()).isEqualTo(expectedVersion + 1);
        assertThat(decision.claims()).extracting("claimKey", "applicant")
                .containsExactly(tuple("CL-1", "alice"), tuple("CL-2", "bob"));
        assertThat(decision.artifacts()).extracting(FrozenArtifactView::artifactNo,
                        FrozenArtifactView::applicant)
                .containsExactly(tuple("A", "alice"), tuple("B", "bob"));
        assertThat(decision.evidence()).extracting("claimKey", "evidenceKey")
                .containsExactlyInAnyOrder(tuple("CL-1", "E-1"), tuple("CL-2", "E-2"));
        assertThat(decision.approvals()).extracting("claimKey", "reviewer")
                .containsExactlyInAnyOrder(
                        tuple("CL-1", "r1"), tuple("CL-1", "r2"),
                        tuple("CL-2", "r1"), tuple("CL-2", "r3"));

        // 终态案件版本、状态一致；历史查询读快照不重算。
        CaseResponse decided = service.getCase(c.caseKey());
        assertThat(decided.status()).isEqualTo("DECIDED");
        assertThat(decided.version()).isEqualTo(expectedVersion + 1);
        DecisionResponse queried = service.getDecision(c.caseKey());
        assertThat(queried).usingRecursiveComparison().isEqualTo(decision);

        // 终态拒绝一切新写入。
        assertThatThrownBy(() -> registerClaim(c, "CL-3", "carol", List.of("A")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);
        assertThatThrownBy(() -> service.withdrawClaim("judge", rid(), c.caseKey(), "CL-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);
        assertThatThrownBy(() -> addEvidence(c, "CL-1", "E-9", "摘要"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);
        assertThatThrownBy(() -> approve(c, "CL-1", "r9"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);
        assertThatThrownBy(() -> service.decide("judge", rid(), c.caseKey(),
                new DecideRequest(expectedVersion + 1, List.of("CL-1", "CL-2"))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);
    }

    @Test
    void getDecisionBeforeDecidedIs404() {
        CaseResponse c = newCase("A");
        assertThatThrownBy(() -> service.getDecision(c.caseKey()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(404);
    }

    @Test
    void staleApprovalsAreNeverFrozen() {
        // 证据版本 1 上取得两名批准后再追加证据：旧批准失效，裁决必须 422 而不是冻结失效批准。
        CaseResponse c = newCase("A");
        registerClaim(c, "CL-1", "alice", List.of("A"));
        addEvidence(c, "CL-1", "E-1", "摘要一");
        approve(c, "CL-1", "r1");
        approve(c, "CL-1", "r2");
        addEvidence(c, "CL-1", "E-2", "摘要二"); // 版本升至 2，批准失效
        long version = currentVersion(c);

        assertThatThrownBy(() -> service.decide("judge", rid(), c.caseKey(),
                new DecideRequest(version, List.of("CL-1"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(422);
                    java.util.Map<String, Object> details = detailsOf(api);
                    assertThat(listIn(mapAt(details, "claimDeficiencies"), "CL-1"))
                            .contains("current_approval");
                    assertThat(listIn(mapAt(details, "claimReviewers"), "CL-1")).isEmpty();
                });
    }
}
