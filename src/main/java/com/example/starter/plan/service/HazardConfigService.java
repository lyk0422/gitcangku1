package com.example.starter.plan.service;

import com.example.starter.plan.model.HazardRule;
import com.example.starter.plan.model.HazardRuleSet;
import com.example.starter.plan.model.SectionRestriction;
import com.example.starter.plan.repo.HazardConfigRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.HazardRuleRequest;
import com.example.starter.plan.web.dto.HazardRuleSetView;
import com.example.starter.plan.web.dto.HazardRuleView;
import com.example.starter.plan.web.dto.ReplaceHazardRulesRequest;
import com.example.starter.plan.web.dto.SectionRestrictionView;
import com.example.starter.plan.web.dto.UpdateSectionRestrictionRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 危险品互斥规则集与限制区段开放清单的版本化配置服务。
 *
 * <p>写入在全局 publish_lock 内串行化（与发布/改签互斥，按提交顺序裁决），
 * 整体替换追加新版本行，历史版本保留；配置非法（类别对冲突、类别重复）返回 422 且不落库。
 * 幂等按 (opType, requestKey)：同参重放返回首次结果，异参 409，失败不占键。
 * 指纹以规范化 JSON（排序对象键、规范化集合）构造，不使用未转义分隔符拼接。
 */
@Service
public class HazardConfigService {

    private static final String OP_RULES = "RULES";
    private static final String OP_SECTION = "SECTION";

    private final HazardConfigRepository configRepo;
    private final IdempotencyRepository idemRepo;
    private final PlanRepository planRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public HazardConfigService(HazardConfigRepository configRepo,
                               IdempotencyRepository idemRepo,
                               PlanRepository planRepo,
                               ObjectMapper objectMapper,
                               Clock clock,
                               PlatformTransactionManager txManager) {
        this.configRepo = configRepo;
        this.idemRepo = idemRepo;
        this.planRepo = planRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 整体替换互斥规则集：校验配置合法后追加新版本。
     */
    public HazardRuleSetView replaceRules(ReplaceHazardRulesRequest req) {
        HazardValidation.RulesNormalization normalization =
                HazardValidation.normalizeRules(req.rules());
        if (!normalization.conflicts().isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "HAZARD_CONFIG_INVALID",
                    "危险品互斥规则集配置无效", normalization.conflicts());
        }
        List<HazardRule> rules = normalization.rules();
        String rulesJson = writeRulesJson(rules);
        String rulesHash = sha256(rulesJson);
        Map<String, Object> fingerprintParts = new TreeMap<>();
        fingerprintParts.put("op", OP_RULES);
        fingerprintParts.put("requestKey", req.requestKey());
        fingerprintParts.put("operator", req.operator());
        fingerprintParts.put("expectedVersion", req.expectedVersion());
        fingerprintParts.put("rules", canonicalRules(req.rules()));
        String fingerprint = sha256(canonical(fingerprintParts));
        Optional<HazardRuleSetView> replay = replay(OP_RULES, req.requestKey(), fingerprint,
                HazardRuleSetView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<HazardRuleSetView> lockedReplay =
                        replay(OP_RULES, req.requestKey(), fingerprint, HazardRuleSetView.class);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                int current = configRepo.currentRuleVersion();
                if (req.expectedVersion() != current) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前规则集版本 " + current + " 不一致");
                }
                long now = clock.millis();
                int next = current + 1;
                configRepo.insertRuleVersion(next, rulesJson, rulesHash, req.operator(), now);
                HazardRuleSetView response = new HazardRuleSetView(next, toRuleViews(rules),
                        rulesHash, req.operator(), Instant.ofEpochMilli(now));
                idemRepo.insert(OP_RULES, req.requestKey(), fingerprint, writeJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replay(OP_RULES, req.requestKey(), fingerprint, HazardRuleSetView.class)
                    .orElseThrow(() -> conflict("VERSION_CONFLICT", "规则集版本并发冲突"));
        }
    }

