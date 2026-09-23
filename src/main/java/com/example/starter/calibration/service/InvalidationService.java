package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.AffectedMeasurementItem;
import com.example.starter.calibration.api.dto.AffectedStandardItem;
import com.example.starter.calibration.api.dto.CreateInvalidationRequest;
import com.example.starter.calibration.api.dto.InvalidationClosureResponse;
import com.example.starter.calibration.api.dto.ReleaseRecordResponse;
import com.example.starter.calibration.model.ImpactPathNode;
import com.example.starter.calibration.model.InvalidationOrder;
import com.example.starter.calibration.model.InvalidationStatus;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseRecord;
import com.example.starter.calibration.model.SnapshotItem;
import com.example.starter.calibration.model.StandardVersion;
import com.example.starter.calibration.model.StandardVersionStatus;
import com.example.starter.calibration.repo.DomainStateRepository;
import com.example.starter.calibration.repo.InvalidationRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.StandardVersionRepository;

/**
 * 标准器失效服务：失效预览（只读闭包）、创建失效单（闭包快照、requestId 幂等、expectedVersion 乐观校验）、
 * 两名不同质量人员确认后在一个事务内重算闭包并整体冻结；影响查询按 impactVersion 只读重现。
 */
@Service
public class InvalidationService {

    /** 血缘路径上溯的最大代数，防御异常数据成环。 */
    private static final int MAX_PATH_DEPTH = 10_000;

    private final InvalidationRepository invalidations;
    private final StandardVersionRepository versions;
    private final MeasurementRepository measurements;
    private final ReleaseRepository releases;
    private final DomainStateRepository domainState;

    public InvalidationService(InvalidationRepository invalidations,
                               StandardVersionRepository versions,
                               MeasurementRepository measurements,
                               ReleaseRepository releases,
                               DomainStateRepository domainState) {
        this.invalidations = invalidations;
        this.versions = versions;
        this.measurements = measurements;
        this.releases = releases;
        this.domainState = domainState;
    }

    /**
     * 失效预览：沿血缘向下计算在 effectiveFrom 时刻及以后受影响的全部子孙标准器，
     * 及直接或间接使用它们的校准记录与已放行结果；稳定排序返回完整闭包，不写任何数据。
     */
    @Transactional(readOnly = true)
    public InvalidationClosureResponse preview(CreateInvalidationRequest request) {
        ParsedCreate parsed = parse(request, false);
        StandardVersion root = versions.findById(parsed.rootVersionId())
                .orElseThrow(() -> ApiException.notFound("标准器版本不存在: " + request.rootVersionKey()));
        ClosureView view = computeClosure(root, parsed.effectiveFrom());
        return toResponse(null, null, root.id(), parsed.effectiveFrom(), view,
                domainState.currentVersion(), List.of(), null);
    }

    /**
     * 创建失效单。requestId 同参重放首次闭包快照；异参 409；校验或业务失败事务回滚不占用 requestId。
     * expectedVersion 与当前领域版本不一致返回 409；invalidationKey 全局唯一。
     */
    @Transactional
    public InvalidationClosureResponse create(CreateInvalidationRequest request, String requestId, String actor) {
        String reqId = Inputs.requireText(requestId, "X-Request-Id");
        String createdBy = Inputs.requireText(actor, "X-Actor-Id");
        ParsedCreate parsed = parse(request, true);

        InvalidationOrder existing = invalidations.findByRequestId(reqId).orElse(null);
        if (existing != null) {
            ensureSameParameters(existing, parsed);
            return replay(existing);
        }

        // 领域版本行锁：创建快照期间阻断血缘新增、测量提交、放行与其他失效激活。
        long currentVersion = domainState.currentVersionForUpdate();
        if (parsed.expectedVersion() != currentVersion) {
            throw ApiException.conflict("STALE_EXPECTED_VERSION",
                    "expectedVersion 与当前领域版本不一致: expected=" + parsed.expectedVersion()
                            + " current=" + currentVersion);
        }
        if (invalidations.findByKey(parsed.invalidationKey()).isPresent()) {
            throw ApiException.conflict("DUPLICATE_INVALIDATION_KEY",
                    "失效单已存在: " + parsed.invalidationKey());
        }
        StandardVersion root = versions.findByIdForUpdate(parsed.rootVersionId())
                .orElseThrow(() -> ApiException.notFound("标准器版本不存在: " + request.rootVersionKey()));

        ClosureView view = computeClosure(root, parsed.effectiveFrom());
        InvalidationOrder order = new InvalidationOrder(
                0L, reqId, parsed.invalidationKey(), root.id(), parsed.effectiveFrom(),
                parsed.expectedVersion(), parsed.reason(), createdBy, InvalidationStatus.PENDING,
                null, Instant.now(), null);
        long orderId;
        try {
            orderId = invalidations.insertOrder(order, currentVersion);
            invalidations.insertSnapshotItems(orderId, toSnapshotItems(view));
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_INVALIDATION_KEY", "失效单或请求键冲突");
        }
        // 从数据库重读，保证时间字段精度与后续 requestId 重放完全一致。
        InvalidationOrder persisted = invalidations.findByKey(parsed.invalidationKey()).orElseThrow();
        return toResponse(persisted, null, root.id(), persisted.invalidFrom(), view,
                currentVersion, List.of(), null);
    }

