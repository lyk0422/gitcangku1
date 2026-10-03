package com.example.starter.web;

import com.example.starter.domain.Inflorescence;
import com.example.starter.domain.PollinationConfirmation;
import com.example.starter.domain.PollinationOperation;
import com.example.starter.service.PollinationService.InflorescenceTimeline;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 花序时间线响应：窗口、花粉来源、操作及结果确认按时间排列。
 */
public record TimelineResponse(
        long inflorescenceId,
        String code,
        WindowView window,
        Instant removedAt,
        String confirmedPollenBatchNo,
        Instant confirmedAt,
        List<OperationView> operations) {

    /** 授粉窗口视图。expired 按当前 UTC 时钟计算。 */
    public record WindowView(Instant firstOpenAt, Instant closeAt, int windowHours, boolean expired) {
    }

    /** 操作及对应结果确认（可空）。 */
    public record OperationView(
            long id,
            String requestId,
            String pollenBatchNo,
            String technician,
            Instant executedAt,
            String status,
            Instant revokedAt,
            ConfirmationView confirmation) {
    }

    /** 结果确认视图。 */
    public record ConfirmationView(
            long id,
            String requestId,
            String result,
            String technician,
            Instant confirmedAt) {
    }

    /** 由领域对象组装响应。 */
    public static TimelineResponse from(InflorescenceTimeline timeline) {
        Inflorescence inf = timeline.inflorescence();
        Map<Long, PollinationConfirmation> byOperation = timeline.confirmations().stream()
                .collect(Collectors.toMap(PollinationConfirmation::operationId, Function.identity()));
        List<OperationView> ops = timeline.operations().stream()
                .map(op -> toOperationView(op, byOperation.get(op.id())))
                .toList();
        WindowView window = new WindowView(inf.firstOpenAt(), inf.window().closeAt(),
                inf.windowHours(), timeline.windowExpired());
        return new TimelineResponse(inf.id(), inf.code(), window, inf.removedAt(),
                inf.confirmedPollenBatchNo(), inf.confirmedAt(), ops);
    }

    private static OperationView toOperationView(PollinationOperation op, PollinationConfirmation conf) {
        ConfirmationView view = conf == null ? null
                : new ConfirmationView(conf.id(), conf.requestId(), conf.result().name(),
                        conf.technician(), conf.confirmedAt());
        return new OperationView(op.id(), op.requestId(), op.pollenBatchNo(), op.technician(),
                op.executedAt(), op.status().name(), op.revokedAt(), view);
    }
}