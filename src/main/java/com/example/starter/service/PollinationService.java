package com.example.starter.service;

import com.example.starter.domain.Inflorescence;
import com.example.starter.domain.PollenBatch;
import com.example.starter.domain.PollenBatch.BatchStatus;
import com.example.starter.domain.PollinationOperation;
import com.example.starter.domain.PollinationOperation.OperationStatus;
import com.example.starter.repo.InflorescenceRepository;
import com.example.starter.repo.PollenBatchRepository;
import com.example.starter.repo.PollinationOperationRepository;
import com.example.starter.web.ConfirmOperationRequest;
import com.example.starter.web.CreateInflorescenceRequest;
import com.example.starter.web.CreateOperationRequest;
import com.example.starter.web.InflorescenceView;
import com.example.starter.web.OperationView;
import com.example.starter.web.PollenBatchView;
import com.example.starter.web.TimelineView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 授粉窗口核心服务。
 * 规则：窗口为首次开放时刻起 24 小时（左闭右开，UTC）；同一花序同一窗口仅一条有效操作；
 * 结果确认须在执行后 72 小时内；确认成功固化花粉批号且不可撤销；撤销未确认操作后窗口内可重做；
 * 操作与确认均按 requestId 幂等（同参重放、异参 409）。
 */
@Service
public class PollinationService {

    /** 授粉窗口时长：24 小时。 */
    public static final long WINDOW_HOURS = 24;

    /** 结果确认期限：操作执行后 72 小时。 */
    public static final long CONFIRM_HOURS = 72;

    private final InflorescenceRepository inflorescences;
    private final PollenBatchRepository pollenBatches;
    private final PollinationOperationRepository operations;
    private final Clock clock;

    public PollinationService(InflorescenceRepository inflorescences, PollenBatchRepository pollenBatches,
            PollinationOperationRepository operations, Clock clock) {
        this.inflorescences = inflorescences;
        this.pollenBatches = pollenBatches;
        this.operations = operations;
        this.clock = clock;
    }

    /** 新建花序。 */
    @Transactional
    public InflorescenceView createInflorescence(CreateInflorescenceRequest request) {
        try {
            return toView(inflorescences.insert(request.code(), request.firstOpenAt(), Instant.now(clock)));
        } catch (DuplicateKeyException duplicate) {
            throw BusinessException.conflict("INFLORESCENCE_CODE_EXISTS", "花序编号已存在：" + request.code());
        }
    }

    /** 摘除花序：禁止后续授粉操作，但保留全部历史。 */
    @Transactional
    public InflorescenceView removeInflorescence(long inflorescenceId) {
        Inflorescence current = inflorescences.findById(inflorescenceId)
                .orElseThrow(() -> BusinessException.notFound("INFLORESCENCE_NOT_FOUND", "花序不存在"));
        if (current.removedAt() == null) {
            inflorescences.markRemoved(inflorescenceId, Instant.now(clock));
            current = inflorescences.findById(inflorescenceId).orElseThrow();
        }
        return toView(current);
    }

    /** 登记授粉操作：窗口外/已摘除/批号停用返回 422，重复有效操作或幂等键异参返回 409。 */
    @Transactional
    public OperationView createOperation(long inflorescenceId, CreateOperationRequest request) {
        String hash = hash(inflorescenceId, request.pollenBatchNo(), request.technicianId());
        var replay = operations.findByRequestId(request.requestId());
        if (replay.isPresent()) {
            return replayOrConflict(replay.get(), hash);
        }
        Inflorescence inflorescence = inflorescences.findByIdForUpdate(inflorescenceId)
                .orElseThrow(() -> BusinessException.notFound("INFLORESCENCE_NOT_FOUND", "花序不存在"));
        if (inflorescence.removedAt() != null) {
            throw BusinessException.unprocessable("INFLORESCENCE_REMOVED", "花序已摘除，不能继续登记授粉操作");
        }
        Instant now = Instant.now(clock);
        Instant windowEnd = inflorescence.firstOpenAt().plus(WINDOW_HOURS, ChronoUnit.HOURS);
        if (now.isBefore(inflorescence.firstOpenAt())) {
            throw BusinessException.unprocessable("WINDOW_NOT_OPEN", "授粉窗口尚未开始");
        }
        if (!now.isBefore(windowEnd)) {
            throw BusinessException.unprocessable("WINDOW_EXPIRED", "授粉窗口已过期");
        }
        PollenBatch batch = pollenBatches.findByBatchNo(request.pollenBatchNo())
                .orElseThrow(() -> BusinessException.unprocessable("POLLEN_BATCH_UNKNOWN", "花粉批号未登记"));
        if (batch.status() == BatchStatus.DISABLED) {
            throw BusinessException.unprocessable("POLLEN_BATCH_DISABLED", "花粉批号已停用");
        }
        try {
            return toView(operations.insert(request.requestId(), hash, inflorescenceId, batch.id(),
                    batch.batchNo(), request.technicianId(), now, now));
        } catch (DuplicateKeyException duplicate) {
            return operations.findByRequestId(request.requestId())
                    .map(existing -> replayOrConflict(existing, hash))
                    .orElseThrow(() -> BusinessException.conflict(
                            "DUPLICATE_EFFECTIVE_OPERATION", "同一花序同一窗口已存在有效授粉操作"));
        }
    }

    /** 撤销尚未确认的操作；已确认成功的操作返回 409。 */
    @Transactional
    public OperationView revokeOperation(long operationId) {
        PollinationOperation current = operations.findById(operationId)
                .orElseThrow(() -> BusinessException.notFound("OPERATION_NOT_FOUND", "授粉操作不存在"));
        if (current.status() == OperationStatus.REVOKED) {
            return toView(current);
        }
        if (operations.revokeIfEffective(operationId, Instant.now(clock)) == 1) {
            return toView(operations.findById(operationId).orElseThrow());
        }
        throw BusinessException.conflict("OPERATION_ALREADY_CONFIRMED", "操作已确认结果，不能撤销");
    }

