package com.example.starter.curtailment;

import com.example.starter.curtailment.api.ApiDtos;
import com.example.starter.curtailment.api.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 采样上报与对账：合成 100 kW 上限场景的成功、失败分支、迟到更正与差额链、重复对账不重复累计。
 */
class SampleAndReconcileTest extends AbstractCurtailmentIntegrationTest {

    @Test
    void reconcileFourEightySamplesYieldsEightyKwh() {
        ApiDtos.InstructionView instruction = newConfirmedInstruction("H1", "100");
        reportFour(instruction.id(), instruction.startUtc(), "80");
        ApiDtos.ReconciliationView reconciliation = service.reconcile(instruction.id(),
                new ApiDtos.ReconcileRequest("recon-1", instruction.startUtc()));
        assertThat(reconciliation.totalEnergyKwh()).isEqualByComparingTo(new BigDecimal("80.000"));
        assertThat(reconciliation.ticks()).hasSize(4);
        assertThat(reconciliation.ticks()).allSatisfy(tick -> {
            assertThat(tick.avgPowerKw()).isEqualByComparingTo(new BigDecimal("80"));
            assertThat(tick.energyKwh()).isEqualByComparingTo(new BigDecimal("20.000"));
            assertThat(tick.sampleVersion()).isEqualTo(1);
        });
        assertThat(reconciliation.instructionVersion()).isEqualTo(1);
    }

    @Test
    void reconcileRejectsWholeWindowWhenAnySampleExceedsCap() {
        ApiDtos.InstructionView instruction = newConfirmedInstruction("H2", "100");
        reportFour(instruction.id(), instruction.startUtc(), "80");
        // 任一采样 101 kW 超上限：整次拒绝
        service.reportSample(instruction.id(), new ApiDtos.SampleRequest(
                "fix-1", instruction.startUtc().plusSeconds(1800), new BigDecimal("101")));
        assertThatThrownBy(() -> service.reconcile(instruction.id(),
                new ApiDtos.ReconcileRequest("recon-2", instruction.startUtc())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("RECONCILE_REJECTED");
                    assertThat(e.problems()).hasSize(1);
                    assertThat(e.problems().get(0).reason()).isEqualTo("EXCEEDS_CAP");
                    assertThat(e.problems().get(0).tickStart())
                            .isEqualTo(instruction.startUtc().plusSeconds(1800));
                });
        // 不生成部分结果
        assertThat(count("reconciliation")).isZero();
        assertThat(count("reconciliation_tick")).isZero();
    }

