package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 4x4 二值栅格观测的离线三方合并服务，与普通字段观测共用 request_log 全局幂等表。
 *
 * <p>写操作（创建/合并/删除）均在单事务内完成：先占位写入幂等记录，再对
 * mask_observation_current 行加锁（SELECT ... FOR UPDATE），事务内重读最新当前版后
 * 重算整张图的逐格三方合并并做四邻接连通核验，最后原子写入版本、格值并回填去重响应。
 *
 * <p>合并后勾选格不连通时返回 422：当前版本号与全部 16 格保持不变，不自动补桥、不写部分结果。
 * 操作指纹结构化覆盖操作、资源标识、全参数（含期望/基版号、操作者与 16 格原值），
 * 同 requestId 同参返回首次完整响应，异参 409；失败随事务回滚不占键。
 */
@Service
public class MaskObservationService {

    private static final String SEPARATOR = "\u0001";

    private final MaskObservationRepository maskRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    public MaskObservationService(MaskObservationRepository maskRepository,
                                  RequestLogRepository requestLogRepository,
                                  ObjectMapper objectMapper) {
        this.maskRepository = maskRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建栅格观测：初始完整图必须勾选非空且四邻接连通，初始版本为 1；记录已存在返回 409。
     */
    @Transactional
    public MaskOutcome create(CreateMaskRequest request) {
        MaskGrid grid = MaskGrid.of(request.cells());
        requireConnected(grid);
        String fingerprint = fingerprint("CREATE_MASK", request.observationId(),
                request.operator(), cellsParam(request.cells()));
        MaskOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        MaskOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "CREATE_MASK");
        if (concurrent != null) {
            return concurrent;
        }

        if (maskRepository.findCurrentForUpdate(request.observationId()).isPresent()) {
            throw ApiException.conflict("mask observation already exists: " + request.observationId(), null);
        }
        try {
            maskRepository.insertCurrent(request.observationId());
        } catch (DuplicateKeyException e) {
            // 并发创建同一 observationId：由主键串行化，后到者按冲突处理
            throw ApiException.conflict("mask observation already exists: " + request.observationId(), null);
        }
        MaskSnapshot snapshot = new MaskSnapshot(request.observationId(), 1, null,
                request.operator(), false, request.cells());
        maskRepository.insertVersionWithCells(snapshot);
        return complete(request.requestId(), HttpStatus.CREATED, MaskResponse.of(snapshot));
    }

    /**
     * 离线合并：行锁内重读最新当前版，对整张 16 格图重算逐格三方合并，再做连通核验；
     * 合并结果与当前完全相同不加版本，否则版本加一并保存完整版本、基版号与客户端操作者。
     */
    @Transactional
    public MaskOutcome merge(String observationId, MergeMaskRequest request) {
        MaskGrid candidate = MaskGrid.of(request.cells());
        String fingerprint = fingerprint("MERGE_MASK", observationId,
                String.valueOf(request.baseVersion()), request.operator(), cellsParam(request.cells()));
        MaskOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        MaskOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "MERGE_MASK");
        if (concurrent != null) {
            return concurrent;
        }

