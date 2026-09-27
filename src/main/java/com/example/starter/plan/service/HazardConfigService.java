package com.example.starter.plan.service;

import com.example.starter.plan.model.HazardConfigVersion;
import com.example.starter.plan.model.HazardSectionOpen;
import com.example.starter.plan.model.HazardSeparationRule;
import com.example.starter.plan.repo.HazardConfigRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.HazardConfigRequest;
import com.example.starter.plan.web.dto.HazardConfigView;
import com.example.starter.plan.web.dto.HazardSeparationRuleRequest;
import com.example.starter.plan.web.dto.SectionOpenListRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 危险品隔离配置版本写入与查询。
 *
 * <p>配置写入与发布/改签共用全局发布锁，按事务提交顺序裁决；生效区间左闭右开、UTC，
 * 区间重叠拒绝（409）。配置内容（生效区间、互斥规则、开放清单）规范化后取 SHA-256 指纹，
 * 发布时固化该指纹。写操作按 requestKey 幂等：同参重放返回首次响应，异参 409，失败不占键。
 */
@Service
public class HazardConfigService {

    private static final String OP_SECTION_CONFIG = "SECTION_CONFIG";

    private final HazardConfigRepository configRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final CanonicalHasher hasher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final TimeProvider timeProvider;

    public HazardConfigService(HazardConfigRepository configRepo, PlanRepository planRepo,
                               IdempotencyRepository idemRepo, CanonicalHasher hasher,
                               ObjectMapper objectMapper, PlatformTransactionManager txManager,
                               TimeProvider timeProvider) {
        this.configRepo = configRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.timeProvider = timeProvider;
    }

