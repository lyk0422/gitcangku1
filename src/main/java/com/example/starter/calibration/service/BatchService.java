package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.BatchReleaseResponse;
import com.example.starter.calibration.api.dto.BatchResponse;
import com.example.starter.calibration.api.dto.CreateBatchRequest;
import com.example.starter.calibration.api.dto.DiagnosticsResponse;
import com.example.starter.calibration.api.dto.OutlierResponse;
import com.example.starter.calibration.api.dto.ReadingResponse;
import com.example.starter.calibration.api.dto.ReleaseSnapshotResponse;
import com.example.starter.calibration.api.dto.ReviewRecordResponse;
import com.example.starter.calibration.api.dto.StatsRecordResponse;
import com.example.starter.calibration.api.dto.SubmitReadingRequest;
import com.example.starter.calibration.model.Batch;
import com.example.starter.calibration.model.BatchReading;
import com.example.starter.calibration.model.BatchReleaseSnapshot;
import com.example.starter.calibration.model.BatchStatsRecord;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.OutlierRepository;
import com.example.starter.calibration.repo.ReadingRepository;
import com.example.starter.calibration.repo.ReviewRepository;
import com.example.starter.calibration.repo.StatsRepository;

/**
 * 测量批次服务：创建（目标值+允许偏差）、读数提交（离群检测）、放行（离群阻断）、明细与诊断。
 * 读数提交、复核、证书撤销、测量修订和放行通过批次行锁按事务提交顺序串行化。
 */
@Service
public class BatchService {

