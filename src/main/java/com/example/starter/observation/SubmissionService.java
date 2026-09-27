package com.example.starter.observation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 离线观测提交与人工裁决业务服务。
 *
 * <p>批量提交在单事务内完成：先对全部提交项做坐标语义校验（任一非法整批 422 回滚），
 * 再按提交顺序逐项裁决——按采集时刻匹配站点当前生效围栏（生效区间 UTC 左闭右开，
 * 矩形边界含端点）；落围栏内的项按原三方合并规则自动合并进观测记录，
 * 落围栏外的项保存为 GEOFENCE_REJECTED，不参与自动合并但保留原始证据。
 * 观测记录不存在/已删除/基线版本缺失/合并冲突时整批回滚，查询不到半成品。
 *
 * <p>提交的原始坐标、采集/提交时刻与匹配结果不可变；后续围栏改版不重算历史匹配结果。
 * 人工裁决仅可接受 GEOFENCE_REJECTED 提交且必须填写理由，裁决时按提交保存的候选值
 * 与基线版本重算三方合并；裁决记录不可变，一次提交至多一条。
 *
 * <p>requestId 同参重放返回首次完整响应快照、异参 409、失败不占键；
 * 幂等指纹包含操作者、操作类型、规范化提交项集合及所有影响结果的字段。
 */
