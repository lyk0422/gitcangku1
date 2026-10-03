package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.AuditView;
import com.example.starter.calibration.api.dto.BatchDetailResponse;
import com.example.starter.calibration.api.dto.BatchResponse;
import com.example.starter.calibration.api.dto.CreateBatchRequest;
import com.example.starter.calibration.api.dto.OutlierView;
import com.example.starter.calibration.api.dto.ReadingResponse;
import com.example.starter.calibration.api.dto.SubmitReadingRequest;
import com.example.starter.calibration.model.AuditEventType;
import com.example.starter.calibration.model.BatchReading;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.BatchStatsRecord;
import com.example.starter.calibration.model.CalibrationBatch;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.OutlierAudit;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.model.StatsTrigger;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.EvidenceRepository;
import com.example.starter.calibration.repo.OutlierRepository;
import com.example.starter.calibration.repo.ReadingRepository;

/**
 * 测量批次服务：创建批次（目标值+允许偏差）、提交读数（自动离群检测）、明细与审计查询。
 * 批次级操作通过批次行锁按提交顺序串行化；已放行批次新增读数只能创建后续修订版本。
 */
@Service
public class BatchService {

    private final BatchRepository batches;
    private final ReadingRepository readings;
    private final OutlierRepository outliers;
    private final EvidenceRepository evidence;
    private final CertificateRepository certificates;
    private final StatsService stats;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public BatchService(BatchRepository batches, ReadingRepository readings, OutlierRepository outliers,
                        EvidenceRepository evidence, CertificateRepository certificates, StatsService stats,
                        IdempotencyService idempotency, Clock clock) {
        this.batches = batches;
        this.readings = readings;
        this.outliers = outliers;
        this.evidence = evidence;
        this.certificates = certificates;
        this.stats = stats;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 创建测量批次。允许偏差必须非负；batchKey 重复返回 409。
     */
    @Transactional
    public BatchResponse createBatch(CreateBatchRequest request) {
        String batchKey = Inputs.requireText(request.batchKey(), "batchKey");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        BigDecimal target = Inputs.requireDecimal(request.targetValue(), "targetValue");
        BigDecimal deviation = Inputs.requireDecimal(request.allowedDeviation(), "allowedDeviation");
        if (deviation.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest("allowedDeviation 不能为负数");
        }
        String requestId = idempotency.begin(request.requestId(), "CREATE_BATCH",
                String.join("|", batchKey, instrumentId, target.toPlainString(), deviation.toPlainString()));

        CalibrationBatch batch = new CalibrationBatch(0L, batchKey, instrumentId, target, deviation,
                1, BatchStatus.OPEN, Instant.now(clock));
        long id;
        try {
            id = batches.insert(batch);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_BATCH_KEY", "批次键已存在: " + batchKey);
        }
        BatchResponse response = toResponse(batches.findByKey(batchKey).orElseThrow());
        idempotency.complete(requestId, 201, response);
        return response;
    }

    /**
     * 提交读数。按测量时刻匹配唯一有效证书（无匹配 422）；读数与目标值之差的绝对值
     * 超过允许偏差时自动生成 OUTLIER 记录并写入审计；随后重算当前修订版本统计。
     * 已放行批次提交读数时自动创建后续修订版本（revision+1，状态回到 OPEN）。
     */
    @Transactional
    public ReadingResponse submitReading(String batchKey, SubmitReadingRequest request) {
        String readingKey = Inputs.requireText(request.readingKey(), "readingKey");
        Instant measuredAt = Inputs.requireInstant(request.measuredAt(), "measuredAt");
        BigDecimal value = Inputs.requireDecimal(request.value(), "value");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");
        String requestId = idempotency.begin(request.requestId(), "SUBMIT_READING",
                String.join("|", batchKey, readingKey, measuredAt.toString(),
                        value.toPlainString(), submittedBy));

        CalibrationBatch batch = batches.findByKeyForUpdate(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        int revision = batch.revision();
        if (batch.status() == BatchStatus.RELEASED) {
            // 已放行批次发现新增测量事实：只能创建后续修订版本，旧放行快照不改写
            revision = batch.revision() + 1;
            batches.updateRevisionAndStatus(batch.id(), revision, BatchStatus.OPEN);
        }

        Certificate cert = certificates.findMatching(batch.instrumentId(), measuredAt)
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + batch.instrumentId()));

        BatchReading reading = new BatchReading(0L, batch.id(), readingKey, revision, measuredAt,
                value, submittedBy, cert.id(), cert.version(), false, null, Instant.now(clock));
        long readingId;
        try {
            readingId = readings.insert(reading);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_READING_KEY", "读数键已存在: " + readingKey);
        }

        Long outlierId = detectOutlier(batch, reading, readingId, revision);
        stats.recompute(batch.id(), revision, StatsTrigger.READING_SUBMITTED);

        ReadingResponse response = new ReadingResponse(readingId, readingKey, batch.batchKey(), revision,
                measuredAt, DtoMapper.format(value), submittedBy, cert.id(), cert.version(),
                false, outlierId, reading.createdAt());
        idempotency.complete(requestId, 201, response);
        return response;
    }