    private final BatchRepository batches;
    private final ReadingRepository readings;
    private final OutlierRepository outliers;
    private final ReviewRepository reviews;
    private final StatsRepository statsRepository;
    private final CertificateRepository certificates;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public BatchService(BatchRepository batches, ReadingRepository readings, OutlierRepository outliers,
                        ReviewRepository reviews, StatsRepository statsRepository,
                        CertificateRepository certificates, IdempotencyService idempotency, Clock clock) {
        this.batches = batches;
        this.readings = readings;
        this.outliers = outliers;
        this.reviews = reviews;
        this.statsRepository = statsRepository;
        this.certificates = certificates;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 创建测量批次。batchKey 重复返回 409；tolerance 为负返回 400。
     */
    @Transactional
    public BatchResponse createBatch(CreateBatchRequest request) {
        String batchKey = Inputs.requireText(request.batchKey(), "batchKey");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        BigDecimal target = Inputs.requireDecimal(request.targetValue(), "targetValue");
        BigDecimal tolerance = Inputs.requireDecimal(request.tolerance(), "tolerance");
        if (tolerance.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest("tolerance 不能为负");
        }
        String fingerprint = String.join("|", batchKey, instrumentId,
                target.toPlainString(), tolerance.toPlainString());
        return idempotency.execute(request.requestId(), fingerprint, BatchResponse.class, () -> {
            Batch batch = new Batch(0L, batchKey, instrumentId, target, tolerance, 1,
                    BatchStatus.OPEN, null, null, Instant.now(clock));
            try {
                batches.insert(batch);
            } catch (DuplicateKeyException ex) {
                throw ApiException.conflict("DUPLICATE_BATCH_KEY", "批次键已存在: " + batchKey);
            }
            return toResponse(batches.findByKey(batchKey).orElseThrow());
        });
    }

    /**
     * 提交读数。按测量时刻匹配唯一有效证书并封存证书版本与批次修订版本；
     * 读数偏差超过允许偏差时自动生成 PENDING 离群记录；已放行批次新增读数只能创建后续修订版本。
     */
    @Transactional
    public ReadingResponse submitReading(String batchKey, SubmitReadingRequest request) {
        String key = Inputs.requireText(batchKey, "batchKey");
        String readingKey = Inputs.requireText(request.readingKey(), "readingKey");
        Instant measuredAt = Inputs.requireInstant(request.measuredAt(), "measuredAt");
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");
        String fingerprint = String.join("|", key, readingKey, measuredAt.toString(),
                reading.toPlainString(), submittedBy);
        return idempotency.execute(request.requestId(), fingerprint, ReadingResponse.class, () -> {
            Instant now = Instant.now(clock);
            Batch batch = batches.findByKeyForUpdate(key)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
            int revision = batch.revision();
            if (batch.status() == BatchStatus.RELEASED) {
                revision = batch.revision() + 1;
                batches.reopenWithNewRevision(batch.id(), revision);
            }
            Certificate cert = certificates.findMatching(batch.instrumentId(), measuredAt)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "测量时刻无匹配的有效证书: instrument=" + batch.instrumentId()));
            Certificate locked = certificates.findByIdForUpdate(cert.id())
                    .orElseThrow(() -> ApiException.unprocessable(
                            "测量时刻无匹配的有效证书: instrument=" + batch.instrumentId()));
            if (locked.revoked()) {
                throw ApiException.unprocessable("测量时刻无匹配的有效证书: instrument=" + batch.instrumentId());
            }
            BatchReading entity = new BatchReading(0L, readingKey, batch.id(), measuredAt, reading,
                    submittedBy, locked.id(), locked.version(), revision, false, now);
            long readingId;
            try {
                readingId = readings.insert(entity);
            } catch (DuplicateKeyException ex) {
                throw ApiException.conflict("DUPLICATE_READING_KEY", "读数键已存在: " + readingKey);
            }
            BigDecimal deviation = reading.subtract(batch.targetValue()).abs();
            Long outlierId = null;
            if (deviation.compareTo(batch.tolerance()) > 0) {
                outlierId = outliers.insert(new OutlierRecord(0L, batch.id(), readingId, revision,
                        batch.targetValue(), batch.tolerance(), reading, deviation,
                        OutlierStatus.PENDING, now, null));
            }
            recordStats(batch.id(), revision, "SUBMIT");
            return new ReadingResponse(readingId, readingKey, key, measuredAt,
                    DtoMapper.format(reading), submittedBy, locked.id(), locked.version(), revision,
                    false, outlierId, outlierId == null ? null : DtoMapper.format(deviation), now);
        });
    }
    /**
     * 批次放行。存在未裁决离群值时返回 422 并给出实际与要求数量；
     * 放行写入不可变快照与统计记录，后续测量修订不改写旧快照。
     */
    @Transactional
    public BatchReleaseResponse release(String batchKey, String actor, String requestId) {
        String key = Inputs.requireText(batchKey, "batchKey");
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        String fingerprint = String.join("|", key, releaser);
        return idempotency.execute(requestId, fingerprint, BatchReleaseResponse.class, () -> {
            Instant now = Instant.now(clock);
            Batch batch = batches.findByKeyForUpdate(key)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
            if (batch.status() == BatchStatus.RELEASED) {
                throw ApiException.conflict("ALREADY_RELEASED", "批次已放行: " + key);
            }
            int pending = outliers.countPendingByBatchId(batch.id());
            if (pending > 0) {
                throw ApiException.unprocessable("OUTSTANDING_OUTLIERS",
                        "存在未裁决离群值，放行被阻断: 实际 " + pending + " 条，要求 0 条");
            }
            BatchStatsRecord stats = recordStats(batch.id(), batch.revision(), "RELEASE");
            batches.markReleased(batch.id(), releaser, now);
            statsRepository.insertSnapshot(new BatchReleaseSnapshot(
                    0L, batch.id(), batch.revision(), stats.readingCount(), stats.excludedCount(),
                    stats.meanValue(), releaser, now));
            return new BatchReleaseResponse(key, batch.revision(), releaser, now,
                    stats.readingCount(), stats.excludedCount(),
                    stats.meanValue() == null ? null : DtoMapper.format(stats.meanValue()));
        });
    }

    /**
     * 批次明细：当前状态与最近一次统计结果；不存在返回 404。读取不改变状态。
     */
    @Transactional(readOnly = true)
    public BatchResponse detail(String batchKey) {
        String key = Inputs.requireText(batchKey, "batchKey");
        return toResponse(batches.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + key)));
    }

    /**
     * 批次诊断：明细、全部离群记录及复核、统计重算历史与放行快照。读取不改变状态。
     */
    @Transactional(readOnly = true)
    public DiagnosticsResponse diagnostics(String batchKey) {
        String key = Inputs.requireText(batchKey, "batchKey");
        Batch batch = batches.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
        List<OutlierResponse> outlierResponses = outliers.findByBatchId(batch.id()).stream()
                .map(o -> {
                    BatchReading reading = readings.findById(o.readingId()).orElseThrow();
                    List<ReviewRecordResponse> reviewResponses = reviews.findReviewsByOutlierId(o.id())
                            .stream()
                            .map(r -> new ReviewRecordResponse(r.id(), r.reviewer(), r.decision().name(),
                                    r.reason(), r.batchRevision(), r.certificateVersion(),
                                    r.status().name(), r.createdAt()))
                            .toList();
                    return new OutlierResponse(o.id(), key, o.readingId(), reading.readingKey(),
                            o.batchRevision(), DtoMapper.format(o.targetValue()),
                            DtoMapper.format(o.tolerance()), DtoMapper.format(o.readingValue()),
                            DtoMapper.format(o.deviation()), o.status().name(), o.detectedAt(),
                            o.adjudicatedAt(), reviewResponses);
                })
                .toList();
        List<StatsRecordResponse> statsHistory = statsRepository.findStatsByBatchId(batch.id()).stream()
                .map(s -> new StatsRecordResponse(s.revision(), s.triggerAction(), s.readingCount(),
                        s.excludedCount(), formatNullable(s.meanValue()), formatNullable(s.minValue()),
                        formatNullable(s.maxValue()), s.createdAt()))
                .toList();
        List<ReleaseSnapshotResponse> snapshots = statsRepository.findSnapshotsByBatchId(batch.id()).stream()
                .map(s -> new ReleaseSnapshotResponse(s.revision(), s.readingCount(), s.excludedCount(),
                        formatNullable(s.meanValue()), s.releasedBy(), s.releasedAt()))
                .toList();
        return new DiagnosticsResponse(toResponse(batch), outlierResponses, statsHistory, snapshots);
    }

    /**
     * 重算批次统计并写入不可变统计记录（须在持有批次行锁的事务内调用）。
     * 统计口径为未排除读数；均值为 HALF_UP 12 位小数。
     */
    public BatchStatsRecord recordStats(long batchId, int revision, String triggerAction) {
        List<BatchReading> all = readings.findByBatchId(batchId);
        List<BatchReading> active = all.stream().filter(r -> !r.excluded()).toList();
        BigDecimal mean = null;
        BigDecimal min = null;
        BigDecimal max = null;
        if (!active.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            min = active.get(0).rawReading();
            max = min;
            for (BatchReading r : active) {
                sum = sum.add(r.rawReading());
                if (r.rawReading().compareTo(min) < 0) {
                    min = r.rawReading();
                }
                if (r.rawReading().compareTo(max) > 0) {
                    max = r.rawReading();
                }
            }
            mean = sum.divide(BigDecimal.valueOf(active.size()), 12, RoundingMode.HALF_UP);
        }
        BatchStatsRecord record = new BatchStatsRecord(0L, batchId, revision, triggerAction,
                active.size(), all.size() - active.size(), mean, min, max, Instant.now(clock));
        statsRepository.insertStats(record);
        return record;
    }

    private BatchResponse toResponse(Batch batch) {
        List<BatchStatsRecord> history = statsRepository.findStatsByBatchId(batch.id());
        BatchStatsRecord latest = history.isEmpty() ? null : history.get(history.size() - 1);
        int pending = outliers.countPendingByBatchId(batch.id());
        return new BatchResponse(batch.id(), batch.batchKey(), batch.instrumentId(),
                DtoMapper.format(batch.targetValue()), DtoMapper.format(batch.tolerance()),
                batch.revision(), batch.status().name(),
                latest == null ? 0 : latest.readingCount(),
                latest == null ? 0 : latest.excludedCount(),
                latest == null ? null : formatNullable(latest.meanValue()),
                latest == null ? null : formatNullable(latest.minValue()),
                latest == null ? null : formatNullable(latest.maxValue()),
                pending, batch.releasedBy(), batch.releasedAt(), batch.createdAt());
    }

    private static String formatNullable(BigDecimal value) {
        return value == null ? null : DtoMapper.format(value);
    }
}