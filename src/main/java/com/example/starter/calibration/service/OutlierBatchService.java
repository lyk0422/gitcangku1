package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.OutlierBlockedException;
import com.example.starter.calibration.api.dto.BatchHistoryResponse;
import com.example.starter.calibration.api.dto.BatchReleaseResponse;
import com.example.starter.calibration.api.dto.CreateOutlierBatchRequest;
import com.example.starter.calibration.api.dto.OutlierBatchResponse;
import com.example.starter.calibration.api.dto.ReadingResponse;
import com.example.starter.calibration.api.dto.ReleaseBatchRequest;
import com.example.starter.calibration.api.dto.SubmitReadingRequest;
import com.example.starter.calibration.model.BatchReading;
import com.example.starter.calibration.model.BatchReleaseSnapshot;
import com.example.starter.calibration.model.BatchStats;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.MeasurementBatch;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.repo.BatchAuditRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.OutlierBatchRepository;
import com.example.starter.calibration.repo.OutlierRepository;

/**
 * 测量批次服务：创建（绑定证书版本）、读数提交（离群检测）、放行门禁、明细与历史查询。
 * 批次内写操作通过批次行锁串行化，按提交顺序裁决；查询只读不改写状态。
 */
@Service
public class OutlierBatchService {

    private final OutlierBatchRepository batches;
    private final OutlierRepository outliers;
    private final CertificateRepository certificates;
    private final BatchAuditRepository audit;
    private final BatchStatsService stats;
    private final IdempotencyService idempotency;

    public OutlierBatchService(OutlierBatchRepository batches,
                               OutlierRepository outliers,
                               CertificateRepository certificates,
                               BatchAuditRepository audit,
                               BatchStatsService stats,
                               IdempotencyService idempotency) {
        this.batches = batches;
        this.outliers = outliers;
        this.certificates = certificates;
        this.audit = audit;
        this.stats = stats;
        this.idempotency = idempotency;
    }

    /**
     * 创建批次：201；参数非法 400；证书不存在 404；证书已撤销或批次键重复 409。
     */
    public IdempotencyService.StoredResponse createBatch(CreateOutlierBatchRequest request) {
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        return idempotency.execute("OUTLIER_BATCH_CREATE", requestId, request,
                () -> doCreateBatch(request));
    }

    /**
     * 提交读数：201；批次不存在 404；读数键重复或证书已撤销 409。
     * 已放行批次新增读数时创建后续修订版本（revision+1 并回到 OPEN），旧放行快照不改写。
     */
    public IdempotencyService.StoredResponse submitReading(String batchKey, SubmitReadingRequest request) {
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        return idempotency.execute("READING_SUBMIT", requestId, List.of(batchKey, request),
                () -> doSubmitReading(batchKey, request));
    }

    /**
     * 放行批次：200；批次不存在 404；重复放行或证书已撤销 409；存在未裁决离群值 422。
     */
    public IdempotencyService.StoredResponse releaseBatch(String batchKey, ReleaseBatchRequest request,
                                                          String actor) {
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        return idempotency.execute("BATCH_RELEASE", requestId, List.of(batchKey, actor, request),
                () -> doReleaseBatch(batchKey, actor));
    }

    /**
     * 批次明细：当前统计实际值；不存在 404。只读。
     */
    @Transactional(readOnly = true)
    public OutlierBatchResponse detail(String batchKey) {
        MeasurementBatch batch = batches.findBatchByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return toResponse(batch);
    }

    /**
     * 批次历史：统计重算与放行快照，按发生顺序升序；不存在 404。只读。
     */
    @Transactional(readOnly = true)
    public BatchHistoryResponse history(String batchKey) {
        MeasurementBatch batch = batches.findBatchByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<BatchHistoryResponse.StatsItem> statsItems = stats.history(batch.id()).stream()
                .map(s -> new BatchHistoryResponse.StatsItem(s.id(), s.revision(), s.reason(),
                        s.readingCount(), s.includedCount(), s.excludedCount(),
                        formatOrNull(s.meanValue()), formatOrNull(s.minValue()), formatOrNull(s.maxValue()),
                        s.createdAt()))
                .toList();
        List<BatchHistoryResponse.SnapshotItem> snapshots = audit.findSnapshotsByBatch(batch.id()).stream()
                .map(s -> new BatchHistoryResponse.SnapshotItem(s.id(), s.revision(), s.certificateId(),
                        s.releasedBy(), s.includedCount(), formatOrNull(s.meanValue()), s.releasedAt()))
                .toList();
        return new BatchHistoryResponse(batch.batchKey(), statsItems, snapshots);
    }

