package com.example.starter.plan.service;

import com.example.starter.plan.model.CrewRole;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.Qualification;
import com.example.starter.plan.model.RiskRecord;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.QualificationRepository;
import com.example.starter.plan.repo.RiskRecordRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CrewGapDiagnosisResponse;
import com.example.starter.plan.web.dto.CrewGapView;
import com.example.starter.plan.web.dto.CrewReplacementRequest;
import com.example.starter.plan.web.dto.PlanCrewQualificationResponse;
import com.example.starter.plan.web.dto.PlanCrewRoleView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.QualificationResponse;
import com.example.starter.plan.web.dto.RegisterQualificationRequest;
import com.example.starter.plan.web.dto.RiskRecordListResponse;
import com.example.starter.plan.web.dto.RiskRecordView;
import com.example.starter.plan.web.dto.TerminateQualificationRequest;
import com.example.starter.plan.web.dto.TerminateQualificationResponse;
import com.example.starter.plan.web.dto.UpdateQualificationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
 * 乘务资质门禁核心：资质登记/修改/提前终止、风险回查、风险计划换人与缺口诊断。
 *
 * <p>资质合格判定：乘务员存在未提前终止的资质，其覆盖区段集合（换序同参）覆盖计划全部区段，
 * 且到期时刻严格晚于计划终到时刻。提前终止在同一事务内回查所有未来已发布计划
 * （终到时刻晚于当前时刻）并写入不可变风险记录、置风险门禁，任一回查失败整次回滚。
 */
@Service
public class CrewService {

    /** 风险原因：资质被提前终止。 */
    public static final String REASON_QUALIFICATION_TERMINATED = "QUALIFICATION_TERMINATED";

    private static final String OP_CREW_REGISTER = "CREW_REGISTER";
    private static final String OP_CREW_UPDATE = "CREW_UPDATE";
    private static final String OP_CREW_TERMINATE = "CREW_TERMINATE";
    private static final String OP_CREW_REPLACE = "CREW_REPLACE";

    private final PlanRepository planRepo;
    private final QualificationRepository qualRepo;
    private final RiskRecordRepository riskRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TimeSource timeSource;
    private final TransactionTemplate tx;

    public CrewService(PlanRepository planRepo, QualificationRepository qualRepo,
                       RiskRecordRepository riskRepo, IdempotencyRepository idemRepo,
                       ObjectMapper objectMapper, TimeSource timeSource,
                       PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.qualRepo = qualRepo;
        this.riskRepo = riskRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.timeSource = timeSource;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 登记乘务员资质（版本 1）。同一乘务员同一资质代码重复登记返回 409。
     */
    public QualificationResponse register(RegisterQualificationRequest req) {
        String hash = hashQualification(OP_CREW_REGISTER, req.crewId(), req.qualificationCode(),
                0, req.sections(), req.expiresAtUtc());
        Optional<QualificationResponse> replay =
                replayIfPresent(OP_CREW_REGISTER, req.requestKey(), hash, QualificationResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (qualRepo.find(req.crewId(), req.qualificationCode()).isPresent()) {
                    throw conflict("QUALIFICATION_EXISTS",
                            "资质已存在: " + req.crewId() + "/" + req.qualificationCode());
                }
                long now = timeSource.now().toEpochMilli();
                qualRepo.insert(req.crewId(), req.qualificationCode(), req.expiresAtUtc(), 1, now);
                qualRepo.insertSections(req.crewId(), req.qualificationCode(),
                        normalizeSections(req.sections()));
                QualificationResponse response = loadQualification(req.crewId(),
                        req.qualificationCode());
                idemRepo.insert(OP_CREW_REGISTER, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CREW_REGISTER, req.requestKey(), hash,
                    QualificationResponse.class)
                    .orElseThrow(() -> conflict("QUALIFICATION_EXISTS",
                            "资质已存在: " + req.crewId() + "/" + req.qualificationCode()));
        }
    }

    /**
     * 修改资质：整体替换覆盖区段与到期时刻，版本加一；已终止资质不可再修改。
     */
    public QualificationResponse update(String crewId, String qualificationCode,
                                        UpdateQualificationRequest req) {
        String hash = hashQualification(OP_CREW_UPDATE, crewId, qualificationCode,
                req.expectedVersion(), req.sections(), req.expiresAtUtc());
        Optional<QualificationResponse> replay =
                replayIfPresent(OP_CREW_UPDATE, req.requestKey(), hash, QualificationResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                Qualification qual = qualRepo.findForUpdate(crewId, qualificationCode)
                        .orElseThrow(() -> qualNotFound(crewId, qualificationCode));
                if (qual.terminated()) {
                    throw conflict("QUALIFICATION_STATE_CONFLICT",
                            "资质已提前终止，不可修改: " + crewId + "/" + qualificationCode);
                }
                if (req.expectedVersion() != qual.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + qual.version() + " 不一致");
                }
                long now = timeSource.now().toEpochMilli();
                qualRepo.updateQualification(crewId, qualificationCode, req.expiresAtUtc(),
                        qual.version() + 1, now);
                qualRepo.replaceSections(crewId, qualificationCode,
                        normalizeSections(req.sections()));
                QualificationResponse response = loadQualification(crewId, qualificationCode);
                idemRepo.insert(OP_CREW_UPDATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CREW_UPDATE, req.requestKey(), hash,
                    QualificationResponse.class)
                    .orElseThrow(() -> conflict("QUALIFICATION_EXISTS", "资质区段写入冲突"));
        }
    }