    /**
     * 离群检测：|读数-目标值| &gt; 允许偏差时生成离群记录（封存目标值、阈值、读数）并写审计。
     *
     * @return 离群记录 ID；未触发为 null
     */
    private Long detectOutlier(CalibrationBatch batch, BatchReading reading, long readingId, int revision) {
        BigDecimal actual = reading.value().subtract(batch.targetValue()).abs();
        if (actual.compareTo(batch.allowedDeviation()) <= 0) {
            return null;
        }
        Instant now = Instant.now(clock);
        OutlierRecord outlier = new OutlierRecord(0L, batch.id(), readingId, revision,
                batch.targetValue(), batch.allowedDeviation(), reading.value(),
                actual, actual.subtract(batch.allowedDeviation()),
                OutlierStatus.PENDING, null, now);
        long outlierId = outliers.insertOutlier(outlier);
        evidence.insertAudit(new OutlierAudit(0L, batch.id(), outlierId, AuditEventType.OUTLIER_DETECTED,
                revision, batch.targetValue(), batch.allowedDeviation(), reading.value(),
                reading.submittedBy(), null,
                "实际偏差 " + actual.toPlainString() + " 超过允许偏差 "
                        + batch.allowedDeviation().toPlainString(),
                now));
        return outlierId;
    }

    /**
     * 批次明细：批次本体、最新统计、全部读数、离群（含复核历史）、统计历史与放行快照。只读。
     */
    @Transactional(readOnly = true)
    public BatchDetailResponse detail(String batchKey) {
        CalibrationBatch batch = batches.findByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<BatchReading> allReadings = readings.findByBatch(batch.id());
        Map<Long, BatchReading> byId = allReadings.stream()
                .collect(Collectors.toMap(BatchReading::id, Function.identity()));
        List<OutlierView> outlierViews = outliers.findOutliersByBatch(batch.id()).stream()
                .map(o -> BatchDtoMapper.toView(o, byId.get(o.readingId()).readingKey(),
                        outliers.findReviewsByOutlier(o.id())))
                .toList();
        BatchStatsRecord latest = evidence.findLatestStats(batch.id()).orElse(null);
        return new BatchDetailResponse(
                batch.batchKey(), batch.instrumentId(),
                DtoMapper.format(batch.targetValue()), DtoMapper.format(batch.allowedDeviation()),
                batch.revision(), batch.status().name(), batch.createdAt(),
                latest == null ? null : BatchDtoMapper.toView(latest),
                allReadings.stream().map(BatchDtoMapper::toView).toList(),
                outlierViews,
                evidence.findStatsByBatch(batch.id()).stream().map(BatchDtoMapper::toView).toList(),
                evidence.findSnapshotsByBatch(batch.id()).stream().map(BatchDtoMapper::toView).toList());
    }

    /**
     * 批次审计历史（按发生顺序）。只读。
     */
    @Transactional(readOnly = true)
    public List<AuditView> audits(String batchKey) {
        CalibrationBatch batch = batches.findByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return evidence.findAuditsByBatch(batch.id()).stream().map(BatchDtoMapper::toView).toList();
    }

    private BatchResponse toResponse(CalibrationBatch batch) {
        return new BatchResponse(batch.id(), batch.batchKey(), batch.instrumentId(),
                DtoMapper.format(batch.targetValue()), DtoMapper.format(batch.allowedDeviation()),
                batch.revision(), batch.status().name(), batch.createdAt());
    }
}