    private IdempotencyService.StoredResponse doCreateBatch(CreateOutlierBatchRequest request) {
        String batchKey = Inputs.requireText(request.batchKey(), "batchKey");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        if (request.certificateId() == null) {
            throw ApiException.badRequest("certificateId 不能为空");
        }
        BigDecimal target = Inputs.requireDecimal(request.targetValue(), "targetValue");
        BigDecimal deviation = Inputs.requireDecimal(request.allowedDeviation(), "allowedDeviation");
        if (deviation.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest("allowedDeviation 不能为负");
        }

        Certificate cert = certificates.findById(request.certificateId())
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + request.certificateId()));
        if (cert.revoked()) {
            throw ApiException.conflict("CERTIFICATE_REVOKED", "证书已撤销: " + cert.id());
        }
        if (!cert.instrumentId().equals(instrumentId)) {
            throw ApiException.badRequest("instrumentId 与证书仪器不一致: " + cert.instrumentId());
        }

        MeasurementBatch batch = new MeasurementBatch(0L, batchKey, instrumentId, cert.id(),
                target, deviation, 1, BatchStatus.OPEN, Instant.now());
        try {
            batches.insertBatch(batch);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_BATCH_KEY", "批次键已存在: " + batchKey);
        }
        MeasurementBatch saved = batches.findBatchByKey(batchKey).orElseThrow();
        stats.recomputeAndRecord(saved, BatchStatsService.REASON_CREATE);
        return new IdempotencyService.StoredResponse(201, idempotency.toJson(toResponse(saved)));
    }

    private IdempotencyService.StoredResponse doSubmitReading(String batchKey, SubmitReadingRequest request) {
        String readingKey = Inputs.requireText(request.readingKey(), "readingKey");
        BigDecimal value = Inputs.requireDecimal(request.value(), "value");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");

        MeasurementBatch batch = batches.findBatchByKeyForUpdate(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        long boundCertificateId = batch.certificateId();
        Certificate cert = certificates.findByIdForUpdate(boundCertificateId)
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                        "批次绑定的证书不存在: " + boundCertificateId));
        if (cert.revoked()) {
            throw ApiException.conflict("CERTIFICATE_REVOKED", "批次绑定证书已撤销: " + cert.id());
        }
        if (batch.status() == BatchStatus.RELEASED) {
            // 已放行批次新增离群事实只能创建后续修订版本，旧放行快照不改写
            batches.bumpRevision(batch.id(), batch.revision() + 1);
            batch = batches.findBatchByKeyForUpdate(batchKey).orElseThrow();
        }

        BatchReading reading = new BatchReading(0L, batch.id(), readingKey, value, submittedBy,
                batch.revision(), false, null, Instant.now());
        long readingId;
        try {
            readingId = batches.insertReading(reading);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_READING_KEY", "读数键已存在: " + readingKey);
        }
        BatchReading saved = batches.findReadingById(readingId).orElseThrow();

        BigDecimal deviation = value.subtract(batch.targetValue()).abs();
        Long outlierId = null;
        if (deviation.compareTo(batch.allowedDeviation()) > 0) {
            OutlierRecord outlier = new OutlierRecord(0L, batch.id(), saved.id(), batch.revision(),
                    batch.certificateId(), batch.targetValue(), batch.allowedDeviation(), value,
                    deviation, submittedBy, OutlierStatus.PENDING, null, null, Instant.now());
            outlierId = outliers.insertOutlier(outlier);
        }
        stats.recomputeAndRecord(batch, BatchStatsService.REASON_SUBMIT);

        ReadingResponse response = new ReadingResponse(saved.id(), saved.readingKey(), batch.batchKey(),
                saved.batchRevision(), DtoMapper.format(value), DtoMapper.format(batch.targetValue()),
                DtoMapper.format(batch.allowedDeviation()), DtoMapper.format(deviation),
                outlierId != null, outlierId, saved.excluded(), saved.submittedBy(), saved.createdAt());
        return new IdempotencyService.StoredResponse(201, idempotency.toJson(response));
    }

    private IdempotencyService.StoredResponse doReleaseBatch(String batchKey, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        MeasurementBatch batch = batches.findBatchByKeyForUpdate(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        if (batch.status() == BatchStatus.RELEASED) {
            throw ApiException.conflict("ALREADY_RELEASED", "批次已放行: " + batchKey);
        }
        Certificate cert = certificates.findByIdForUpdate(batch.certificateId())
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                        "批次绑定的证书不存在: " + batch.certificateId()));
        if (cert.revoked()) {
            throw ApiException.conflict("CERTIFICATE_REVOKED", "批次绑定证书已撤销: " + cert.id());
        }
        int pending = outliers.countPendingByBatch(batch.id());
        if (pending > 0) {
            throw new OutlierBlockedException(pending);
        }

        BatchStats current = stats.compute(batch, "RELEASE");
        Instant releasedAt = Instant.now();
        batches.markReleased(batch.id());
        audit.insertSnapshot(new BatchReleaseSnapshot(0L, batch.id(), batch.revision(),
                batch.certificateId(), releaser, current.includedCount(), current.meanValue(), releasedAt));

        BatchReleaseResponse response = new BatchReleaseResponse(batch.batchKey(), batch.revision(),
                BatchStatus.RELEASED.name(), batch.certificateId(), releaser,
                current.includedCount(), current.excludedCount(), formatOrNull(current.meanValue()),
                releasedAt);
        return new IdempotencyService.StoredResponse(200, idempotency.toJson(response));
    }

    private OutlierBatchResponse toResponse(MeasurementBatch batch) {
        boolean certRevoked = certificates.findById(batch.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        BatchStats current = stats.compute(batch, "QUERY");
        int pending = outliers.countPendingByBatch(batch.id());
        return new OutlierBatchResponse(batch.id(), batch.batchKey(), batch.instrumentId(),
                batch.certificateId(), certRevoked, DtoMapper.format(batch.targetValue()),
                DtoMapper.format(batch.allowedDeviation()), batch.revision(), batch.status().name(),
                current.readingCount(), current.includedCount(), current.excludedCount(), pending,
                formatOrNull(current.meanValue()), formatOrNull(current.minValue()),
                formatOrNull(current.maxValue()), batch.createdAt());
    }

    private static String formatOrNull(BigDecimal value) {
        return value == null ? null : DtoMapper.format(value);
    }
}
