package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.AffectedMeasurementDto;
import com.example.starter.calibration.api.dto.AffectedStandardDto;
import com.example.starter.calibration.api.dto.CreateInvalidationRequest;
import com.example.starter.calibration.api.dto.ImpactItemDto;
import com.example.starter.calibration.api.dto.ImpactResponse;
import com.example.starter.calibration.api.dto.InvalidationClosureDto;
import com.example.starter.calibration.api.dto.InvalidationResponse;
import com.example.starter.calibration.model.ImpactItem;
import com.example.starter.calibration.model.InvalidationConfirmation;
import com.example.starter.calibration.model.InvalidationOrder;
import com.example.starter.calibration.model.InvalidationStatus;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.StandardVersion;
import com.example.starter.calibration.repo.ImpactRepository;
import com.example.starter.calibration.repo.InvalidationRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.StandardRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 标准器失效服务：创建失效单（固化首次闭包快照）、双人确认、单事务激活整体冻结、影响查询。
 *
 * <p>失效闭包：根标准器及失效起始时刻及以后受影响的全部子孙标准器（窗口终点晚于 invalidFrom），
 * 以及绑定这些标准器且测量时刻不早于 invalidFrom 的校准记录。闭包稳定排序：
 * 标准器按 standardId 升序，记录按 measurementKey 升序。
 *
 * <p>激活在持有血缘全局锁与失效单行锁的事务内重算闭包，并对受影响记录按 ID 升序加行锁；
 * 任一血缘、窗口、标准器版本或结果状态与首次快照不一致，整单 409，不产生部分冻结。
 */
@Service
public class InvalidationService {

    private final InvalidationRepository invalidations;
    private final StandardRepository standards;
    private final MeasurementRepository measurements;
    private final ImpactRepository impacts;
    private final ObjectMapper objectMapper;

    public InvalidationService(InvalidationRepository invalidations,
                               StandardRepository standards,
                               MeasurementRepository measurements,
                               ImpactRepository impacts,
                               ObjectMapper objectMapper) {
        this.invalidations = invalidations;
        this.standards = standards;
        this.measurements = measurements;
        this.impacts = impacts;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建失效单。requestId 同参重放返回首次闭包快照，异参 409；校验失败不写数据、不占键。
     * invalidationKey 重复 409。创建时校验 expectedVersion 与根标准器当前版本一致。
     */
    @Transactional
    public InvalidationResponse create(CreateInvalidationRequest request, String actor) {
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String key = Inputs.requireText(request.invalidationKey(), "invalidationKey");
        String rootStandardId = Inputs.requireText(request.rootStandardId(), "rootStandardId");
        Instant invalidFrom = Inputs.requireInstant(request.invalidFrom(), "invalidFrom");
        if (request.expectedVersion() == null) {
            throw ApiException.badRequest("expectedVersion 不能为空");
        }
        int expectedVersion = request.expectedVersion();
        String reason = Inputs.requireText(request.reason(), "reason");
        String creator = Inputs.requireText(actor, "X-Actor-Id");
        String params = canonicalParams(key, rootStandardId, invalidFrom, expectedVersion, reason);

        var existing = invalidations.findByRequestId(requestId);
        if (existing.isPresent()) {
            InvalidationOrder order = existing.get();
            if (!order.requestParams().equals(params)) {
                throw ApiException.conflict("REQUEST_ID_CONFLICT", "requestId 已存在且参数不一致: " + requestId);
            }
            return toResponse(order, parseClosure(order.closureSnapshot()));
        }

        standards.lockLineage();
        StandardVersion root = standards.findByStandardId(rootStandardId)
                .orElseThrow(() -> ApiException.notFound("标准器版本不存在: " + rootStandardId));
        if (root.version() != expectedVersion) {
            throw ApiException.conflict("EXPECTED_VERSION_MISMATCH",
                    "根标准器版本已变化: 期望 " + expectedVersion + "，当前 " + root.version());
        }

        InvalidationClosureDto closure = computeClosure(root, invalidFrom);
        InvalidationOrder order = new InvalidationOrder(
                0L, key, requestId, rootStandardId, invalidFrom, expectedVersion, reason, creator,
                InvalidationStatus.PENDING_CONFIRMATION, null, writeClosure(closure), params,
                Instant.now(), null);
        try {
            invalidations.insertOrder(order);
        } catch (DuplicateKeyException ex) {
            var raced = invalidations.findByRequestId(requestId);
            if (raced.isPresent() && raced.get().requestParams().equals(params)) {
                return toResponse(raced.get(), parseClosure(raced.get().closureSnapshot()));
            }
            if (raced.isPresent()) {
                throw ApiException.conflict("REQUEST_ID_CONFLICT",
                        "requestId 已存在且参数不一致: " + requestId);
            }
            throw ApiException.conflict("DUPLICATE_INVALIDATION_KEY", "失效单业务键已存在: " + key);
        }
        // 重新读取持久化行，保证首次响应与同参重放逐字节一致（时间精度以数据库为准）
        InvalidationOrder persisted = invalidations.findByKey(key).orElseThrow();
        return toResponse(persisted, parseClosure(persisted.closureSnapshot()));
    }

    /**
     * 失效单详情：返回首次闭包快照。不存在 404。
     */
    @Transactional(readOnly = true)
    public InvalidationResponse get(String key) {
        InvalidationOrder order = invalidations.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("失效单不存在: " + key));
        return toResponse(order, parseClosure(order.closureSnapshot()));
    }

