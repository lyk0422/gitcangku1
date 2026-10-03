package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.starter.calibration.model.BatchReading;
import com.example.starter.calibration.model.BatchStatsRecord;
import com.example.starter.calibration.model.StatsTrigger;
import com.example.starter.calibration.repo.EvidenceRepository;
import com.example.starter.calibration.repo.ReadingRepository;

/**
 * 批次统计服务：在事务内重算指定修订版本的统计并追加一条不可变统计记录。
 * 统计范围为该修订版本内未排除的读数；均值按 HALF_UP 保留 12 位小数。
 */
@Service
public class StatsService {

    private final ReadingRepository readings;
    private final EvidenceRepository evidence;
    private final Clock clock;

    public StatsService(ReadingRepository readings, EvidenceRepository evidence, Clock clock) {
        this.readings = readings;
        this.evidence = evidence;
        this.clock = clock;
    }

    /**
     * 重算并落库，返回本次统计记录。无纳入读数时 mean/min/max 为 null。
     */
    public BatchStatsRecord recompute(long batchId, int revision, StatsTrigger trigger) {
        List<BatchReading> all = readings.findByBatchAndRevision(batchId, revision);
        List<BatchReading> included = all.stream().filter(r -> !r.excluded()).toList();
        int excludedCount = all.size() - included.size();

        BigDecimal mean = null;
        BigDecimal min = null;
        BigDecimal max = null;
        if (!included.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            min = included.get(0).value();
            max = included.get(0).value();
            for (BatchReading reading : included) {
                sum = sum.add(reading.value());
                if (reading.value().compareTo(min) < 0) {
                    min = reading.value();
                }
                if (reading.value().compareTo(max) > 0) {
                    max = reading.value();
                }
            }
            mean = sum.divide(BigDecimal.valueOf(included.size()), 12, RoundingMode.HALF_UP);
        }

        BatchStatsRecord record = new BatchStatsRecord(
                0L, batchId, revision, included.size(), excludedCount, mean, min, max,
                trigger, Instant.now(clock));
        evidence.insertStats(record);
        return record;
    }
}