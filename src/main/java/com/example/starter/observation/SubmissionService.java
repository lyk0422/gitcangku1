package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 离线观测提交与人工裁决服务。
 *
 * <p>批量提交在单事务内处理：先占位写入幂等去重记录，再逐条（按提交顺序）校验格式——
 * 任一条目经纬度等格式错误整批 422 回滚；随后按采集时刻匹配站点当前版本围栏
 * （生效区间左闭右开），落在矩形内的条目立即参与三方合并（ACCEPTED），
 * 落在围栏外或无生效围栏的条目保存为 GEOFENCE_REJECTED 证据、不参与自动合并；
 * 合并冲突等业务失败同样整批回滚，不留下部分结果，去重记录不占键。
 *
 * <p>原始坐标、采集时刻、提交时刻与匹配结果落库后不可变；后续围栏变更不重算历史结果。
 * 人工裁决（ADJUDICATE）可接受被拒观测（必须填写理由），在同一事务内完成三方合并、
 * 状态推进与不可变裁决记录写入；裁决记录不可回写。
 */
@Service
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final AdjudicationRepository adjudicationRepository;
    private final GeofenceService geofenceService;
    private final ObservationService observationService;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SubmissionService(SubmissionRepository submissionRepository,
                             AdjudicationRepository adjudicationRepository,
                             GeofenceService geofenceService,
                             ObservationService observationService,
                             RequestLogRepository requestLogRepository,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.submissionRepository = submissionRepository;
        this.adjudicationRepository = adjudicationRepository;
        this.geofenceService = geofenceService;
        this.observationService = observationService;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 批量提交离线观测：任一格式错误整批 422 回滚；围栏内条目自动合并，围栏外条目保留证据。
     */
    @Transactional
    public BatchOutcome submitBatch(BatchSubmissionRequest request) {
        String fingerprint = batchFingerprint(request);
        BatchOutcome replayed = checkBatchReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        BatchOutcome concurrent = insertBatchPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        validateItems(request.items());
        Instant submittedAt = Instant.now(clock);
        List<SubmissionRecord> records = new ArrayList<>();
        int seq = 0;
        for (SubmissionItem item : request.items()) {
            seq++;
            if (submissionRepository.find(item.submissionId()).isPresent()) {
                throw ApiException.conflict("submission already exists: " + item.submissionId(), null);
            }
            records.add(processItem(item, request.requestId(), seq, submittedAt));
        }

        int accepted = 0;
        int rejected = 0;
        List<BatchSubmissionResponse.ItemResult> results = new ArrayList<>();
        for (SubmissionRecord record : records) {
            if (SubmissionRecord.STATUS_ACCEPTED.equals(record.status())) {
                accepted++;
            } else {
                rejected++;
            }
            results.add(BatchSubmissionResponse.ItemResult.of(record));
        }
        BatchSubmissionResponse body = new BatchSubmissionResponse(
                request.requestId(), records.size(), accepted, rejected, List.copyOf(results));
        return completeBatch(request.requestId(), HttpStatus.OK, body);
    }

    /**
     * 人工裁决：接受被围栏拒绝的提交（必须填写理由），在同一事务内完成合并、状态推进与裁决记录。
     */
    @Transactional
    public AdjudicationOutcome adjudicate(String submissionId, AdjudicateSubmissionRequest request) {
        String decision = request.decision().trim().toUpperCase(Locale.ROOT);
        if (!"ACCEPT".equals(decision)) {
            throw ApiException.badRequest("decision must be ACCEPT: " + request.decision());
        }
        String fingerprint = Fingerprinting.of(List.of("ADJUDICATE", submissionId, request.adjudicationId(),
                decision, request.reason(), request.operator()));
        AdjudicationOutcome replayed = checkAdjudicationReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        AdjudicationOutcome concurrent = insertAdjudicationPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        SubmissionRecord submission = submissionRepository.findForUpdate(submissionId)
                .orElseThrow(() -> ApiException.notFound("submission not found: " + submissionId));

        // adjudicationId 重放判定先于状态校验：同参重放始终返回原结果，改参或跨提交复用一律 409。
        AdjudicationRecord existing = adjudicationRepository.findByAdjudicationId(request.adjudicationId())
                .orElse(null);
        if (existing != null) {
            if (!sameAdjudicationParams(existing, submissionId, decision, request)) {
                throw ApiException.conflict(
                        "adjudicationId reused with different parameters: " + request.adjudicationId(), null);
            }
            return completeAdjudication(request.requestId(), HttpStatus.OK,
                    AdjudicationResponse.of(existing, SubmissionRecord.STATUS_MANUAL_ACCEPTED));
        }

        if (!SubmissionRecord.STATUS_GEOFENCE_REJECTED.equals(submission.status())) {
            throw ApiException.conflict(
                    "submission is not in GEOFENCE_REJECTED status: " + submissionId
                            + " (current: " + submission.status() + ")",
                    null);
        }

        ObservationSnapshot merged = observationService.mergeForSubmission(submission.observationId(),
                submission.baseVersion(), submission.location(), submission.reading(), submission.note());
        submissionRepository.markManuallyAccepted(submissionId, merged.version());

        AdjudicationRecord record = new AdjudicationRecord(request.adjudicationId(), submissionId,
                request.requestId(), decision, request.reason(), request.operator(),
                merged.version(), Instant.now(clock));
        try {
            adjudicationRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下 adjudicationId 或 submission 已被其他事务裁决：重读后同参重放，否则 409。
            AdjudicationRecord winner = adjudicationRepository.findByAdjudicationId(request.adjudicationId())
                    .orElseThrow(() -> ApiException.conflict(
                            "adjudicationId conflict: " + request.adjudicationId(), null));
            if (!sameAdjudicationParams(winner, submissionId, decision, request)) {
                throw ApiException.conflict(
                        "adjudicationId reused with different parameters: " + request.adjudicationId(), null);
            }
            return completeAdjudication(request.requestId(), HttpStatus.OK,
                    AdjudicationResponse.of(winner, SubmissionRecord.STATUS_MANUAL_ACCEPTED));
        }
        return completeAdjudication(request.requestId(), HttpStatus.OK,
                AdjudicationResponse.of(record, SubmissionRecord.STATUS_MANUAL_ACCEPTED));
    }

    /**
     * 查询提交详情；不存在返回 404。查询不修改状态。
     */
    @Transactional(readOnly = true)
    public SubmissionResponse getSubmission(String submissionId) {
        SubmissionRecord record = submissionRepository.find(submissionId)
                .orElseThrow(() -> ApiException.notFound("submission not found: " + submissionId));
        return SubmissionResponse.of(record);
    }

    /**
     * 按站点查询提交历史，按提交时刻与批内顺序稳定排序；查询不修改状态。
     */
    @Transactional(readOnly = true)
    public List<SubmissionResponse> listBySite(String siteId) {
        return submissionRepository.findBySite(siteId).stream().map(SubmissionResponse::of).toList();
    }

    /**
     * 查询提交的裁决历史；提交不存在返回 404。查询不修改状态。
     */
    @Transactional(readOnly = true)
    public List<AdjudicationResponse> listAdjudications(String submissionId) {
        SubmissionRecord submission = submissionRepository.find(submissionId)
                .orElseThrow(() -> ApiException.notFound("submission not found: " + submissionId));
        return adjudicationRepository.findBySubmissionId(submissionId).stream()
                .map(record -> AdjudicationResponse.of(record, submission.status()))
                .toList();
    }

    /**
     * 处理单条提交：匹配围栏（采集时刻落在生效区间左闭右开内），围栏内自动合并，围栏外保留证据。
     */
    private SubmissionRecord processItem(SubmissionItem item, String batchRequestId, int seq,
                                         Instant submittedAt) {
        GeofenceRecord matched = matchFence(item.siteId(), item.collectedAtUtc());
        String matchResult;
        String status;
        Integer mergedVersion = null;
        if (matched == null) {
            matchResult = SubmissionRecord.MATCH_NO_FENCE;
            status = SubmissionRecord.STATUS_GEOFENCE_REJECTED;
        } else if (matched.contains(item.latitude(), item.longitude())) {
            matchResult = SubmissionRecord.MATCH_INSIDE;
            status = SubmissionRecord.STATUS_ACCEPTED;
            mergedVersion = observationService.mergeForSubmission(item.observationId(), item.baseVersion(),
                    item.location(), item.reading(), item.note()).version();
        } else {
            matchResult = SubmissionRecord.MATCH_OUTSIDE;
            status = SubmissionRecord.STATUS_GEOFENCE_REJECTED;
        }
        SubmissionRecord record = new SubmissionRecord(item.submissionId(), batchRequestId, seq,
                item.siteId(), item.observationId(), item.baseVersion(), item.location(), item.reading(),
                item.note(), item.latitude(), item.longitude(), item.collectedAtUtc(), submittedAt,
                status, matchResult,
                matched == null ? null : matched.fenceId(),
                matched == null ? null : matched.fenceVersion(),
                mergedVersion);
        submissionRepository.insert(record);
        return record;
    }

    /**
     * 按采集时刻匹配站点当前版本围栏：生效区间左闭右开；同站点区间不重叠保证至多一条命中，
     * 防御性地按围栏标识排序取第一条保证确定性。
     */
    private GeofenceRecord matchFence(String siteId, Instant collectedAtUtc) {
        return geofenceService.currentFences(siteId).stream()
                .filter(fence -> fence.covers(collectedAtUtc))
                .min(Comparator.comparing(GeofenceRecord::fenceId))
                .orElse(null);
    }

    /**
     * 批量条目格式校验：经纬度范围非法或批内 submissionId 重复时整批 422，不写入任何记录。
     */
    private void validateItems(List<SubmissionItem> items) {
        Set<String> seen = new HashSet<>();
        for (SubmissionItem item : items) {
            GeoValidation.requireLatitude("latitude", item.latitude());
            GeoValidation.requireLongitude("longitude", item.longitude());
            if (!seen.add(item.submissionId())) {
                throw ApiException.unprocessable(
                        "duplicate submissionId in batch: " + item.submissionId(),
                        item.submissionId(), "submissionId unique within batch");
            }
        }
    }

    /**
     * 批量提交指纹：操作类型、操作者及规范化后的条目集合（按 submissionId 排序，
     * 每条含全部影响结果的字段），长度前缀编码，不使用未转义分隔符。
     */
    private String batchFingerprint(BatchSubmissionRequest request) {
        List<SubmissionItem> sorted = new ArrayList<>(request.items());
        sorted.sort(Comparator.comparing(SubmissionItem::submissionId));
        List<String> parts = new ArrayList<>();
        parts.add("SUBMIT_BATCH");
        parts.add(request.operator());
        for (SubmissionItem item : sorted) {
            parts.add(item.submissionId());
            parts.add(item.siteId());
            parts.add(item.observationId());
            parts.add(String.valueOf(item.baseVersion()));
            parts.add(item.location());
            parts.add(item.reading());
            parts.add(item.note());
            parts.add(item.latitude().toPlainString());
            parts.add(item.longitude().toPlainString());
            parts.add(item.collectedAtUtc().toString());
        }
        return Fingerprinting.of(parts);
    }

    /**
     * 判断本次裁决请求参数与已存在裁决记录是否完全一致（用于 adjudicationId 同参重放）。
     */
    private boolean sameAdjudicationParams(AdjudicationRecord record, String submissionId,
                                           String decision, AdjudicateSubmissionRequest request) {
        return Objects.equals(record.submissionId(), submissionId)
                && Objects.equals(record.decision(), decision)
                && Objects.equals(record.reason(), request.reason())
                && Objects.equals(record.operator(), request.operator());
    }

    // ---------- 批量提交幂等 ----------

    private BatchOutcome checkBatchReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new BatchOutcome(entry.responseStatus(), readBatchBody(entry.responseBody()));
    }

    private BatchOutcome insertBatchPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "SUBMIT_BATCH");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new BatchOutcome(entry.responseStatus(), readBatchBody(entry.responseBody()));
        }
    }

    private BatchOutcome completeBatch(String requestId, HttpStatus status, BatchSubmissionResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize batch response", e);
        }
        return new BatchOutcome(status.value(), body);
    }

    private BatchSubmissionResponse readBatchBody(String json) {
        try {
            return objectMapper.readValue(json, BatchSubmissionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored batch response", e);
        }
    }

    // ---------- 人工裁决幂等 ----------

    private AdjudicationOutcome checkAdjudicationReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new AdjudicationOutcome(entry.responseStatus(), readAdjudicationBody(entry.responseBody()));
    }

    private AdjudicationOutcome insertAdjudicationPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "ADJUDICATE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new AdjudicationOutcome(entry.responseStatus(), readAdjudicationBody(entry.responseBody()));
        }
    }

    private AdjudicationOutcome completeAdjudication(String requestId, HttpStatus status,
                                                     AdjudicationResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize adjudication response", e);
        }
        return new AdjudicationOutcome(status.value(), body);
    }

    private AdjudicationResponse readAdjudicationBody(String json) {
        try {
            return objectMapper.readValue(json, AdjudicationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored adjudication response", e);
        }
    }

    /**
     * 批量提交结果：HTTP 状态码与响应体。
     */
    public record BatchOutcome(int status, BatchSubmissionResponse body) {
    }

    /**
     * 人工裁决结果：HTTP 状态码与响应体。
     */
    public record AdjudicationOutcome(int status, AdjudicationResponse body) {
    }
}
