package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.starter.calibration.model.BatchReading;
import com.example.starter.calibration.model.BatchStats;
import com.example.starter.calibration.model.MeasurementBatch;
import com.example.starter.calibration.repo.BatchAuditRepository;
import com.example.starter.calibration.repo.OutlierBatchRepository;

/**
 * 批次统计服务：按未排除读数重算条数、均值与最值，并追加不可变重算历史。
 * 均值按 HALF_UP 保留 12 位小数；无计入读数时均值/最值为 null。
 */
@Service
public class BatchStatsService {

    /** 重算原因：批次创建。 */
    public static final String REASON_CREATE = "CREATE";
    /** 重算原因：读数提交。 */
    public static final String REASON_SUBMIT = "SUBMIT";
    /** 重算原因：读数排除。 */
    public static final String REASON_EXCLUDE = "EXCLUDE";

    private final OutlierBatchRepository batches;
    private final BatchAuditRepository audit;

    public BatchStatsService(OutlierBatchRepository batches, BatchAuditRepository audit) {
        this.batches = batches;
        this.audit = audit;
    }

    /**
     * 计算批次当前统计（不写入）。
     */
    public BatchStats compute(MeasurementBatch batch, String reason) {
        List<BatchReading> readings = batches.findReadingsByBatch(batch.id());
        List<BigDecimal> included = readings.stream()
                .filter(r -> !r.excluded())
                .map(BatchReading::readingValue)
                .toList();
        BigDecimal mean = null;
        BigDecimal min = null;
        BigDecimal max = null;
        if (!included.isEmpty()) {
            BigDecimal sum = included.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            mean = sum.divide(BigDecimal.valueOf(included.size()), 12, RoundingMode.HALF_UP);
            min = included.stream().min(BigDecimal::compareTo).orElseThrow();
            max = included.stream().max(BigDecimal::compareTo).orElseThrow();
        }
        return new BatchStats(0L, batch.id(), batch.revision(), reason,
                readings.size(), included.size(), readings.size() - included.size(),
                mean, min, max, Instant.now());
    }

    /**
     * 重算并追加不可变统计历史（须在持有批次行锁的事务内调用）。
     */
    public BatchStats recomputeAndRecord(MeasurementBatch batch, String reason) {
        BatchStats stats = compute(batch, reason);
        audit.insertStats(stats);
        return stats;
    }

    /**
     * 查询批次统计重算历史（按重算顺序升序）。
     */
    public List<BatchStats> history(long batchId) {
        return audit.findStatsByBatch(batchId);
    }
}