    @Test
    void reconcileRejectsMissingSampleMisalignedWindowAndUnconfirmed() {
        ApiDtos.InstructionView confirmed = newConfirmedInstruction("H3", "100");
        report(confirmed.id(), confirmed.startUtc(), "80");
        // 缺采样
        assertThatThrownBy(() -> service.reconcile(confirmed.id(),
                new ApiDtos.ReconcileRequest("recon-m", confirmed.startUtc())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.problems()).hasSize(3);
                    assertThat(e.problems()).allSatisfy(p -> assertThat(p.reason()).isEqualTo("MISSING_SAMPLE"));
                });
        // 窗口起点不对齐
        assertThatThrownBy(() -> service.reconcile(confirmed.id(),
                new ApiDtos.ReconcileRequest("recon-a", confirmed.startUtc().plusSeconds(60))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.problems().get(0).reason()).isEqualTo("NOT_ALIGNED"));
        // 指令未确认
        ApiDtos.InstructionView pending = newInstruction("H4", "100");
        assertThatThrownBy(() -> service.reconcile(pending.id(),
                new ApiDtos.ReconcileRequest("recon-u", pending.startUtc())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("NOT_CONFIRMED");
                    assertThat(e.problems()).hasSize(4);
                });
        assertThat(count("reconciliation")).isZero();
    }

    @Test
    void sampleRejectsMisalignedTickOutOfIntervalAndUnconfirmed() {
        ApiDtos.InstructionView pending = newInstruction("H5", "100");
        assertThatThrownBy(() -> report(pending.id(), pending.startUtc(), "80"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_CONFIRMED"));
        ApiDtos.InstructionView confirmed = service.confirm(pending.id(), new ApiDtos.ConfirmRequest("c-h5"));
        assertThatThrownBy(() -> report(confirmed.id(), confirmed.startUtc().plusSeconds(30), "80"))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.problems().get(0).reason()).isEqualTo("NOT_ALIGNED"));
        assertThatThrownBy(() -> report(confirmed.id(), confirmed.endUtc(), "80"))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.problems().get(0).reason()).isEqualTo("OUT_OF_INTERVAL"));
        assertThat(count("power_sample")).isZero();
    }

    @Test
    void duplicateReconcileDoesNotDoubleCount() {
        ApiDtos.InstructionView instruction = newConfirmedInstruction("H6", "100");
        reportFour(instruction.id(), instruction.startUtc(), "80");
        ApiDtos.ReconciliationView first = service.reconcile(instruction.id(),
                new ApiDtos.ReconcileRequest("recon-dup", instruction.startUtc()));
        // 同 requestId 重放
        ApiDtos.ReconciliationView replayed = service.reconcile(instruction.id(),
                new ApiDtos.ReconcileRequest("recon-dup", instruction.startUtc()));
        assertThat(replayed).isEqualTo(first);
        // 不同 requestId 同窗口：返回既有对账单，不重复累计
        ApiDtos.ReconciliationView again = service.reconcile(instruction.id(),
                new ApiDtos.ReconcileRequest("recon-dup-2", instruction.startUtc()));
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(count("reconciliation")).isEqualTo(1);
        assertThat(count("reconciliation_tick")).isEqualTo(4);
        BigDecimal total = jdbc.queryForObject("SELECT SUM(total_energy_kwh) FROM reconciliation", BigDecimal.class);
        assertThat(total).isEqualByComparingTo(new BigDecimal("80.000"));
    }

    @Test
    void lateCorrectionKeepsOriginalAndAppendsAdjustment() {
        ApiDtos.InstructionView instruction = newConfirmedInstruction("H7", "100");
        reportFour(instruction.id(), instruction.startUtc(), "80");
        ApiDtos.ReconciliationView reconciliation = service.reconcile(instruction.id(),
                new ApiDtos.ReconcileRequest("recon-c", instruction.startUtc()));
        // 迟到更正：第二刻度 80 -> 60
        ApiDtos.SampleView correction = service.reportSample(instruction.id(), new ApiDtos.SampleRequest(
                "fix-late", instruction.startUtc().plusSeconds(900), new BigDecimal("60")));
        assertThat(correction.sampleVersion()).isEqualTo(2);
        ApiDtos.InstructionDetailView detail = service.getDetail(instruction.id());
        // 原采样与原对账保留
        assertThat(detail.samples()).hasSize(5);
        assertThat(detail.reconciliations()).hasSize(1);
        assertThat(detail.reconciliations().get(0).totalEnergyKwh()).isEqualByComparingTo(new BigDecimal("80.000"));
        assertThat(detail.reconciliations().get(0).ticks().get(1).avgPowerKw()).isEqualByComparingTo(new BigDecimal("80"));
        // 追加差额：(60-80)*0.25 = -5 kWh
        assertThat(detail.adjustments()).hasSize(1);
        ApiDtos.AdjustmentView adjustment = detail.adjustments().get(0);
        assertThat(adjustment.reconciliationId()).isEqualTo(reconciliation.id());
        assertThat(adjustment.deltaEnergyKwh()).isEqualByComparingTo(new BigDecimal("-5.000"));
        assertThat(adjustment.oldAvgPowerKw()).isEqualByComparingTo(new BigDecimal("80"));
        assertThat(adjustment.newAvgPowerKw()).isEqualByComparingTo(new BigDecimal("60"));
        // 指令版本随更正递增
        assertThat(detail.instruction().version()).isEqualTo(2);
    }

    @Test
    void correctionBeforeReconcileUsesLatestVersion() {
        ApiDtos.InstructionView instruction = newConfirmedInstruction("H8", "100");
        reportFour(instruction.id(), instruction.startUtc(), "80");
        report(instruction.id(), instruction.startUtc(), "90");
        ApiDtos.ReconciliationView reconciliation = service.reconcile(instruction.id(),
                new ApiDtos.ReconcileRequest("recon-v", instruction.startUtc()));
        // (90+80+80+80)*0.25 = 82.5
        assertThat(reconciliation.totalEnergyKwh()).isEqualByComparingTo(new BigDecimal("82.500"));
        assertThat(reconciliation.ticks().get(0).sampleVersion()).isEqualTo(2);
        assertThat(count("reconciliation_adjustment")).isZero();
    }
}