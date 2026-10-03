package com.example.starter.kiln;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 窑炉批次业务服务：程序版本、入炉、测温上传、异常标记、质检放行与批次详情。
 */
@Service
public class KilnService {

    private static final String STATUS_FIRING = "FIRING";
    private static final String STATUS_RELEASED = "RELEASED";
    private static final String STATUS_REJECTED = "REJECTED";

    private final KilnRepository repository;
    private final TrajectoryValidator validator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public KilnService(KilnRepository repository, TrajectoryValidator validator,
                       ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 创建升温程序版本，段必须按 UTC 首尾相接连续覆盖。 */
    @Transactional
    public long createProgram(KilnDtos.CreateProgramRequest request) {
        List<KilnDtos.SegmentInput> segments = new ArrayList<>(request.segments());
        segments.sort(Comparator.comparing(KilnDtos.SegmentInput::startUtc));
        for (int i = 0; i < segments.size(); i++) {
            KilnDtos.SegmentInput segment = segments.get(i);
            if (!segment.startUtc().isBefore(segment.endUtc())) {
                throw new KilnException(HttpStatus.UNPROCESSABLE_ENTITY, "SEGMENT_RANGE_INVALID",
                        "第 " + (i + 1) + " 段起止时间无效");
            }
            if (segment.tolerance() < 0) {
                throw new KilnException(HttpStatus.UNPROCESSABLE_ENTITY, "SEGMENT_TOLERANCE_INVALID",
                        "第 " + (i + 1) + " 段允许偏差不能为负");
            }
            if (i > 0 && !segments.get(i - 1).endUtc().equals(segment.startUtc())) {
                throw new KilnException(HttpStatus.UNPROCESSABLE_ENTITY, "SEGMENTS_NOT_CONTIGUOUS",
                        "第 " + (i + 1) + " 段与上一段不连续，程序段必须首尾相接覆盖烧制区间");
            }
        }
        long programId = repository.insertProgram(request.name(), request.version(), Instant.now(clock));
        for (int i = 0; i < segments.size(); i++) {
            KilnDtos.SegmentInput segment = segments.get(i);
            repository.insertSegment(programId, i + 1, segment.startUtc(), segment.endUtc(),
                    segment.targetTemp(), segment.tolerance());
        }
        return programId;
    }

    /** 入炉建批：锁定程序版本。 */
    @Transactional
    public long createBatch(KilnDtos.CreateBatchRequest request) {
        String version = repository.findProgramVersion(request.programId())
                .orElseThrow(() -> new KilnException(HttpStatus.NOT_FOUND, "PROGRAM_NOT_FOUND",
                        "程序 " + request.programId() + " 不存在"));
        return repository.insertBatch(request.batchNo(), request.programId(), version, null,
                Instant.now(clock));
    }

    /** 对已标记异常的批次发起独立复烧批次，沿用原程序版本，不改动原始数据。 */
    @Transactional
    public long refire(long sourceBatchId) {
        KilnRepository.BatchRow source = repository.findBatch(sourceBatchId)
                .orElseThrow(() -> new KilnException(HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND",
                        "批次 " + sourceBatchId + " 不存在"));
        if (repository.anomaliesOf(sourceBatchId).isEmpty()) {
            throw new KilnException(HttpStatus.UNPROCESSABLE_ENTITY, "REFIRE_REQUIRES_ANOMALY",
                    "只有已标记异常的批次才能发起复烧");
        }
        int seq = repository.countRefiresOf(sourceBatchId) + 1;
        return repository.insertBatch(source.batchNo() + "-R" + seq, source.programId(),
                source.programVersion(), sourceBatchId, Instant.now(clock));
    }
    /** 测温上传：requestId 幂等，同参重放返回原结果，异参 409；批次关闭后拒绝写入。 */
    @Transactional
    public KilnDtos.UploadResult uploadPoints(long batchId, KilnDtos.UploadPointsRequest request) {
        String payloadHash = hashPayload(request);
        repository.lockBatch(batchId)
                .orElseThrow(() -> new KilnException(HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND",
                        "批次 " + batchId + " 不存在"));
        var existing = repository.findUploadRequest(batchId, request.requestId());
        if (existing.isPresent()) {
            String[] record = existing.get();
            if (!record[0].equals(payloadHash)) {
                throw new KilnException(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT",
                        "requestId " + request.requestId() + " 已用于不同参数的测温上传");
            }
            return new KilnDtos.UploadResult(true, Integer.parseInt(record[1]));
        }
        if (repository.findRelease(batchId).isPresent()) {
            throw new KilnException(HttpStatus.CONFLICT, "BATCH_CLOSED",
                    "批次已完成质检判定，不能再写入测温点");
        }
        Instant now = Instant.now(clock);
        for (KilnDtos.PointInput point : request.points()) {
            try {
                repository.insertPoint(batchId, point.measuredAt(), point.temperature(),
                        request.requestId(), now);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new KilnException(HttpStatus.CONFLICT, "DUPLICATE_TIMESTAMP",
                        "采样时刻 " + point.measuredAt() + " 已存在，测温点只能追加不能覆盖");
            }
        }
        repository.insertUploadRequest(batchId, request.requestId(), payloadHash,
                request.points().size(), now);
        return new KilnDtos.UploadResult(false, request.points().size());
    }

    /** 标记异常：被标记批次只能驳回或复烧。 */
    @Transactional
    public long markAnomaly(long batchId, KilnDtos.MarkAnomalyRequest request) {
        KilnRepository.BatchRow batch = repository.lockBatch(batchId)
                .orElseThrow(() -> new KilnException(HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND",
                        "批次 " + batchId + " 不存在"));
        if (!STATUS_FIRING.equals(batch.status())) {
            throw new KilnException(HttpStatus.CONFLICT, "BATCH_CLOSED",
                    "批次已完成质检判定，不能再标记异常");
        }
        return repository.insertAnomaly(batchId, request.reason(), request.markedBy(),
                Instant.now(clock));
    }

    /** 质检判定：放行需全轨迹校核通过且无异常标记；判定一次性固化程序版本与测温点上限。 */
    @Transactional
    public KilnDtos.ReleaseResult release(long batchId, KilnDtos.ReleaseRequest request) {
        KilnRepository.BatchRow batch = repository.lockBatch(batchId)
                .orElseThrow(() -> new KilnException(HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND",
                        "批次 " + batchId + " 不存在"));
        var existing = repository.findRelease(batchId);
        if (existing.isPresent()) {
            return toReleaseResult(existing.get(), true);
        }
        String decision = request.decision().trim().toUpperCase();
        if (!decision.equals("RELEASE") && !decision.equals("REJECT")) {
            throw new KilnException(HttpStatus.UNPROCESSABLE_ENTITY, "DECISION_INVALID",
                    "decision 只能是 RELEASE 或 REJECT");
        }
        List<KilnRepository.SegmentRow> segments = repository.segmentsOf(batch.programId());
        List<KilnRepository.PointRow> points = repository.pointsOf(batchId);
        List<KilnDtos.SegmentDeviation> deviations = validator.deviations(segments, points);
        String stored;
        String status;
        if (decision.equals("REJECT")) {
            stored = STATUS_REJECTED;
            status = STATUS_REJECTED;
        } else {
            if (!repository.anomaliesOf(batchId).isEmpty()) {
                throw new KilnException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_HAS_ANOMALY",
                        "批次已标记异常，只能驳回或发起复烧，不能放行");
            }
            List<KilnDtos.Violation> violations = validator.validate(segments, points);
            if (!violations.isEmpty()) {
                throw new KilnException(HttpStatus.UNPROCESSABLE_ENTITY, "RELEASE_CHECK_FAILED",
                        "轨迹校核未通过，共 " + violations.size() + " 项违规", violations);
            }
            stored = STATUS_RELEASED;
            status = STATUS_RELEASED;
        }
        String deviationsJson = writeDeviations(deviations);
        long recordId = repository.insertRelease(batchId, stored, batch.programVersion(),
                points.size(), deviationsJson, request.inspector(), Instant.now(clock));
        repository.updateBatchStatus(batchId, status);
        return new KilnDtos.ReleaseResult(recordId, batchId, stored, batch.programVersion(),
                points.size(), deviations, request.inspector(), Instant.now(clock), false);
    }

