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
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * 按时刻一致视图与冻结快照业务服务。
 *
 * <p>按时刻查询（as-of）为只读：对每个 observationId 取目标时刻之前（含）最后一个已提交版本，
 * 时刻后才创建的记录按 ABSENT 返回（不视为 404），墓碑按删除提交时刻生效，删除期间的视图不暴露此后版本。
 *
 * <p>冻结快照在单事务内完成：先占位写入幂等去重记录，再对全局版本单行加 FOR UPDATE 行锁
 * （与所有版本写入同一把锁、同一加锁顺序），随后读取一致状态并原子写入不可变快照。
 * 因而在快照事务提交前完成提交的版本全部包含，之后的版本全部不包含，不允许同一快照内新旧版本混取。
 * 同键同参（ID 集合换序视为同参）重放首次响应；异参 409；失败回滚不占 snapshotKey/requestId。
 */
@Service
public class SnapshotService {

    private static final String SEPARATOR = "\u0001";

    private final ObservationRepository observationRepository;
    private final ResolutionRepository resolutionRepository;
    private final GlobalRevisionRepository globalRevisionRepository;
    private final SnapshotRepository snapshotRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SnapshotService(ObservationRepository observationRepository,
                           ResolutionRepository resolutionRepository,
                           GlobalRevisionRepository globalRevisionRepository,
                           SnapshotRepository snapshotRepository,
                           RequestLogRepository requestLogRepository,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.observationRepository = observationRepository;
        this.resolutionRepository = resolutionRepository;
        this.globalRevisionRepository = globalRevisionRepository;
        this.snapshotRepository = snapshotRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 按时刻一致视图（只读）：目标时刻晚于服务端当前时刻返回 400；返回结果按 observationId 升序。
     */
    @Transactional(readOnly = true)
    public AsOfResponse queryAsOf(AsOfQueryRequest request) {
        Instant asOfUtc = request.asOfUtc();
        validateNotFuture(asOfUtc);
        List<String> ids = normalizeIds(request.observationIds());
        List<AsOfResponse.Item> items = ids.stream().map(id -> asOfItem(id, asOfUtc)).toList();
        return new AsOfResponse(asOfUtc, items);
    }

    /**
     * 创建冻结快照。集合内任一 ID 在任何时刻都不存在则整次 404，不保存任何部分快照。
     */
    @Transactional
    public SnapshotOutcome createSnapshot(CreateSnapshotRequest request) {
        Instant targetTimeUtc = request.targetTimeUtc();
        validateNotFuture(targetTimeUtc);
        List<String> ids = normalizeIds(request.observationIds());

        String fingerprint = fingerprint("SNAPSHOT", request.snapshotKey(),
                targetTimeUtc.toString(), String.join(",", ids));
        SnapshotOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        SnapshotOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // 取全局版本行锁：此后到本事务提交之间，任何版本写入都被阻塞在该行锁上，
        // 保证读取到的状态对应一个明确的提交点，之后提交的版本一律不含。
        long globalLatestRevision = globalRevisionRepository.lockAndGet();

        // snapshotKey 全局唯一：键已存在时，同参返回原快照（快照不可变，结果相同），异参 409。
        SnapshotHeader existing = snapshotRepository.findHeader(request.snapshotKey()).orElse(null);
        if (existing != null) {
            if (!sameSnapshotParams(existing, ids, targetTimeUtc)) {
                throw ApiException.conflict(
                        "snapshotKey reused with different parameters: " + request.snapshotKey(), null);
            }
            return complete(request.requestId(), HttpStatus.CREATED,
                    loadSnapshotResponse(existing));
        }

        // 先整体校验存在性：任一 ID 从未存在（墓碑也算存在）即整次 404，不保存部分快照。
        for (String id : ids) {
            if (!observationRepository.existsEver(id)) {
                throw ApiException.notFound("observation never existed: " + id);
            }
        }

        SnapshotHeader header = new SnapshotHeader(request.snapshotKey(), targetTimeUtc,
                globalLatestRevision, Instant.now(clock));
        List<SnapshotItemView> views = ids.stream()
                .map(id -> toItemView(id, targetTimeUtc))
                .toList();
        try {
            snapshotRepository.insertHeader(header);
        } catch (DuplicateKeyException e) {
            // 并发下 snapshotKey 已被其他事务占用：同参重放原快照，异参 409。
            SnapshotHeader winner = snapshotRepository.findHeader(request.snapshotKey())
                    .orElseThrow(() -> ApiException.conflict(
                            "snapshotKey conflict: " + request.snapshotKey(), null));
            if (!sameSnapshotParams(winner, ids, targetTimeUtc)) {
                throw ApiException.conflict(
                        "snapshotKey reused with different parameters: " + request.snapshotKey(), null);
            }
            return complete(request.requestId(), HttpStatus.CREATED, loadSnapshotResponse(winner));
        }
        int ordinal = 1;
        for (SnapshotItemView view : views) {
            snapshotRepository.insertItem(new SnapshotItem(
                    header.snapshotKey(), ordinal++, view.observationId(), view.state(), view.version(),
                    view.deleted(), view.location(), view.reading(), view.note(), view.lastResolutionId()));
        }
        return complete(request.requestId(), HttpStatus.CREATED,
                SnapshotResponse.of(header, snapshotRepository.findItems(header.snapshotKey())));
    }

    /**
     * 按 snapshotKey 读取不可变冻结快照；不存在返回 404。只读，不推进观测版本。
     */
    @Transactional(readOnly = true)
    public SnapshotResponse getSnapshot(String snapshotKey) {
        SnapshotHeader header = snapshotRepository.findHeader(snapshotKey)
                .orElseThrow(() -> ApiException.notFound("snapshot not found: " + snapshotKey));
        return SnapshotResponse.of(header, snapshotRepository.findItems(snapshotKey));
    }

    // ---------- 内部辅助 ----------

    private void validateNotFuture(Instant targetUtc) {
        if (targetUtc.isAfter(Instant.now(clock))) {
            throw ApiException.badRequest("target instant must not be later than server current time");
        }
    }

    /**
     * 规整 ID 集合：去空白、去重并按字典序升序；重复 ID 只固化/返回一条，集合换序视为同参。
     */
    private List<String> normalizeIds(List<String> rawIds) {
        return rawIds.stream()
                .map(id -> id == null ? null : id.trim())
                .distinct()
                .sorted()
                .toList();
    }

    private AsOfResponse.Item asOfItem(String observationId, Instant asOfUtc) {
        return toItemView(observationId, asOfUtc).toAsOfItem();
    }

    /**
     * 计算单条记录在目标时刻的视图：未创建为 ABSENT；墓碑生效则为 TOMBSTONE 且不暴露业务字段；否则 ACTIVE。
     */
    private SnapshotItemView toItemView(String observationId, Instant targetTimeUtc) {
        VersionRecord versionRecord = observationRepository.findVersionAsOf(observationId, targetTimeUtc)
                .orElse(null);
        String lastResolutionId = resolutionRepository.findLatestAsOf(observationId, targetTimeUtc)
                .map(ResolutionRecord::resolutionId)
                .orElse(null);
        if (versionRecord == null) {
            return new SnapshotItemView(observationId, "ABSENT", null, false,
                    null, null, null, lastResolutionId);
        }
        ObservationSnapshot snapshot = versionRecord.snapshot();
        if (snapshot.deleted()) {
            return new SnapshotItemView(observationId, "TOMBSTONE", snapshot.version(), true,
                    null, null, null, lastResolutionId);
        }
        return new SnapshotItemView(observationId, "ACTIVE", snapshot.version(), false,
                snapshot.location(), snapshot.reading(), snapshot.note(), lastResolutionId);
    }

    private boolean sameSnapshotParams(SnapshotHeader header, List<String> ids, Instant targetTimeUtc) {
        if (!header.targetTimeUtc().equals(targetTimeUtc)) {
            return false;
        }
        List<String> storedIds = snapshotRepository.findItems(header.snapshotKey()).stream()
                .map(SnapshotItem::observationId)
                .toList();
        return storedIds.equals(ids);
    }

    private SnapshotResponse loadSnapshotResponse(SnapshotHeader header) {
        return SnapshotResponse.of(header, snapshotRepository.findItems(header.snapshotKey()));
    }

    private SnapshotOutcome checkReplay(String requestId, String fingerprint) {
        return requestLogRepository.find(requestId)
                .map(entry -> {
                    if (!entry.fingerprint().equals(fingerprint)) {
                        throw ApiException.conflict(
                                "requestId reused with different parameters: " + requestId, null);
                    }
                    return new SnapshotOutcome(entry.responseStatus(), readBody(entry.responseBody()));
                })
                .orElse(null);
    }

    private SnapshotOutcome insertPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "SNAPSHOT");
            return null;
        } catch (DuplicateKeyException e) {
            return requestLogRepository.find(requestId)
                    .map(entry -> {
                        if (!entry.fingerprint().equals(fingerprint)) {
                            throw ApiException.conflict(
                                    "requestId reused with different parameters: " + requestId, null);
                        }
                        return new SnapshotOutcome(entry.responseStatus(), readBody(entry.responseBody()));
                    })
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
        }
    }

    private SnapshotOutcome complete(String requestId, HttpStatus status, SnapshotResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize snapshot response", e);
        }
        return new SnapshotOutcome(status.value(), body);
    }

    private SnapshotResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, SnapshotResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored snapshot response", e);
        }
    }

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

    /**
     * 单条记录时刻视图的内部承载类型，用于同时构造按时刻查询条目与快照固化条目。
     */
    private record SnapshotItemView(
            String observationId,
            String state,
            Integer version,
            boolean deleted,
            String location,
            String reading,
            String note,
            String lastResolutionId) {

        private AsOfResponse.Item toAsOfItem() {
            return new AsOfResponse.Item(observationId, state, version, deleted,
                    location, reading, note, lastResolutionId);
        }
    }

    /**
     * 快照创建结果：HTTP 状态码与响应体。
     */
    public record SnapshotOutcome(int status, SnapshotResponse body) {
    }
}
