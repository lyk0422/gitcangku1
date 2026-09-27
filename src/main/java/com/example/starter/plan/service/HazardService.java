package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanCar;
import com.example.starter.plan.model.PublishSnapshot;
import com.example.starter.plan.model.SectionRestriction;
import com.example.starter.plan.model.SeparationRule;
import com.example.starter.plan.repo.HazardRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CarView;
import com.example.starter.plan.web.dto.HazardCheckView;
import com.example.starter.plan.web.dto.HazardConfigRequest;
import com.example.starter.plan.web.dto.HazardConfigResponse;
import com.example.starter.plan.web.dto.HazardDiagnosticView;
import com.example.starter.plan.web.dto.PublishSnapshotSummary;
import com.example.starter.plan.web.dto.PublishSnapshotView;
import com.example.starter.plan.web.dto.RestrictionVersionView;
import com.example.starter.plan.web.dto.SectionRestrictionRequest;
import com.example.starter.plan.web.dto.SectionRestrictionView;
import com.example.starter.plan.web.dto.SeparationRuleRequest;
import com.example.starter.plan.web.dto.SeparationRuleView;
import com.example.starter.plan.web.dto.SnapshotBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 危险品编组隔离与区段通行联合校验：配置整体替换、发布前校验、诊断查询与发布快照。
 *
 * <p>校验三项检查按固定顺序执行：车厢位置唯一（CAR_POSITION_UNIQUE）、
 * 互斥类别间隔（SEPARATION）、限制区段类别授权（SECTION_CATEGORY_AUTH）。
 * 任一检查失败汇总全部冲突对，由调用方以 422 抛出；仅全部通过才允许发布并固化快照。
 */
@Service
public class HazardService {

    /** 检查项：车厢位置唯一。 */
    public static final String CHECK_CAR_POSITION = "CAR_POSITION_UNIQUE";
    /** 检查项：互斥类别间隔。 */
    public static final String CHECK_SEPARATION = "SEPARATION";
    /** 检查项：限制区段类别授权。 */
    public static final String CHECK_SECTION_AUTH = "SECTION_CATEGORY_AUTH";

    private static final String OP_HAZARD_CONFIG = "HAZARD_CONFIG";

