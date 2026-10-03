package com.example.starter.plan.service;

import com.example.starter.plan.model.CrewAssignment;
import com.example.starter.plan.model.CrewQualification;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.OccupiedCrewSlot;
import com.example.starter.plan.repo.CrewRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CrewCheckResponse;
import com.example.starter.plan.web.dto.CrewGroupRequest;
import com.example.starter.plan.web.dto.CrewGroupResponse;
import com.example.starter.plan.web.dto.QualificationGrantRequest;
import com.example.starter.plan.web.dto.QualificationResponse;
import com.example.starter.plan.web.dto.QualificationRevokeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
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
 * 乘务资格门禁：乘务组登记、资格授予/撤销、发布前逐人资格核查与逐人覆盖查询。
 *
 * <p>资格区间左闭右开 [validFromUtc, validToUtc)，须完整覆盖计划行程 [journeyStart, journeyEnd)；
 * 资格等级须不低于计划经过区段的最高要求等级；同一乘务员在重叠计划上不能重复占用，
 * 相邻计划端点可交接。资格授予/撤销与发布经全局发布锁串行化，
 * 撤销先于发布提交时发布不得基于已失效资格通过。
 */
@Service
public class CrewService {

    /** 幂等操作类型：创建乘务组。 */
    public static final String OP_CREW_GROUP = "CREW_GROUP";
    /** 幂等操作类型：授予资格。 */
    public static final String OP_QUAL_GRANT = "QUAL_GRANT";
    /** 幂等操作类型：撤销资格。 */
    public static final String OP_QUAL_REVOKE = "QUAL_REVOKE";

    private final CrewRepository crewRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public CrewService(CrewRepository crewRepo, PlanRepository planRepo,
                       IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                       PlatformTransactionManager txManager) {
        this.crewRepo = crewRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建乘务组（名册不可变）；同键同参重放返回首次结果，同键不同参 409。
     */
    public CrewGroupResponse createGroup(CrewGroupRequest req) {
        if (req.memberCodes().stream().distinct().count() != req.memberCodes().size()) {
            throw badRequest("乘务组成员不可重复");
        }
        String hash = sha256(OP_CREW_GROUP + '\n' + req.groupCode() + '\n'
                + String.join("|", new TreeSet<>(req.memberCodes())));
        Optional<CrewGroupResponse> replay = replayIfPresent(OP_CREW_GROUP, req.requestKey(), hash,
                CrewGroupResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                crewRepo.insertGroup(req.groupCode(), System.currentTimeMillis());
                crewRepo.insertGroupMembers(req.groupCode(), req.memberCodes());
                CrewGroupResponse response = new CrewGroupResponse(req.groupCode(),
                        crewRepo.findGroupMembers(req.groupCode()));
                idemRepo.insert(OP_CREW_GROUP, req.requestKey(), hash, toJson(response),
                        System.currentTimeMillis());
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CREW_GROUP, req.requestKey(), hash, CrewGroupResponse.class)
                    .orElseThrow(() -> conflict("CREW_GROUP_EXISTS", "乘务组已存在: " + req.groupCode()));
        }
    }

