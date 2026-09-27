package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.HazardCar;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishSnapshot;
import com.example.starter.plan.model.RestrictedSection;
import com.example.starter.plan.model.SeparationRule;
import com.example.starter.plan.repo.HazardRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ConfigureSectionRequest;
import com.example.starter.plan.web.dto.HazardCarRequest;
import com.example.starter.plan.web.dto.HazardCarView;
import com.example.starter.plan.web.dto.HazardCarsResponse;
import com.example.starter.plan.web.dto.HazardDiagnosticsResponse;
import com.example.starter.plan.web.dto.PublishSnapshotView;
import com.example.starter.plan.web.dto.RestrictedSectionView;
import com.example.starter.plan.web.dto.RestrictedSectionsResponse;
import com.example.starter.plan.web.dto.SeparationRuleRequest;
import com.example.starter.plan.web.dto.SeparationRuleView;
import com.example.starter.plan.web.dto.SeparationRulesResponse;
import com.example.starter.plan.web.dto.UpdateHazardCarsRequest;
import com.example.starter.plan.web.dto.UpdateRulesRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 危险品编组隔离与区段通行配置的核心业务：编组整体替换、互斥规则集整体替换、
 * 限制区段配置、诊断试校验与发布快照历史查询。
 *
 * <p>并发与幂等约定与 PlanService 一致：写操作按 (操作类型, requestKey) 幂等，
 * 同键同参重放返回首次成功结果，同键不同参 409，失败不占键；
 * 编组修改经计划行锁、配置变更经全局配置锁按事务提交顺序生效。
 */
@Service
public class HazardService {

    private static final String OP_HAZARD_CARS = "HAZARD_CARS";
    private static final String OP_RULES = "RULES";
    private static final String OP_SECTION = "SECTION";

