package com.example.starter.service;

import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.ConfirmationResult;
import com.example.starter.domain.Inflorescence;
import com.example.starter.domain.OperationStatus;
import com.example.starter.domain.PollenBatch;
import com.example.starter.domain.PollinationConfirmation;
import com.example.starter.domain.PollinationOperation;
import com.example.starter.domain.PollinationWindow;
import com.example.starter.repo.InflorescenceRepository;
import com.example.starter.repo.PollenBatchRepository;
import com.example.starter.repo.PollinationConfirmationRepository;
import com.example.starter.repo.PollinationOperationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 授粉窗口业务服务。所有写操作在单事务内完成：
 * 花序互斥通过 active_operation_id 条件更新保证，终态（确认/撤销）通过
 * 状态条件更新保证，事务失败时客户端可用原 requestId 安全重试。
 */
@Service
public class PollinationService {

    /** 结果确认时限：操作后 72 小时内。 */
    public static final long CONFIRM_WINDOW_HOURS = 72L;

    private final InflorescenceRepository inflorescences;
    private final PollenBatchRepository batches;
    private final PollinationOperationRepository operations;
    private final PollinationConfirmationRepository confirmations;
    private final Clock clock;

    public PollinationService(InflorescenceRepository inflorescences, PollenBatchRepository batches,
                              PollinationOperationRepository operations,
                              PollinationConfirmationRepository confirmations, Clock clock) {
        this.inflorescences = inflorescences;
        this.batches = batches;
        this.operations = operations;
        this.confirmations = confirmations;
        this.clock = clock;
    }

    /** 登记花序；编号重复返回 409。 */
    @Transactional
    public Inflorescence createInflorescence(String code, Instant firstOpenAt, Integer windowHours) {
        int hours = windowHours == null ? 24 : windowHours;
        long id;
        try {
            id = inflorescences.insert(code, firstOpenAt, hours, Instant.now(clock));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("INFLORESCENCE_CODE_EXISTS", "花序编号已存在: " + code);
        }
        return inflorescences.findById(id).orElseThrow();
    }

    /** 摘除花序；重复摘除幂等返回当前状态。 */
    @Transactional
    public Inflorescence removeInflorescence(long id, Instant removedAt) {
        Inflorescence current = inflorescences.findById(id)
                .orElseThrow(() -> ApiException.notFound("INFLORESCENCE_NOT_FOUND", "花序不存在: " + id));
        if (current.removedAt() == null) {
            inflorescences.markRemoved(id, removedAt);
        }
        return inflorescences.findById(id).orElseThrow();
    }

    /** 新建花粉批号；批号重复返回 409。 */
    @Transactional
    public PollenBatch createPollenBatch(String batchNo) {
        long id;
        try {
            id = batches.insert(batchNo, Instant.now(clock));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("POLLEN_BATCH_EXISTS", "花粉批号已存在: " + batchNo);
        }
        return batches.findByBatchNo(batchNo).orElseThrow();
    }

