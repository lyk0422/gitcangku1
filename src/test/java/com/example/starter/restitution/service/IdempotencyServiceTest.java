package com.example.starter.restitution.service;

import com.example.starter.restitution.AbstractH2IntegrationTest;
import com.example.starter.restitution.error.ApiException;
import com.example.starter.restitution.error.IdempotentReplayException;
import com.example.starter.restitution.web.dto.AddEvidenceRequest;
import com.example.starter.restitution.web.dto.CaseResponse;
import com.example.starter.restitution.web.dto.DecideRequest;
import com.example.starter.restitution.web.dto.RegisterClaimRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 写操作幂等语义的真实 H2 测试：同键同参重放、集合换序同参、异参/异操作者 409、失败不占键。
 */
class IdempotencyServiceTest extends AbstractH2IntegrationTest {

    @Autowired
    private RestitutionService service;
    @Autowired
    private ObjectMapper objectMapper;

    private static long caseCount(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return jdbc.queryForObject("select count(*) from restitution_case", Long.class);
    }

    @Test
    void sameRequestIdSameParamsReplaysFirstCreateCaseResult() throws Exception {
        CaseResponse first = service.createCase("officer", "RID-1", List.of("A", "B"));

        IdempotentReplayException replay = catchReplay(
                () -> service.createCase("officer", "RID-1", List.of("A", "B")));
        assertThat(replay.getHttpStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(replay.getResponseBody());
        assertThat(body.get("caseKey").asText()).isEqualTo(first.caseKey());

        // 重放不产生第二个案件。
        assertThat(caseCount(jdbcTemplate)).isEqualTo(1L);
    }

    @Test
    void collectionReorderingIsSameParameters() {
        CaseResponse c = service.createCase("officer", "RID-CASE", List.of("A", "B"));
        service.registerClaim("officer", "RID-CL", c.caseKey(),
                new RegisterClaimRequest("CL-1", "alice", "说明", List.of("A", "B")));

        // 同一 requestId、集合换序：视为同参，重放首次结果而非新建主张。
        assertThatThrownBy(() -> service.registerClaim("officer", "RID-CL", c.caseKey(),
                new RegisterClaimRequest("CL-1", "alice", "说明", List.of("B", "A"))))
                .isInstanceOf(IdempotentReplayException.class);

        assertThat(service.listClaims(c.caseKey())).hasSize(1);
    }

    @Test
    void sameRequestIdDifferentParamsOrActorConflicts() {
        service.createCase("officer", "RID-2", List.of("A"));

        assertThatThrownBy(() -> service.createCase("officer", "RID-2", List.of("B")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);

        assertThatThrownBy(() -> service.createCase("someone-else", "RID-2", List.of("A")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(409);
    }

    @Test
    void failedAttemptDoesNotOccupyRequestId() {
        CaseResponse c = service.createCase("officer", "RID-CC", List.of("A"));

        // 首次使用该 requestId 的写操作因案件不存在而失败（404），不应占键。
        assertThatThrownBy(() -> service.registerClaim("officer", "RID-REUSE", "C-missing",
                new RegisterClaimRequest("CL-X", "alice", "说明", List.of("A"))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(404);

        // 同 requestId 随后用于合法写操作应当成功。
        com.example.starter.restitution.web.dto.ClaimResponse claimResult =
                service.registerClaim("officer", "RID-REUSE", c.caseKey(),
                        new RegisterClaimRequest("CL-1", "alice", "说明", List.of("A")));
        assertThat(claimResult.claimKey()).isEqualTo("CL-1");
    }

    @Test
    void successfulDecisionIsReplayedNotRecomputed() {
        CaseResponse c = service.createCase("officer", "RID-DC", List.of("A"));
        service.registerClaim("officer", "RID-RC", c.caseKey(),
                new RegisterClaimRequest("CL-1", "alice", "说明", List.of("A")));
        service.addEvidence("officer", "RID-E", c.caseKey(), "CL-1",
                new AddEvidenceRequest("E-1", "摘要"));
        service.approve("r1", "RID-A1", c.caseKey(), "CL-1");
        service.approve("r2", "RID-A2", c.caseKey(), "CL-1");
        long version = service.getCase(c.caseKey()).version();

        service.decide("judge", "RID-DECIDE", c.caseKey(),
                new DecideRequest(version, List.of("CL-1")));

        assertThatThrownBy(() -> service.decide("judge", "RID-DECIDE", c.caseKey(),
                new DecideRequest(version, List.of("CL-1"))))
                .isInstanceOfSatisfying(IdempotentReplayException.class,
                        replay -> assertThat(replay.getHttpStatus()).isEqualTo(201));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from frozen_decision", Long.class)).isEqualTo(1L);
    }

    @Test
    void noContentWriteIsReplayedWith204() {
        CaseResponse c = service.createCase("officer", "RID-AC", List.of("A"));
        service.registerClaim("officer", "RID-ARC", c.caseKey(),
                new RegisterClaimRequest("CL-1", "alice", "说明", List.of("A")));
        service.addEvidence("officer", "RID-AE", c.caseKey(), "CL-1",
                new AddEvidenceRequest("E-1", "摘要"));
        service.approve("r1", "RID-APPROVE", c.caseKey(), "CL-1");

        IdempotentReplayException replay =
                catchReplay(() -> service.approve("r1", "RID-APPROVE", c.caseKey(), "CL-1"));
        assertThat(replay.getHttpStatus()).isEqualTo(204);
        assertThat(replay.getResponseBody()).isNull();
    }

    private static IdempotentReplayException catchReplay(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        try {
            call.call();
        } catch (IdempotentReplayException replay) {
            return replay;
        } catch (Throwable other) {
            throw new AssertionError("期望幂等重放异常，实际为: " + other, other);
        }
        throw new AssertionError("期望抛出幂等重放异常");
    }
}
