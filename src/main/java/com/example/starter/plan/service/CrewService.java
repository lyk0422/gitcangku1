package com.example.starter.plan.service;

import com.example.starter.plan.model.CrewQualification;
import com.example.starter.plan.model.CrewRiskRecord;
import com.example.starter.plan.model.CrewRole;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanCrew;
import com.example.starter.plan.repo.CrewRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreateQualificationRequest;
import com.example.starter.plan.web.dto.CrewAssignmentRequest;
import com.example.starter.plan.web.dto.CrewRiskRecordView;
import com.example.starter.plan.web.dto.PlanCrewView;
import com.example.starter.plan.web.dto.QualificationView;
import com.example.starter.plan.web.dto.TerminateQualificationRequest;
import com.example.starter.plan.web.dto.UpdateQualificationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
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
 * 乘务资质核心业务：资质创建/修改/提前终止、发布与改签的资质门禁校验、
 * 风险记录回查与乘务/风险/缺口查询。
 *
 * <p>并发与幂等约定：资质写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；资质修改与提前终止经资质行锁串行化，提前终止在全局发布锁内回查未来已发布计划，
 * 任一回查失败整次回滚；仅成功结果写入幂等记录，失败不缓存、可修正后重试。
 */
@Service
public class CrewService {

    /** 风险原因：资质提前终止。 */
    public static final String REASON_QUAL_TERMINATED = "QUAL_TERMINATED";

    private static final String OP_QUAL_UPSERT = "QUAL_UPSERT";
    private static final String OP_QUAL_TERMINATE = "QUAL_TERMINATE";

    private final CrewRepository crewRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public CrewService(CrewRepository crewRepo, PlanRepository planRepo,
                       IdempotencyRepository idemRepo,
                       ObjectMapper objectMapper, Clock clock,
                       PlatformTransactionManager txManager) {
        this.crewRepo = crewRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建乘务资质（版本 1，未终止）。覆盖区段集合规范化（排序去重），换序视为同参。
     */
    public QualificationView createQualification(CreateQualificationRequest req) {
        List<String> sections = normalizeSections(req.sections());
        String hash = hashQual(OP_QUAL_UPSERT, req.qualCode(), null, req.crewId(), sections,
                req.expiresUtc(), null);
        Optional<QualificationView> replay = replayIfPresent(OP_QUAL_UPSERT, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (crewRepo.findQualByCode(req.qualCode()).isPresent()) {
                    throw conflict("QUAL_CODE_EXISTS", "qualCode 已存在: " + req.qualCode());
                }
                long now = clock.millis();
                crewRepo.insertQualification(req.qualCode(), req.crewId(), sections,
                        req.expiresUtc(), now);
                QualificationView view = loadQualification(req.qualCode());
                idemRepo.insert(OP_QUAL_UPSERT, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_QUAL_UPSERT, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("QUAL_CODE_EXISTS", "qualCode 已存在"));
        }
    }

    /**
     * 修改资质覆盖区段与到期时刻：行锁内校验 expectedVersion 与未终止状态，成功版本加一。
     */
    public QualificationView updateQualification(String qualCode, UpdateQualificationRequest req) {
        List<String> sections = normalizeSections(req.sections());
        String hash = hashQual(OP_QUAL_UPSERT, qualCode, req.expectedVersion(), req.crewId(),
                sections, req.expiresUtc(), null);
        Optional<QualificationView> replay = replayIfPresent(OP_QUAL_UPSERT, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                CrewQualification qual = crewRepo.findQualByCodeForUpdate(qualCode)
                        .orElseThrow(() -> qualNotFound(qualCode));
                if (qual.terminated()) {
                    throw conflict("QUAL_STATE_CONFLICT", "资质已提前终止，不可修改: " + qualCode);
                }
                if (req.expectedVersion() != qual.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion() + " 与当前版本 "
                                    + qual.version() + " 不一致");
                }
                if (!qual.crewId().equals(req.crewId())) {
                    throw conflict("CREW_MISMATCH",
                            "资质 " + qualCode + " 属于乘务员 " + qual.crewId()
                                    + "，不可改派给 " + req.crewId());
                }
                long now = clock.millis();
                crewRepo.updateQualification(qual.id(), sections, req.expiresUtc(),
                        qual.version() + 1, now);
                QualificationView view = loadQualification(qualCode);
                idemRepo.insert(OP_QUAL_UPSERT, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_QUAL_UPSERT, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("QUAL_CODE_EXISTS", "qualCode 已存在"));
        }
    }