    private final PlanRepository planRepo;
    private final HazardRepository hazardRepo;
    private final IdempotencyRepository idemRepo;
    private final HazardValidator validator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public HazardService(PlanRepository planRepo, HazardRepository hazardRepo,
                         IdempotencyRepository idemRepo, HazardValidator validator,
                         ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.hazardRepo = hazardRepo;
        this.idemRepo = idemRepo;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 整体替换计划危险品编组（仅草稿，expectedVersion 乐观校验，成功版本加一）。
     * 车厢位置重复或互斥间隔不足返回 422 并列出冲突对；空清单表示清空编组。
     */
    public HazardCarsResponse replaceHazardCars(String scheduleKey, UpdateHazardCarsRequest req) {
        String hash = hashHazardCars(scheduleKey, req);
        Optional<HazardCarsResponse> replay =
                replayIfPresent(OP_HAZARD_CARS, req.requestKey(), hash, HazardCarsResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可修改编组，当前状态: " + plan.status());
                }
                if (req.expectedVersion() != plan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + plan.version() + " 不一致");
                }
                List<HazardCar> cars = toHazardCars(plan.id(), req.cars());
                List<Map<String, Object>> conflicts = validator.validateFormation(
                        scheduleKey, cars, hazardRepo.findSeparationRules());
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "HAZARD_CONFLICT",
                            "编组校验未通过，编组未变更", conflicts);
                }
                long now = System.currentTimeMillis();
                hazardRepo.replaceHazardCars(plan.id(), cars);
                planRepo.updateVersionAndStatus(plan.id(), plan.version() + 1, PlanStatus.DRAFT, now);
                HazardCarsResponse response = new HazardCarsResponse(
                        scheduleKey, plan.version() + 1, toCarViews(cars));
                idemRepo.insert(OP_HAZARD_CARS, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_HAZARD_CARS, req.requestKey(), hash, HazardCarsResponse.class)
                    .orElseThrow(() -> conflict("HAZARD_CAR_CONFLICT", "编组唯一约束冲突"));
        }
    }

    /**
     * 整体替换互斥规则集（全局配置锁内串行，成功规则集版本加一）。
     * 类别相同或类别对重复视为配置无效，返回 422 并列出明细；空清单表示清空规则集。
     */
    public SeparationRulesResponse replaceRules(UpdateRulesRequest req) {
        List<SeparationRule> normalized = normalizeRules(req.rules());
        String hash = hashRules(req, normalized);
        Optional<SeparationRulesResponse> replay =
                replayIfPresent(OP_RULES, req.requestKey(), hash, SeparationRulesResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                hazardRepo.lockConfigVersions();
                long now = System.currentTimeMillis();
                hazardRepo.replaceSeparationRules(normalized, now);
                hazardRepo.bumpRuleVersion(now);
                int newVersion = hazardRepo.findConfigVersions().ruleVersion();
                SeparationRulesResponse response = new SeparationRulesResponse(newVersion,
                        normalized.stream()
                                .map(r -> new SeparationRuleView(r.classA(), r.classB(),
                                        r.minSeparation()))
                                .toList());
                idemRepo.insert(OP_RULES, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_RULES, req.requestKey(), hash, SeparationRulesResponse.class)
                    .orElseThrow(() -> conflict("RULE_CONFLICT", "互斥规则唯一约束冲突"));
        }
    }

    /**
     * 覆盖式设置限制区段开放清单（全局配置锁内串行，成功区段配置版本加一）。
     * 空清单、空白类别或重复类别视为配置无效，返回 422 并列出明细。
     */
    public RestrictedSectionView configureSection(String sectionId, ConfigureSectionRequest req) {
        List<String> normalized = normalizeAllowedClasses(sectionId, req.allowedClasses());
        String hash = hashSection(sectionId, req, normalized);
        Optional<RestrictedSectionView> replay =
                replayIfPresent(OP_SECTION, req.requestKey(), hash, RestrictedSectionView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                hazardRepo.lockConfigVersions();
                long now = System.currentTimeMillis();
                hazardRepo.upsertRestrictedSection(sectionId, normalized, now);
                hazardRepo.bumpSectionVersion(now);
                int newVersion = hazardRepo.findConfigVersions().sectionVersion();
                RestrictedSectionView response =
                        new RestrictedSectionView(sectionId, normalized, newVersion);
                idemRepo.insert(OP_SECTION, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_SECTION, req.requestKey(), hash, RestrictedSectionView.class)
                    .orElseThrow(() -> conflict("SECTION_CONFLICT", "限制区段配置唯一约束冲突"));
        }
    }

    /**
     * 查询当前互斥规则集（含版本，按类别对字典序）。
     */
    public SeparationRulesResponse getRules() {
        int version = hazardRepo.findConfigVersions().ruleVersion();
        List<SeparationRuleView> rules = hazardRepo.findSeparationRules().stream()
                .map(r -> new SeparationRuleView(r.classA(), r.classB(), r.minSeparation()))
                .toList();
        return new SeparationRulesResponse(version, rules);
    }

    /**
     * 查询全部限制区段配置（含版本，按区段 ID 字典序）。
     */
    public RestrictedSectionsResponse getSections() {
        int version = hazardRepo.findConfigVersions().sectionVersion();
        List<RestrictedSectionsResponse.RestrictedSectionItem> sections =
                hazardRepo.findRestrictedSections().stream()
                        .map(s -> new RestrictedSectionsResponse.RestrictedSectionItem(
                                s.sectionId(), s.allowedClasses()))
                        .toList();
        return new RestrictedSectionsResponse(version, sections);
    }

    /**
     * 查询单个限制区段配置，未配置返回 404。
     */
    public RestrictedSectionView getSection(String sectionId) {
        RestrictedSection section = hazardRepo.findRestrictedSection(sectionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SECTION_NOT_FOUND",
                        "限制区段未配置: " + sectionId));
        int version = hazardRepo.findConfigVersions().sectionVersion();
        return new RestrictedSectionView(section.sectionId(), section.allowedClasses(), version);
    }

    /**
     * 查询计划当前编组（按车厢位置升序；取消后历史编组仍原样返回）。
     */
    public HazardCarsResponse getHazardCars(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<HazardCarView> cars = toCarViews(hazardRepo.findHazardCars(plan.id()));
        return new HazardCarsResponse(scheduleKey, plan.version(), cars);
    }

    /**
     * 诊断试校验：以当前编组与完整区段路径对照当前版本配置校验，不持有锁、不改变状态。
     */
    public HazardDiagnosticsResponse diagnostics(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        var occupancies = planRepo.findOccupancies(plan.id());
        List<HazardCar> cars = hazardRepo.findHazardCars(plan.id());
        HazardRepository.ConfigVersions versions = hazardRepo.findConfigVersions();
        List<Map<String, Object>> conflicts =
                validator.validateAll(scheduleKey, occupancies, cars);
        return new HazardDiagnosticsResponse(scheduleKey, versions.ruleVersion(),
                versions.sectionVersion(), conflicts.isEmpty(), toCarViews(cars),
                sectionPath(occupancies), conflicts);
    }

    /**
     * 查询计划发布快照历史（按追加顺序，快照不可变）。
     */
    public List<PublishSnapshotView> getSnapshots(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        return hazardRepo.findSnapshotsByPlanId(plan.id()).stream()
                .map(this::toSnapshotView)
                .toList();
    }

    // ---------- 内部实现 ----------

    /**
     * 提取完整区段路径：去重后按区段 ID 字典序。
     */
    static List<String> sectionPath(List<com.example.starter.plan.model.Occupancy> occupancies) {
        return occupancies.stream()
                .map(com.example.starter.plan.model.Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
    }

    static List<HazardCarView> toCarViews(List<HazardCar> cars) {
        return cars.stream()
                .sorted(Comparator.comparingInt(HazardCar::carPosition))
                .map(c -> new HazardCarView(c.carPosition(), c.hazardClass()))
                .toList();
    }

    private List<HazardCar> toHazardCars(long planId, List<HazardCarRequest> requests) {
        List<HazardCar> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            HazardCarRequest r = requests.get(i);
            result.add(new HazardCar(0L, planId, i, r.carPosition(), r.hazardClass().trim()));
        }
        return result;
    }

    /**
     * 规则规范化：类别对按字典序排列、类别去空白；类别相同或类别对重复返回 422。
     */
    private List<SeparationRule> normalizeRules(List<SeparationRuleRequest> rules) {
        List<Map<String, Object>> invalid = new ArrayList<>();
        Map<String, SeparationRule> byPair = new LinkedHashMap<>();
        for (int i = 0; i < rules.size(); i++) {
            SeparationRuleRequest r = rules.get(i);
            String classA = r.classA().trim();
            String classB = r.classB().trim();
            if (classA.equals(classB)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "INVALID_RULE_CONFIG");
                detail.put("reason", "互斥类别对的两个类别相同");
                detail.put("index", i);
                detail.put("hazardClass", classA);
                invalid.add(detail);
                continue;
            }
            SeparationRule rule = classA.compareTo(classB) <= 0
                    ? new SeparationRule(classA, classB, r.minSeparation())
                    : new SeparationRule(classB, classA, r.minSeparation());
            String pairKey = HazardValidator.pairKey(rule.classA(), rule.classB());
            if (byPair.putIfAbsent(pairKey, rule) != null) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "INVALID_RULE_CONFIG");
                detail.put("reason", "互斥类别对重复");
                detail.put("index", i);
                detail.put("classA", rule.classA());
                detail.put("classB", rule.classB());
                invalid.add(detail);
            }
        }
        if (!invalid.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RULE_CONFIG",
                    "互斥规则配置无效，规则集未变更", invalid);
        }
        return byPair.values().stream()
                .sorted(Comparator.comparing(SeparationRule::classA)
                        .thenComparing(SeparationRule::classB))
                .toList();
    }

    /**
     * 开放清单规范化：去空白、去重、字典序；空清单、空白类别或重复类别返回 422。
     */
    private List<String> normalizeAllowedClasses(String sectionId, List<String> allowedClasses) {
        List<Map<String, Object>> invalid = new ArrayList<>();
        if (allowedClasses.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "INVALID_SECTION_CONFIG");
            detail.put("sectionId", sectionId);
            detail.put("reason", "开放类别清单为空");
            invalid.add(detail);
        }
        List<String> normalized = new ArrayList<>(allowedClasses.size());
        for (int i = 0; i < allowedClasses.size(); i++) {
            String raw = allowedClasses.get(i);
            if (raw == null || raw.isBlank()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "INVALID_SECTION_CONFIG");
                detail.put("sectionId", sectionId);
                detail.put("reason", "开放类别为空白");
                detail.put("index", i);
                invalid.add(detail);
                continue;
            }
            normalized.add(raw.trim());
        }
        Map<String, Integer> firstIndex = new LinkedHashMap<>();
        for (int i = 0; i < normalized.size(); i++) {
            String c = normalized.get(i);
            if (firstIndex.putIfAbsent(c, i) != null) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "INVALID_SECTION_CONFIG");
                detail.put("sectionId", sectionId);
                detail.put("reason", "开放类别重复");
                detail.put("hazardClass", c);
                invalid.add(detail);
            }
        }
        if (!invalid.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SECTION_CONFIG",
                    "限制区段配置无效，配置未变更", invalid);
        }
        return normalized.stream().sorted().distinct().toList();
    }

    private PublishSnapshotView toSnapshotView(PublishSnapshot s) {
        return new PublishSnapshotView(s.id(), s.planId(), s.scheduleKey(), s.opDate(),
                s.operator(), s.requestKey(), s.source(), s.ruleVersion(), s.sectionVersion(),
                fromJson(s.formationJson(), new TypeReference<List<HazardCarView>>() { }),
                fromJson(s.sectionsJson(), new TypeReference<List<String>>() { }),
                s.validationResult(), Instant.ofEpochMilli(s.createdAt()));
    }

    // ---------- 幂等指纹（长度前缀编码，集合先规范化排序） ----------

    private String hashHazardCars(String scheduleKey, UpdateHazardCarsRequest req) {
        Fingerprint fp = Fingerprint.of(OP_HAZARD_CARS)
                .field(req.operator())
                .field(scheduleKey)
                .field(req.expectedVersion());
        // 编组为集合语义：按 (车厢位置, 类别) 规范化排序后逐项编码，项内同样长度前缀
        List<String> items = req.cars().stream()
                .map(c -> {
                    String hazardClass = c.hazardClass().trim();
                    return c.carPosition() + ":" + hazardClass.length() + ":" + hazardClass;
                })
                .sorted()
                .toList();
        return fp.fields(items).sha256();
    }

    private String hashRules(UpdateRulesRequest req, List<SeparationRule> normalized) {
        Fingerprint fp = Fingerprint.of(OP_RULES).field(req.operator());
        List<String> items = normalized.stream()
                .map(r -> r.classA().length() + ":" + r.classA()
                        + r.classB().length() + ":" + r.classB() + r.minSeparation())
                .toList();
        return fp.fields(items).sha256();
    }

    private String hashSection(String sectionId, ConfigureSectionRequest req,
                               List<String> normalized) {
        return Fingerprint.of(OP_SECTION)
                .field(req.operator())
                .field(sectionId)
                .fields(normalized)
                .sha256();
    }

    // ---------- 通用 ----------

    private <T> Optional<T> replayIfPresent(String opType, String requestKey, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("快照反序列化失败", e);
        }
    }

    private ApiException notFound(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                "计划不存在: " + scheduleKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