    /** 停用花粉批号。 */
    @Transactional
    public PollenBatch disablePollenBatch(String batchNo) {
        if (batches.findByBatchNo(batchNo).isEmpty()) {
            throw ApiException.notFound("POLLEN_BATCH_NOT_FOUND", "花粉批号不存在: " + batchNo);
        }
        batches.disable(batchNo);
        return batches.findByBatchNo(batchNo).orElseThrow();
    }
    /**
     * 登记授粉操作。同一 requestId 同参重放返回原记录，异参返回 409；
     * 窗口外、已摘除、批号停用返回 422；花序已有有效操作返回 409。
     */
    @Transactional
    public PollinationOperation pollinate(long inflorescenceId, String requestId, String pollenBatchNo,
                                          String technician, Instant executedAt) {
        String hash = hash("pollinate", inflorescenceId, pollenBatchNo, technician, executedAt);
        var replayed = operations.findByRequestId(requestId);
        if (replayed.isPresent()) {
            PollinationOperation op = replayed.get();
            if (!op.requestHash().equals(hash)) {
                throw ApiException.conflict("REQUEST_ID_MISMATCH", "requestId 已存在但参数不一致: " + requestId);
            }
            return op;
        }
        Inflorescence inf = inflorescences.findById(inflorescenceId)
                .orElseThrow(() -> ApiException.notFound("INFLORESCENCE_NOT_FOUND", "花序不存在: " + inflorescenceId));
        if (inf.removedAt() != null) {
            throw ApiException.unprocessable("INFLORESCENCE_REMOVED", "花序已摘除，不能登记授粉操作");
        }
        PollinationWindow window = inf.window();
        if (executedAt.isBefore(window.openAt())) {
            throw ApiException.unprocessable("WINDOW_NOT_STARTED", "授粉窗口尚未开放: " + window.openAt());
        }
        if (!window.contains(executedAt)) {
            throw ApiException.unprocessable("WINDOW_EXPIRED", "授粉窗口已过期: " + window.closeAt());
        }
        PollenBatch batch = batches.findByBatchNo(pollenBatchNo)
                .orElseThrow(() -> ApiException.unprocessable("POLLEN_BATCH_NOT_FOUND", "花粉批号不存在: " + pollenBatchNo));
        if (batch.status() == BatchStatus.DISABLED) {
            throw ApiException.unprocessable("POLLEN_BATCH_DISABLED", "花粉批号已停用: " + pollenBatchNo);
        }
        long operationId;
        try {
            operationId = operations.insert(requestId, hash, inflorescenceId, pollenBatchNo,
                    technician, executedAt, Instant.now(clock));
        } catch (DuplicateKeyException e) {
            PollinationOperation op = operations.findByRequestId(requestId).orElseThrow();
            if (!op.requestHash().equals(hash)) {
                throw ApiException.conflict("REQUEST_ID_MISMATCH", "requestId 已存在但参数不一致: " + requestId);
            }
            return op;
        }
        if (inflorescences.occupyIfFree(inflorescenceId, operationId) == 0) {
            throw ApiException.conflict("ACTIVE_OPERATION_EXISTS", "花序已存在有效授粉操作: " + inflorescenceId);
        }
        return operations.findById(operationId).orElseThrow();
    }