    /** 批次详情：程序版本、各段最坏偏差与放行依据。 */
    @Transactional(readOnly = true)
    public KilnDtos.BatchDetail detail(long batchId) {
        KilnRepository.BatchRow batch = repository.findBatch(batchId)
                .orElseThrow(() -> new KilnException(HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND",
                        "批次 " + batchId + " 不存在"));
        List<KilnRepository.SegmentRow> segments = repository.segmentsOf(batch.programId());
        List<KilnRepository.PointRow> points = repository.pointsOf(batchId);
        List<KilnDtos.SegmentDeviation> deviations = validator.deviations(segments, points);
        List<KilnDtos.SegmentView> segmentViews = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            KilnRepository.SegmentRow segment = segments.get(i);
            KilnDtos.SegmentDeviation deviation = deviations.get(i);
            segmentViews.add(new KilnDtos.SegmentView(segment.seq(), segment.startUtc(),
                    segment.endUtc(), segment.targetTemp(), segment.tolerance(),
                    deviation.sampleCount() == 0 ? null : deviation.worstDeviation(),
                    deviation.sampleCount()));
        }
        List<KilnDtos.AnomalyView> anomalies = repository.anomaliesOf(batchId).stream()
                .map(a -> new KilnDtos.AnomalyView(a.id(), a.reason(), a.markedBy(), a.markedAt()))
                .toList();
        KilnDtos.ReleaseResult release = repository.findRelease(batchId)
                .map(row -> toReleaseResult(row, false)).orElse(null);
        return new KilnDtos.BatchDetail(batch.id(), batch.batchNo(), batch.status(),
                batch.programId(), batch.programVersion(), batch.refireOf(), segmentViews,
                anomalies, release);
    }
    private KilnDtos.ReleaseResult toReleaseResult(KilnRepository.ReleaseRow row, boolean replayed) {
        List<KilnDtos.SegmentDeviation> deviations;
        try {
            deviations = objectMapper.readValue(row.deviations(),
                    new TypeReference<List<KilnDtos.SegmentDeviation>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException("放行记录偏差快照无法解析", e);
        }
        return new KilnDtos.ReleaseResult(row.id(), row.batchId(), row.decision(),
                row.programVersion(), row.pointsLimit(), deviations, row.inspector(),
                row.decidedAt(), replayed);
    }

    private String writeDeviations(List<KilnDtos.SegmentDeviation> deviations) {
        try {
            return objectMapper.writeValueAsString(deviations);
        } catch (Exception e) {
            throw new IllegalStateException("偏差快照序列化失败", e);
        }
    }

    /** 请求体规范化指纹：测温点按时刻与温度排序后拼接，做 SHA-256。 */
    private String hashPayload(KilnDtos.UploadPointsRequest request) {
        List<KilnDtos.PointInput> points = new ArrayList<>(request.points());
        points.sort(Comparator.comparing(KilnDtos.PointInput::measuredAt)
                .thenComparing(KilnDtos.PointInput::temperature));
        StringBuilder canonical = new StringBuilder();
        for (KilnDtos.PointInput point : points) {
            canonical.append(point.measuredAt()).append('=').append(point.temperature()).append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}