    private final HazardRepository hazardRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public HazardService(HazardRepository hazardRepo, PlanRepository planRepo,
                         IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                         PlatformTransactionManager txManager) {
        this.hazardRepo = hazardRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 一次完整联合校验的结果：逐项检查结论、全部冲突对与快照素材。
     * conflicts 为空表示校验通过。
     */
    public record HazardEvaluation(List<HazardCheckView> checks,
                                   List<Map<String, Object>> conflicts,
                                   List<CarView> cars,
                                   List<String> categories,
                                   List<RestrictionVersionView> restrictionVersions) {

        public boolean passed() {
            return conflicts.isEmpty();
        }
    }

    // ---------- 配置整体替换 ----------

    /**
     * 整体替换危险品配置（隔离规则 + 限制区段）：先校验完整最终状态，
     * 再在一个事务中提交；任一非法则 422 且整体不生效。幂等：同键同参重放返回首次结果。
     */
    public HazardConfigResponse replaceConfig(HazardConfigRequest req) {
        String hash = hashConfig(req);
        Optional<HazardConfigResponse> replay =
                replayIfPresent(req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                List<Map<String, Object>> conflicts = validateConfig(req);
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "HAZARD_CONFIG_INVALID",
                            "危险品配置非法，整体未生效", conflicts);
                }
                long now = System.currentTimeMillis();
                List<SeparationRule> rules = normalizeRules(req.separationRules());
                Map<String, SectionRestriction> existing = new TreeMap<>();
                for (SectionRestriction r : hazardRepo.findAllRestrictions()) {
                    existing.put(r.sectionId(), r);
                }
                List<SectionRestriction> restrictions = new ArrayList<>();
                List<List<String>> allowedLists = new ArrayList<>();
                for (SectionRestrictionRequest r : sortedRestrictions(req.sectionRestrictions())) {
                    List<String> allowed = sortedDistinct(r.allowedCategories());
                    int version = nextVersion(existing.get(r.sectionId()), r.restricted(), allowed);
                    restrictions.add(new SectionRestriction(r.sectionId(), r.restricted(), version));
                    allowedLists.add(allowed);
                }
                hazardRepo.replaceRules(rules, now);
                hazardRepo.replaceRestrictions(restrictions, allowedLists, now);
                HazardConfigResponse response = loadConfig();
                idemRepo.insert(OP_HAZARD_CONFIG, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return replayIfPresent(req.requestKey(), hash)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "CONFIG_CONFLICT",
                            "危险品配置并发替换冲突"));
        }
    }

    /**
     * 查询当前危险品配置（隔离规则与限制区段），稳定排序。
     */
    public HazardConfigResponse getConfig() {
        return loadConfig();
    }

    // ---------- 联合校验 ----------

    /**
     * 对计划在完整编组与完整区段路径上执行三项联合校验，返回逐项结论与全部冲突对。
     * 只读，不改变状态。
     */
    public HazardEvaluation evaluate(DayPlan plan, List<Occupancy> occupancies) {
        List<PlanCar> cars = hazardRepo.findCars(plan.id());
        List<Map<String, Object>> positionConflicts = findDuplicatePositions(plan, cars);
        List<Map<String, Object>> separationConflicts = findSeparationViolations(plan, cars);
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        Map<String, SectionRestriction> restrictions = new TreeMap<>();
        for (SectionRestriction r : hazardRepo.findRestrictions(sectionIds)) {
            restrictions.put(r.sectionId(), r);
        }
        List<String> categories = cars.stream()
                .map(PlanCar::category)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<Map<String, Object>> authConflicts = new ArrayList<>();
        List<RestrictionVersionView> restrictionVersions = new ArrayList<>();
        for (Map.Entry<String, SectionRestriction> e : restrictions.entrySet()) {
            SectionRestriction restriction = e.getValue();
            if (!restriction.restricted()) {
                continue;
            }
            restrictionVersions.add(new RestrictionVersionView(e.getKey(), restriction.version()));
            List<String> allowed = hazardRepo.findAllowedCategories(e.getKey());
            for (String category : categories) {
                if (!allowed.contains(category)) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "CATEGORY_NOT_AUTHORIZED");
                    detail.put("scheduleKey", plan.scheduleKey());
                    detail.put("sectionId", e.getKey());
                    detail.put("category", category);
                    authConflicts.add(detail);
                }
            }
        }
        List<HazardCheckView> checks = List.of(
                new HazardCheckView(CHECK_CAR_POSITION, positionConflicts.isEmpty() ? "PASSED" : "FAILED"),
                new HazardCheckView(CHECK_SEPARATION, separationConflicts.isEmpty() ? "PASSED" : "FAILED"),
                new HazardCheckView(CHECK_SECTION_AUTH, authConflicts.isEmpty() ? "PASSED" : "FAILED"));
        List<Map<String, Object>> conflicts = new ArrayList<>();
        conflicts.addAll(positionConflicts);
        conflicts.addAll(separationConflicts);
        conflicts.addAll(authConflicts);
        List<CarView> carViews = cars.stream()
                .map(c -> new CarView(c.carPosition(), c.category()))
                .toList();
        return new HazardEvaluation(checks, conflicts, carViews, categories, restrictionVersions);
    }

    /**
     * 诊断查询：对计划当前编组与完整区段路径做只读联合校验，不改变任何状态。
     */
    public HazardDiagnosticView getDiagnostic(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                        "计划不存在: " + scheduleKey));
        HazardEvaluation evaluation = evaluate(plan, planRepo.findOccupancies(plan.id()));
        return new HazardDiagnosticView(scheduleKey,
                evaluation.passed() ? "PASSED" : "FAILED",
                evaluation.checks(), evaluation.conflicts());
    }

    // ---------- 发布快照 ----------

    /**
     * 在调用方事务内固化发布快照：编组、类别、区段限制版本与校验结论写入后不再修改。
     */
    public void saveSnapshot(DayPlan plan, String opType, String operator,
                             HazardEvaluation evaluation, long nowMillis) {
        SnapshotBody body = new SnapshotBody(evaluation.cars(), evaluation.categories(),
                evaluation.restrictionVersions(), evaluation.checks());
        hazardRepo.insertSnapshot(plan.id(), plan.scheduleKey(), opType, "PASSED", operator,
                toJson(body), nowMillis);
    }

    /**
     * 查询计划发布快照明细；计划不存在或未发布（无快照）均返回 404。
     */
    public PublishSnapshotView getSnapshot(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                        "计划不存在: " + scheduleKey));
        PublishSnapshot snapshot = hazardRepo.findSnapshotByPlanId(plan.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SNAPSHOT_NOT_FOUND",
                        "计划无发布快照: " + scheduleKey));
        return toView(snapshot);
    }

    /**
     * 查询指定运营日的发布快照历史，按 (createdAt, id) 稳定排序。
     */
    public List<PublishSnapshotSummary> listSnapshots(LocalDate opDate) {
        return hazardRepo.findSnapshotsByOpDate(opDate).stream()
                .map(s -> new PublishSnapshotSummary(s.scheduleKey(), s.opType(), s.conclusion(),
                        s.operator(), Instant.ofEpochMilli(s.createdAt())))
                .toList();
    }

    // ---------- 内部实现 ----------

    private HazardConfigResponse loadConfig() {
        List<SeparationRuleView> rules = hazardRepo.findAllRules().stream()
                .map(r -> new SeparationRuleView(r.categoryA(), r.categoryB(), r.minSeparation()))
                .toList();
        List<SectionRestrictionView> restrictions = hazardRepo.findAllRestrictions().stream()
                .map(r -> new SectionRestrictionView(r.sectionId(), r.restricted(), r.version(),
                        hazardRepo.findAllowedCategories(r.sectionId())))
                .toList();
        return new HazardConfigResponse(rules, restrictions);
    }

    /**
     * 校验配置完整最终状态，返回全部冲突对（不短路）。
     */
    private List<Map<String, Object>> validateConfig(HazardConfigRequest req) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, SeparationRuleRequest> seenPairs = new TreeMap<>();
        for (SeparationRuleRequest r : req.separationRules()) {
            if (r.categoryA().equals(r.categoryB())) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "SELF_RULE");
                detail.put("categoryA", r.categoryA());
                detail.put("categoryB", r.categoryB());
                conflicts.add(detail);
                continue;
            }
            String a = min(r.categoryA(), r.categoryB());
            String b = max(r.categoryA(), r.categoryB());
            if (seenPairs.putIfAbsent(a + "" + b, r) != null) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "DUPLICATE_RULE");
                detail.put("categoryA", a);
                detail.put("categoryB", b);
                conflicts.add(detail);
            }
        }
        Map<String, Boolean> seenSections = new TreeMap<>();
        for (SectionRestrictionRequest r : req.sectionRestrictions()) {
            if (seenSections.putIfAbsent(r.sectionId(), Boolean.TRUE) != null) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "DUPLICATE_SECTION");
                detail.put("sectionId", r.sectionId());
                conflicts.add(detail);
            }
            if (r.restricted() && r.allowedCategories().isEmpty()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "EMPTY_ALLOWED_LIST");
                detail.put("sectionId", r.sectionId());
                conflicts.add(detail);
            }
            if (!r.restricted() && !r.allowedCategories().isEmpty()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "UNRESTRICTED_WITH_ALLOWED_LIST");
                detail.put("sectionId", r.sectionId());
                conflicts.add(detail);
            }
            if (new TreeSet<>(r.allowedCategories()).size() != r.allowedCategories().size()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "DUPLICATE_ALLOWED_CATEGORY");
                detail.put("sectionId", r.sectionId());
                conflicts.add(detail);
            }
        }
        return conflicts;
    }

    private List<SeparationRule> normalizeRules(List<SeparationRuleRequest> rules) {
        return rules.stream()
                .map(r -> new SeparationRule(0L, min(r.categoryA(), r.categoryB()),
                        max(r.categoryA(), r.categoryB()), r.minSeparation()))
                .sorted(Comparator.comparing(SeparationRule::categoryA)
                        .thenComparing(SeparationRule::categoryB))
                .toList();
    }

    private List<SectionRestrictionRequest> sortedRestrictions(List<SectionRestrictionRequest> list) {
        return list.stream()
                .sorted(Comparator.comparing(SectionRestrictionRequest::sectionId))
                .toList();
    }

    private List<String> sortedDistinct(List<String> categories) {
        return new ArrayList<>(new TreeSet<>(categories));
    }

    /**
     * 计算区段限制新版本：新区段从 1 开始；配置（限制标记或开放清单）变化时旧版本加一，
     * 未变化保持旧版本。
     */
    private int nextVersion(SectionRestriction existing, boolean restricted, List<String> allowed) {
        if (existing == null) {
            return 1;
        }
        boolean unchanged = existing.restricted() == restricted
                && hazardRepo.findAllowedCategories(existing.sectionId()).equals(allowed);
        return unchanged ? existing.version() : existing.version() + 1;
    }

    /**
     * 车厢位置重复检测：每个重复位置产生一条冲突对明细。
     */
    private List<Map<String, Object>> findDuplicatePositions(DayPlan plan, List<PlanCar> cars) {
        Map<Integer, List<PlanCar>> byPosition = new TreeMap<>();
        for (PlanCar car : cars) {
            byPosition.computeIfAbsent(car.carPosition(), k -> new ArrayList<>()).add(car);
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        byPosition.forEach((position, group) -> {
            if (group.size() > 1) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "DUPLICATE_CAR_POSITION");
                detail.put("scheduleKey", plan.scheduleKey());
                detail.put("carPosition", position);
                detail.put("categories", group.stream().map(PlanCar::category).sorted().toList());
                detail.put("actualCount", group.size());
                detail.put("requiredCount", 1);
                conflicts.add(detail);
            }
        });
        return conflicts;
    }

    /**
     * 互斥类别间隔检测：对每条隔离规则枚举两类车厢的全部位置对，
     * 间隔不足时列出实际值、要求值与差额。
     */
    private List<Map<String, Object>> findSeparationViolations(DayPlan plan, List<PlanCar> cars) {
        Map<String, List<Integer>> positionsByCategory = new TreeMap<>();
        for (PlanCar car : cars) {
            positionsByCategory.computeIfAbsent(car.category(), k -> new ArrayList<>())
                    .add(car.carPosition());
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (SeparationRule rule : hazardRepo.findAllRules()) {
            List<Integer> positionsA = positionsByCategory.get(rule.categoryA());
            List<Integer> positionsB = positionsByCategory.get(rule.categoryB());
            if (positionsA == null || positionsB == null) {
                continue;
            }
            for (int posA : positionsA) {
                for (int posB : positionsB) {
                    int actual = Math.abs(posA - posB);
                    if (actual < rule.minSeparation()) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("type", "SEPARATION_INSUFFICIENT");
                        detail.put("scheduleKey", plan.scheduleKey());
                        detail.put("categoryA", rule.categoryA());
                        detail.put("categoryB", rule.categoryB());
                        detail.put("carPositionA", posA);
                        detail.put("carPositionB", posB);
                        detail.put("actualSeparation", actual);
                        detail.put("requiredSeparation", rule.minSeparation());
                        detail.put("deficit", rule.minSeparation() - actual);
                        conflicts.add(detail);
                    }
                }
            }
        }
        return conflicts;
    }

    private Optional<HazardConfigResponse> replayIfPresent(String requestKey, String hash) {
        return idemRepo.find(OP_HAZARD_CONFIG, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), HazardConfigResponse.class);
        });
    }

    private String hashConfig(HazardConfigRequest req) {
        FingerprintBuilder fp = new FingerprintBuilder(OP_HAZARD_CONFIG);
        fp.field(req.operator());
        for (SeparationRule rule : normalizeRules(req.separationRules())) {
            fp.field("RULE").field(rule.categoryA()).field(rule.categoryB())
                    .field(String.valueOf(rule.minSeparation()));
        }
        for (SectionRestrictionRequest r : sortedRestrictions(req.sectionRestrictions())) {
            fp.field("SECTION").field(r.sectionId()).field(String.valueOf(r.restricted()));
            for (String category : sortedDistinct(r.allowedCategories())) {
                fp.field(category);
            }
            fp.field("END");
        }
        return fp.sha256();
    }

    private PublishSnapshotView toView(PublishSnapshot snapshot) {
        return new PublishSnapshotView(snapshot.scheduleKey(), snapshot.opType(),
                snapshot.conclusion(), snapshot.operator(),
                Instant.ofEpochMilli(snapshot.createdAt()),
                fromJson(snapshot.snapshotJson(), SnapshotBody.class));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("快照反序列化失败", e);
        }
    }

    private static String min(String a, String b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String max(String a, String b) {
        return a.compareTo(b) <= 0 ? b : a;
    }
}