    /**
     * 授予资格：版本递增，旧有效版本作废；经全局发布锁与发布/撤销串行化。
     */
    public QualificationResponse grant(QualificationGrantRequest req) {
        if (!req.validToUtc().isAfter(req.validFromUtc())) {
            throw badRequest("资格失效时刻必须晚于生效时刻");
        }
        String hash = sha256(OP_QUAL_GRANT + '\n' + req.memberCode() + '\n' + req.lineGrade()
                + '\n' + req.validFromUtc().toEpochMilli() + '\n' + req.validToUtc().toEpochMilli());
        Optional<QualificationResponse> replay = replayIfPresent(OP_QUAL_GRANT, req.requestKey(),
                hash, QualificationResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        return tx.execute(status -> {
            planRepo.acquirePublishLock();
            long now = System.currentTimeMillis();
            int version = crewRepo.findMaxQualificationVersion(req.memberCode()) + 1;
            crewRepo.grantQualification(req.memberCode(), version, req.lineGrade(),
                    req.validFromUtc(), req.validToUtc(), now);
            QualificationResponse response = new QualificationResponse(req.memberCode(), version,
                    req.lineGrade(), req.validFromUtc(), req.validToUtc(), "ACTIVE");
            idemRepo.insert(OP_QUAL_GRANT, req.requestKey(), hash, toJson(response), now);
            return response;
        });
    }

    /**
     * 撤销乘务员当前有效资格；经全局发布锁与发布串行化，
     * 撤销先提交时并发发布不得基于已失效资格通过。
     */
    public QualificationResponse revoke(QualificationRevokeRequest req) {
        String hash = sha256(OP_QUAL_REVOKE + '\n' + req.memberCode());
        Optional<QualificationResponse> replay = replayIfPresent(OP_QUAL_REVOKE, req.requestKey(),
                hash, QualificationResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        return tx.execute(status -> {
            planRepo.acquirePublishLock();
            long now = System.currentTimeMillis();
            CrewQualification revoked = crewRepo.revokeActiveQualification(req.memberCode(), now)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "QUALIFICATION_NOT_FOUND", "乘务员无有效资格: " + req.memberCode()));
            QualificationResponse response = new QualificationResponse(revoked.memberCode(),
                    revoked.version(), revoked.lineGrade(), revoked.validFromUtc(),
                    revoked.validToUtc(), revoked.status().name());
            idemRepo.insert(OP_QUAL_REVOKE, req.requestKey(), hash, toJson(response), now);
            return response;
        });
    }
    /**
     * 逐人资格核查查询：已发布（含已取消）计划返回发布当时的资格版本与人员名单快照；
     * 草稿须指定 crewGroup 实时评估。成员按业务键升序稳定排序。
     */
    public CrewCheckResponse crewCheck(String scheduleKey, String crewGroup) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
        Instant journeyStart = journeyStart(occupancies);
        Instant journeyEnd = journeyEnd(occupancies);
        int requiredGrade = crewRepo.findMaxRequiredGrade(sectionIds(occupancies));
        List<CrewAssignment> snapshot = crewRepo.findAssignments(plan.id());
        List<CrewCheckResponse.MemberCrewCheck> members = new ArrayList<>();
        if (!snapshot.isEmpty()) {
            for (CrewAssignment a : snapshot) {
                members.add(new CrewCheckResponse.MemberCrewCheck(a.memberCode(), true, null, true,
                        a.qualVersion(), a.qualLineGrade(), a.qualValidFromUtc(),
                        a.qualValidToUtc(), a.startUtc(), a.endUtc(),
                        conflictingPlans(a.memberCode(), a.startUtc(), a.endUtc(), plan.id())));
            }
        } else {
            if (crewGroup == null || crewGroup.isBlank()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "CREW_NOT_REGISTERED",
                        "计划未登记乘务组: " + scheduleKey);
            }
            if (!crewRepo.groupExists(crewGroup)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "CREW_GROUP_NOT_FOUND",
                        "乘务组不存在: " + crewGroup);
            }
            for (String memberCode : crewRepo.findGroupMembers(crewGroup)) {
                members.add(evaluateMember(memberCode, journeyStart, journeyEnd, requiredGrade,
                        plan.id()));
            }
        }
        return new CrewCheckResponse(plan.scheduleKey(), plan.status().name(), journeyStart,
                journeyEnd, requiredGrade, members);
    }

    /**
     * 发布前逐人资格核查（须在持有全局发布锁的事务内调用）。
     * 任一成员资格过期、等级不足或人员冲突都会返回结构化明细，由上层整单判 422。
     *
     * @return 冲突明细列表，为空表示全部通过
     */
    public List<Map<String, Object>> validateCrewForPublish(DayPlan plan,
                                                            List<Occupancy> occupancies,
                                                            String crewGroup) {
        if (!crewRepo.groupExists(crewGroup)) {
            throw badRequest("乘务组不存在: " + crewGroup);
        }
        Instant journeyStart = journeyStart(occupancies);
        Instant journeyEnd = journeyEnd(occupancies);
        int requiredGrade = crewRepo.findMaxRequiredGrade(sectionIds(occupancies));
        List<Map<String, Object>> details = new ArrayList<>();
        for (String memberCode : crewRepo.findGroupMembers(crewGroup)) {
            Optional<CrewQualification> qual = crewRepo.findActiveQualification(memberCode);
            if (qual.isEmpty() || !covers(qual.get(), journeyStart, journeyEnd)) {
                Map<String, Object> detail = baseDetail("QUALIFICATION_EXPIRED", plan, memberCode,
                        journeyStart, journeyEnd);
                detail.put("qualification", qual.map(q -> Map.of(
                        "version", q.version(),
                        "validFromUtc", q.validFromUtc().toString(),
                        "validToUtc", q.validToUtc().toString(),
                        "status", q.status().name())).orElse(null));
                details.add(detail);
                continue;
            }
            CrewQualification q = qual.get();
            if (q.lineGrade() < requiredGrade) {
                Map<String, Object> detail = baseDetail("GRADE_INSUFFICIENT", plan, memberCode,
                        journeyStart, journeyEnd);
                detail.put("requiredGrade", requiredGrade);
                detail.put("qualLineGrade", q.lineGrade());
                details.add(detail);
                continue;
            }
            for (OccupiedCrewSlot slot : crewRepo.findConflictingCrewSlots(memberCode, journeyStart,
                    journeyEnd, plan.id())) {
                Map<String, Object> detail = baseDetail("CREW_CONFLICT", plan, memberCode,
                        journeyStart, journeyEnd);
                detail.put("conflictingScheduleKey", slot.scheduleKey());
                detail.put("conflictStartUtc", slot.startUtc().toString());
                detail.put("conflictEndUtc", slot.endUtc().toString());
                details.add(detail);
            }
        }
        return details;
    }

    /**
     * 构建发布乘务占用快照（须在 validateCrewForPublish 通过的同一事务内调用）：
     * 逐人记录发布当时的资格版本、等级与有效区间，以及计划行程占用窗口。
     */
    public List<CrewAssignment> buildAssignments(DayPlan plan, List<Occupancy> occupancies,
                                                 String crewGroup) {
        Instant journeyStart = journeyStart(occupancies);
        Instant journeyEnd = journeyEnd(occupancies);
        List<CrewAssignment> assignments = new ArrayList<>();
        for (String memberCode : crewRepo.findGroupMembers(crewGroup)) {
            CrewQualification q = crewRepo.findActiveQualification(memberCode)
                    .orElseThrow(() -> new IllegalStateException("资格核查通过后有效资格缺失: "
                            + memberCode));
            assignments.add(new CrewAssignment(0L, plan.id(), crewGroup, memberCode, q.version(),
                    q.lineGrade(), q.validFromUtc(), q.validToUtc(), journeyStart, journeyEnd));
        }
        return assignments;
    }

    /**
     * 写入发布乘务占用快照。
     */
    public void saveAssignments(List<CrewAssignment> assignments, long nowMillis) {
        crewRepo.insertAssignments(assignments, nowMillis);
    }

    // ---------- 内部实现 ----------

    /**
     * 实时评估一名成员对给定行程窗口的覆盖结果（草稿查询用）。
     */
    private CrewCheckResponse.MemberCrewCheck evaluateMember(String memberCode, Instant start,
                                                             Instant end, int requiredGrade,
                                                             long planId) {
        Optional<CrewQualification> qual = crewRepo.findActiveQualification(memberCode);
        List<String> conflicts = conflictingPlans(memberCode, start, end, planId);
        String failureReason = null;
        if (qual.isEmpty() || !covers(qual.get(), start, end)) {
            failureReason = "QUALIFICATION_EXPIRED";
        } else if (qual.get().lineGrade() < requiredGrade) {
            failureReason = "GRADE_INSUFFICIENT";
        } else if (!conflicts.isEmpty()) {
            failureReason = "CREW_CONFLICT";
        }
        return new CrewCheckResponse.MemberCrewCheck(memberCode, failureReason == null,
                failureReason, false,
                qual.map(CrewQualification::version).orElse(null),
                qual.map(CrewQualification::lineGrade).orElse(null),
                qual.map(CrewQualification::validFromUtc).orElse(null),
                qual.map(CrewQualification::validToUtc).orElse(null),
                start, end, conflicts);
    }

    /**
     * 查询乘务员在其他已发布计划上与窗口重叠的冲突计划业务键（升序去重）。
     */
    private List<String> conflictingPlans(String memberCode, Instant start, Instant end,
                                          long excludePlanId) {
        return crewRepo.findConflictingCrewSlots(memberCode, start, end, excludePlanId).stream()
                .map(OccupiedCrewSlot::scheduleKey).distinct().sorted().toList();
    }

    /**
     * 资格区间完整覆盖行程窗口（左闭右开）：生效不晚于行程开始，失效不早于行程结束。
     */
    private boolean covers(CrewQualification qual, Instant start, Instant end) {
        return !qual.validFromUtc().isAfter(start) && !qual.validToUtc().isBefore(end);
    }

    private Map<String, Object> baseDetail(String type, DayPlan plan, String memberCode,
                                           Instant journeyStart, Instant journeyEnd) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("scheduleKey", plan.scheduleKey());
        detail.put("memberCode", memberCode);
        detail.put("journeyStartUtc", journeyStart.toString());
        detail.put("journeyEndUtc", journeyEnd.toString());
        return detail;
    }

    private static Instant journeyStart(List<Occupancy> occupancies) {
        return occupancies.stream().map(Occupancy::startUtc).min(Instant::compareTo)
                .orElseThrow(() -> new IllegalStateException("计划无占用，无法确定行程开始"));
    }

    private static Instant journeyEnd(List<Occupancy> occupancies) {
        return occupancies.stream().map(Occupancy::endUtc).max(Instant::compareTo)
                .orElseThrow(() -> new IllegalStateException("计划无占用，无法确定行程结束"));
    }

    private static List<String> sectionIds(List<Occupancy> occupancies) {
        return occupancies.stream().map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
    }

    private <T> Optional<T> replayIfPresent(String opType, String requestKey, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED", "requestKey 已用于其他参数: " + requestKey);
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

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException notFound(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "计划不存在: " + scheduleKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}