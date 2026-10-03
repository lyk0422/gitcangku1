package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.allocation.AllocationService;
import com.example.starter.allocation.AllocationStatus;
import com.example.starter.allocation.SlotAllocation;
import com.example.starter.allocation.dto.AllocationRequest;
import com.example.starter.allocation.dto.CompletionRequest;
import com.example.starter.prediction.PassPrediction;
import com.example.starter.prediction.PredictionService;
import com.example.starter.prediction.dto.PredictionRequest;

/**
 * 预测修订测试：未开始分配重新校核，不满足新窗口的转为待重新分配并释放天线，
 * 已完成接收保留旧预测证据。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:revisiontest;MODE=MySQL;DB_CLOSE_DELAY=-1")
class PredictionRevisionTest extends AbstractIntegrationTest {

    @Autowired
    private PredictionService predictionService;

    @Autowired
    private AllocationService allocationService;

    private PredictionRequest body(String start, String end, String elevation, long plannedMb) {
        return new PredictionRequest("SAT-1", Instant.parse(start), Instant.parse(end),
                new BigDecimal(elevation), plannedMb);
    }

    private AllocationRequest request(String requestId, String key, String station,
            String start, String end) {
        return new AllocationRequest(requestId, key, null, station,
                Instant.parse(start), Instant.parse(end));
    }

    @Test
    void revisionMovesUnsatisfiedAllocationsToPendingAndKeepsCompletedEvidence() {
        predictionService.create("P-REV", body("2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z", "10", 500));
        SlotAllocation pending = allocationService.allocate(
                request("r-pend", "P-REV", "A", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z"));
        SlotAllocation completed = allocationService.allocate(
                request("r-done", "P-REV", "B", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z"));
        allocationService.complete(completed.id(), new CompletionRequest("c-done", 1024));

        // 修订：窗口收缩为 12:05-12:10，原 12:00 起点的未开始分配不再满足
        predictionService.revise("P-REV", body("2026-01-01T12:05:00Z", "2026-01-01T12:10:00Z", "10", 500));

        SlotAllocation reloaded = allocationService.findById(pending.id());
        assertThat(reloaded.status()).isEqualTo(AllocationStatus.PENDING_REASSIGNMENT);
        assertThat(allocationService.pendingReassignment())
                .extracting(SlotAllocation::id).containsExactly(pending.id());

        SlotAllocation doneReloaded = allocationService.findById(completed.id());
        assertThat(doneReloaded.status()).isEqualTo(AllocationStatus.COMPLETED);
        assertThat(doneReloaded.predictionVersion()).isEqualTo(1);
        assertThat(doneReloaded.actualBytes()).isEqualTo(1024);

        List<PassPrediction> versions = predictionService.listVersions("P-REV");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).status()).isEqualTo(PassPrediction.STATUS_SUPERSEDED);
        assertThat(versions.get(1).status()).isEqualTo(PassPrediction.STATUS_CURRENT);

        // 待重排记录不再占用天线，同时段新申请可以成功
        SlotAllocation reallocated = allocationService.allocate(
                request("r-new", "P-REV", "A", "2026-01-01T12:05:00Z", "2026-01-01T12:10:00Z"));
        assertThat(reallocated.status()).isEqualTo(AllocationStatus.CONFIRMED);
    }

    @Test
    void revisionWithLowerElevationOrBiggerPlanTriggersPending() {
        predictionService.create("P-REV2", body("2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z", "10", 500));
        SlotAllocation allocation = allocationService.allocate(
                request("r-e2", "P-REV2", "A", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z"));

        // 修订后仰角 4 度低于站点 A 门槛 5 度
        predictionService.revise("P-REV2", body("2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z", "4", 500));
        assertThat(allocationService.findById(allocation.id()).status())
                .isEqualTo(AllocationStatus.PENDING_REASSIGNMENT);
    }

    @Test
    void startedAllocationIsNotRevalidated() {
        predictionService.create("P-REV3", body("2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z", "10", 500));
        SlotAllocation allocation = allocationService.allocate(
                request("r-s3", "P-REV3", "A", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z"));
        // 时钟推进到时隙开始之后，该分配视为已开始
        clock.setInstant(Instant.parse("2026-01-01T12:05:00Z"));
        predictionService.revise("P-REV3", body("2026-01-01T12:06:00Z", "2026-01-01T12:10:00Z", "10", 500));
        assertThat(allocationService.findById(allocation.id()).status())
                .isEqualTo(AllocationStatus.CONFIRMED);
    }
}