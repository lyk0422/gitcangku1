package com.example.starter.calibration.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.OutliersPendingException;
import com.example.starter.calibration.api.dto.BatchReleaseResponse;
import com.example.starter.calibration.model.BatchReleaseSnapshot;
import com.example.starter.calibration.model.BatchStatsRecord;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.CalibrationBatch;
import com.example.starter.calibration.model.StatsTrigger;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.EvidenceRepository;
import com.example.starter.calibration.repo.OutlierRepository;

/**
 * 批次放行服务：存在未裁决离群记录时阻断放行（422 并返回实际/要求/差额）；
 * 放行在批次行锁内读取一致的离群与复核集合，封存不可变放行快照。
 */
@Service
public class BatchReleaseService {

    private final BatchRepository batches;
    private final OutlierRepository outliers;
    private final EvidenceRepository evidence;
    private final StatsService stats;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public BatchReleaseService(BatchRepository batches, OutlierRepository outliers,
                               EvidenceRepository evidence, StatsService stats,
                               IdempotencyService idempotency, Clock clock) {
        this.batches = batches;
        this.outliers = outliers;
        this.evidence = evidence;
        this.stats = stats;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 放行批次当前修订版本。批次不存在 404；已放行 409；存在未裁决离群 422。
     * 成功时写入放行快照并将批次置为 RELEASED，全部对象校验通过后统一事务提交。
     */
    @Transactional
    public BatchReleaseResponse release(String batchKey, String actor, String requestIdRaw) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        String requestId = idempotency.begin(requestIdRaw, "RELEASE_BATCH", batchKey + "|" + releaser);

        CalibrationBatch batch = batches.findByKeyForUpdate(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        if (batch.status() == BatchStatus.RELEASED) {
            throw ApiException.conflict("BATCH_ALREADY_RELEASED", "批次当前修订已放行: " + batchKey);
        }
        // 行锁内读取一致的离群集合：与复核、读数提交按提交顺序互斥
        int pending = outliers.countPendingByBatch(batch.id());
        if (pending > 0) {
            throw new OutliersPendingException(pending);
        }

        BatchStatsRecord current = stats.recompute(batch.id(), batch.revision(),
                StatsTrigger.RELEASE);
        Instant releasedAt = Instant.now(clock);
        evidence.insertSnapshot(new BatchReleaseSnapshot(0L, batch.id(), batch.revision(),
                current.includedCount(), current.excludedCount(),
                current.meanValue(), current.minValue(), current.maxValue(),
                releaser, releasedAt));
        batches.updateStatus(batch.id(), BatchStatus.RELEASED);

        BatchReleaseResponse response = new BatchReleaseResponse(batch.batchKey(), batch.revision(),
                releaser, releasedAt, current.includedCount(), current.excludedCount(),
                BatchDtoMapper.format(current.meanValue()), BatchDtoMapper.format(current.minValue()),
                BatchDtoMapper.format(current.maxValue()));
        idempotency.complete(requestId, 200, response);
        return response;
    }
}