    /** 确认授粉结果：超过 72 小时返回 422；与撤销竞争时只允许一个终态。 */
    @Transactional
    public OperationView confirmOperation(long operationId, ConfirmOperationRequest request) {
        String hash = hash(operationId, request.result().name());
        PollinationOperation current = operations.findById(operationId)
                .orElseThrow(() -> BusinessException.notFound("OPERATION_NOT_FOUND", "授粉操作不存在"));
        if (current.confirmRequestId() != null) {
            return confirmReplayOrConflict(current, request.requestId(), hash);
        }
        if (current.status() == OperationStatus.REVOKED) {
            throw BusinessException.conflict("OPERATION_ALREADY_REVOKED", "操作已撤销，不能确认结果");
        }
        Instant now = Instant.now(clock);
        if (now.isAfter(current.operatedAt().plus(CONFIRM_HOURS, ChronoUnit.HOURS))) {
            throw BusinessException.unprocessable("CONFIRMATION_EXPIRED", "结果确认已超过操作后 72 小时期限");
        }
        OperationStatus result = request.result() == ConfirmOperationRequest.ConfirmResult.SUCCESS
                ? OperationStatus.CONFIRMED_SUCCESS : OperationStatus.CONFIRMED_FAILED;
        String confirmedBatchNo = result == OperationStatus.CONFIRMED_SUCCESS ? current.batchNo() : null;
        try {
            if (operations.confirmIfEffective(operationId, result, request.requestId(), hash, now,
                    confirmedBatchNo) == 1) {
                return toView(operations.findById(operationId).orElseThrow());
            }
        } catch (DuplicateKeyException duplicate) {
            // 确认幂等键并发冲突，落入下方重读逻辑
        }
        PollinationOperation latest = operations.findById(operationId).orElseThrow();
        if (latest.confirmRequestId() != null) {
            return confirmReplayOrConflict(latest, request.requestId(), hash);
        }
        throw BusinessException.conflict("OPERATION_ALREADY_REVOKED", "操作已撤销，不能确认结果");
    }

    /** 按花序查询窗口、花粉来源、操作及结果时间线。 */
    @Transactional(readOnly = true)
    public TimelineView getTimeline(long inflorescenceId) {
        Inflorescence inflorescence = inflorescences.findById(inflorescenceId)
                .orElseThrow(() -> BusinessException.notFound("INFLORESCENCE_NOT_FOUND", "花序不存在"));
        List<OperationView> items = operations.findByInflorescenceId(inflorescenceId).stream()
                .map(PollinationService::toView).toList();
        return new TimelineView(toView(inflorescence), items);
    }

    /** 登记花粉批次（ACTIVE）。 */
    @Transactional
    public PollenBatchView createPollenBatch(String batchNo) {
        try {
            return toView(pollenBatches.insert(batchNo, Instant.now(clock)));
        } catch (DuplicateKeyException duplicate) {
            throw BusinessException.conflict("POLLEN_BATCH_EXISTS", "花粉批号已存在：" + batchNo);
        }
    }

    /** 停用花粉批次。 */
    @Transactional
    public PollenBatchView disablePollenBatch(String batchNo) {
        PollenBatch current = pollenBatches.findByBatchNo(batchNo)
                .orElseThrow(() -> BusinessException.notFound("POLLEN_BATCH_NOT_FOUND", "花粉批号不存在"));
        if (current.status() == BatchStatus.ACTIVE) {
            pollenBatches.disable(batchNo);
            current = pollenBatches.findByBatchNo(batchNo).orElseThrow();
        }
        return toView(current);
    }

    private InflorescenceView toView(Inflorescence inflorescence) {
        Instant now = Instant.now(clock);
        Instant windowEnd = inflorescence.firstOpenAt().plus(WINDOW_HOURS, ChronoUnit.HOURS);
        boolean windowOpen = !now.isBefore(inflorescence.firstOpenAt()) && now.isBefore(windowEnd);
        return new InflorescenceView(inflorescence.id(), inflorescence.code(), inflorescence.firstOpenAt(),
                windowEnd, windowOpen, inflorescence.removedAt());
    }

    private static PollenBatchView toView(PollenBatch batch) {
        return new PollenBatchView(batch.id(), batch.batchNo(), batch.status().name());
    }

    /** 领域记录转视图。 */
    static OperationView toView(PollinationOperation operation) {
        return new OperationView(operation.id(), operation.requestId(), operation.inflorescenceId(),
                operation.batchNo(), operation.technicianId(), operation.operatedAt(),
                operation.status().name(), operation.revokedAt(), operation.confirmedAt(),
                operation.confirmedBatchNo());
    }

    private static OperationView replayOrConflict(PollinationOperation existing, String hash) {
        if (existing.requestHash().equals(hash)) {
            return toView(existing);
        }
        throw BusinessException.conflict("REQUEST_ID_CONFLICT", "相同 requestId 已存在但参数不一致");
    }

    private static OperationView confirmReplayOrConflict(PollinationOperation existing, String requestId,
            String hash) {
        if (existing.confirmRequestId().equals(requestId)) {
            if (existing.confirmRequestHash().equals(hash)) {
                return toView(existing);
            }
            throw BusinessException.conflict("REQUEST_ID_CONFLICT", "相同 requestId 已存在但参数不一致");
        }
        throw BusinessException.conflict("OPERATION_ALREADY_CONFIRMED", "操作已确认结果");
    }

    private static String hash(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            canonical.append(part).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }
}