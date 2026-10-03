package com.example.starter.web;

import com.example.starter.domain.Assignment;
import com.example.starter.domain.Segment;
import com.example.starter.service.PilotageService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * REST 请求与响应模型。时间一律为 UTC ISO-8601。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 航段申请请求。 */
    public record CreateSegmentRequest(@NotBlank String vesselCode,
                                       @Min(1) int requiredGrade,
                                       @NotNull Instant estStartUtc,
                                       @NotNull Instant estEndUtc,
                                       @NotBlank String boardingPoint) {
    }

    /** 指派请求，requestId 为幂等键。 */
    public record AssignRequest(@NotBlank String requestId,
                                @NotNull Long segmentId,
                                @NotNull Long pilotId) {
    }

    /** 登轮确认请求，requestId 为幂等键。 */
    public record BoardRequest(@NotBlank String requestId) {
    }

    /** 登轮点修改请求。 */
    public record BoardingPointRequest(@NotBlank String boardingPoint) {
    }

    /** 航段响应。 */
    public record SegmentResponse(long id, String vesselCode, int requiredGrade,
                                  Instant estStartUtc, Instant estEndUtc,
                                  String boardingPoint, String originalBoardingPoint,
                                  String status, Instant cancelledAtUtc, Instant createdAtUtc) {
        public static SegmentResponse of(Segment s) {
            return new SegmentResponse(s.id(), s.vesselCode(), s.requiredGrade(), s.estStartUtc(), s.estEndUtc(),
                    s.boardingPoint(), s.originalBoardingPoint(), s.status().name(), s.cancelledAtUtc(),
                    s.createdAtUtc());
        }
    }

    /** 指派响应。 */
    public record AssignmentResponse(long id, String requestId, long segmentId, long pilotId, String status,
                                     Instant estStartUtc, Instant estEndUtc, Instant boardedAtUtc,
                                     Instant departedAtUtc, Instant revokedAtUtc, String boardingRequestId,
                                     Instant createdAtUtc, boolean replayed) {
        public static AssignmentResponse of(Assignment a, boolean replayed) {
            return new AssignmentResponse(a.id(), a.requestId(), a.segmentId(), a.pilotId(), a.status().name(),
                    a.estStartUtc(), a.estEndUtc(), a.boardedAtUtc(), a.departedAtUtc(), a.revokedAtUtc(),
                    a.boardingRequestId(), a.createdAtUtc(), replayed);
        }
    }

    /** 引航员时间轴条目。 */
    public record PilotTimelineEntry(long assignmentId, String requestId, long segmentId, String vesselCode,
                                     String status, Instant estStartUtc, Instant estEndUtc,
                                     Instant boardedAtUtc, Instant departedAtUtc, Instant revokedAtUtc) {
        public static PilotTimelineEntry of(PilotageService.PilotTimelineItem item) {
            Assignment a = item.assignment();
            return new PilotTimelineEntry(a.id(), a.requestId(), a.segmentId(), item.vesselCode(), a.status().name(),
                    a.estStartUtc(), a.estEndUtc(), a.boardedAtUtc(), a.departedAtUtc(), a.revokedAtUtc());
        }
    }

    /** 船舶时间轴条目：航段及其最近一次指派事实。 */
    public record VesselTimelineEntry(long segmentId, String segmentStatus,
                                      String boardingPoint, String originalBoardingPoint,
                                      Instant estStartUtc, Instant estEndUtc, Instant cancelledAtUtc,
                                      Long assignmentId, Long pilotId, String assignmentStatus,
                                      Instant boardedAtUtc, Instant departedAtUtc, Instant revokedAtUtc) {
        public static VesselTimelineEntry of(PilotageService.VesselTimelineItem item) {
            Segment s = item.segment();
            Assignment a = item.latestAssignment();
            return new VesselTimelineEntry(s.id(), s.status().name(), s.boardingPoint(), s.originalBoardingPoint(),
                    s.estStartUtc(), s.estEndUtc(), s.cancelledAtUtc(),
                    a == null ? null : a.id(), a == null ? null : a.pilotId(),
                    a == null ? null : a.status().name(),
                    a == null ? null : a.boardedAtUtc(),
                    a == null ? null : a.departedAtUtc(),
                    a == null ? null : a.revokedAtUtc());
        }
    }

    /** 时间轴响应。 */
    public record TimelineResponse<T>(List<T> entries) {
    }

    /** 错误响应。 */
    public record ErrorResponse(String code, String message) {
    }
}