    /**
     * 失效预览：沿血缘实时重算完整闭包，稳定排序，只读不写数据。不存在 404。
     */
    @Transactional(readOnly = true)
    public InvalidationResponse preview(String key) {
        InvalidationOrder order = invalidations.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("失效单不存在: " + key));
        StandardVersion root = standards.findByStandardId(order.rootStandardId())
                .orElseThrow(() -> ApiException.notFound("标准器版本不存在: " + order.rootStandardId()));
        return toResponse(order, computeClosure(root, order.invalidFrom()));
    }

    /**
     * 双人确认：确认人须为不同于创建人的质量人员，且同一确认人仅可确认一次。
     * 已激活的失效单 409。
     */
    @Transactional
    public InvalidationResponse confirm(String key, String actor) {
        String confirmer = Inputs.requireText(actor, "X-Actor-Id");
        InvalidationOrder order = invalidations.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("失效单不存在: " + key));
        if (order.status() == InvalidationStatus.ACTIVATED) {
            throw ApiException.conflict("ALREADY_ACTIVATED", "失效单已激活: " + key);
        }
        if (confirmer.equals(order.createdBy())) {
            throw ApiException.conflict("CREATOR_CANNOT_CONFIRM", "确认人须不同于创建人");
        }
        try {
            invalidations.insertConfirmation(key, confirmer, Instant.now());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_CONFIRMATION", "确认人已确认过该失效单: " + confirmer);
        }
        return toResponse(order, parseClosure(order.closureSnapshot()));
    }

    /**
     * 激活失效单。需两名不同质量人员已确认；在单事务内重算闭包并与首次快照比对，
     * 任一漂移或 expectedVersion 不一致整单 409。成功后根及受影响子孙标记 INVALID，
     * 未审核记录标记 BLOCKED，已放行结果改为 REVIEW_REQUIRED（保留原放行快照与数值），
     * 生成单一 impactVersion 并冻结每条结果到失效根的最短血缘路径。
     */
    @Transactional
    public ImpactResponse activate(String key, String actor) {
        Inputs.requireText(actor, "X-Actor-Id");
        standards.lockLineage();
        InvalidationOrder order = invalidations.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("失效单不存在: " + key));
        if (order.status() == InvalidationStatus.ACTIVATED) {
            throw ApiException.conflict("ALREADY_ACTIVATED", "失效单已激活: " + key);
        }
        List<InvalidationConfirmation> confirmations = invalidations.findConfirmations(key);
        if (confirmations.size() < 2) {
            throw ApiException.conflict("CONFIRMATION_REQUIRED", "失效单需两名不同质量人员确认后方可激活");
        }
        StandardVersion root = standards.findByStandardId(order.rootStandardId())
                .orElseThrow(() -> ApiException.conflict("ROOT_STANDARD_MISSING",
                        "根标准器版本不存在: " + order.rootStandardId()));
        if (root.version() != order.expectedVersion()) {
            throw ApiException.conflict("EXPECTED_VERSION_MISMATCH",
                    "根标准器版本已变化: 期望 " + order.expectedVersion() + "，当前 " + root.version());
        }

        List<StandardVersion> affectedStandards = computeAffectedStandards(root, order.invalidFrom());
        List<Long> standardIds = affectedStandards.stream().map(StandardVersion::id).toList();
        List<Measurement> candidates = standardIds.isEmpty() ? List.of()
                : measurements.findAffectedByStandards(standardIds, order.invalidFrom());
        List<Long> measurementIds = candidates.stream().map(Measurement::id).toList();
        List<Measurement> locked = measurementIds.isEmpty() ? List.of()
                : measurements.findByIdsForUpdate(measurementIds);

        InvalidationClosureDto recomputed = buildClosure(affectedStandards, locked);
        if (!writeClosure(recomputed).equals(order.closureSnapshot())) {
            throw ApiException.conflict("CLOSURE_DRIFT",
                    "闭包已漂移：血缘、窗口、标准器版本或结果状态发生变化，整单拒绝");
        }

        String impactVersion = UUID.randomUUID().toString();
        Instant now = Instant.now();
        for (StandardVersion standard : affectedStandards) {
            standards.markInvalid(standard.id());
        }
        Map<Long, StandardVersion> standardByRecordId = new HashMap<>();
        Map<String, StandardVersion> standardByStandardId = new HashMap<>();
        for (StandardVersion standard : standards.findAll()) {
            standardByRecordId.put(standard.id(), standard);
            standardByStandardId.put(standard.standardId(), standard);
        }
        List<ImpactItem> items = new ArrayList<>();
        for (Measurement measurement : locked) {
            StandardVersion bound = standardByRecordId.get(measurement.standardVersionId());
            String path = shortestPath(bound, root.standardId(), standardByStandardId);
            if (measurement.status() == MeasurementStatus.PENDING) {
                measurements.markBlocked(measurement.id(), impactVersion, path);
            } else if (measurement.status() == MeasurementStatus.RELEASED) {
                measurements.markReviewRequired(measurement.id(), impactVersion, path);
                ImpactItem item = new ImpactItem(0L, impactVersion, measurement.id(),
                        measurement.measurementKey(), bound.standardId(), path,
                        MeasurementStatus.RELEASED.name(), now);
                impacts.insert(item);
                items.add(item);
            }
        }
        invalidations.markActivated(key, impactVersion, now);
        return toImpactResponse(order, impactVersion, now, items);
    }

    /**
     * 影响查询：只读，按 impactVersion 重现激活时冻结的全部明细。不存在 404。
     */
    @Transactional(readOnly = true)
    public ImpactResponse impact(String impactVersion) {
        InvalidationOrder order = invalidations.findByImpactVersion(impactVersion)
                .orElseThrow(() -> ApiException.notFound("影响版本不存在: " + impactVersion));
        return toImpactResponse(order, impactVersion, order.activatedAt(),
                impacts.findByImpactVersion(impactVersion));
    }

    /**
     * 计算失效闭包（预览/创建用，不加行锁）。
     */
    private InvalidationClosureDto computeClosure(StandardVersion root, Instant invalidFrom) {
        List<StandardVersion> affectedStandards = computeAffectedStandards(root, invalidFrom);
        List<Long> standardIds = affectedStandards.stream().map(StandardVersion::id).toList();
        List<Measurement> affected = standardIds.isEmpty() ? List.of()
                : measurements.findAffectedByStandards(standardIds, invalidFrom);
        return buildClosure(affectedStandards, affected);
    }

    /**
     * 受影响标准器：根及窗口终点晚于 invalidFrom 的全部子孙（子孙窗口含于父级，故窗口已结束的子树整体不受影响）。
     * 按 standardId 升序返回。
     */
    private List<StandardVersion> computeAffectedStandards(StandardVersion root, Instant invalidFrom) {
        Map<String, List<StandardVersion>> childrenByParent = new HashMap<>();
        for (StandardVersion standard : standards.findAll()) {
            if (standard.parentStandardId() != null) {
                childrenByParent.computeIfAbsent(standard.parentStandardId(), k -> new ArrayList<>())
                        .add(standard);
            }
        }
        List<StandardVersion> affected = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<StandardVersion> queue = new ArrayDeque<>();
        queue.add(root);
        visited.add(root.standardId());
        affected.add(root);
        while (!queue.isEmpty()) {
            StandardVersion current = queue.poll();
            for (StandardVersion child : childrenByParent.getOrDefault(current.standardId(), List.of())) {
                if (!visited.add(child.standardId())) {
                    continue;
                }
                if (!child.validTo().isAfter(invalidFrom)) {
                    continue;
                }
                affected.add(child);
                queue.add(child);
            }
        }
        affected.sort(Comparator.comparing(StandardVersion::standardId));
        return affected;
    }

    /**
     * 组装稳定排序的闭包 DTO：标准器按 standardId 升序，记录按 measurementKey 升序。
     */
    private InvalidationClosureDto buildClosure(List<StandardVersion> affectedStandards,
                                                List<Measurement> affectedMeasurements) {
        Map<Long, String> standardIdByRecordId = new HashMap<>();
        List<AffectedStandardDto> standardDtos = affectedStandards.stream()
                .peek(s -> standardIdByRecordId.put(s.id(), s.standardId()))
                .map(s -> new AffectedStandardDto(s.standardId(), s.parentStandardId(),
                        s.validFrom(), s.validTo(), s.status().name(), s.version()))
                .toList();
        List<AffectedMeasurementDto> measurementDtos = affectedMeasurements.stream()
                .map(m -> new AffectedMeasurementDto(m.measurementKey(),
                        standardIdByRecordId.get(m.standardVersionId()),
                        m.measuredAt(), m.status().name()))
                .sorted(Comparator.comparing(AffectedMeasurementDto::measurementKey))
                .toList();
        return new InvalidationClosureDto(standardDtos, measurementDtos);
    }

    /**
     * 冻结结果到失效根的最短血缘路径。单父血缘下到根的路径唯一；
     * 等长候选按 standardId 字典序选择（此处仅一条候选，规则自然满足）。
     */
    private String shortestPath(StandardVersion start, String rootStandardId,
                                Map<String, StandardVersion> standardByStandardId) {
        List<String> segments = new ArrayList<>();
        StandardVersion current = start;
        while (current != null) {
            segments.add(current.standardId());
            if (current.standardId().equals(rootStandardId)) {
                break;
            }
            current = current.parentStandardId() == null ? null
                    : standardByStandardId.get(current.parentStandardId());
        }
        return String.join(">", segments);
    }

    private String canonicalParams(String key, String rootStandardId, Instant invalidFrom,
                                   int expectedVersion, String reason) {
        return String.join("|", key, rootStandardId, invalidFrom.toString(),
                String.valueOf(expectedVersion), reason);
    }

    private String writeClosure(InvalidationClosureDto closure) {
        try {
            return objectMapper.writeValueAsString(closure);
        } catch (Exception ex) {
            throw new IllegalStateException("闭包序列化失败", ex);
        }
    }

    private InvalidationClosureDto parseClosure(String snapshot) {
        try {
            return objectMapper.readValue(snapshot, InvalidationClosureDto.class);
        } catch (Exception ex) {
            throw new IllegalStateException("闭包快照反序列化失败", ex);
        }
    }

    private InvalidationResponse toResponse(InvalidationOrder order, InvalidationClosureDto closure) {
        List<String> confirmations = invalidations.findConfirmations(order.invalidationKey()).stream()
                .map(InvalidationConfirmation::confirmedBy)
                .toList();
        return new InvalidationResponse(
                order.invalidationKey(),
                order.requestId(),
                order.rootStandardId(),
                order.invalidFrom(),
                order.expectedVersion(),
                order.reason(),
                order.createdBy(),
                order.status().name(),
                order.impactVersion(),
                confirmations,
                closure,
                order.createdAt(),
                order.activatedAt());
    }

    private ImpactResponse toImpactResponse(InvalidationOrder order, String impactVersion,
                                            Instant activatedAt, List<ImpactItem> items) {
        List<ImpactItemDto> itemDtos = items.stream()
                .map(i -> new ImpactItemDto(i.measurementKey(), i.standardId(), i.path(), i.previousStatus()))
                .sorted(Comparator.comparing(ImpactItemDto::measurementKey))
                .toList();
        return new ImpactResponse(impactVersion, order.invalidationKey(), order.rootStandardId(),
                order.invalidFrom(), activatedAt, itemDtos);
    }
}
