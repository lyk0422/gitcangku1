package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.ReadingResponse;
import com.example.starter.calibration.api.dto.SubmitReadingRequest;
import com.example.starter.calibration.model.BatchReading;
import com.example.starter.calibration.model.BatchStatsRecord;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.MeasurementBatch;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.OutlierRepository;
import com.example.starter.calibration.repo.ReadingRepository;
import com.example.starter.calibration.repo.StatsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 批次读数服务：提交读数（幂等）、自动离群检测并阻断放行、统计重算留痕。
 * 离群判定：校准计算值落在 [目标值-允许偏差, 目标值+允许偏差) 之外（左闭右开）。
 */
@Service
public class ReadingService {

    /** 幂等作用域：读数提交。 */
    static final String SCOPE = "reading";

    private final BatchRepository batches;
    private final ReadingRepository readings;
    private final OutlierRepository outliers;
    private final StatsRepository stats;
    private final CertificateRepository certificates;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public ReadingService(BatchRepository batches, ReadingRepository readings,
                          OutlierRepository outliers, StatsRepository stats,
                          CertificateRepository certificates, IdempotencyService idempotency,
                          ObjectMapper objectMapper) {
        this.batches = batches;
        this.readings = readings;
        this.outliers = outliers;
        this.stats = stats;
        this.certificates = certificates;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交读数。requestId 同参重放返回首次结果，异参 409，失败不占键。
     * 已放行批次拒绝新读数（409），新增离群事实只能进入后续修订版本。
     */
    @Transactional
    public IdempotentResponse submit(String batchKey, SubmitReadingRequest request) {
        String key = Inputs.requireText(batchKey, "batchKey");
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        Instant measuredAt = Inputs.requireInstant(request.measuredAt(), "measuredAt");
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");
        String fingerprint = String.join("|", measuredAt.toString(),
                reading.stripTrailingZeros().toPlainString(), submittedBy);

        MeasurementBatch batch = batches.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));

        var replay = idempotency.replay(SCOPE, requestId, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }

        if (batch.status() == BatchStatus.RELEASED) {
            throw ApiException.conflict("BATCH_RELEASED",
                    "批次当前修订已放行，新增事实须创建后续修订版本: " + key);
        }

        Certificate cert = certificates.findMatching(batch.instrumentId(), measuredAt)
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + batch.instrumentId()));

        BigDecimal computed = cert.a().multiply(reading).add(cert.b());
        BigDecimal lower = batch.targetValue().subtract(batch.allowedDeviation());
        BigDecimal upper = batch.targetValue().add(batch.allowedDeviation());
        boolean isOutlier = computed.compareTo(lower) < 0 || computed.compareTo(upper) >= 0;

        Instant now = Instant.now();
        BatchReading entity = new BatchReading(0L, batch.id(), batch.revision(), requestId,
                measuredAt, reading, submittedBy, cert.id(), computed, isOutlier, false, now);
        long readingId = readings.insert(entity);

        Long outlierId = null;
        if (isOutlier) {
            OutlierRecord outlier = new OutlierRecord(0L, batch.id(), batch.revision(), readingId,
                    batch.targetValue(), batch.allowedDeviation(), computed,
                    computed.subtract(batch.targetValue()).abs(),
                    OutlierStatus.PENDING, now, null);
            outlierId = outliers.insert(outlier);
        }

        BatchStats.Values current = BatchStats.compute(
                readings.findByBatchAndRevision(batch.id(), batch.revision()));
        stats.insert(new BatchStatsRecord(0L, batch.id(),
                batch.revision(), "READING_SUBMIT", current.includedCount(), current.excludedCount(),
                current.mean(), current.min(), current.max(), now));

        ReadingResponse response = new ReadingResponse(readingId, batch.batchKey(), batch.revision(),
                requestId, measuredAt, DtoMapper.format(reading), submittedBy, cert.id(),
                DtoMapper.format(computed), isOutlier, outlierId, false, now);
        return idempotency.record(SCOPE, requestId, fingerprint, 201, writeJson(response));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }
}
