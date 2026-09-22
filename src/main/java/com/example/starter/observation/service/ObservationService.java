package com.example.starter.observation.service;

import com.example.starter.observation.dto.CreateRequest;
import com.example.starter.observation.dto.DeleteRequest;
import com.example.starter.observation.dto.ObservationResponse;
import com.example.starter.observation.dto.OfflineSubmitRequest;
import com.example.starter.observation.exception.ConflictException;
import com.example.starter.observation.exception.ObservationGoneException;
import com.example.starter.observation.exception.ObservationNotFoundException;
import com.example.starter.observation.exception.VersionMismatchException;
import com.example.starter.observation.model.DedupRecord;
import com.example.starter.observation.model.ObservationSnapshot;
import com.example.starter.observation.model.OperationType;
import com.example.starter.observation.repository.ObservationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 现场观测离线三方合并应用服务。
 *
 * <p>每个写操作在单个事务内完成：先抢占 requestId 幂等键（行锁串行化同键并发），
 * 再以 SELECT ... FOR UPDATE 锁定该观测的版本行，基于提交时最新版本判定，
 * 业务变更与幂等结果原子提交；业务失败随事务回滚，不占用幂等键。</p>
 */
@Service
public class ObservationService {

    private static final String FIELD_LOCATION = "location";
    private static final String FIELD_READING = "reading";
    private static final String FIELD_REMARK = "remark";
    private static final String SEP = "";

