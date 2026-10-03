package com.example.gitcangku1.service;

import com.example.gitcangku1.domain.Adjudication;
import com.example.gitcangku1.domain.BatchStatus;
import com.example.gitcangku1.domain.DyeBatch;
import com.example.gitcangku1.domain.MeasureType;
import com.example.gitcangku1.domain.Measurement;
import com.example.gitcangku1.domain.ReleaseOrder;
import com.example.gitcangku1.repo.AdjudicationRepository;
import com.example.gitcangku1.repo.BatchRepository;
import com.example.gitcangku1.repo.MeasurementRepository;
import com.example.gitcangku1.repo.ReleaseRepository;
import com.example.gitcangku1.service.Views.BatchView;
import com.example.gitcangku1.service.Views.ErrataView;
import com.example.gitcangku1.service.Views.MeasurementView;
import com.example.gitcangku1.service.Views.ReleaseView;
import com.example.gitcangku1.service.Views.RollView;
import com.example.gitcangku1.service.Views.AdjudicationView;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 整批放行、结果查询与放行后勘误。 */
@Service
public class ReleaseService {

    /** 色差合格阈值，比较前不四舍五入。 */
    public static final double TOLERANCE = 2.0;

    private final BatchRepository batches;
    private final MeasurementRepository measurements;
    private final AdjudicationRepository adjudications;
    private final ReleaseRepository releases;
    private final Clock clock;

    public ReleaseService(BatchRepository batches, MeasurementRepository measurements,
            AdjudicationRepository adjudications, ReleaseRepository releases, Clock clock) {
        this.batches = batches;
        this.measurements = measurements;
        this.adjudications = adjudications;
        this.releases = releases;
        this.clock = clock;
    }