        // 先锁当前行：并发提交基于同一服务器版时，后到事务在此等待，随后读到的已是提交后的最新版本。
        MaskSnapshot current = maskRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("mask observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("mask observation already deleted: " + observationId);
        }
        MaskSnapshot base = maskRepository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));
        if (base.deleted()) {
            // 墓碑不得作为基版参与合并
            throw ApiException.badRequest("base version is a tombstone: " + request.baseVersion());
        }

        // 服务器当前版若已被并发事务推进，这里基于新当前版对整张图重算，而不是只核验两张局部合法图。
        MaskGrid baseGrid = new MaskGrid(base.cells());
        MaskGrid currentGrid = new MaskGrid(current.cells());
        MaskGrid mergedGrid = baseGrid.merge(currentGrid, candidate);
        if (!mergedGrid.isConnected()) {
            // 合并后不连通：版本号与全部格值不变，不自动补桥
            throw ApiException.disconnected(current.version());
        }

        if (mergedGrid.equals(currentGrid)) {
            // 合并结果与当前完全相同：返回当前版本，不加版本
            return complete(request.requestId(), HttpStatus.OK,
                    MaskResponse.of(new MaskSnapshot(observationId, current.version(),
                            current.baseVersion(), current.operator(), false, current.cells())));
        }
        int nextVersion = current.version() + 1;
        MaskSnapshot next = new MaskSnapshot(observationId, nextVersion, request.baseVersion(),
                request.operator(), false, mergedGrid.cells());
        maskRepository.updateCurrentVersion(observationId, nextVersion, false);
        maskRepository.insertVersionWithCells(next);
        return complete(request.requestId(), HttpStatus.OK, MaskResponse.of(next));
    }

    /**
     * 删除栅格观测：expectedVersion 匹配当前版本时生成新版本墓碑；已删除返回 410，墓碑不得再合并。
     */
    @Transactional
    public MaskOutcome delete(String observationId, DeleteMaskRequest request) {
        String fingerprint = fingerprint("DELETE_MASK", observationId,
                String.valueOf(request.expectedVersion()), request.operator());
        MaskOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        MaskOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "DELETE_MASK");
        if (concurrent != null) {
            return concurrent;
        }

        MaskSnapshot current = maskRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("mask observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("mask observation already deleted: " + observationId);
        }
        if (request.expectedVersion() != current.version()) {
            throw ApiException.conflict("expectedVersion mismatch", current.version());
        }
        int nextVersion = current.version() + 1;
        MaskSnapshot tombstone = new MaskSnapshot(observationId, nextVersion, null,
                request.operator(), true, null);
        maskRepository.updateCurrentVersion(observationId, nextVersion, true);
        maskRepository.insertTombstone(tombstone);
        return complete(request.requestId(), HttpStatus.OK, MaskResponse.of(tombstone));
    }

    /**
     * 查询当前内容；墓碑只返回删除状态、版本与操作者。
     */
    @Transactional(readOnly = true)
    public MaskResponse getCurrent(String observationId) {
        return MaskResponse.of(loadCurrent(observationId));
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public MaskResponse getVersion(String observationId, int version) {
        MaskSnapshot snapshot = maskRepository.findVersion(observationId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + version));
        return MaskResponse.of(snapshot);
    }

    private MaskSnapshot loadCurrent(String observationId) {
        return maskRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("mask observation not found: " + observationId));
    }

    private void requireConnected(MaskGrid grid) {
        if (!grid.isConnected()) {
            throw ApiException.badRequest(
                    "mask cells must be non-empty and connected via 4-neighborhood");
        }
    }

    /**
     * 幂等检查：同键同参返回原成功结果（首次完整图，不按当前图重组）；同键异参 409；无记录返回 null。
     */
    private MaskOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new MaskOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录；并发同键主键冲突时等待对方事务结束后读取已提交结果：同参重放、异参 409。
     * 业务失败时占位随事务回滚，不占键，也不向调用方泄漏唯一键异常。
     */
    private MaskOutcome insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new MaskOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    /**
     * 业务成功后回填去重记录响应（保留首次完整图），与业务变更同事务提交。
     */
    private MaskOutcome complete(String requestId, HttpStatus status, MaskResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new MaskOutcome(status.value(), body);
    }

    /**
     * 结构化指纹：操作、资源标识与全部参数（基版/期望版本、操作者原文、16 格原值）逐段分隔后取 SHA-256。
     */
    private String fingerprint(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String cellsParam(List<Integer> cells) {
        return String.join(",", cells.stream().map(String::valueOf).toList());
    }

    private String writeBody(MaskResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize mask response", e);
        }
    }

    private MaskResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, MaskResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored mask response", e);
        }
    }

    /**
     * 栅格写操作结果：HTTP 状态码与响应体。
     */
    public record MaskOutcome(int status, MaskResponse body) {
    }
}
