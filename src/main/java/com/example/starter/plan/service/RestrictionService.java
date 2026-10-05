package com.example.starter.plan.service;

import com.example.starter.plan.model.RestrictionStatus;
import com.example.starter.plan.model.Section;
import com.example.starter.plan.model.SpeedRestriction;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.RestrictionRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.EffectiveSpeedView;
import com.example.starter.plan.web.dto.RegisterRestrictionRequest;
import com.example.starter.plan.web.dto.RestrictionResponse;
import com.example.starter.plan.web.dto.ReviseRestrictionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 气象限速令业务：登记、修订、撤销与查询诊断。
 *
 * <p>限速令按 (restrictionKey, version) 唯一；登记为版本 1，修订产生新版本并取代旧版本，
 * 撤销仅置状态，历史版本保留不改写。登记/修订/撤销与发布/改签共用全局发布锁，
 * 按事务提交顺序裁决。写操作按 (操作类型, requestKey) 幂等，指纹含参数与操作者，
 * 同键同参重放首次结果，异参 409，失败不占键。
 */
@Service
public class RestrictionService {

    /** 限速速度合法下限（km/h）。 */
    public static final int MIN_SPEED_KMH = 10;
    /** 限速速度合法上限（km/h）。 */
    public static final int MAX_SPEED_KMH = 300;

    private static final String OP_REGISTER = "RESTRICTION_REGISTER";
    private static final String OP_REVISE = "RESTRICTION_REVISE";
    private static final String OP_REVOKE = "RESTRICTION_REVOKE";

    private final RestrictionRepository restrictionRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public RestrictionService(RestrictionRepository restrictionRepo, PlanRepository planRepo,
                              IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                              Clock clock, PlatformTransactionManager txManager) {
        this.restrictionRepo = restrictionRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 登记限速令（版本 1，状态 ACTIVE）。区段未注册、时段非法或速度越界返回 422。
     */
    public RestrictionResponse register(RegisterRestrictionRequest req) {
        validateWindow(req.startUtc(), req.endUtc());
        validateSpeed(req.maxSpeedKmh());
        String hash = sha256(OP_REGISTER + '\n' + req.restrictionKey() + '\n' + req.sectionId()
                + '\n' + req.startUtc().toEpochMilli() + '\n' + req.endUtc().toEpochMilli()
                + '\n' + req.maxSpeedKmh() + '\n' + req.operator());
        Optional<RestrictionResponse> replay = replayIfPresent(OP_REGISTER, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                if (restrictionRepo.findSection(req.sectionId()).isEmpty()) {
                    throw invalidRestriction("区段未注册: " + req.sectionId(),
                            Map.of("sectionId", req.sectionId()));
                }
                if (restrictionRepo.findLatestByKey(req.restrictionKey()).isPresent()) {
                    throw conflict("RESTRICTION_KEY_EXISTS", "restrictionKey 已存在: "
                            + req.restrictionKey());
                }
                long now = clock.millis();
                SpeedRestriction restriction = new SpeedRestriction(0L, req.restrictionKey(), 1,
                        req.sectionId(), req.startUtc(), req.endUtc(), req.maxSpeedKmh(),
                        RestrictionStatus.ACTIVE, req.operator(), now);
                long id = restrictionRepo.insertRestriction(restriction);
                RestrictionResponse response = toResponse(
                        restrictionRepo.findVersionsByKey(req.restrictionKey()).get(0));
                idemRepo.insert(OP_REGISTER, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_REGISTER, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_KEY_EXISTS",
                            "restrictionKey 已存在: " + req.restrictionKey()));
        }
    }