    /**
     * 质量人员确认。同一人重复确认 409；两名不同人员齐备时在同一事务内重算闭包并整体冻结；
     * 已激活单据再确认返回 409。
     */
    @Transactional
    public InvalidationClosureResponse confirm(String invalidationKey, String actor) {
        String confirmer = Inputs.requireText(actor, "X-Actor-Id");
        String key = Inputs.requireText(invalidationKey, "invalidationKey");

        InvalidationOrder order = invalidations.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("失效单不存在: " + key));
        if (order.status() == InvalidationStatus.ACTIVATED) {
            throw ApiException.conflict("ALREADY_ACTIVATED", "失效单已激活: " + key);
        }
        try {
            invalidations.insertConfirmation(order.id(), confirmer, Instant.now());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("ALREADY_CONFIRMED", "已确认过该失效单: " + confirmer);
        }
        List<String> confirmers = invalidations.findConfirmerIds(order.id());
        if (confirmers.size() < 2) {
            return replay(order);
        }
        return activate(order, confirmers);
    }

    /**
     * 按失效单业务键查询当前闭包视图：待激活返回首次快照重建；已激活返回冻结结果。
     */
    @Transactional(readOnly = true)
    public InvalidationClosureResponse getByKey(String invalidationKey) {
        String key = Inputs.requireText(invalidationKey, "invalidationKey");
        InvalidationOrder order = invalidations.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("失效单不存在: " + key));
        return replay(order);
    }

    /**
     * 影响查询（只读）：按 impactVersion 重现冻结闭包、每条结果的最短血缘路径与冻结后状态。
     */
    @Transactional(readOnly = true)
    public InvalidationClosureResponse getByImpactVersion(String impactVersion) {
        String iv = Inputs.requireText(impactVersion, "impactVersion");
        InvalidationOrder order = invalidations.findByImpactVersion(iv)
                .orElseThrow(() -> ApiException.notFound("影响版本不存在: " + iv));
        return buildActivatedView(order);
    }

    /**
     * 激活：领域版本行锁内重算闭包并与创建快照逐项比对；任一血缘、窗口、标准器版本或结果状态变化，
     * 整单 409，不允许只冻结部分结果。比对通过后在同一事务内整体冻结并生成单一 impactVersion。
     */
    private InvalidationClosureResponse activate(InvalidationOrder order, List<String> confirmers) {
        domainState.currentVersionForUpdate();

        StandardVersion root = versions.findByIdForUpdate(order.rootVersionId())
                .orElseThrow(() -> ApiException.conflict("ROOT_VERSION_MISSING",
                        "失效根标准器版本缺失: " + order.rootVersionId()));
        ClosureView current = computeClosure(root, order.invalidFrom());
        List<SnapshotItem> snapshot = invalidations.findSnapshotItems(order.id());
        if (!snapshot.equals(toSnapshotItems(current))) {
            throw ApiException.conflict("CLOSURE_CHANGED",
                    "失效闭包自创建后发生变化（血缘、窗口、标准器版本或结果状态），整单拒绝");
        }

        String impactVersion = "IV-" + java.util.UUID.randomUUID();
        Instant activatedAt = Instant.now();
        versions.markInvalid(current.versions().stream().map(StandardVersion::id).toList());
        for (AffectedMeasurement affected : current.measurements()) {
            Measurement measurement = affected.measurement();
            MeasurementStatus target = measurement.status() == MeasurementStatus.RELEASED
                    ? MeasurementStatus.REVIEW_REQUIRED
                    : MeasurementStatus.BLOCKED;
            measurements.markImpacted(measurement.id(), target, impactVersion);
            invalidations.insertImpactPath(
                    new ImpactPathNode(impactVersion, measurement.id(), 0, 0),
                    affected.pathVersionIds());
        }
        invalidations.markActivated(order.id(), impactVersion, activatedAt);
        domainState.increment();

        InvalidationOrder activated = new InvalidationOrder(
                order.id(), order.requestId(), order.invalidationKey(), order.rootVersionId(),
                order.invalidFrom(), order.expectedVersion(), order.reason(), order.createdBy(),
                InvalidationStatus.ACTIVATED, impactVersion, order.createdAt(), activatedAt);
        return buildActivatedView(activated);
    }

    /**
     * 计算失效闭包：沿血缘向下收集在 effectiveFrom 时刻及以后仍有效的全部子孙版本（含根），
     * 再收集绑定这些版本且测量时刻不早于 effectiveFrom 的测量结果；
     * 为每条结果沿唯一父链求到失效根的最短血缘路径。
     */
    private ClosureView computeClosure(StandardVersion root, Instant effectiveFrom) {
        StandardVersionRepository.Closure closure =
                versions.loadDownwardClosure(root.id(), effectiveFrom);
        Map<Long, StandardVersion> byId = new LinkedHashMap<>();
        closure.versions().forEach(v -> byId.put(v.id(), v));

        List<AffectedMeasurement> items = new ArrayList<>();
        for (Measurement measurement : measurements.findAffected(new ArrayList<>(byId.keySet()),
                effectiveFrom)) {
            List<Long> path = shortestPathToRoot(measurement.standardVersionId(), root.id(),
                    closure.parents(), byId);
            items.add(new AffectedMeasurement(measurement, path));
        }
        return new ClosureView(closure.versions(), items);
    }

    /**
     * 沿父指针上溯求绑定版本到失效根的版本 ID 路径（含两端，depth 0 为绑定版本）。
     * 单父血缘下父链即最短路径；出现无法到达根的情况视为血缘断裂。
     */
    private List<Long> shortestPathToRoot(Long boundVersionId, long rootId,
                                          Map<Long, Long> parents, Map<Long, StandardVersion> byId) {
        List<Long> path = new ArrayList<>();
        Long cursor = boundVersionId;
        int guard = 0;
        while (cursor != null) {
            path.add(cursor);
            if (cursor == rootId) {
                return path;
            }
            if (++guard > MAX_PATH_DEPTH) {
                throw ApiException.conflict("LINEAGE_CYCLE", "冻结血缘路径上溯超过上限，疑似成环");
            }
            cursor = parents.get(cursor);
        }
        throw ApiException.conflict("LINEAGE_BROKEN",
                "测量绑定版本不在失效闭包内: versionId=" + boundVersionId);
    }

    private List<SnapshotItem> toSnapshotItems(ClosureView view) {
        List<SnapshotItem> items = new ArrayList<>();
        for (StandardVersion version : view.versions()) {
            items.add(new SnapshotItem(SnapshotItem.TYPE_STANDARD, version.id(), version.status().name()));
        }
        for (AffectedMeasurement affected : view.measurements()) {
            items.add(new SnapshotItem(SnapshotItem.TYPE_MEASUREMENT,
                    affected.measurement().id(), affected.measurement().status().name()));
        }
        items.sort(SnapshotItem::compareTo);
        return items;
    }

    /**
     * 待激活：用首次快照的状态重建闭包（血缘结构不可变，路径按当前血缘重算）；已激活：返回冻结结果。
     */
    private InvalidationClosureResponse replay(InvalidationOrder order) {
        List<String> confirmers = invalidations.findConfirmerIds(order.id());
        if (order.status() == InvalidationStatus.ACTIVATED) {
            return buildActivatedView(order);
        }
        StandardVersion root = versions.findById(order.rootVersionId())
                .orElseThrow(() -> ApiException.notFound("标准器版本缺失: " + order.rootVersionId()));
        List<SnapshotItem> snapshot = invalidations.findSnapshotItems(order.id());
        Map<Long, String> versionStatus = new HashMap<>();
        List<Long> measurementIds = new ArrayList<>();
        Map<Long, String> measurementStatus = new HashMap<>();
        for (SnapshotItem item : snapshot) {
            if (item.itemType().equals(SnapshotItem.TYPE_STANDARD)) {
                versionStatus.put(item.refId(), item.refStatus());
            } else {
                measurementIds.add(item.refId());
                measurementStatus.put(item.refId(), item.refStatus());
            }
        }
        List<StandardVersion> snapshotVersions = versionStatus.keySet().stream()
                .sorted()
                .map(id -> withStatus(versions.findById(id).orElseThrow(), versionStatus.get(id)))
                .sorted(this::compareVersions)
                .toList();

        Map<Long, Long> parents = new HashMap<>();
        for (StandardVersion version : versions.findAll()) {
            if (version.parentVersionId() != null) {
                parents.put(version.id(), version.parentVersionId());
            }
        }
        Map<Long, StandardVersion> byId = new LinkedHashMap<>();
        snapshotVersions.forEach(v -> byId.put(v.id(), v));
        List<AffectedMeasurement> rebuilt = new ArrayList<>();
        for (Measurement measurement : measurements.findByIds(measurementIds)) {
            Measurement snapshotView = withStatus(measurement, measurementStatus.get(measurement.id()));
            List<Long> path = shortestPathToRoot(measurement.standardVersionId(), root.id(), parents, byId);
            rebuilt.add(new AffectedMeasurement(snapshotView, path));
        }
        rebuilt.sort((a, b) -> Long.compare(a.measurement().id(), b.measurement().id()));
        ClosureView view = new ClosureView(snapshotVersions, rebuilt);
        return toResponse(order, null, root.id(), order.invalidFrom(), view,
                order.expectedVersion(), confirmers, null);
    }

    private InvalidationClosureResponse buildActivatedView(InvalidationOrder order) {
        StandardVersion root = versions.findById(order.rootVersionId())
                .orElseThrow(() -> ApiException.notFound("标准器版本缺失: " + order.rootVersionId()));
        StandardVersionRepository.Closure closure =
                versions.loadDownwardClosure(root.id(), order.invalidFrom());
        List<StandardVersion> sortedVersions = closure.versions().stream()
                .sorted(this::compareVersions)
                .toList();
        Map<Long, List<ImpactPathNode>> pathsByMeasurement = new LinkedHashMap<>();
        for (ImpactPathNode node : invalidations.findImpactPaths(order.impactVersion())) {
            pathsByMeasurement.computeIfAbsent(node.measurementId(), k -> new ArrayList<>()).add(node);
        }
        List<AffectedMeasurement> items = new ArrayList<>();
        for (Measurement measurement : measurements.findByImpactVersion(order.impactVersion())) {
            List<Long> path = pathsByMeasurement.getOrDefault(measurement.id(), List.of()).stream()
                    .sorted((a, b) -> Integer.compare(a.depth(), b.depth()))
                    .map(ImpactPathNode::standardVersionId)
                    .toList();
            items.add(new AffectedMeasurement(measurement, path));
        }
        ClosureView view = new ClosureView(sortedVersions, items);
        return toResponse(order, order.impactVersion(), root.id(), order.invalidFrom(), view,
                order.expectedVersion(), invalidations.findConfirmerIds(order.id()), order.activatedAt());
    }

    private int compareVersions(StandardVersion a, StandardVersion b) {
        int byStandard = a.standardId().compareTo(b.standardId());
        return byStandard != 0 ? byStandard : Long.compare(a.id(), b.id());
    }

    private InvalidationClosureResponse toResponse(InvalidationOrder order, String impactVersion,
                                                   long rootVersionId, Instant effectiveFrom,
                                                   ClosureView view, long domainVersion,
                                                   List<String> confirmers, Instant activatedAt) {
        List<AffectedStandardItem> standardItems = view.versions().stream()
                .map(v -> new AffectedStandardItem(v.id(), v.versionKey(), v.standardId(), v.status().name()))
                .toList();
        List<AffectedMeasurementItem> measurementItems = view.measurements().stream()
                .map(a -> {
                    Measurement m = a.measurement();
                    ReleaseRecordResponse releaseSnapshot = releaseSnapshot(m);
                    return new AffectedMeasurementItem(m.id(), m.measurementKey(), m.status().name(),
                            m.standardVersionId() == null ? 0L : m.standardVersionId(),
                            a.pathVersionIds(), releaseSnapshot);
                })
                .toList();
        return new InvalidationClosureResponse(
                order == null ? null : order.invalidationKey(),
                order == null ? null : order.status().name(),
                impactVersion,
                rootVersionId,
                effectiveFrom,
                domainVersion,
                standardItems,
                measurementItems,
                confirmers,
                order == null ? null : order.reason(),
                order == null ? null : order.createdAt(),
                activatedAt);
    }

    /**
     * 已放行（含失效后转 REVIEW_REQUIRED）结果的原放行快照：保留最后一次放行人、批次与时间。
     */
    private ReleaseRecordResponse releaseSnapshot(Measurement m) {
        if (m.status() != MeasurementStatus.RELEASED && m.status() != MeasurementStatus.REVIEW_REQUIRED) {
            return null;
        }
        List<ReleaseRecord> history = releases.findByMeasurementId(m.id());
        if (history.isEmpty()) {
            return null;
        }
        ReleaseRecord last = history.get(history.size() - 1);
        return new ReleaseRecordResponse(last.batchId(), last.releasedBy(), last.releasedAt());
    }

    private ParsedCreate parse(CreateInvalidationRequest request, boolean requireExpectedVersion) {
        String key = Inputs.requireText(request.invalidationKey(), "invalidationKey");
        String rootVersionKey = Inputs.requireText(request.rootVersionKey(), "rootVersionKey");
        Instant effectiveFrom = Inputs.requireInstant(request.effectiveFrom(), "effectiveFrom");
        String reason = Inputs.requireText(request.reason(), "reason");
        if (requireExpectedVersion && request.expectedVersion() == null) {
            throw ApiException.badRequest("expectedVersion 不能为空");
        }
        long expectedVersion = request.expectedVersion() == null ? 0L : request.expectedVersion();
        if (expectedVersion < 0) {
            throw ApiException.badRequest("expectedVersion 不能为负");
        }
        StandardVersion root = versions.findByVersionKey(rootVersionKey)
                .orElseThrow(() -> ApiException.notFound("标准器版本不存在: " + rootVersionKey));
        return new ParsedCreate(key, root.id(), effectiveFrom, expectedVersion, reason);
    }

    private void ensureSameParameters(InvalidationOrder existing, ParsedCreate parsed) {
        boolean same = existing.invalidationKey().equals(parsed.invalidationKey())
                && existing.rootVersionId() == parsed.rootVersionId()
                && existing.invalidFrom().equals(parsed.effectiveFrom())
                && existing.expectedVersion() == parsed.expectedVersion()
                && existing.reason().equals(parsed.reason());
        if (!same) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 携带了不同的失效单参数");
        }
    }

    private StandardVersion withStatus(StandardVersion version, String status) {
        return new StandardVersion(version.id(), version.versionKey(), version.standardId(),
                version.parentVersionId(), version.validFrom(), version.validTo(),
                version.certificateNo(), StandardVersionStatus.valueOf(status), version.createdAt());
    }

    private Measurement withStatus(Measurement measurement, String status) {
        return new Measurement(measurement.id(), measurement.measurementKey(), measurement.instrumentId(),
                measurement.measuredAt(), measurement.rawReading(), measurement.lowerLimit(),
                measurement.upperLimit(), measurement.submittedBy(), measurement.certificateId(),
                measurement.standardVersionId(), measurement.computedValue(), measurement.passed(),
                MeasurementStatus.valueOf(status), measurement.impactVersion(), measurement.createdAt());
    }

    /** 创建请求解析结果。 */
    private record ParsedCreate(String invalidationKey, long rootVersionId, Instant effectiveFrom,
                                long expectedVersion, String reason) {
    }

    /** 闭包内测量及其到失效根的最短血缘路径（版本 ID，depth 0 起）。 */
    private record AffectedMeasurement(Measurement measurement, List<Long> pathVersionIds) {
    }

    /** 完整失效闭包视图。 */
    private record ClosureView(List<StandardVersion> versions, List<AffectedMeasurement> measurements) {
    }
}