    /**
     * 整体替换某限制区段开放清单：校验合法后追加该区段新版本。
     */
    public SectionRestrictionView replaceSectionRestriction(String sectionId,
                                                            UpdateSectionRestrictionRequest req) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        List<String> allowed = HazardValidation.normalizeAllowedClasses(req.allowedClasses(),
                conflicts);
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "HAZARD_CONFIG_INVALID",
                    "限制区段开放清单配置无效", conflicts);
        }
        String allowedJson = writeJson(allowed);
        Map<String, Object> fingerprintParts = new TreeMap<>();
        fingerprintParts.put("op", OP_SECTION);
        fingerprintParts.put("requestKey", req.requestKey());
        fingerprintParts.put("sectionId", sectionId);
        fingerprintParts.put("operator", req.operator());
        fingerprintParts.put("expectedVersion", req.expectedVersion());
        fingerprintParts.put("allowedClasses", req.allowedClasses().stream().sorted().toList());
        String fingerprint = sha256(canonical(fingerprintParts));
        Optional<SectionRestrictionView> replay = replay(OP_SECTION, req.requestKey(), fingerprint,
                SectionRestrictionView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<SectionRestrictionView> lockedReplay =
                        replay(OP_SECTION, req.requestKey(), fingerprint, SectionRestrictionView.class);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                int current = configRepo.currentSectionVersion(sectionId);
                if (req.expectedVersion() != current) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与区段 " + sectionId + " 当前版本 " + current + " 不一致");
                }
                long now = clock.millis();
                int next = current + 1;
                configRepo.insertSectionVersion(sectionId, next, allowedJson, req.operator(), now);
                SectionRestrictionView response = new SectionRestrictionView(sectionId, next,
                        allowed, req.operator(), Instant.ofEpochMilli(now));
                idemRepo.insert(OP_SECTION, req.requestKey(), fingerprint, writeJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replay(OP_SECTION, req.requestKey(), fingerprint, SectionRestrictionView.class)
                    .orElseThrow(() -> conflict("VERSION_CONFLICT", "区段配置版本并发冲突"));
        }
    }

    /**
     * 查询当前规则集（无配置时 version=0、空列表、hash/operator/createdAt 为 null）。只读，不改状态。
     */
    public HazardRuleSetView getCurrentRules() {
        int current = configRepo.currentRuleVersion();
        if (current == 0) {
            return new HazardRuleSetView(0, List.of(), null, null, null);
        }
        HazardConfigRepository.RuleVersionRow row = configRepo.findRuleVersion(current).orElseThrow();
        return new HazardRuleSetView(current, toRuleViews(readRules(row.rulesJson())),
                row.rulesHash(), row.operator(), Instant.ofEpochMilli(row.createdAt()));
    }

    /**
     * 查询区段当前开放清单；未配置 version=0、allowedClasses=null（不限制）。只读。
     */
    public SectionRestrictionView getCurrentSection(String sectionId) {
        int current = configRepo.currentSectionVersion(sectionId);
        if (current == 0) {
            return new SectionRestrictionView(sectionId, 0, null, null, null);
        }
        HazardConfigRepository.SectionRow row =
                configRepo.findSectionVersion(sectionId, current).orElseThrow();
        return new SectionRestrictionView(sectionId, current, readClasses(row.allowedClassesJson()),
                row.operator(), Instant.ofEpochMilli(row.createdAt()));
    }

    // ---------- 供 PlanService 发布校验使用的内部读取 ----------

    /**
     * 读取当前规则集模型（无配置返回版本 0、空规则、空列表哈希）。
     */
    public HazardRuleSet loadCurrentRuleSet() {
        int current = configRepo.currentRuleVersion();
        if (current == 0) {
            return new HazardRuleSet(0, List.of(), sha256("[]"), "system",
                    clock.millis());
        }
        HazardConfigRepository.RuleVersionRow row = configRepo.findRuleVersion(current).orElseThrow();
        return row.toModel(readRules(row.rulesJson()));
    }

    /**
     * 读取区段当前版本模型；未配置返回空（调用方据此视为不限制，不进入快照）。
     */
    public Optional<SectionRestriction> loadCurrentSection(String sectionId) {
        int current = configRepo.currentSectionVersion(sectionId);
        if (current == 0) {
            return Optional.empty();
        }
        HazardConfigRepository.SectionRow row =
                configRepo.findSectionVersion(sectionId, current).orElseThrow();
        return Optional.of(row.toModel(readClasses(row.allowedClassesJson())));
    }

    // ---------- JSON / 指纹 ----------

    private List<HazardRuleView> toRuleViews(List<HazardRule> rules) {
        List<HazardRuleView> views = new ArrayList<>(rules.size());
        for (HazardRule r : rules) {
            views.add(new HazardRuleView(r.classA(), r.classB(), r.minSeparationCars()));
        }
        return views;
    }

    private List<HazardRule> readRules(String json) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, new TypeReference<>() {
            });
            List<HazardRule> rules = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                rules.add(new HazardRule((String) m.get("classA"), (String) m.get("classB"),
                        ((Number) m.get("minSeparationCars")).intValue()));
            }
            return rules;
        } catch (Exception e) {
            throw new IllegalStateException("规则集反序列化失败", e);
        }
    }

    private List<String> readClasses(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("开放清单反序列化失败", e);
        }
    }

    private String writeRulesJson(List<HazardRule> rules) {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (HazardRule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("classA", r.classA());
            m.put("classB", r.classB());
            m.put("minSeparationCars", r.minSeparationCars());
            raw.add(m);
        }
        return writeJson(raw);
    }

    private List<Map<String, Object>> canonicalRules(List<HazardRuleRequest> rules) {
        return HazardValidation.normalizeRules(rules).rules().stream()
                .<Map<String, Object>>map(r -> {
                    Map<String, Object> m = new TreeMap<>();
                    m.put("classA", r.classA());
                    m.put("classB", r.classB());
                    m.put("minSeparationCars", r.minSeparationCars());
                    return m;
                }).toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /**
     * 规范化 JSON：递归排序对象键；列表保持调用方给定顺序（集合需先排序去重）。
     */
    private String canonical(Object value) {
        return writeJson(normalize(value));
    }

    private Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), normalize(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::normalize).toList();
        }
        return value;
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> Optional<T> replay(String opType, String requestKey, String fingerprint,
                                   Class<T> type) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(fingerprint)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            try {
                return objectMapper.readValue(record.responseJson(), type);
            } catch (Exception e) {
                throw new IllegalStateException("幂等响应反序列化失败", e);
            }
        });
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