    /**
     * 提前终止资质：全局发布锁内回查所有未来已发布计划（终到时刻晚于当前时刻），
     * 对每个命中计划写入不可变风险记录并置风险门禁；任一回查失败整次回滚。
     */
    public TerminateQualificationResponse terminate(String crewId, String qualificationCode,
                                                    TerminateQualificationRequest req) {
        String hash = hashQualification(OP_CREW_TERMINATE, crewId, qualificationCode,
                req.expectedVersion(), List.of(), null);
        Optional<TerminateQualificationResponse> replay = replayIfPresent(OP_CREW_TERMINATE,
                req.requestKey(), hash, TerminateQualificationResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Qualification qual = qualRepo.findForUpdate(crewId, qualificationCode)
                        .orElseThrow(() -> qualNotFound(crewId, qualificationCode));
                if (qual.terminated()) {
                    throw conflict("QUALIFICATION_STATE_CONFLICT",
                            "资质已提前终止: " + crewId + "/" + qualificationCode);
                }
                if (req.expectedVersion() != qual.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + qual.version() + " 不一致");
                }
                long now = timeSource.now().toEpochMilli();
                List<DayPlan> affected =
                        planRepo.findFuturePublishedPlansByCrewForUpdate(crewId, now);
                List<String> affectedKeys = new ArrayList<>();
                for (DayPlan plan : affected) {
                    if (crewId.equals(plan.driverId())) {
                        riskRepo.insert(plan.id(), crewId, CrewRole.DRIVER, qualificationCode,
                                REASON_QUALIFICATION_TERMINATED, now);
                    }
                    if (crewId.equals(plan.conductorId())) {
                        riskRepo.insert(plan.id(), crewId, CrewRole.CONDUCTOR, qualificationCode,
                                REASON_QUALIFICATION_TERMINATED, now);
                    }
                    planRepo.markRiskBlocked(plan.id(), now);
                    affectedKeys.add(plan.scheduleKey());
                }
                qualRepo.markTerminated(crewId, qualificationCode, qual.version() + 1, now);
                TerminateQualificationResponse response = new TerminateQualificationResponse(
                        loadQualification(crewId, qualificationCode), affectedKeys);
                idemRepo.insert(OP_CREW_TERMINATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CREW_TERMINATE, req.requestKey(), hash,
                    TerminateQualificationResponse.class)
                    .orElseThrow(() -> conflict("RISK_SCAN_CONFLICT",
                            "风险回查写入冲突，本次终止已整体回滚"));
        }
    }

    /**
     * 风险计划换人：两角色一次性替换为合格人员并解除风险门禁。
     * 仅风险门禁状态的已发布计划可换人；新乘务须通过完整资质校验。
     */
    public PlanResponse replaceCrew(String scheduleKey, CrewReplacementRequest req) {
        validateCrewPairParams(req.driverId(), req.conductorId());
        String hash = sha256(OP_CREW_REPLACE + '\n' + scheduleKey + '\n' + req.expectedVersion()
                + '\n' + req.driverId() + '\n' + req.conductorId());
        Optional<PlanResponse> replay =
                replayIfPresent(OP_CREW_REPLACE, req.requestKey(), hash, PlanResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> planNotFound(scheduleKey));
                Optional<PlanResponse> txReplay =
                        replayIfPresent(OP_CREW_REPLACE, req.requestKey(), hash, PlanResponse.class);
                if (txReplay.isPresent()) {
                    return txReplay.get();
                }
                if (plan.status() != PlanStatus.PUBLISHED || !plan.riskBlocked()) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅风险门禁状态的已发布计划可换人，当前状态: " + plan.status()
                                    + ", riskBlocked=" + plan.riskBlocked());
                }
                if (req.expectedVersion() != plan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + plan.version() + " 不一致");
                }
                validateCrewOrThrow(plan.id(), req.driverId(), req.conductorId());
                long now = timeSource.now().toEpochMilli();
                planRepo.updateCrewAndClearRisk(plan.id(), req.driverId(), req.conductorId(), now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_CREW_REPLACE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CREW_REPLACE, req.requestKey(), hash, PlanResponse.class)
                    .orElseThrow(() -> conflict("PLAN_STATE_CONFLICT", "换人写入冲突"));
        }
    }

    /**
     * 查询计划乘务资质：两角色指派、各自资质清单与当前合格性。
     */
    public PlanCrewQualificationResponse getPlanCrewQualification(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> planNotFound(scheduleKey));
        List<String> sections = planSections(plan.id());
        Instant planEnd = planEndUtc(plan.id());
        List<PlanCrewRoleView> roles = new ArrayList<>();
        roles.add(roleView(CrewRole.DRIVER, plan.driverId(), sections, planEnd));
        roles.add(roleView(CrewRole.CONDUCTOR, plan.conductorId(), sections, planEnd));
        return new PlanCrewQualificationResponse(plan.scheduleKey(), plan.status().name(),
                plan.riskBlocked(), roles);
    }

    /**
     * 缺口诊断：稳定列出两角色的资质缺口；无缺口返回空列表。
     */
    public CrewGapDiagnosisResponse getCrewGaps(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> planNotFound(scheduleKey));
        List<CrewGapView> gaps = evaluateGaps(plan.driverId(), plan.conductorId(),
                planSections(plan.id()), planEndUtc(plan.id()));
        return new CrewGapDiagnosisResponse(scheduleKey, gaps);
    }

    /**
     * 查询计划的不可变风险记录。
     */
    public RiskRecordListResponse getRiskRecords(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> planNotFound(scheduleKey));
        List<RiskRecordView> records = riskRepo.findByPlanId(plan.id()).stream()
                .map(r -> new RiskRecordView(r.crewId(), r.role().name(), r.qualificationCode(),
                        r.reason(), r.createdAtUtc()))
                .toList();
        return new RiskRecordListResponse(scheduleKey, plan.riskBlocked(), records);
    }

    // ---------- 供计划发布/改签复用的乘务校验 ----------

    /**
     * 乘务指派参数校验：司机与车长须同时指定或同时缺省，且不得为同一人。
     */
    public void validateCrewPairParams(String driverId, String conductorId) {
        if ((driverId == null) != (conductorId == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                    "司机与车长必须同时指定或同时缺省");
        }
        if (driverId != null && driverId.equals(conductorId)) {
            throw crewGapException(List.of(
                    new CrewGapView("BOTH", driverId, "SAME_PERSON", List.of())));
        }
    }

    /**
     * 完整资质校验：任一角色存在缺口抛出 422 并稳定列出角色与缺口。
     */
    public void validateCrewOrThrow(long planId, String driverId, String conductorId) {
        validateCrewPairParams(driverId, conductorId);
        if (driverId == null) {
            return;
        }
        List<CrewGapView> gaps = evaluateGaps(driverId, conductorId,
                planSections(planId), planEndUtc(planId));
        if (!gaps.isEmpty()) {
            throw crewGapException(gaps);
        }
    }

    /**
     * 评估两角色资质缺口（稳定顺序：先司机后车长）。
     */
    public List<CrewGapView> evaluateGaps(String driverId, String conductorId,
                                          Collection<String> planSections, Instant planEndUtc) {
        List<CrewGapView> gaps = new ArrayList<>();
        if (driverId != null && driverId.equals(conductorId)) {
            gaps.add(new CrewGapView("BOTH", driverId, "SAME_PERSON", List.of()));
            return gaps;
        }
        evaluateRole(CrewRole.DRIVER, driverId, planSections, planEndUtc).ifPresent(gaps::add);
        evaluateRole(CrewRole.CONDUCTOR, conductorId, planSections, planEndUtc)
                .ifPresent(gaps::add);
        return gaps;
    }

    // ---------- 内部实现 ----------

    private Optional<CrewGapView> evaluateRole(CrewRole role, String crewId,
                                               Collection<String> planSections,
                                               Instant planEndUtc) {
        if (crewId == null) {
            return Optional.empty();
        }
        List<String> required = normalizeSections(planSections);
        List<Qualification> active = qualRepo.findByCrewId(crewId).stream()
                .filter(q -> !q.terminated())
                .toList();
        if (active.isEmpty()) {
            return Optional.of(new CrewGapView(role.name(), crewId, "QUALIFICATION_MISSING",
                    required));
        }
        // 预载每条有效资质的覆盖区段，避免逐区段重复查询
        Map<Qualification, java.util.Set<String>> coverage = new LinkedHashMap<>();
        for (Qualification q : active) {
            coverage.put(q, new java.util.HashSet<>(
                    qualRepo.findSections(q.crewId(), q.qualificationCode())));
        }
        List<String> uncovered = required.stream()
                .filter(section -> coverage.values().stream().noneMatch(s -> s.contains(section)))
                .toList();
        if (!uncovered.isEmpty()) {
            return Optional.of(new CrewGapView(role.name(), crewId, "SECTION_COVERAGE",
                    uncovered));
        }
        List<String> expired = required.stream()
                .filter(section -> coverage.entrySet().stream()
                        .filter(e -> e.getValue().contains(section))
                        .map(Map.Entry::getKey)
                        .noneMatch(q -> q.expiresAtUtc().isAfter(planEndUtc)))
                .toList();
        if (!expired.isEmpty()) {
            return Optional.of(new CrewGapView(role.name(), crewId, "EXPIRED", expired));
        }
        return Optional.empty();
    }

    private PlanCrewRoleView roleView(CrewRole role, String crewId, List<String> sections,
                                      Instant planEndUtc) {
        if (crewId == null) {
            return new PlanCrewRoleView(role.name(), null, false, List.of());
        }
        List<QualificationResponse> quals = qualRepo.findByCrewId(crewId).stream()
                .map(q -> new QualificationResponse(q.crewId(), q.qualificationCode(),
                        qualRepo.findSections(q.crewId(), q.qualificationCode()),
                        q.expiresAtUtc(), q.terminated(), q.version()))
                .toList();
        boolean qualified = evaluateRole(role, crewId, sections, planEndUtc).isEmpty();
        return new PlanCrewRoleView(role.name(), crewId, qualified, quals);
    }

    private QualificationResponse loadQualification(String crewId, String qualificationCode) {
        Qualification qual = qualRepo.find(crewId, qualificationCode)
                .orElseThrow(() -> qualNotFound(crewId, qualificationCode));
        return new QualificationResponse(qual.crewId(), qual.qualificationCode(),
                qualRepo.findSections(crewId, qualificationCode), qual.expiresAtUtc(),
                qual.terminated(), qual.version());
    }

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> planNotFound(scheduleKey));
        List<com.example.starter.plan.web.dto.OccupancyView> views =
                planRepo.findOccupancies(plan.id()).stream()
                        .map(o -> new com.example.starter.plan.web.dto.OccupancyView(o.trainNo(),
                                o.sectionId(), o.startUtc(), o.endUtc()))
                        .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), plan.driverId(), plan.conductorId(), plan.riskBlocked(),
                views);
    }

    private List<String> planSections(long planId) {
        return planRepo.findOccupancies(planId).stream()
                .map(o -> o.sectionId())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
    }

    private Instant planEndUtc(long planId) {
        return planRepo.findOccupancies(planId).stream()
                .map(o -> o.endUtc())
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);
    }

    private List<String> normalizeSections(Collection<String> sections) {
        return List.copyOf(new TreeSet<>(sections));
    }

    private ApiException crewGapException(List<CrewGapView> gaps) {
        List<Map<String, Object>> details = gaps.stream()
                .map(g -> {
                    Map<String, Object> detail = new LinkedHashMap<String, Object>();
                    detail.put("type", "CREW_QUALIFICATION_GAP");
                    detail.put("role", g.role());
                    detail.put("crewId", g.crewId());
                    detail.put("gapType", g.gapType());
                    detail.put("missingSections", g.missingSections());
                    return detail;
                })
                .toList();
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CREW_QUALIFICATION_GAP",
                "乘务资质不完整，发布/改签未生效", details);
    }

    private String hashQualification(String opType, String crewId, String qualificationCode,
                                     int expectedVersion, List<String> sections,
                                     Instant expiresAtUtc) {
        StringBuilder sb = new StringBuilder(opType).append('\n')
                .append(crewId).append('\n').append(qualificationCode).append('\n')
                .append(expectedVersion).append('\n')
                .append(expiresAtUtc == null ? "" : expiresAtUtc.toEpochMilli());
        for (String section : normalizeSections(sections)) {
            sb.append('\n').append(section);
        }
        return sha256(sb.toString());
    }

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

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private ApiException planNotFound(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                "计划不存在: " + scheduleKey);
    }

    private ApiException qualNotFound(String crewId, String qualificationCode) {
        return new ApiException(HttpStatus.NOT_FOUND, "QUALIFICATION_NOT_FOUND",
                "资质不存在: " + crewId + "/" + qualificationCode);
    }
}