    /**
     * 写入新的配置版本（整体不可变，仅追加）。
     */
    public HazardConfigView writeConfig(HazardConfigRequest req) {
        String operator = normalizeOperator(req.operator());
        if (req.effectiveToUtc() != null
                && !req.effectiveToUtc().isAfter(req.effectiveFromUtc())) {
            throw badRequest("生效结束时刻必须晚于开始时刻（左闭右开）");
        }
        NormalizedConfig normalized = normalize(req.separations(), req.restrictedSections());
        String requestHash = hashRequest(operator, req.effectiveFromUtc(), req.effectiveToUtc(),
                normalized);
        HazardConfigView replay = replayIfPresent(req.requestKey(), requestHash);
        if (replay != null) {
            return replay;
        }
        try {
            return tx.execute(status -> {
                // 与发布/改签/其他配置写入串行，按提交顺序裁决生效区间
                planRepo.acquirePublishLock();
                if (!configRepo.findOverlapping(req.effectiveFromUtc(), req.effectiveToUtc())
                        .isEmpty()) {
                    throw conflict("CONFIG_RANGE_OVERLAP",
                            "配置生效区间与已有版本重叠（左闭右开）: "
                                    + req.effectiveFromUtc() + " ~ " + req.effectiveToUtc());
                }
                long now = timeProvider.nowMillis();
                int versionNo = configRepo.findMaxVersionNo() + 1;
                String contentFingerprint = hashContent(req.effectiveFromUtc(),
                        req.effectiveToUtc(), normalized);
                long configId = configRepo.insertVersion(versionNo, req.effectiveFromUtc(),
                        req.effectiveToUtc(), contentFingerprint, now);
                List<HazardSeparationRule> rules = normalized.separations().stream()
                        .map(p -> new HazardSeparationRule(configId, p.classA(), p.classB(),
                                p.minCars()))
                        .toList();
                configRepo.insertSeparations(configId, rules);
                configRepo.insertRestrictedSections(configId,
                        new TreeSet<>(normalized.openBySection().keySet()));
                List<HazardSectionOpen> opens = new ArrayList<>();
                normalized.openBySection().forEach((sectionId, classes) ->
                        classes.forEach(hazardClass ->
                                opens.add(new HazardSectionOpen(configId, sectionId, hazardClass))));
                configRepo.insertOpenClasses(configId, opens);
                HazardConfigView view = toView(configRepo.findById(configId)
                        .orElseThrow(() -> new IllegalStateException("配置版本写入后丢失: " + configId)),
                        normalized);
                idemRepo.insert(OP_SECTION_CONFIG, req.requestKey(), requestHash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            HazardConfigView again = replayIfPresent(req.requestKey(), requestHash);
            if (again != null) {
                return again;
            }
            throw conflict("CONFIG_VERSION_CONFLICT", "配置版本并发写入冲突");
        }
    }

    /**
     * 查询指定 UTC 时刻生效的配置版本（含规则与开放清单）；无生效版本返回 null（语义：无限制）。
     */
    public HazardConfigView getEffectiveAt(Instant atUtc) {
        return configRepo.findEffectiveAt(atUtc)
                .map(v -> toView(v, loadNormalized(v.id())))
                .orElse(null);
    }

    /**
     * 按版本号查询配置版本，不存在返回 404。
     */
    public HazardConfigView getVersion(int versionNo) {
        HazardConfigVersion version = configRepo.findByVersionNo(versionNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "HAZARD_CONFIG_NOT_FOUND",
                        "危险品配置版本不存在: " + versionNo));
        return toView(version, loadNormalized(version.id()));
    }

    // ---------- 内部实现 ----------

    private NormalizedConfig loadNormalized(long configVersionId) {
        TreeMap<String, SortedSet<String>> openBySection =
                configRepo.findOpenClassMap(configVersionId);
        SortedSet<String> registered = configRepo.findRestrictedSections(configVersionId);
        // 已登记但无开放条目的区段也须出现在视图中（空集合）
        registered.forEach(s -> openBySection.putIfAbsent(s, new TreeSet<>()));
        List<HazardSeparationRule> rules = configRepo.findSeparations(configVersionId);
        List<NormalizedPair> pairs = rules.stream()
                .map(r -> new NormalizedPair(r.classA(), r.classB(), r.minCars()))
                .toList();
        return new NormalizedConfig(pairs, openBySection);
    }

    private NormalizedConfig normalize(List<HazardSeparationRuleRequest> ruleRequests,
                                       List<SectionOpenListRequest> sectionRequests) {
        TreeSet<NormalizedPair> pairSet = new TreeSet<>();
        for (HazardSeparationRuleRequest r : ruleRequests) {
            String a = r.classA();
            String b = r.classB();
            if (a.isBlank() || b.isBlank()) {
                throw badRequest("危险品类别不能为空");
            }
            if (a.equals(b)) {
                throw badRequest("互斥类别对不能相同: " + a);
            }
            if (a.compareTo(b) > 0) {
                String t = a;
                a = b;
                b = t;
            }
            NormalizedPair pair = new NormalizedPair(a, b, r.minCars());
            NormalizedPair existing = pairSet.floor(pair);
            if (existing != null && existing.classA().equals(a) && existing.classB().equals(b)) {
                throw badRequest("互斥类别对重复: (" + a + ", " + b + ")");
            }
            pairSet.add(pair);
        }
        TreeMap<String, SortedSet<String>> openBySection = new TreeMap<>();
        for (SectionOpenListRequest s : sectionRequests) {
            SortedSet<String> classes = s.openHazardClasses() == null
                    ? new TreeSet<>() : new TreeSet<>(s.openHazardClasses());
            for (String hazardClass : classes) {
                if (hazardClass.isBlank()) {
                    throw badRequest("区段 " + s.sectionId() + " 的开放类别不能为空");
                }
            }
            if (openBySection.putIfAbsent(s.sectionId(), classes) != null) {
                throw badRequest("限制区段重复登记: " + s.sectionId());
            }
        }
        return new NormalizedConfig(List.copyOf(pairSet), openBySection);
    }

    private String hashRequest(String operator, Instant from, Instant to, NormalizedConfig config) {
        TreeMap<String, Object> root = hasher.object();
        root.put("opType", OP_SECTION_CONFIG);
        root.put("operator", operator);
        root.put("effectiveFromUtc", from.toEpochMilli());
        root.put("effectiveToUtc", to == null ? null : to.toEpochMilli());
        root.put("content", contentNode(config));
        return hasher.sha256(root);
    }

    private String hashContent(Instant from, Instant to, NormalizedConfig config) {
        TreeMap<String, Object> root = hasher.object();
        root.put("effectiveFromUtc", from.toEpochMilli());
        root.put("effectiveToUtc", to == null ? null : to.toEpochMilli());
        root.put("content", contentNode(config));
        return hasher.sha256(root);
    }

    private TreeMap<String, Object> contentNode(NormalizedConfig config) {
        TreeMap<String, Object> content = hasher.object();
        List<TreeMap<String, Object>> separations = new ArrayList<>();
        for (NormalizedPair p : config.separations()) {
            TreeMap<String, Object> node = hasher.object();
            node.put("classA", p.classA());
            node.put("classB", p.classB());
            node.put("minCars", p.minCars());
            separations.add(node);
        }
        content.put("separations", separations);
        List<TreeMap<String, Object>> sections = new ArrayList<>();
        config.openBySection().forEach((sectionId, classes) -> {
            TreeMap<String, Object> node = hasher.object();
            node.put("sectionId", sectionId);
            node.put("openHazardClasses", List.copyOf(classes));
            sections.add(node);
        });
        content.put("restrictedSections", sections);
        return content;
    }

    private HazardConfigView replayIfPresent(String requestKey, String hash) {
        return idemRepo.find(OP_SECTION_CONFIG, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson());
        }).orElse(null);
    }

    private HazardConfigView toView(HazardConfigVersion version, NormalizedConfig config) {
        List<HazardConfigView.SeparationView> separations = config.separations().stream()
                .map(p -> new HazardConfigView.SeparationView(p.classA(), p.classB(), p.minCars()))
                .toList();
        List<HazardConfigView.SectionOpenListView> sections = new ArrayList<>();
        config.openBySection().forEach((sectionId, classes) ->
                sections.add(new HazardConfigView.SectionOpenListView(sectionId, classes)));
        return new HazardConfigView(version.versionNo(), version.effectiveFrom(),
                version.effectiveTo(), version.fingerprint(), separations, sections);
    }

    private String toJson(HazardConfigView view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private HazardConfigView fromJson(String json) {
        try {
            return objectMapper.readValue(json, HazardConfigView.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private String normalizeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return PlanService.DEFAULT_OPERATOR;
        }
        return operator;
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /**
     * 归一化后的类别对，按 (classA, classB) 字典序去重与排列。
     */
    private record NormalizedPair(String classA, String classB, int minCars)
            implements Comparable<NormalizedPair> {
        @Override
        public int compareTo(NormalizedPair o) {
            int c = classA.compareTo(o.classA);
            if (c != 0) {
                return c;
            }
            return classB.compareTo(o.classB);
        }
    }

    private record NormalizedConfig(List<NormalizedPair> separations,
                                    TreeMap<String, SortedSet<String>> openBySection) {
    }
}