    public static double deltaE(double l, double a, double b, DyeBatch batch) {
        double dl = l - batch.targetL();
        double da = a - batch.targetA();
        double db = b - batch.targetB();
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    /**
     * 整批放行。批次行锁串行化并发放行与裁决竞争，保证读取完整一致的采纳集合；
     * release_order.batch_id 唯一约束兜底，失败事务回滚不占 requestId 键。
     */
    @Transactional
    public ReleaseView release(long batchId, String requestId, Integer expectedTargetVersion) {
        DyeBatch batch = batches.findByIdForUpdate(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        String hash = BatchService.hash("release", batchId, requestId, expectedTargetVersion);
        var byRequest = releases.findOrderByRequestId(requestId);
        if (byRequest.isPresent()) {
            if (byRequest.get().requestHash().equals(hash)) {
                return viewOf(byRequest.get());
            }
            throw ApiException.conflict("requestId 已存在但参数不同: " + requestId);
        }
        var existing = releases.findOrderByBatch(batchId);
        if (existing.isPresent()) {
            return viewOf(existing.get());
        }
        if (expectedTargetVersion != null && expectedTargetVersion != batch.targetVersion()) {
            throw ApiException.conflict("目标值版本已变化，期望" + expectedTargetVersion
                    + "，当前" + batch.targetVersion());
        }

        Map<Integer, Measurement> originals = new LinkedHashMap<>();
        Map<Integer, Measurement> rechecks = new LinkedHashMap<>();
        for (Measurement m : measurements.findByBatch(batchId)) {
            (m.type() == MeasureType.ORIGINAL ? originals : rechecks).put(m.rollNo(), m);
        }
        Map<Integer, Adjudication> decisions = adjudications.findByBatch(batchId).stream()
                .collect(Collectors.toMap(Adjudication::rollNo, Function.identity()));

        Map<String, List<Integer>> problems = new LinkedHashMap<>();
        Map<Integer, Measurement> adopted = new LinkedHashMap<>();
        for (int roll = 1; roll <= batch.expectedRolls(); roll++) {
            Measurement original = originals.get(roll);
            if (original == null) {
                problems.computeIfAbsent("MISSING", k -> new ArrayList<>()).add(roll);
                continue;
            }
            Adjudication decision = decisions.get(roll);
            if (rechecks.containsKey(roll) && decision == null) {
                problems.computeIfAbsent("UNDECIDED_RECHECK", k -> new ArrayList<>()).add(roll);
                continue;
            }
            Measurement chosen = decision != null && decision.decision() == MeasureType.RECHECK
                    ? rechecks.get(roll) : original;
            if (chosen.targetVersion() != batch.targetVersion()) {
                problems.computeIfAbsent("STALE_TARGET_VERSION", k -> new ArrayList<>()).add(roll);
                continue;
            }
            if (deltaE(chosen.l(), chosen.a(), chosen.b(), batch) > TOLERANCE) {
                problems.computeIfAbsent("OUT_OF_TOLERANCE", k -> new ArrayList<>()).add(roll);
                continue;
            }
            adopted.put(roll, chosen);
        }
        if (!problems.isEmpty()) {
            throw new ReleaseRejectedException(problems);
        }

        ReleaseOrder order = releases.insertOrder(batchId, batch.targetVersion(), requestId, hash,
                Instant.now(clock));
        for (var entry : adopted.entrySet()) {
            Measurement m = entry.getValue();
            releases.insertItem(order.id(), entry.getKey(),
                    rechecks.get(entry.getKey()) == m ? MeasureType.RECHECK : MeasureType.ORIGINAL,
                    m.l(), m.a(), m.b(), deltaE(m.l(), m.a(), m.b(), batch), m.measuredAt());
        }
        batches.updateStatus(batchId, BatchStatus.RELEASED);
        return viewOf(order);
    }
    /** 放行后勘误：只允许追加记录，不修改已冻结交付数据。 */
    @Transactional
    public ErrataView appendErrata(long batchId, String note) {
        DyeBatch batch = batches.findByIdForUpdate(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        if (batch.status() != BatchStatus.RELEASED) {
            throw ApiException.conflict("批次未放行，不能追加勘误: " + batch.batchNo());
        }
        ReleaseOrder order = releases.findOrderByBatch(batchId).orElseThrow();
        return ErrataView.of(releases.insertErrata(order.id(), note, Instant.now(clock)));
    }

    /** 查询：目标版本、每卷原测与复检、采纳依据和最终色差（放行后为冻结值）。 */
    @Transactional(readOnly = true)
    public BatchView view(long batchId) {
        DyeBatch batch = batches.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchId));
        Map<Integer, Measurement> originals = new LinkedHashMap<>();
        Map<Integer, Measurement> rechecks = new LinkedHashMap<>();
        for (Measurement m : measurements.findByBatch(batchId)) {
            (m.type() == MeasureType.ORIGINAL ? originals : rechecks).put(m.rollNo(), m);
        }
        Map<Integer, Adjudication> decisions = adjudications.findByBatch(batchId).stream()
                .collect(Collectors.toMap(Adjudication::rollNo, Function.identity()));
        var frozen = releases.findOrderByBatch(batchId)
                .map(o -> releases.findItems(o.id()).stream()
                        .collect(Collectors.toMap(i -> i.rollNo(), Function.identity())))
                .orElse(Map.of());

        List<RollView> rolls = new ArrayList<>();
        for (int roll = 1; roll <= batch.expectedRolls(); roll++) {
            Measurement original = originals.get(roll);
            Measurement recheck = rechecks.get(roll);
            Adjudication decision = decisions.get(roll);
            String adoptedSource = null;
            Double delta = null;
            Boolean pass = null;
            var frozenItem = frozen.get(roll);
            if (frozenItem != null) {
                adoptedSource = frozenItem.adoptedSource().name();
                delta = frozenItem.deltaE();
                pass = delta <= TOLERANCE;
            } else if (original != null) {
                Measurement chosen = decision != null && decision.decision() == MeasureType.RECHECK
                        ? recheck : original;
                if (chosen != null && (recheck == null || decision != null)) {
                    adoptedSource = chosen == recheck ? MeasureType.RECHECK.name()
                            : MeasureType.ORIGINAL.name();
                    delta = deltaE(chosen.l(), chosen.a(), chosen.b(), batch);
                    pass = delta <= TOLERANCE;
                }
            }
            rolls.add(new RollView(roll,
                    original == null ? null : MeasurementView.of(original),
                    recheck == null ? null : MeasurementView.of(recheck),
                    decision == null ? null : AdjudicationView.of(decision),
                    adoptedSource, delta, pass));
        }
        ReleaseView release = releases.findOrderByBatch(batchId).map(this::viewOf).orElse(null);
        return new BatchView(batch.id(), batch.batchNo(), batch.targetL(), batch.targetA(),
                batch.targetB(), batch.targetVersion(), batch.expectedRolls(), batch.status().name(),
                rolls, release);
    }

    private ReleaseView viewOf(ReleaseOrder order) {
        return ReleaseView.of(order, releases.findItems(order.id()), releases.findErrata(order.id()));
    }
}