package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.allocation.AllocationService;
import com.example.starter.allocation.dto.AllocationRequest;
import com.example.starter.allocation.dto.CompletionRequest;
import com.example.starter.common.ApiException;
import com.example.starter.common.ErrorReason;
import com.example.starter.prediction.PredictionService;
import com.example.starter.prediction.dto.PredictionRequest;

/**
 * 拒绝分支测试：窗口缺口、仰角不足、容量超限、过期预测、超计划完成，
 * 逐一核对可区分的原因码，且失败后不留下任何占用记录。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:rejecttest;MODE=MySQL;DB_CLOSE_DELAY=-1")
class AllocationRejectionTest extends AbstractIntegrationTest {

    @Autowired
    private PredictionService predictionService;

    @Autowired
    private AllocationService allocationService;

    private void createPrediction(String key, String start, String end, String elevation, long plannedMb) {
        predictionService.create(key, new PredictionRequest("SAT-1", Instant.parse(start),
                Instant.parse(end), new BigDecimal(elevation), plannedMb));
    }

    private AllocationRequest request(String requestId, String key, Integer version,
            String station, String start, String end) {
        return new AllocationRequest(requestId, key, version, station,
                Instant.parse(start), Instant.parse(end));
    }

    @Test
    void windowGapIsRejected() {
        createPrediction("P-GAP", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z", "10", 500);
        assertThatThrownBy(() -> allocationService.allocate(
                request("r-gap", "P-GAP", null, "A", "2026-01-01T12:00:00Z", "2026-01-01T12:11:00Z")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.reason()).isEqualTo(ErrorReason.WINDOW_GAP));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slot_allocation", Long.class)).isZero();
    }

    @Test
    void lowElevationIsRejected() {
        createPrediction("P-ELEV", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z", "3", 500);
        assertThatThrownBy(() -> allocationService.allocate(
                request("r-elev", "P-ELEV", null, "A", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.reason()).isEqualTo(ErrorReason.ELEVATION_BELOW_THRESHOLD));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slot_allocation", Long.class)).isZero();
    }

    @Test
    void overCapacityIsRejected() {
        // 站点 A 容量 100 MB/分钟，5 分钟时隙最多容纳 500 MB，计划 600 MB 超限
        createPrediction("P-CAP", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z", "10", 600);
        assertThatThrownBy(() -> allocationService.allocate(
                request("r-cap", "P-CAP", null, "A", "2026-01-01T12:00:00Z", "2026-01-01T12:05:00Z")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.reason()).isEqualTo(ErrorReason.CAPACITY_EXCEEDED));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slot_allocation", Long.class)).isZero();
    }

    @Test
    void stalePredictionVersionIsRejected() {
        createPrediction("P-STALE", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z", "10", 500);
        predictionService.revise("P-STALE", new PredictionRequest("SAT-1",
                Instant.parse("2026-01-01T12:00:00Z"), Instant.parse("2026-01-01T12:10:00Z"),
                new BigDecimal("10"), 500));
        assertThatThrownBy(() -> allocationService.allocate(
                request("r-stale", "P-STALE", 1, "A", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.reason()).isEqualTo(ErrorReason.STALE_PREDICTION));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM slot_allocation", Long.class)).isZero();
    }

    @Test
    void actualBytesBeyondPlanIsRejected() {
        createPrediction("P-OVER", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z", "10", 1);
        var allocation = allocationService.allocate(
                request("r-over", "P-OVER", null, "A", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z"));
        long tooManyBytes = AllocationService.BYTES_PER_MB + 1;
        assertThatThrownBy(() -> allocationService.complete(allocation.id(),
                new CompletionRequest("c-over", tooManyBytes)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.reason()).isEqualTo(ErrorReason.ACTUAL_EXCEEDS_PLANNED));
        // 失败事务不留下完成标记，记录仍处于已确认状态
        assertThat(allocationService.findById(allocation.id()).status().name()).isEqualTo("CONFIRMED");
        assertThat(allocationService.findById(allocation.id()).actualBytes()).isNull();
    }

    @Test
    void unknownPredictionAndStationAreNotFound() {
        assertThatThrownBy(() -> allocationService.allocate(
                request("r-404", "NOPE", null, "A", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.reason()).isEqualTo(ErrorReason.NOT_FOUND));
        createPrediction("P-404", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z", "10", 500);
        assertThatThrownBy(() -> allocationService.allocate(
                request("r-404b", "P-404", null, "Z", "2026-01-01T12:00:00Z", "2026-01-01T12:10:00Z")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.reason()).isEqualTo(ErrorReason.NOT_FOUND));
    }
}