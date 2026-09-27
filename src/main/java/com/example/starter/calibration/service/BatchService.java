package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.BatchHistoryResponse;
import com.example.starter.calibration.api.dto.BatchResponse;
import com.example.starter.calibration.api.dto.CreateBatchRequest;
import com.example.starter.calibration.api.dto.OutlierResponse;
import com.example.starter.calibration.api.dto.ReviseBatchRequest;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.MeasurementBatch;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierVerdict;
import com.example.starter.calibration.repo.BatchReleaseRepository;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.OutlierRepository;
import com.example.starter.calibration.repo.ReadingRepository;
import com.example.starter.calibration.repo.ReviewRepository;
import com.example.starter.calibration.repo.StatsRepository;
import com.example.starter.calibration.repo.VerdictRepository;

/**
 * 测量批次服务：创建（目标值+允许偏差）、明细、测量修订、历史与诊断查询。
 * 合格区间按左闭右开解释：[目标值-允许偏差, 目标值+允许偏差)。
 */
@Service
public class BatchService {

    private final BatchRepository batches;
    private final ReadingRepository readings;
    private final OutlierRepository outliers;
    private final ReviewRepository reviews;
    private final VerdictRepository verdicts;
    private final StatsRepository stats;
    private final BatchReleaseRepository releases;

    public BatchService(BatchRepository batches, ReadingRepository readings,
                        OutlierRepository outliers, ReviewRepository reviews,
                        VerdictRepository verdicts, StatsRepository stats,
                        BatchReleaseRepository releases) {
        this.batches = batches;
        this.readings = readings;
        this.outliers = outliers;
        this.reviews = reviews;
        this.verdicts = verdicts;
        this.stats = stats;
        this.releases = releases;
    }

    /**
     * 创建批次。允许偏差必须 >= 0；batchKey 重复返回 409。
     */
    @Transactional
    public BatchResponse create(CreateBatchRequest request) {
        String batchKey = Inputs.requireText(request.batchKey(), "batchKey");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        BigDecimal target = Inputs.requireDecimal(request.targetValue(), "targetValue");
        BigDecimal deviation = requireDeviation(request.allowedDeviation());

        MeasurementBatch batch = new MeasurementBatch(0L, batchKey, instrumentId, target, deviation,
                1, BatchStatus.OPEN, Instant.now());
        try {
            batches.insert(batch);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_BATCH_KEY", "批次键已存在: " + batchKey);
        }
        return toDetail(batches.findByKey(batchKey).orElseThrow());
    }

    /**
     * 批次明细：当前修订版本配置、最新统计与待裁决离群数；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public BatchResponse detail(String batchKey) {
        MeasurementBatch batch = batches.findByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return toDetail(batch);
    }

    /**
     * 测量修订：目标值与允许偏差更新、修订版本号 +1、状态回到 OPEN。
     * 已放行批次的放行快照保持不变，新增离群事实只能进入后续修订版本。
     */
    @Transactional
    public BatchResponse revise(String batchKey, ReviseBatchRequest request) {
        BigDecimal target = Inputs.requireDecimal(request.targetValue(), "targetValue");
        BigDecimal deviation = requireDeviation(request.allowedDeviation());
        MeasurementBatch batch = batches.findByKeyForUpdate(Inputs.requireText(batchKey, "batchKey"))
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        batches.applyRevision(batch.id(), target, deviation, batch.revision() + 1);
        return toDetail(batches.findById(batch.id()).orElseThrow());
    }

    /**
     * 当前修订版本的离群记录列表（按检测顺序）；不存在返回 404。只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public List<OutlierResponse> outliersOf(String batchKey) {
        MeasurementBatch batch = batches.findByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return outliers.findByBatchAndRevision(batch.id(), batch.revision()).stream()
                .map(o -> toOutlierResponse(batch, o))
                .toList();
    }

    /**
     * 历史与诊断：全部修订版本的离群记录（含复核与裁决）、统计重算历史与放行快照。
     * 只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public BatchHistoryResponse history(String batchKey) {
        MeasurementBatch batch = batches.findByKey(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<OutlierResponse> allOutliers = new java.util.ArrayList<>();
        for (int revision = 1; revision <= batch.revision(); revision++) {
            for (OutlierRecord outlier : outliers.findByBatchAndRevision(batch.id(), revision)) {
                allOutliers.add(toOutlierResponse(batch, outlier));
            }
        }
        return new BatchHistoryResponse(
                batch.batchKey(),
                batch.revision(),
                batch.status().name(),
                allOutliers,
                stats.findByBatchId(batch.id()).stream().map(BatchDtoMapper::toResponse).toList(),
                releases.findByBatchId(batch.id()).stream()
                        .map(r -> BatchDtoMapper.toResponse(batch, r)).toList());
    }

    /**
     * 离群记录明细：封存证据、裁决与全部复核（含留痕的失效复核）；不存在返回 404。
     * 只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public OutlierResponse outlierDetail(long outlierId) {
        OutlierRecord outlier = outliers.findById(outlierId)
                .orElseThrow(() -> ApiException.notFound("离群记录不存在: " + outlierId));
        MeasurementBatch batch = batches.findById(outlier.batchId())
                .orElseThrow(() -> ApiException.notFound("离群记录所属批次不存在: " + outlier.batchId()));
        return toOutlierResponse(batch, outlier);
    }

    private BatchResponse toDetail(MeasurementBatch batch) {
        BatchStats.Values current = BatchStats.compute(
                readings.findByBatchAndRevision(batch.id(), batch.revision()));
        int pending = outliers.findPendingByBatchAndRevision(batch.id(), batch.revision()).size();
        return BatchDtoMapper.toResponse(batch, current, pending);
    }

    private OutlierResponse toOutlierResponse(MeasurementBatch batch, OutlierRecord outlier) {
        OutlierVerdict verdict = verdicts.findByOutlierId(outlier.id()).orElse(null);
        return BatchDtoMapper.toResponse(outlier, batch.batchKey(), verdict,
                reviews.findByOutlierId(outlier.id()));
    }

    static BigDecimal requireDeviation(String value) {
        BigDecimal deviation = Inputs.requireDecimal(value, "allowedDeviation");
        if (deviation.compareTo(BigDecimal.ZERO) < 0) {
            throw ApiException.badRequest("allowedDeviation 不能为负数");
        }
        return deviation;
    }
}
