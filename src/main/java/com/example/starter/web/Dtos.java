package com.example.starter.web;

import com.example.starter.model.PassPrediction;
import com.example.starter.model.SlotAllocation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * API 传输对象集合。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 发布（或修订）预测请求。 */
    public record PublishPredictionRequest(
            @NotBlank String satelliteCode,
            @NotNull Instant windowStartUtc,
            @NotNull Instant windowEndUtc,
            @NotNull BigDecimal minElevationDeg,
            @Positive long plannedBytes) {
    }

    /** 预测响应。 */
    public record PredictionResponse(
            long id,
            String satelliteCode,
            int version,
            Instant windowStartUtc,
            Instant windowEndUtc,
            BigDecimal minElevationDeg,
            long plannedBytes,
            boolean superseded) {

        public static PredictionResponse from(PassPrediction p) {
            return new PredictionResponse(p.id(), p.satelliteCode(), p.version(),
                    p.windowStartUtc(), p.windowEndUtc(), p.minElevationDeg(),
                    p.plannedBytes(), p.superseded());
        }
    }

    /** 分配申请请求。expectedPredictionVersion 可空，用于拒绝基于过期预测的确认。 */
    public record AllocateRequest(
            @NotBlank String requestId,
            @NotBlank String satelliteCode,
            @NotBlank String stationCode,
            @NotNull Instant slotStartUtc,
            @NotNull Instant slotEndUtc,
            Integer expectedPredictionVersion) {
    }

    /** 取消请求。 */
    public record CancelRequest(@NotBlank @Size(max = 128) String reason) {
    }

    /** 完成回报请求。 */
    public record CompleteRequest(
            @NotBlank String requestId,
            @PositiveOrZero long actualBytes) {
    }

    /** 分配响应，含预测版本快照、容量快照、接收实绩与取消原因。 */
    public record AllocationResponse(
            long id,
            String requestId,
            long predictionId,
            int predictionVersion,
            long stationId,
            Instant slotStartUtc,
            Instant slotEndUtc,
            long capacityBytesPerMin,
            long plannedBytes,
            String status,
            String cancelReason,
            Long actualBytes) {

        public static AllocationResponse from(SlotAllocation a) {
            return new AllocationResponse(a.id(), a.requestId(), a.predictionId(),
                    a.predictionVersion(), a.stationId(), a.slotStartUtc(), a.slotEndUtc(),
                    a.capacityBytesPerMin(), a.plannedBytes(), a.status().name(),
                    a.cancelReason(), a.actualBytes());
        }
    }

    /** 错误响应，reason 为可区分的拒绝原因码。 */
    public record ErrorResponse(String reason, String message) {
    }
}