    /**
     * 修订限速令：旧版本置 SUPERSEDED，产生版本加一的新 ACTIVE 版本。
     * 限速令不存在返回 404；已撤销不可修订返回 409。
     */
    public RestrictionResponse revise(String restrictionKey, ReviseRestrictionRequest req) {
        validateWindow(req.startUtc(), req.endUtc());
        validateSpeed(req.maxSpeedKmh());
        String hash = sha256(OP_REVISE + '\n' + restrictionKey + '\n'
                + req.startUtc().toEpochMilli() + '\n' + req.endUtc().toEpochMilli()
                + '\n' + req.maxSpeedKmh() + '\n' + req.operator());
        Optional<RestrictionResponse> replay = replayIfPresent(OP_REVISE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                SpeedRestriction latest = restrictionRepo.findLatestByKey(restrictionKey)
                        .orElseThrow(() -> notFound(restrictionKey));
                if (latest.status() != RestrictionStatus.ACTIVE) {
                    throw conflict("RESTRICTION_STATE_CONFLICT",
                            "仅生效中的限速令可修订，当前状态: " + latest.status());
                }
                long now = clock.millis();
                restrictionRepo.updateStatus(restrictionKey, latest.version(),
                        RestrictionStatus.SUPERSEDED);
                SpeedRestriction revised = new SpeedRestriction(0L, restrictionKey,
                        latest.version() + 1, latest.sectionId(), req.startUtc(), req.endUtc(),
                        req.maxSpeedKmh(), RestrictionStatus.ACTIVE, req.operator(), now);
                restrictionRepo.insertRestriction(revised);
                RestrictionResponse response = toResponse(
                        restrictionRepo.findLatestByKey(restrictionKey).orElseThrow());
                idemRepo.insert(OP_REVISE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_REVISE, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_STATE_CONFLICT", "限速令版本冲突"));
        }
    }

    /**
     * 撤销限速令：最新版本置 REVOKED，历史版本保留。不存在返回 404；非生效状态返回 409。
     */
    public RestrictionResponse revoke(String restrictionKey, String requestKey, String operator) {
        String hash = sha256(OP_REVOKE + '\n' + restrictionKey + '\n' + operator);
        Optional<RestrictionResponse> replay = replayIfPresent(OP_REVOKE, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        return tx.execute(status -> {
            planRepo.acquirePublishLock();
            SpeedRestriction latest = restrictionRepo.findLatestByKey(restrictionKey)
                    .orElseThrow(() -> notFound(restrictionKey));
            if (latest.status() != RestrictionStatus.ACTIVE) {
                throw conflict("RESTRICTION_STATE_CONFLICT",
                        "仅生效中的限速令可撤销，当前状态: " + latest.status());
            }
            long now = clock.millis();
            restrictionRepo.updateStatus(restrictionKey, latest.version(), RestrictionStatus.REVOKED);
            RestrictionResponse response = toResponse(
                    restrictionRepo.findLatestByKey(restrictionKey).orElseThrow());
            idemRepo.insert(OP_REVOKE, requestKey, hash, toJson(response), now);
            return response;
        });
    }

    /**
     * 查询限速令最新版本，不存在返回 404。
     */
    public RestrictionResponse getRestriction(String restrictionKey) {
        return toResponse(restrictionRepo.findLatestByKey(restrictionKey)
                .orElseThrow(() -> notFound(restrictionKey)));
    }

    /**
     * 查询限速令全部版本历史（含 SUPERSEDED / REVOKED），按版本升序，不存在返回 404。
     */
    public List<RestrictionResponse> getHistory(String restrictionKey) {
        List<SpeedRestriction> versions = restrictionRepo.findVersionsByKey(restrictionKey);
        if (versions.isEmpty()) {
            throw notFound(restrictionKey);
        }
        return versions.stream().map(this::toResponse).toList();
    }

    /**
     * 查询指定区段上的全部限速令版本（历史证据），按业务键与版本升序。
     */
    public List<RestrictionResponse> listBySection(String sectionId) {
        return restrictionRepo.findAllBySection(sectionId).stream()
                .map(this::toResponse).toList();
    }

    /**
     * 诊断：区段在指定时刻的生效限速（多条叠加取最低值）；无生效限速时 effectiveSpeedKmh 为 null。
     * 区段未注册返回 404。
     */
    public EffectiveSpeedView getEffectiveSpeed(String sectionId, Instant at) {
        if (restrictionRepo.findSection(sectionId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SECTION_NOT_FOUND",
                    "区段未注册: " + sectionId);
        }
        List<SpeedRestriction> active = restrictionRepo
                .findActiveBySections(List.of(sectionId)).stream()
                .filter(r -> !at.isBefore(r.startUtc()) && at.isBefore(r.endUtc()))
                .toList();
        Integer effective = active.stream().map(SpeedRestriction::maxSpeedKmh)
                .min(Integer::compareTo).orElse(null);
        return new EffectiveSpeedView(sectionId, at, effective,
                active.stream().map(this::toResponse).toList());
    }

    // ---------- 供计划发布/改签使用的内部查询 ----------

    /**
     * 按区段集合查询注册区段，返回 sectionId → Section 映射。
     */
    public Map<String, Section> loadSections(java.util.Collection<String> sectionIds) {
        Map<String, Section> result = new LinkedHashMap<>();
        for (Section s : restrictionRepo.findSections(sectionIds)) {
            result.put(s.sectionId(), s);
        }
        return result;
    }

    /**
     * 按区段集合查询生效限速令，返回 sectionId → 限速列表映射。
     */
    public Map<String, List<SpeedRestriction>> loadActiveRestrictions(
            java.util.Collection<String> sectionIds) {
        Map<String, List<SpeedRestriction>> result = new LinkedHashMap<>();
        for (SpeedRestriction r : restrictionRepo.findActiveBySections(sectionIds)) {
            result.computeIfAbsent(r.sectionId(), k -> new java.util.ArrayList<>()).add(r);
        }
        return result;
    }

    /**
     * 生效限速版本快照指纹：参与发布/改签幂等指纹，限速版本变化即异参。
     */
    public String restrictionSnapshot(Map<String, List<SpeedRestriction>> activeBySection) {
        StringBuilder sb = new StringBuilder();
        activeBySection.values().stream()
                .flatMap(List::stream)
                .sorted(java.util.Comparator.comparing(SpeedRestriction::restrictionKey)
                        .thenComparing(SpeedRestriction::version))
                .forEach(r -> sb.append('\n').append(r.restrictionKey()).append('#')
                        .append(r.version()).append('#').append(r.maxSpeedKmh()).append('#')
                        .append(r.startUtc().toEpochMilli()).append('#')
                        .append(r.endUtc().toEpochMilli()));
        return sb.toString();
    }

    // ---------- 内部实现 ----------

    private void validateWindow(Instant startUtc, Instant endUtc) {
        if (!endUtc.isAfter(startUtc)) {
            throw invalidRestriction("限速时段非法：结束必须晚于开始",
                    Map.of("startUtc", startUtc.toString(), "endUtc", endUtc.toString()));
        }
    }

    private void validateSpeed(int maxSpeedKmh) {
        if (maxSpeedKmh < MIN_SPEED_KMH || maxSpeedKmh > MAX_SPEED_KMH) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("actualMaxSpeedKmh", maxSpeedKmh);
            detail.put("requiredMinKmh", MIN_SPEED_KMH);
            detail.put("requiredMaxKmh", MAX_SPEED_KMH);
            throw invalidRestriction("限速速度越界: " + maxSpeedKmh + " km/h", detail);
        }
    }

    private RestrictionResponse toResponse(SpeedRestriction r) {
        return new RestrictionResponse(r.restrictionKey(), r.version(), r.sectionId(),
                r.startUtc(), r.endUtc(), r.maxSpeedKmh(), r.status().name(), r.operator(),
                Instant.ofEpochMilli(r.createdAt()));
    }

    private Optional<RestrictionResponse> replayIfPresent(String opType, String requestKey,
                                                          String hash) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED", "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson());
        });
    }

    private ApiException invalidRestriction(String message, Map<String, Object> detail) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        enriched.put("type", "RESTRICTION_INVALID");
        enriched.putAll(detail);
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESTRICTION_INVALID",
                message, List.of(enriched));
    }

    private ApiException notFound(String restrictionKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "RESTRICTION_NOT_FOUND",
                "限速令不存在: " + restrictionKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private RestrictionResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, RestrictionResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
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
}