    private final ObservationRepository repository;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ObservationService(ObservationRepository repository, Clock clock, ObjectMapper objectMapper) {
        this.repository = repository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WriteResult create(CreateRequest request, String requestId) {
        String hash = hash(OperationType.CREATE, request.getObservationId(),
                request.getObservationId(), request.getLocation(), request.getReading(), request.getRemark());
        return executeIdempotent(requestId, OperationType.CREATE, request.getObservationId(), hash,
                () -> doCreate(request));
    }

    @Transactional
    public WriteResult offlineSubmit(String observationId, OfflineSubmitRequest request, String requestId) {
        String hash = hash(OperationType.OFFLINE_SUBMIT, observationId,
                String.valueOf(request.getBaseVersion()), request.getLocation(),
                request.getReading(), request.getRemark());
        return executeIdempotent(requestId, OperationType.OFFLINE_SUBMIT, observationId, hash,
                () -> doSubmit(observationId, request));
    }

    @Transactional
    public WriteResult delete(String observationId, DeleteRequest request, String requestId) {
        String hash = hash(OperationType.DELETE, observationId, String.valueOf(request.getExpectedVersion()));
        return executeIdempotent(requestId, OperationType.DELETE, observationId, hash,
                () -> doDelete(observationId, request.getExpectedVersion()));
    }

    @Transactional(readOnly = true)
    public ObservationResponse getCurrent(String observationId) {
        ObservationSnapshot snapshot = repository.findLatest(observationId)
                .orElseThrow(() -> new ObservationNotFoundException("观测记录不存在"));
        return toResponse(snapshot);
    }

    @Transactional(readOnly = true)
    public ObservationResponse getVersion(String observationId, int version) {
        ObservationSnapshot snapshot = repository.findVersion(observationId, version)
                .orElseThrow(() -> new ObservationNotFoundException("指定版本不存在"));
        return toResponse(snapshot);
    }

    // ---------------- 写操作主流程 ----------------

    private WriteResult doCreate(CreateRequest request) {
        List<ObservationSnapshot> locked = repository.findAllForUpdate(request.getObservationId());
        if (!locked.isEmpty()) {
            ObservationSnapshot existing = locked.get(0);
            if (existing.isDeleted()) {
                throw new ObservationGoneException("观测记录已删除，不能重新创建或修改");
            }
            throw new ConflictException(existing.getVersion(), List.of());
        }
        Instant now = Instant.now(clock);
        ObservationSnapshot snapshot = new ObservationSnapshot(
                request.getObservationId(), 1,
                request.getLocation(), request.getReading(), request.getRemark(), false, now);
        try {
            repository.insertVersion(snapshot);
        } catch (DuplicateKeyException ex) {
            // 极端并发下抢占失败，基于提交时最新版本重新判定。
            ObservationSnapshot latest = repository.findLatest(request.getObservationId()).orElseThrow();
            if (latest.isDeleted()) {
                throw new ObservationGoneException("观测记录已删除，不能重新创建或修改");
            }
            throw new ConflictException(latest.getVersion(), List.of());
        }
        return new WriteResult(HttpStatus.CREATED, toResponse(snapshot));
    }

    private WriteResult doSubmit(String observationId, OfflineSubmitRequest request) {
        ObservationSnapshot current = repository.findAllForUpdate(observationId).stream()
                .findFirst()
                .orElseThrow(() -> new ObservationNotFoundException("观测记录不存在"));
        if (current.isDeleted()) {
            throw new ObservationGoneException("观测记录已删除，拒绝离线修改");
        }
        int baseVersion = request.getBaseVersion();
        ObservationSnapshot base;
        if (baseVersion == current.getVersion()) {
            base = current;
        } else {
            base = repository.findVersion(observationId, baseVersion)
                    .orElseThrow(() -> new ObservationNotFoundException("基线版本不存在"));
            if (base.isDeleted()) {
                throw new ObservationGoneException("基线版本已是墓碑，拒绝离线修改");
            }
        }

        ObservationSnapshot merged = mergeOrConflict(observationId, request, current, base);
        if (merged == null) {
            return new WriteResult(HttpStatus.OK, toResponse(current));
        }
        try {
            repository.insertVersion(merged);
        } catch (DuplicateKeyException ex) {
            // 锁未覆盖的极端并发下，版本号被其他事务抢先占用：基于最新版本重新判定一次。
            return retrySubmitAgainstLatest(observationId, request);
        }
        return new WriteResult(HttpStatus.OK, toResponse(merged));
    }

    private WriteResult retrySubmitAgainstLatest(String observationId, OfflineSubmitRequest request) {
        ObservationSnapshot latest = repository.findLatest(observationId)
                .orElseThrow(() -> new ObservationNotFoundException("观测记录不存在"));
        if (latest.isDeleted()) {
            throw new ObservationGoneException("观测记录已删除，拒绝离线修改");
        }
        ObservationSnapshot base = request.getBaseVersion() == latest.getVersion()
                ? latest
                : repository.findVersion(observationId, request.getBaseVersion())
                        .orElseThrow(() -> new ObservationNotFoundException("基线版本不存在"));
        ObservationSnapshot merged = mergeOrConflict(observationId, request, latest, base);
        if (merged == null) {
            return new WriteResult(HttpStatus.OK, toResponse(latest));
        }
        repository.insertVersion(merged);
        return new WriteResult(HttpStatus.OK, toResponse(merged));
    }

    /**
     * 执行三方字段合并；存在冲突抛 409（不写部分结果）；合并后与当前完全相同返回 null，
     * 否则返回版本号为当前版本加一的新快照。
     */
    private ObservationSnapshot mergeOrConflict(String observationId, OfflineSubmitRequest request,
                                                ObservationSnapshot current, ObservationSnapshot base) {
        List<String> conflicts = new ArrayList<>();
        String mergedLocation = mergeText(FIELD_LOCATION, base.getLocation(),
                current.getLocation(), request.getLocation(), conflicts);
        String mergedReading = mergeReading(base.getReading(),
                current.getReading(), request.getReading(), conflicts);
        String mergedRemark = mergeText(FIELD_REMARK, base.getRemark(),
                current.getRemark(), request.getRemark(), conflicts);
        if (!conflicts.isEmpty()) {
            throw new ConflictException(current.getVersion(), List.copyOf(conflicts));
        }

        boolean unchanged = equalsText(mergedLocation, current.getLocation())
                && equalsText(mergedRemark, current.getRemark())
                && equalsReading(mergedReading, current.getReading());
        if (unchanged) {
            return null;
        }
        return new ObservationSnapshot(
                observationId, current.getVersion() + 1,
                mergedLocation, mergedReading, mergedRemark, false, Instant.now(clock));
    }

    private WriteResult doDelete(String observationId, int expectedVersion) {
        List<ObservationSnapshot> locked = repository.findAllForUpdate(observationId);
        ObservationSnapshot current = locked.stream()
                .findFirst()
                .orElseThrow(() -> new ObservationNotFoundException("观测记录不存在"));
        if (current.isDeleted()) {
            throw new ObservationGoneException("观测记录已删除，不能重复删除");
        }
        if (current.getVersion() != expectedVersion) {
            throw new VersionMismatchException(current.getVersion(), "expectedVersion 与当前版本不一致");
        }
        Instant now = Instant.now(clock);
        // 墓碑是新版本，不携带任何业务字段。
        ObservationSnapshot tombstone = new ObservationSnapshot(
                observationId, current.getVersion() + 1, null, null, null, true, now);
        try {
            repository.insertVersion(tombstone);
        } catch (DuplicateKeyException ex) {
            ObservationSnapshot latest = repository.findLatest(observationId).orElseThrow();
            if (latest.isDeleted()) {
                throw new ObservationGoneException("观测记录已删除，不能重复删除");
            }
            throw new VersionMismatchException(latest.getVersion(), "expectedVersion 与当前版本不一致");
        }
        return new WriteResult(HttpStatus.OK, toResponse(tombstone));
    }

    // ---------------- 三方字段合并 ----------------

    /**
     * 文本字段（地点、备注）按原文比较；候选未改保留当前，当前未改接受候选，
     * 两边改成相同值接受，否则记为冲突。
     */
    private String mergeText(String field, String baseValue, String currentValue,
                             String candidateValue, List<String> conflicts) {
        if (equalsText(candidateValue, baseValue)) {
            return currentValue;
        }
        if (equalsText(currentValue, baseValue) || equalsText(candidateValue, currentValue)) {
            return candidateValue;
        }
        conflicts.add(field);
        return currentValue;
    }

    /**
     * 读数字段按数值比较（最多三位小数字符串），规则同文本字段。
     */
    private String mergeReading(String baseValue, String currentValue,
                                String candidateValue, List<String> conflicts) {
        if (equalsReading(candidateValue, baseValue)) {
            return currentValue;
        }
        if (equalsReading(currentValue, baseValue) || equalsReading(candidateValue, currentValue)) {
            return candidateValue;
        }
        conflicts.add(FIELD_READING);
        return currentValue;
    }

    private boolean equalsText(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private boolean equalsReading(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
    }

    // ---------------- 幂等控制 ----------------

    /**
     * 同 requestId 同参重放原成功结果；异参返回 409。PENDING 占位与业务结果在同一事务，
     * 唯一索引使同键并发请求阻塞到本事务结束后再判定为重放或抢占成功。
     */
    private WriteResult executeIdempotent(String requestId, OperationType operation,
                                          String observationId, String requestHash,
                                          java.util.function.Supplier<WriteResult> action) {
        Optional<DedupRecord> existing = repository.findDedup(requestId);
        if (existing.isPresent()) {
            return replayOrReject(existing.get(), requestHash);
        }
        Instant now = Instant.now(clock);
        DedupRecord pending = new DedupRecord();
        pending.setRequestId(requestId);
        pending.setOperation(operation);
        pending.setObservationId(observationId);
        pending.setRequestHash(requestHash);
        pending.setStatus("PENDING");
        pending.setCreatedAt(now);
        pending.setUpdatedAt(now);
        if (!repository.insertDedupPending(pending)) {
            // 并发同键：唯一索引冲突后重新读取已提交记录。
            DedupRecord committed = repository.findDedup(requestId)
                    .orElseThrow(() -> new ConflictException("请求正在处理中，请稍后重试"));
            return replayOrReject(committed, requestHash);
        }
        try {
            WriteResult result = action.get();
            repository.completeDedup(requestId, result.status().value(),
                    writeJson(result.body()), Instant.now(clock));
            return result;
        } catch (RuntimeException ex) {
            // 失败不占键：同一事务最终回滚，这里显式删除保持语义清晰。
            repository.deleteDedup(requestId);
            throw ex;
        }
    }

    private WriteResult replayOrReject(DedupRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new ConflictException("相同 requestId 的请求参数不一致");
        }
        if (!"DONE".equals(record.getStatus())) {
            throw new ConflictException("请求正在处理中，请稍后重试");
        }
        try {
            ObservationResponse body = objectMapper.readValue(
                    record.getResponseBody(), ObservationResponse.class);
            return new WriteResult(HttpStatus.valueOf(record.getHttpStatus()), body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等结果无法解析", ex);
        }
    }

    private String writeJson(ObservationResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应无法序列化", ex);
        }
    }

    private String hash(OperationType operation, String observationId, String... params) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(operation.name().getBytes(StandardCharsets.UTF_8));
            digest.update(SEP.getBytes(StandardCharsets.UTF_8));
            digest.update(observationId.getBytes(StandardCharsets.UTF_8));
            for (String param : params) {
                digest.update(SEP.getBytes(StandardCharsets.UTF_8));
                digest.update(param.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    // ---------------- 响应组装 ----------------

    private ObservationResponse toResponse(ObservationSnapshot snapshot) {
        ObservationResponse response = new ObservationResponse();
        response.setObservationId(snapshot.getObservationId());
        response.setVersion(snapshot.getVersion());
        response.setDeleted(snapshot.isDeleted());
        response.setCreatedAt(snapshot.getCreatedAt());
        if (!snapshot.isDeleted()) {
            response.setLocation(snapshot.getLocation());
            response.setReading(snapshot.getReading());
            response.setRemark(snapshot.getRemark());
        }
        return response;
    }
}