    /**
     * 撤销尚未确认结果的操作；已确认的操作返回 422，重复撤销幂等返回。
     * 撤销成功后释放花序，窗口未过期时可重新授粉。
     */
    @Transactional
    public PollinationOperation revoke(long operationId, Instant revokedAt) {
        PollinationOperation op = operations.findById(operationId)
                .orElseThrow(() -> ApiException.notFound("OPERATION_NOT_FOUND", "授粉操作不存在: " + operationId));
        if (op.status() == OperationStatus.REVOKED) {
            return op;
        }
        if (op.status() == OperationStatus.CONFIRMED) {
            throw ApiException.unprocessable("OPERATION_ALREADY_CONFIRMED", "已确认结果的操作不能撤销: " + operationId);
        }
        if (operations.revokeIfEffective(operationId, revokedAt) == 0) {
            throw ApiException.conflict("TERMINAL_STATE_RACE", "操作已被并发确认为终态: " + operationId);
        }
        inflorescences.releaseIfHeldBy(op.inflorescenceId(), operationId);
        return operations.findById(operationId).orElseThrow();
    }
    /**
     * 登记结果确认。须在操作后 72 小时内；花序已摘除或操作已撤销返回 422；
     * 与撤销竞争时只允许一个终态，失败方返回 409；同一 requestId 同参重放返回原记录。
     * 确认成功后固化花粉批号；确认失败释放花序，窗口未过期时可重新授粉。
     */
    @Transactional
    public PollinationConfirmation confirm(long operationId, String requestId, ConfirmationResult result,
                                           String technician, Instant confirmedAt) {
        String hash = hash("confirm", operationId, result, technician, confirmedAt);
        var replayed = confirmations.findByRequestId(requestId);
        if (replayed.isPresent()) {
            PollinationConfirmation existing = replayed.get();
            if (!existing.requestHash().equals(hash) || existing.operationId() != operationId) {
                throw ApiException.conflict("REQUEST_ID_MISMATCH", "requestId 已存在但参数不一致: " + requestId);
            }
            return existing;
        }
        PollinationOperation op = operations.findById(operationId)
                .orElseThrow(() -> ApiException.notFound("OPERATION_NOT_FOUND", "授粉操作不存在: " + operationId));
        if (confirmations.findByOperationId(operationId).isPresent()) {
            throw ApiException.conflict("OPERATION_ALREADY_CONFIRMED", "操作已存在结果确认: " + operationId);
        }
        if (op.status() == OperationStatus.REVOKED) {
            throw ApiException.unprocessable("OPERATION_REVOKED", "操作已撤销，不能登记结果: " + operationId);
        }
        Inflorescence inf = inflorescences.findById(op.inflorescenceId())
                .orElseThrow(() -> ApiException.notFound("INFLORESCENCE_NOT_FOUND", "花序不存在: " + op.inflorescenceId()));
        if (inf.removedAt() != null) {
            throw ApiException.unprocessable("INFLORESCENCE_REMOVED", "花序已摘除，不能登记结果");
        }
        if (confirmedAt.isBefore(op.executedAt())) {
            throw ApiException.unprocessable("CONFIRM_BEFORE_OPERATION", "确认时刻早于操作时刻");
        }
        if (confirmedAt.isAfter(op.executedAt().plusSeconds(CONFIRM_WINDOW_HOURS * 3600L))) {
            throw ApiException.unprocessable("CONFIRM_WINDOW_EXPIRED", "结果确认超过操作后 72 小时时限");
        }
        if (operations.confirmIfEffective(operationId) == 0) {
            throw ApiException.conflict("TERMINAL_STATE_RACE", "操作已被并发撤销为终态: " + operationId);
        }
        long confirmationId;
        try {
            confirmationId = confirmations.insert(requestId, hash, operationId, result,
                    technician, confirmedAt, Instant.now(clock));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("REQUEST_ID_MISMATCH", "requestId 或操作已存在确认: " + requestId);
        }
        if (result == ConfirmationResult.SUCCESS) {
            inflorescences.fixSuccess(inf.id(), op.pollenBatchNo(), confirmedAt);
        } else {
            inflorescences.releaseIfHeldBy(inf.id(), operationId);
        }
        return confirmations.findByRequestId(requestId).orElseThrow();
    }

    /**
     * 按花序查询时间线：窗口、花粉来源（固化批号）、全部操作及结果确认。
     */
    @Transactional(readOnly = true)
    public InflorescenceTimeline timeline(long inflorescenceId) {
        Inflorescence inf = inflorescences.findById(inflorescenceId)
                .orElseThrow(() -> ApiException.notFound("INFLORESCENCE_NOT_FOUND", "花序不存在: " + inflorescenceId));
        List<PollinationOperation> ops = operations.findByInflorescenceId(inflorescenceId);
        List<Long> opIds = ops.stream().map(PollinationOperation::id).toList();
        List<PollinationConfirmation> confs = confirmations.findByOperationIds(opIds);
        boolean windowExpired = inf.window().isExpiredAt(Instant.now(clock));
        return new InflorescenceTimeline(inf, ops, confs, windowExpired);
    }

    /** 花序时间线视图：花序、操作列表、确认列表、窗口是否已过期（按当前 UTC 时钟）。 */
    public record InflorescenceTimeline(Inflorescence inflorescence,
                                        List<PollinationOperation> operations,
                                        List<PollinationConfirmation> confirmations,
                                        boolean windowExpired) {
    }

    /** 计算请求参数摘要，用于幂等键的同参/异参判定。 */
    private static String hash(String action, Object... parts) {
        StringBuilder sb = new StringBuilder(action);
        for (Object part : parts) {
            sb.append('|').append(part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}