    /**
     * 提前终止资质：全局发布锁内行锁资质，校验版本与未终止状态后置为终止，
     * 并回查所有未来已发布计划（该资质对应乘务指派且占用尚未结束）逐条写入不可变风险记录；
     * 任一回查失败整次回滚，资质保持有效。不自动取消任何计划。
     */
    public QualificationView terminateQualification(String qualCode,
                                                    TerminateQualificationRequest req) {
        String hash = hashQual(OP_QUAL_TERMINATE, qualCode, req.expectedVersion(), null, null,
                null, req.operator());
        Optional<QualificationView> replay = replayIfPresent(OP_QUAL_TERMINATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                CrewQualification qual = crewRepo.findQualByCodeForUpdate(qualCode)
                        .orElseThrow(() -> qualNotFound(qualCode));
                if (qual.terminated()) {
                    throw conflict("QUAL_STATE_CONFLICT", "资质已提前终止: " + qualCode);
                }
                if (req.expectedVersion() != qual.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion() + " 与当前版本 "
                                    + qual.version() + " 不一致");
                }
                long now = clock.millis();
                crewRepo.terminateQualification(qual.id(), qual.version() + 1, now);
                // 回查未来已发布计划：任一写入失败（如唯一约束冲突）抛出异常，整次回滚
                for (PlanCrew assignment : crewRepo.findFuturePublishedAssignments(
                        qual.qualCode(), now)) {
                    crewRepo.insertRiskRecord(assignment.planId(), assignmentScheduleKey(assignment),
                            assignment.role(), assignment.crewId(), assignment.qualCode(),
                            REASON_QUAL_TERMINATED, now);
                }
                QualificationView view = loadQualification(qualCode);
                idemRepo.insert(OP_QUAL_TERMINATE, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_QUAL_TERMINATE, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RISK_LOOKBACK_CONFLICT",
                            "风险记录回查冲突，终止已回滚: " + qualCode));
        } catch (ApiException e) {
            // 并发下同键请求在锁内看到已终止/版本已推进时，回查幂等记录重放首次结果
            if ("QUAL_STATE_CONFLICT".equals(e.code()) || "VERSION_CONFLICT".equals(e.code())) {
                Optional<QualificationView> replayed =
                        replayIfPresent(OP_QUAL_TERMINATE, req.requestKey(), hash);
                if (replayed.isPresent()) {
                    return replayed.get();
                }
            }
            throw e;
        }
    }

    /**
     * 查询资质，不存在返回 404。
     */
    public QualificationView getQualification(String qualCode) {
        if (crewRepo.findQualByCode(qualCode).isEmpty()) {
            throw qualNotFound(qualCode);
        }
        return loadQualification(qualCode);
    }

    /**
     * 查询计划当前乘务指派（按角色排序），计划不存在返回 404。
     */
    public List<PlanCrewView> getPlanCrew(long planId) {
        return crewRepo.findPlanCrew(planId).stream()
                .map(pc -> new PlanCrewView(pc.role().name(), pc.crewId(), pc.qualCode()))
                .toList();
    }

    /**
     * 查询计划风险记录（写入顺序，不可变）。
     */
    public List<CrewRiskRecordView> getRiskRecords(long planId) {
        return crewRepo.findRiskRecordsByPlan(planId).stream()
                .map(r -> new CrewRiskRecordView(r.scheduleKey(), r.role().name(), r.crewId(),
                        r.qualCode(), r.reason(), r.recordedAt()))
                .toList();
    }

    /**
     * 发布/改签前的乘务资质门禁校验：返回稳定排序的缺口列表（角色升序、原因升序），
     * 空列表表示两角色资质完整。driver/conductor 同时为 null 表示无乘务要求，直接通过。
     *
     * <p>校验规则：两角色必须为不同人员；资质须存在、属于该乘务员、未提前终止、
     * 到期时刻严格晚于计划终到时刻（全部占用最大 endUtc），且覆盖计划全部区段。
     */
    public List<Map<String, Object>> findCrewGaps(List<Occupancy> occupancies,
                                                  CrewAssignmentRequest driver,
                                                  CrewAssignmentRequest conductor) {
        if (driver == null && conductor == null) {
            return List.of();
        }
        if (driver == null || conductor == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                    "司机与车长必须同时指定或同时缺省");
        }
        List<Map<String, Object>> gaps = new ArrayList<>();
        if (driver.crewId().equals(conductor.crewId())) {
            gaps.add(gap("DRIVER", driver, "SAME_CREW_BOTH_ROLES", null));
            gaps.add(gap("CONDUCTOR", conductor, "SAME_CREW_BOTH_ROLES", null));
        }
        Instant planEnd = occupancies.stream().map(Occupancy::endUtc)
                .max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        TreeSet<String> planSections = new TreeSet<>();
        occupancies.forEach(o -> planSections.add(o.sectionId()));
        collectRoleGaps(gaps, "DRIVER", driver, planSections, planEnd);
        collectRoleGaps(gaps, "CONDUCTOR", conductor, planSections, planEnd);
        gaps.sort(Comparator.comparing((Map<String, Object> g) -> (String) g.get("role"))
                .thenComparing(g -> (String) g.get("reason")));
        return gaps;
    }

    /**
     * 缺口诊断查询：对指定计划占用与乘务指派做只读校验，返回与发布门禁一致的缺口列表。
     */
    public List<Map<String, Object>> diagnose(List<Occupancy> occupancies,
                                              CrewAssignmentRequest driver,
                                              CrewAssignmentRequest conductor) {
        return findCrewGaps(occupancies, driver, conductor);
    }

    /**
     * 计划是否处于乘务风险状态：存在风险记录且对应角色的当前指派仍为被终止的
     * 乘务员与资质组合（两角色均替换为合格人员后风险视为已消除，记录本身不可变保留）。
     */
    public boolean hasActiveRisk(long planId) {
        return !crewRepo.findActiveRiskRecords(planId).isEmpty();
    }

    /**
     * 查询计划当前生效的风险记录（当前指派仍命中风险组合的记录）。
     */
    public List<CrewRiskRecord> findActiveRiskRecords(long planId) {
        return crewRepo.findActiveRiskRecords(planId);
    }

    /**
     * 同车底新增段门禁：给定列车编号集合中，是否存在仍处风险状态的已发布计划占用同一列车。
     */
    public boolean hasRiskyPublishedTrain(List<String> trainNos, long excludePlanId) {
        return crewRepo.existsRiskyPublishedTrain(trainNos, excludePlanId);
    }

    // ---------- 内部实现 ----------

    private void collectRoleGaps(List<Map<String, Object>> gaps, String role,
                                 CrewAssignmentRequest assignment, TreeSet<String> planSections,
                                 Instant planEnd) {
        Optional<CrewQualification> qualOpt = crewRepo.findQualByCode(assignment.qualCode());
        if (qualOpt.isEmpty()) {
            gaps.add(gap(role, assignment, "QUAL_NOT_FOUND", null));
            return;
        }
        CrewQualification qual = qualOpt.get();
        if (!qual.crewId().equals(assignment.crewId())) {
            gaps.add(gap(role, assignment, "CREW_MISMATCH", null));
            return;
        }
        if (qual.terminated()) {
            gaps.add(gap(role, assignment, "QUAL_TERMINATED", null));
            return;
        }
        if (!qual.expiresUtc().isAfter(planEnd)) {
            gaps.add(gap(role, assignment, "QUAL_EXPIRED", null));
        }
        TreeSet<String> missing = new TreeSet<>(planSections);
        qual.sections().forEach(missing::remove);
        if (!missing.isEmpty()) {
            gaps.add(gap(role, assignment, "SECTION_NOT_COVERED", List.copyOf(missing)));
        }
    }

    private Map<String, Object> gap(String role, CrewAssignmentRequest assignment, String reason,
                                    List<String> missingSections) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "CREW_QUALIFICATION_GAP");
        detail.put("role", role);
        detail.put("crewId", assignment.crewId());
        detail.put("qualCode", assignment.qualCode());
        detail.put("reason", reason);
        if (missingSections != null) {
            detail.put("missingSections", missingSections);
        }
        return detail;
    }

    /**
     * 覆盖区段集合规范化：去空白、去重、字典序排序，换序视为同参。
     */
    public static List<String> normalizeSections(List<String> sections) {
        TreeSet<String> normalized = new TreeSet<>();
        for (String s : sections) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                        "覆盖区段 ID 不能为空");
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    private QualificationView loadQualification(String qualCode) {
        CrewQualification qual = crewRepo.findQualByCode(qualCode)
                .orElseThrow(() -> qualNotFound(qualCode));
        return new QualificationView(qual.qualCode(), qual.crewId(), qual.sections(),
                qual.expiresUtc(), qual.version(), qual.terminated());
    }

    private String assignmentScheduleKey(PlanCrew assignment) {
        return crewRepo.findScheduleKey(assignment.planId())
                .orElseThrow(() -> new IllegalStateException("计划缺失: " + assignment.planId()));
    }

    private Optional<QualificationView> replayIfPresent(String opType, String requestKey,
                                                        String hash) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson());
        });
    }

    private String hashQual(String opType, String qualCode, Integer expectedVersion,
                            String crewId, List<String> sections, Instant expiresUtc,
                            String operator) {
        StringBuilder sb = new StringBuilder(opType).append('\n').append(qualCode);
        sb.append('\n').append(expectedVersion == null ? "" : expectedVersion);
        sb.append('\n').append(crewId == null ? "" : crewId);
        if (sections != null) {
            sections.forEach(s -> sb.append('\n').append(s));
        }
        sb.append('\n').append(expiresUtc == null ? "" : expiresUtc.toEpochMilli());
        sb.append('\n').append(operator == null ? "" : operator);
        return sha256(sb.toString());
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

    private QualificationView fromJson(String json) {
        try {
            return objectMapper.readValue(json, QualificationView.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private ApiException qualNotFound(String qualCode) {
        return new ApiException(HttpStatus.NOT_FOUND, "QUAL_NOT_FOUND",
                "资质不存在: " + qualCode);
    }
}
