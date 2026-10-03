package com.example.starter.curtailment;

import com.example.starter.curtailment.api.ApiDtos;
import com.example.starter.curtailment.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 指令生命周期：创建校验、区间重叠、确认/撤销规则与幂等、派生结束状态。
 */
class InstructionLifecycleTest extends AbstractCurtailmentIntegrationTest {

    @Test
    void createRejectsInvalidIntervalAndMisalignment() {
        Instant start = TestClockConfiguration.BASE_TIME.plusSeconds(3600);
        assertThatThrownBy(() -> service.createInstruction(new ApiDtos.CreateInstructionRequest(
                "U1", start, start, new BigDecimal("100"), null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("INVALID_INTERVAL");
        assertThatThrownBy(() -> service.createInstruction(new ApiDtos.CreateInstructionRequest(
                "U1", start.plusSeconds(7), start.plusSeconds(3600), new BigDecimal("100"), null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("NOT_ALIGNED");
    }

    @Test
    void createRejectsOverlapButAllowsAdjacentAndRevokedReuse() {
        ApiDtos.InstructionView first = newInstruction("U2", "100");
        Instant start = first.startUtc();
        // 重叠：跨区间
        assertThatThrownBy(() -> service.createInstruction(new ApiDtos.CreateInstructionRequest(
                "U2", start.plusSeconds(1800), start.plusSeconds(5400), new BigDecimal("50"), null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("INSTRUCTION_OVERLAP");
        // 相邻（左闭右开）：允许
        ApiDtos.InstructionView adjacent = service.createInstruction(new ApiDtos.CreateInstructionRequest(
                "U2", first.endUtc(), first.endUtc().plusSeconds(3600), new BigDecimal("50"), null));
        assertThat(adjacent.id()).isPositive();
        // 撤销后同区间可再建
        service.revoke(first.id(), new ApiDtos.RevokeRequest("revoke-1"));
        ApiDtos.InstructionView reused = service.createInstruction(new ApiDtos.CreateInstructionRequest(
                "U2", start, first.endUtc(), new BigDecimal("80"), null));
        assertThat(reused.status()).isEqualTo("PENDING");
    }

    @Test
    void confirmReplayAndConflict() {
        ApiDtos.InstructionView instruction = newInstruction("U3", "100");
        ApiDtos.InstructionView confirmed = service.confirm(instruction.id(), new ApiDtos.ConfirmRequest("c-1"));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.version()).isEqualTo(1);
        // 同 requestId 同参重放：返回首次结果，不产生二次变更
        ApiDtos.InstructionView replayed = service.confirm(instruction.id(), new ApiDtos.ConfirmRequest("c-1"));
        assertThat(replayed).isEqualTo(confirmed);
        assertThat(count("idempotency_record")).isEqualTo(1);
        // 同 requestId 异参（作用于另一条指令）：409
        ApiDtos.InstructionView other = newInstruction("U4", "100");
        assertThatThrownBy(() -> service.confirm(other.id(), new ApiDtos.ConfirmRequest("c-1")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT);
        // 不同 requestId 重复确认：409 状态冲突
        assertThatThrownBy(() -> service.confirm(instruction.id(), new ApiDtos.ConfirmRequest("c-2")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("INSTRUCTION_NOT_PENDING");
    }

    @Test
    void confirmMustPrecedeIntervalStart() {
        ApiDtos.InstructionView instruction = newInstruction("U5", "100");
        clock.setInstant(instruction.startUtc());
        assertThatThrownBy(() -> service.confirm(instruction.id(), new ApiDtos.ConfirmRequest("c-late")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("CONFIRM_TOO_LATE");
    }

    @Test
    void revokeIsTerminalAndReplayable() {
        ApiDtos.InstructionView instruction = newConfirmedInstruction("U6", "100");
        ApiDtos.InstructionView revoked = service.revoke(instruction.id(), new ApiDtos.RevokeRequest("r-1"));
        assertThat(revoked.status()).isEqualTo("REVOKED");
        // 已撤销指令不能确认
        assertThatThrownBy(() -> service.confirm(instruction.id(), new ApiDtos.ConfirmRequest("c-after-revoke")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("INSTRUCTION_REVOKED");
        // 撤销可重放（同 requestId）
        ApiDtos.InstructionView replayed = service.revoke(instruction.id(), new ApiDtos.RevokeRequest("r-1"));
        assertThat(replayed.status()).isEqualTo("REVOKED");
    }

    @Test
    void endedIsDerivedAndDoesNotRevert() {
        ApiDtos.InstructionView instruction = newConfirmedInstruction("U7", "100");
        clock.setInstant(instruction.endUtc().plusSeconds(1));
        ApiDtos.InstructionDetailView detail = service.getDetail(instruction.id());
        assertThat(detail.instruction().status()).isEqualTo("ENDED");
        // 结束后迟到更正：状态仍为 ENDED
        report(instruction.id(), instruction.startUtc(), "70");
        report(instruction.id(), instruction.startUtc(), "75");
        ApiDtos.InstructionDetailView after = service.getDetail(instruction.id());
        assertThat(after.instruction().status()).isEqualTo("ENDED");
        assertThat(after.instruction().version()).isEqualTo(detail.instruction().version() + 1);
    }
}