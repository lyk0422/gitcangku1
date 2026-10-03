package com.example.gitcangku1.service;

import com.example.gitcangku1.domain.Adjudication;
import com.example.gitcangku1.domain.BatchStatus;
import com.example.gitcangku1.domain.DyeBatch;
import com.example.gitcangku1.domain.MeasureType;
import com.example.gitcangku1.domain.Measurement;
import com.example.gitcangku1.repo.AdjudicationRepository;
import com.example.gitcangku1.repo.BatchRepository;
import com.example.gitcangku1.repo.MeasurementRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 批次创建、目标值变更、测色录入与复检裁决。 */
@Service
public class BatchService {

    public static final int MAX_ROLLS = 30;

    private final BatchRepository batches;
    private final MeasurementRepository measurements;
    private final AdjudicationRepository adjudications;
    private final Clock clock;

    public BatchService(BatchRepository batches, MeasurementRepository measurements,
            AdjudicationRepository adjudications, Clock clock) {
        this.batches = batches;
        this.measurements = measurements;
        this.adjudications = adjudications;
        this.clock = clock;
    }

    @Transactional
    public DyeBatch createBatch(String batchNo, double l, double a, double b, int expectedRolls) {
        if (expectedRolls < 1 || expectedRolls > MAX_ROLLS) {
            throw ApiException.badRequest("计划卷数必须在1-" + MAX_ROLLS + "之间");
        }
        try {
            return batches.insert(batchNo, l, a, b, expectedRolls, Instant.now(clock));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("批次号已存在: " + batchNo);
        }
    }

    @Transactional
    public DyeBatch updateTarget(long batchId, double l, double a, double b) {
        DyeBatch batch = lockBatch(batchId);
        requireOpen(batch);
        batches.updateTarget(batchId, l, a, b, batch.targetVersion() + 1);
        return batches.findById(batchId).orElseThrow();
    }

    /**
     * 测色录入，幂等：requestId 同参重放返回原记录，异参抛409。
     * 通过批次行锁与唯一约束保证并发安全。
     */
    @Transactional
    public Measurement recordMeasurement(long batchId, String requestId, int rollNo,
            MeasureType type, double l, double a, double b, Instant measuredAt) {
        DyeBatch batch = lockBatch(batchId);
        String hash = hash("measure", batchId, requestId, rollNo, type, l, a, b, measuredAt);
        var replay = measurements.findByRequestId(requestId);
        if (replay.isPresent()) {
            if (replay.get().requestHash().equals(hash)) {
                return replay.get();
            }
            throw ApiException.conflict("requestId 已存在但参数不同: " + requestId);
        }
        requireOpen(batch);
        if (rollNo < 1 || rollNo > batch.expectedRolls()) {
            throw ApiException.badRequest("卷号超出范围: " + rollNo);
        }
        var m = new Measurement(0, batchId, rollNo, type, l, a, b, measuredAt,
                batch.targetVersion(), requestId, hash);
        try {
            return measurements.insert(m, Instant.now(clock));
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 竞态：唯一约束兜底，重读后按同参重放/异参409处理
            var raced = measurements.findByRequestId(requestId).orElseThrow(() -> e);
            if (raced.requestHash().equals(hash)) {
                return raced;
            }
            throw ApiException.conflict("requestId 已存在但参数不同: " + requestId);
        }
    }

    /** 复检裁决：每卷至多一次，须已存在复检记录；与放行共用批次行锁保证一致读取。 */
    @Transactional
    public Adjudication adjudicate(long batchId, int rollNo, MeasureType decision, String inspector) {
        DyeBatch batch = lockBatch(batchId);
        requireOpen(batch);
        measurements.find(batchId, rollNo, MeasureType.RECHECK)
                .orElseThrow(() -> ApiException.badRequest("卷" + rollNo + "尚无复检记录，无法裁决"));
        if (adjudications.find(batchId, rollNo).isPresent()) {
            throw ApiException.conflict("卷" + rollNo + "已裁决，不可重复裁决");
        }
        return adjudications.insert(batchId, rollNo, decision, inspector, Instant.now(clock));
    }

    DyeBatch lockBatch(long batchId) {
        return batches.findByIdForUpdate(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
    }

    static void requireOpen(DyeBatch batch) {
        if (batch.status() != BatchStatus.OPEN) {
            throw ApiException.conflict("批次已放行冻结，禁止变更: " + batch.batchNo());
        }
    }

    static String hash(Object... parts) {
        var sb = new StringBuilder();
        for (Object part : parts) {
            sb.append(part).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(digest.length * 2);
            for (byte v : digest) {
                hex.append(Character.forDigit((v >> 4) & 0xF, 16));
                hex.append(Character.forDigit(v & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}