@Service
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final AdjudicationRepository adjudicationRepository;
    private final ObservationRepository observationRepository;
    private final GeofenceRepository geofenceRepository;
    private final IdempotentRequests idempotentRequests;
    private final Fingerprinting fingerprinting;
    private final Clock clock;

    public SubmissionService(SubmissionRepository submissionRepository,
                             AdjudicationRepository adjudicationRepository,
                             ObservationRepository observationRepository,
                             GeofenceRepository geofenceRepository,
                             IdempotentRequests idempotentRequests,
                             Fingerprinting fingerprinting,
                             Clock clock) {
        this.submissionRepository = submissionRepository;
        this.adjudicationRepository = adjudicationRepository;
        this.observationRepository = observationRepository;
        this.geofenceRepository = geofenceRepository;
        this.idempotentRequests = idempotentRequests;
        this.fingerprinting = fingerprinting;
        this.clock = clock;
    }

    /**
     * 批量离线提交：任一格式/语义错误整批回滚；成功时逐项落库并返回逐项裁决结果。
     */
    @Transactional
    public BatchOutcome submitBatch(BatchSubmitObservationsRequest request) {
        // 先校验完整最终状态：全部提交项坐标合法、批内 submissionId 不重复，再进入写流程
        Set<String> seenSubmissionIds = new HashSet<>();
        for (SubmissionItemRequest item : request.items()) {
            GeoValidation.validatePoint(item.latitude(), item.longitude());
            if (!seenSubmissionIds.add(item.submissionId())) {
                throw ApiException.badRequest("duplicate submissionId within batch: " + item.submissionId());
            }
        }

        String fingerprint = fingerprinting.of("SUBMIT_BATCH", request.operator(), itemParts(request.items()));
        RequestLogEntry existing = idempotentRequests.findExistingOrThrow(request.requestId(), fingerprint);
        if (existing != null) {
            return replayBatch(existing);
        }
        RequestLogEntry concurrent = idempotentRequests.insertPlaceholderOrNull(
                request.requestId(), fingerprint, "SUBMIT_BATCH");
        if (concurrent != null) {
            return replayBatch(concurrent);
        }

        Instant submittedAt = Instant.now(clock);

        // 统一按排序顺序获取全部站点锁与观测记录行锁，再按提交顺序处理：
        // 站点锁保证批次处理期间该站点围栏配置不变（与围栏创建/改版串行化），
        // 排序加锁避免多站点批次间的死锁；行锁在本事务内可重入，处理时再次锁定无副作用。
        request.items().stream()
                .map(SubmissionItemRequest::siteId)
                .distinct()
                .sorted()
                .forEach(geofenceRepository::lockSiteForUpdate);
        request.items().stream()
                .map(SubmissionItemRequest::observationId)
                .distinct()
                .sorted()
                .forEach(observationId -> observationRepository.findCurrentForUpdate(observationId)
                        .orElseThrow(() -> ApiException.notFound(
                                "observation not found: " + observationId)));

        List<SubmissionResponse> results = new ArrayList<>();
        int itemIndex = 0;
        for (SubmissionItemRequest item : request.items()) {
            results.add(processItem(request, item, itemIndex, submittedAt));
            itemIndex++;
        }
        BatchSubmissionResponse body = new BatchSubmissionResponse(
                request.requestId(), request.operator(), submittedAt, results);
        idempotentRequests.complete(request.requestId(), HttpStatus.OK.value(), body);
        return new BatchOutcome(HttpStatus.OK.value(), body);
    }

    /**
     * 人工裁决：接受围栏拒绝的提交。须写理由；仅 GEOFENCE_REJECTED 可裁决；
     * 裁决时按提交保存的候选值与基线版本重算三方合并，冲突则 409 不写任何结果。
     */
    @Transactional
    public AdjudicationOutcome adjudicate(String submissionId, AdjudicateSubmissionRequest request) {
        if (!"ACCEPT".equals(request.decision())) {
            throw ApiException.badRequest("decision must be ACCEPT: " + request.decision());
        }
        if (request.reason().isBlank()) {
            throw ApiException.badRequest("reason must not be blank");
        }
        String fingerprint = fingerprinting.of("ADJUDICATE", submissionId, request.operator(),
                request.decision(), request.reason());
        RequestLogEntry existing = idempotentRequests.findExistingOrThrow(request.requestId(), fingerprint);
        if (existing != null) {
            return replayAdjudication(existing);
        }
        RequestLogEntry concurrent = idempotentRequests.insertPlaceholderOrNull(
                request.requestId(), fingerprint, "ADJUDICATE");
        if (concurrent != null) {
            return replayAdjudication(concurrent);
        }

        SubmissionRecord submission = submissionRepository.findForUpdate(submissionId)
                .orElseThrow(() -> ApiException.notFound("submission not found: " + submissionId));
        if (adjudicationRepository.findBySubmissionId(submissionId).isPresent()) {
            throw ApiException.conflict("submission already adjudicated: " + submissionId, null);
        }
        if (!SubmissionRecord.STATUS_GEOFENCE_REJECTED.equals(submission.matchStatus())) {
            throw ApiException.conflict(
                    "only GEOFENCE_REJECTED submissions can be adjudicated: " + submissionId
                            + " (matchStatus " + submission.matchStatus() + ")", null);
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(submission.observationId())
                .orElseThrow(() -> ApiException.notFound(
                        "observation not found: " + submission.observationId()));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + submission.observationId());
        }
        ObservationSnapshot base = observationRepository.findVersion(
                submission.observationId(), submission.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + submission.observationId()
                                + "@" + submission.baseVersion()));

        int observationVersion = mergeIntoObservation(submission.observationId(), base, current,
                submission.location(), submission.reading(), submission.note());
        AdjudicationRecord record = new AdjudicationRecord(submissionId, request.requestId(),
                request.operator(), request.decision(), request.reason(),
                observationVersion, Instant.now(clock));
        try {
            adjudicationRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发裁决同一提交：主键串行化，后到者按已裁决处理
            throw ApiException.conflict("submission already adjudicated: " + submissionId, null);
        }
        AdjudicationResponse body = AdjudicationResponse.of(record, submission);
        idempotentRequests.complete(request.requestId(), HttpStatus.OK.value(), body);
        return new AdjudicationOutcome(HttpStatus.OK.value(), body);
    }

    /**
     * 查询提交明细；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public SubmissionResponse getSubmission(String submissionId) {
        return SubmissionResponse.of(submissionRepository.find(submissionId)
                .orElseThrow(() -> ApiException.notFound("submission not found: " + submissionId)));
    }

    /**
     * 按观测记录查询提交历史，按提交时刻与提交标识升序；观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<SubmissionResponse> listByObservation(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return submissionRepository.findByObservationId(observationId).stream()
                .map(SubmissionResponse::of)
                .toList();
    }

    /**
     * 按站点查询提交历史，按提交时刻与提交标识升序。
     */
    @Transactional(readOnly = true)
    public List<SubmissionResponse> listBySite(String siteId) {
        return submissionRepository.findBySiteId(siteId).stream()
                .map(SubmissionResponse::of)
                .toList();
    }

    /**
     * 站点提交诊断：各项数量均为实际值。
     */
    @Transactional(readOnly = true)
    public SubmissionDiagnosticsResponse diagnostics(String siteId) {
        int accepted = submissionRepository.countBySiteAndStatus(siteId, SubmissionRecord.STATUS_ACCEPTED);
        int rejected = submissionRepository.countBySiteAndStatus(
                siteId, SubmissionRecord.STATUS_GEOFENCE_REJECTED);
        int adjudicated = adjudicationRepository.countAcceptedBySite(siteId);
        return new SubmissionDiagnosticsResponse(siteId, accepted + rejected, accepted, rejected,
                adjudicated, rejected - adjudicated);
    }

    /**
     * 处理单个提交项：围栏匹配 → 自动合并或保留拒绝证据，落库不可变提交记录。
     */
    private SubmissionResponse processItem(BatchSubmitObservationsRequest request,
                                           SubmissionItemRequest item, int itemIndex, Instant submittedAt) {
        ObservationSnapshot current = observationRepository.findCurrentForUpdate(item.observationId())
                .orElseThrow(() -> ApiException.notFound("observation not found: " + item.observationId()));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + item.observationId());
        }
        ObservationSnapshot base = observationRepository.findVersion(item.observationId(), item.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + item.observationId() + "@" + item.baseVersion()));

        GeofenceVersionRecord matched = geofenceRepository.findMatching(
                item.siteId(), item.capturedAtUtc(), item.latitude(), item.longitude()).orElse(null);
        String matchStatus;
        String matchedFenceId = null;
        Integer matchedFenceVersion = null;
        Integer observationVersion = null;
        if (matched != null) {
            matchStatus = SubmissionRecord.STATUS_ACCEPTED;
            matchedFenceId = matched.fenceId();
            matchedFenceVersion = matched.version();
            observationVersion = mergeIntoObservation(item.observationId(), base, current,
                    item.location(), item.reading(), item.note());
        } else {
            // 落围栏外：保存 GEOFENCE_REJECTED 原始证据，不参与自动合并
            matchStatus = SubmissionRecord.STATUS_GEOFENCE_REJECTED;
        }

        SubmissionRecord record = new SubmissionRecord(item.submissionId(), request.requestId(), itemIndex,
                request.operator(), item.observationId(), item.siteId(), item.baseVersion(),
                item.location(), item.reading(), item.note(),
                item.latitude(), item.longitude(), item.capturedAtUtc(), submittedAt,
                matchStatus, matchedFenceId, matchedFenceVersion, observationVersion);
        try {
            submissionRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // submissionId 已被历史批次占用：整批 409 回滚
            throw ApiException.conflict("submissionId already exists: " + item.submissionId(), null);
        }
        return SubmissionResponse.of(record);
    }

    /**
     * 三方合并候选值到观测记录：与离线合并同一套规则；冲突抛 409 不写部分结果，
     * 内容无变化不加版本。返回合并后观测记录版本号。
     */
    private int mergeIntoObservation(String observationId, ObservationSnapshot base,
                                     ObservationSnapshot current, String location,
                                     String reading, String note) {
        List<String> conflictFields = new ArrayList<>();
        String mergedLocation = ObservationService.mergeField("location", base.location(),
                current.location(), location, conflictFields);
        String mergedReading = ObservationService.mergeReadingField(base.reading(),
                current.reading(), reading, conflictFields);
        String mergedNote = ObservationService.mergeField("note", base.note(),
                current.note(), note, conflictFields);
        if (!conflictFields.isEmpty()) {
            throw ApiException.mergeConflict(conflictFields, current.version());
        }
        ObservationSnapshot merged = new ObservationSnapshot(observationId, current.version(),
                mergedLocation, mergedReading, mergedNote, false);
        if (ObservationService.sameContent(merged, current)) {
            return current.version();
        }
        ObservationSnapshot next = new ObservationSnapshot(observationId, current.version() + 1,
                mergedLocation, mergedReading, mergedNote, false);
        observationRepository.updateCurrent(next);
        observationRepository.insertVersion(next);
        return next.version();
    }

    /**
     * 组装批量提交项的规范化指纹因子：每项按固定字段顺序展开，集合整体交给 JSON 转义序列化。
     */
    private List<List<Object>> itemParts(List<SubmissionItemRequest> items) {
        return items.stream()
                .<List<Object>>map(item -> List.of(item.submissionId(), item.observationId(), item.siteId(),
                        item.baseVersion(), item.location(), item.reading(), item.note(),
                        item.latitude(), item.longitude(), item.capturedAtUtc()))
                .toList();
    }

    private BatchOutcome replayBatch(RequestLogEntry entry) {
        return new BatchOutcome(entry.responseStatus(),
                idempotentRequests.readStoredBody(entry, BatchSubmissionResponse.class));
    }

    private AdjudicationOutcome replayAdjudication(RequestLogEntry entry) {
        return new AdjudicationOutcome(entry.responseStatus(),
                idempotentRequests.readStoredBody(entry, AdjudicationResponse.class));
    }

    /**
     * 批量提交结果：HTTP 状态码与批量响应体。
     */
    public record BatchOutcome(int status, BatchSubmissionResponse body) {
    }

    /**
     * 人工裁决结果：HTTP 状态码与裁决响应体。
     */
    public record AdjudicationOutcome(int status, AdjudicationResponse body) {
    }
}
