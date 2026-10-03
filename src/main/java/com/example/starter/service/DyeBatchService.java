package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BatchView;
import com.example.starter.api.dto.MeasurementResponse;
import com.example.starter.api.dto.ReleaseResponse;
import com.example.starter.repo.DyeBatchRepository;
import com.example.starter.repo.DyeBatchRepository.BatchRow;
import com.example.starter.repo.DyeBatchRepository.ErrataRow;
import com.example.starter.repo.DyeBatchRepository.MeasurementRow;
import com.example.starter.repo.DyeBatchRepository.ReleaseLineRow;
import com.example.starter.repo.DyeBatchRepository.ReleaseRow;
import com.example.starter.repo.DyeBatchRepository.RollRow;
import com.example.starter.repo.DyeBatchRepository.RulingRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 染色批次整批放行核心业务：测色、复检裁决、整批放行冻结与勘误。
 * 放行与裁决通过对批次行 SELECT ... FOR UPDATE 串行化，保证读取完整一致的采纳集合。
 */
@Service
public class DyeBatchService {

    /** 每批最多布卷数。 */
    public static final int MAX_ROLLS = 30;

    private static final String OPEN = "OPEN";

    private final DyeBatchRepository repo;
    private final Clock clock;

    public DyeBatchService(DyeBatchRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** 创建批次，目标版本自 1 起。 */
    @Transactional
    public BatchRow createBatch(String batchNo, double targetL, double targetA, double targetB) {
        long id = repo.insertBatch(batchNo, targetL, targetA, targetB, clock.instant());
        return repo.findBatch(id).orElseThrow();
    }

    /** 修改目标值并递增版本；已放行批次拒绝修改。 */
    @Transactional
    public BatchRow updateTarget(long batchId, double targetL, double targetA, double targetB) {
        BatchRow batch = lockOpenBatch(batchId);
        repo.updateBatchTarget(batchId, targetL, targetA, targetB, batch.targetVersion() + 1);
        return repo.findBatch(batchId).orElseThrow();
    }

    /** 添加布卷，每批最多 30 卷；已放行批次拒绝。 */
    @Transactional
    public RollRow addRoll(long batchId, String rollNo) {
        lockOpenBatch(batchId);
        if (repo.countRolls(batchId) >= MAX_ROLLS) {
            throw ApiException.conflict("每批最多 " + MAX_ROLLS + " 卷");
        }
        long id = repo.insertRoll(batchId, rollNo, clock.instant());
        return repo.findRoll(id).orElseThrow();
    }

    /** 上报测色：requestId 同参重放、异参 409；按当前目标版本计算色差；历史记录全量保留。 */
    @Transactional
    public MeasurementResponse recordMeasurement(long rollId, String kind, String requestId,
            double l, double a, double b, Instant measuredAt) {
        String hash = sha256(kind + "|" + l + "|" + a + "|" + b + "|" + measuredAt);
        var replay = repo.findMeasurementByRequestId(requestId);
        if (replay.isPresent()) {
            MeasurementRow existing = replay.get();
            if (!existing.requestHash().equals(hash)) {
                throw ApiException.conflict("requestId 已存在但参数不同");
            }
            return toMeasurementResponse(existing, true);
        }
        RollRow roll = repo.findRoll(rollId)
                .orElseThrow(() -> ApiException.notFound("布卷不存在: " + rollId));
        BatchRow batch = lockOpenBatch(roll.batchId());
        double deltaE = ColorDifference.deltaE(l, a, b, batch.targetL(), batch.targetA(), batch.targetB());
        boolean qualified = ColorDifference.qualified(deltaE);
        try {
            long id = repo.insertMeasurement(rollId, kind, l, a, b, measuredAt,
                    batch.targetVersion(), deltaE, qualified, requestId, hash, clock.instant());
            return toMeasurementResponse(repo.findMeasurementByRequestId(requestId).orElseThrow(), false);
        } catch (DuplicateKeyException e) {
            // 并发同 requestId：重读并校验参数一致性
            MeasurementRow existing = repo.findMeasurementByRequestId(requestId).orElseThrow();
            if (!existing.requestHash().equals(hash)) {
                throw ApiException.conflict("requestId 已存在但参数不同");
            }
            return toMeasurementResponse(existing, true);
        }
    }

    /** 复检裁决：质检员明确采纳原测或复检；与放行串行化；裁决绑定当前最新复检记录。 */
    @Transactional
    public RulingRow ruleRecheck(long rollId, String decision, String inspector) {
        RollRow roll = repo.findRoll(rollId)
                .orElseThrow(() -> ApiException.notFound("布卷不存在: " + rollId));
        lockOpenBatch(roll.batchId());
        MeasurementRow recheck = repo.findLatestMeasurement(rollId, "RECHECK")
                .orElseThrow(() -> ApiException.conflict("该卷无复检记录，无需裁决"));
        repo.upsertRuling(rollId, recheck.id(), decision, inspector, clock.instant());
        return repo.findRuling(rollId).orElseThrow();
    }

    private BatchRow lockOpenBatch(long batchId) {
        BatchRow batch = repo.findBatchForUpdate(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        if (!OPEN.equals(batch.status())) {
            throw ApiException.conflict("批次已放行冻结，测色、裁决与目标值不可再修改，只能追加勘误");
        }
        return batch;
    }

    private static MeasurementResponse toMeasurementResponse(MeasurementRow row, boolean replayed) {
        return new MeasurementResponse(row.id(), row.rollId(), row.kind(), row.deltaE(),
                row.qualified(), row.targetVersion(), replayed);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    /**
     * 整批放行：缺卷、未裁决复检、任一卷超差或目标版本过旧均整批拒绝并返回卷号；
     * 成功则冻结每卷采纳记录与色差；requestId 幂等；并发最多一张放行单；失败事务不占键。
     */
    @Transactional
    public ReleaseResponse release(long batchId, String requestId) {
        String hash = sha256("release|" + batchId);
        var replay = repo.findReleaseByRequestId(requestId);
        if (replay.isPresent()) {
            ReleaseRow existing = replay.get();
            if (!existing.requestHash().equals(hash)) {
                throw ApiException.conflict("requestId 已存在但参数不同");
            }
            return toReleaseResponse(existing, true);
        }
        BatchRow batch = repo.findBatchForUpdate(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        var existing = repo.findReleaseByBatchId(batchId);
        if (existing.isPresent()) {
            ReleaseRow order = existing.get();
            if (order.requestId().equals(requestId)) {
                return toReleaseResponse(order, true);
            }
            throw ApiException.conflict("批次已放行，且 requestId 不同");
        }
        List<RollRow> rolls = repo.listRolls(batchId);
        Map<String, List<String>> problems = new LinkedHashMap<>();
        if (rolls.isEmpty()) {
            problems.put("-", List.of("NO_ROLLS"));
        }
        // 在批次行锁内读取测色与裁决，保证采纳集合完整一致
        record Adopted(RollRow roll, MeasurementRow measurement, String kind) {
        }
        List<Adopted> adoptedList = new ArrayList<>();
        for (RollRow roll : rolls) {
            MeasurementRow original = repo.findLatestMeasurement(roll.id(), "ORIGINAL").orElse(null);
            if (original == null) {
                problems.computeIfAbsent(roll.rollNo(), k -> new ArrayList<>()).add("MISSING_MEASUREMENT");
                continue;
            }
            MeasurementRow recheck = repo.findLatestMeasurement(roll.id(), "RECHECK").orElse(null);
            MeasurementRow adopted = original;
            String adoptedKind = "ORIGINAL";
            if (recheck != null) {
                RulingRow ruling = repo.findRuling(roll.id()).orElse(null);
                if (ruling == null || ruling.recheckId() != recheck.id()) {
                    problems.computeIfAbsent(roll.rollNo(), k -> new ArrayList<>()).add("PENDING_RULING");
                    continue;
                }
                if ("RECHECK".equals(ruling.decision())) {
                    adopted = recheck;
                    adoptedKind = "RECHECK";
                }
            }
            if (adopted.targetVersion() != batch.targetVersion()) {
                problems.computeIfAbsent(roll.rollNo(), k -> new ArrayList<>()).add("STALE_TARGET_VERSION");
            }
            if (!ColorDifference.qualified(adopted.deltaE())) {
                problems.computeIfAbsent(roll.rollNo(), k -> new ArrayList<>()).add("OUT_OF_TOLERANCE");
            }
            adoptedList.add(new Adopted(roll, adopted, adoptedKind));
        }
        if (!problems.isEmpty()) {
            // 校验失败抛错回滚，requestId 不占键
            throw ApiException.releaseRejected(problems);
        }
        long releaseId;
        try {
            releaseId = repo.insertRelease(batchId, requestId, hash, batch.targetVersion(), clock.instant());
        } catch (DuplicateKeyException e) {
            // 并发放行：唯一约束保证最多一张放行单
            ReleaseRow order = repo.findReleaseByBatchId(batchId).orElseThrow();
            if (order.requestId().equals(requestId)) {
                return toReleaseResponse(order, true);
            }
            throw ApiException.conflict("批次已被并发放行，且 requestId 不同");
        }
        for (Adopted adopted : adoptedList) {
            MeasurementRow m = adopted.measurement();
            repo.insertReleaseLine(releaseId, adopted.roll().id(), adopted.roll().rollNo(),
                    adopted.kind(), m.l(), m.a(), m.b(), m.measuredAt(), m.deltaE());
        }
        repo.markBatchReleased(batchId);
        return toReleaseResponse(repo.findReleaseByBatchId(batchId).orElseThrow(), false);
    }

    /** 放行后追加勘误，不修改已冻结交付数据。 */
    @Transactional
    public ErrataRow appendErrata(long releaseId, String note) {
        ReleaseRow order = repo.findReleaseById(releaseId)
                .orElseThrow(() -> ApiException.notFound("放行单不存在: " + releaseId));
        long id = repo.insertErrata(order.id(), note, clock.instant());
        return repo.listErrata(order.id()).stream().filter(e -> e.id() == id).findFirst().orElseThrow();
    }

    /** 查询批次视图：目标版本、每卷原测与复检、采纳依据、最终色差、放行冻结明细。 */
    @Transactional(readOnly = true)
    public BatchView getBatchView(long batchId) {
        BatchRow batch = repo.findBatch(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        List<BatchView.RollView> rollViews = new ArrayList<>();
        for (RollRow roll : repo.listRolls(batchId)) {
            MeasurementRow original = repo.findLatestMeasurement(roll.id(), "ORIGINAL").orElse(null);
            MeasurementRow recheck = repo.findLatestMeasurement(roll.id(), "RECHECK").orElse(null);
            RulingRow ruling = repo.findRuling(roll.id()).orElse(null);
            String adoptedKind = null;
            Double adoptedDeltaE = null;
            MeasurementRow adopted = null;
            if (recheck != null && ruling != null && ruling.recheckId() == recheck.id()
                    && "RECHECK".equals(ruling.decision())) {
                adopted = recheck;
                adoptedKind = "RECHECK";
            } else if (original != null && (recheck == null
                    || (ruling != null && ruling.recheckId() == recheck.id()))) {
                adopted = original;
                adoptedKind = "ORIGINAL";
            }
            if (adopted != null) {
                adoptedDeltaE = adopted.deltaE();
            }
            rollViews.add(new BatchView.RollView(roll.id(), roll.rollNo(),
                    toMeasurementView(original), toMeasurementView(recheck),
                    ruling == null ? null
                            : new BatchView.RulingView(ruling.decision(), ruling.inspector(), ruling.createdAt()),
                    adoptedKind, adoptedDeltaE));
        }
        BatchView.ReleaseView releaseView = repo.findReleaseByBatchId(batchId)
                .map(order -> new BatchView.ReleaseView(order.id(), order.targetVersion(), order.releasedAt(),
                        repo.listReleaseLines(order.id()).stream().map(DyeBatchService::toLineView).toList(),
                        repo.listErrata(order.id()).stream()
                                .map(e -> new BatchView.ErrataView(e.id(), e.note(), e.createdAt())).toList()))
                .orElse(null);
        return new BatchView(batch.id(), batch.batchNo(), batch.status(), batch.targetVersion(),
                batch.targetL(), batch.targetA(), batch.targetB(), rollViews, releaseView);
    }

    private ReleaseResponse toReleaseResponse(ReleaseRow order, boolean replayed) {
        List<BatchView.ReleaseLineView> lines = repo.listReleaseLines(order.id()).stream()
                .map(DyeBatchService::toLineView).toList();
        return new ReleaseResponse(order.id(), order.batchId(), order.targetVersion(),
                order.releasedAt(), lines, replayed);
    }

    private static BatchView.ReleaseLineView toLineView(ReleaseLineRow line) {
        return new BatchView.ReleaseLineView(line.rollNo(), line.adoptedKind(), line.l(),
                line.a(), line.b(), line.measuredAt(), line.deltaE());
    }

    private static BatchView.MeasurementView toMeasurementView(MeasurementRow row) {
        if (row == null) {
            return null;
        }
        return new BatchView.MeasurementView(row.id(), row.kind(), row.l(), row.a(), row.b(),
                row.measuredAt(), row.targetVersion(), row.deltaE(), row.qualified());
    }
}