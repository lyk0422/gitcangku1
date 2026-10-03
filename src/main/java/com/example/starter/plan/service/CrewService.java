package com.example.starter.plan.service;

import com.example.starter.plan.model.CrewQualification;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PublishedCrewAssignment;
import com.example.starter.plan.repo.CrewRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CrewCheckItem;
import com.example.starter.plan.web.dto.CrewCheckResponse;
import com.example.starter.plan.web.dto.GrantQualificationRequest;
import com.example.starter.plan.web.dto.QualificationResponse;
import com.example.starter.plan.web.dto.SectionGradeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * 乘务资格门禁：资格授予/撤销、区段等级登记、发布前逐人资格校验与发布快照、
 * 逐人覆盖结果查询。
 *
 * <p>资格区间左闭右开 [validFrom, validTo)，须完整覆盖计划行程 [tripStart, tripEnd)，
 * 即 validFrom &lt;= tripStart 且 validTo &gt;= tripEnd；资格等级须不低于计划经过区段的
 * 最高等级。发布在全局发布锁与资格行锁内校验，撤销与发布并发时按行锁串行，
 * 不会基于已失效资格发布。发布成功写入不可变快照，历史发布保留当时资格版本与人员名单。
 */
@Service
public class CrewService {

    private static final String OP_QUAL_GRANT = "QUAL_GRANT";
    private static final String OP_QUAL_REVOKE = "QUAL_REVOKE";
    private static final String OP_SECTION_GRADE = "SECTION_GRADE";

    /** 乘务门禁拒绝时返回的错误码（HTTP 422）。 */
    public static final String CODE_CREW_DENIED = "CREW_QUALIFICATION_DENIED";

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
     * 授予资格：无记录时创建版本 1；已撤销时再授予，版本加一；存在有效资格时拒绝。
     */
    public QualificationResponse grant(GrantQualificationRequest req) {
        if (!req.validToUtc().isAfter(req.validFromUtc())) {
            throw badRequest("资格失效时刻必须晚于生效时刻");
        }
        String hash = sha256(OP_QUAL_GRANT + '\n' + req.crewMemberId() + '\n' + req.lineGrade()
                + '\n' + req.validFromUtc().toEpochMilli() + '\n' + req.validToUtc().toEpochMilli());
        Optional<QualificationResponse> replay =
                replayIfPresent(OP_QUAL_GRANT, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                long now = System.currentTimeMillis();
                Optional<CrewQualification> existing =
                        crewRepo.findQualificationsForUpdate(List.of(req.crewMemberId()))
                                .stream().findFirst();
                long qualificationId;
                if (existing.isEmpty()) {
                    qualificationId = crewRepo.insertQualification(req.crewMemberId(),
                            req.lineGrade(), req.validFromUtc(), req.validToUtc(), now);
                } else {
                    CrewQualification current = existing.get();
                    if (CrewQualification.STATUS_ACTIVE.equals(current.status())) {
                        throw conflict("QUALIFICATION_EXISTS",
                                "已存在有效资格，须先撤销: " + req.crewMemberId());
                    }
                    qualificationId = current.id();
                    crewRepo.updateQualification(qualificationId, req.lineGrade(),
                            req.validFromUtc(), req.validToUtc(), current.version() + 1,
                            CrewQualification.STATUS_ACTIVE, now);
                }
                QualificationResponse response = toResponse(
                        crewRepo.findQualification(req.crewMemberId()).orElseThrow());
                idemRepo.insert(OP_QUAL_GRANT, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_QUAL_GRANT, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("QUALIFICATION_EXISTS",
                            "已存在有效资格: " + req.crewMemberId()));
        }
    }

    /**
     * 撤销资格：状态置为 REVOKED 且版本加一。与发布并发时经资格行锁串行，
     * 先提交的撤销生效后，发布校验将读取到已失效资格并拒绝。
     */
    public QualificationResponse revoke(String crewMemberId, String requestKey) {
        String hash = sha256(OP_QUAL_REVOKE + '\n' + crewMemberId);
        Optional<QualificationResponse> replay =
                replayIfPresent(OP_QUAL_REVOKE, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                CrewQualification current =
                        crewRepo.findQualificationsForUpdate(List.of(crewMemberId))
                                .stream().findFirst()
                                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                        "QUALIFICATION_NOT_FOUND", "资格不存在: " + crewMemberId));
                if (!CrewQualification.STATUS_ACTIVE.equals(current.status())) {
                    throw conflict("QUALIFICATION_STATE_CONFLICT",
                            "资格已撤销，当前状态: " + current.status());
                }
                long now = System.currentTimeMillis();
                crewRepo.updateQualification(current.id(), current.lineGrade(),
                        current.validFromUtc(), current.validToUtc(), current.version() + 1,
                        CrewQualification.STATUS_REVOKED, now);
                QualificationResponse response = toResponse(
                        crewRepo.findQualification(crewMemberId).orElseThrow());
                idemRepo.insert(OP_QUAL_REVOKE, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_QUAL_REVOKE, requestKey, hash)
                    .orElseThrow(() -> conflict("QUALIFICATION_STATE_CONFLICT",
                            "资格状态冲突: " + crewMemberId));
        }
    }

    /**
     * 按乘务员 ID 查询当前资格，不存在返回 404。
     */
    public QualificationResponse getQualification(String crewMemberId) {
        return crewRepo.findQualification(crewMemberId)
                .map(this::toResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "QUALIFICATION_NOT_FOUND", "资格不存在: " + crewMemberId));
    }

    /**
     * 登记或更新区段线路等级（幂等）。
     */
    public SectionGradeResponse registerSectionGrade(String sectionId, int lineGrade,
                                                     String requestKey) {
        String hash = sha256(OP_SECTION_GRADE + '\n' + sectionId + '\n' + lineGrade);
        Optional<SectionGradeResponse> replay =
                replayIfPresent(OP_SECTION_GRADE, requestKey, hash, SectionGradeResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                long now = System.currentTimeMillis();
                crewRepo.upsertSectionGrade(sectionId, lineGrade, now);
                SectionGradeResponse response = new SectionGradeResponse(sectionId, lineGrade);
                idemRepo.insert(OP_SECTION_GRADE, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_SECTION_GRADE, requestKey, hash, SectionGradeResponse.class)
                    .orElseThrow(() -> conflict("SECTION_GRADE_CONFLICT",
                            "区段等级登记冲突: " + sectionId));
        }
    }
    /**
     * 发布前逐人资格校验（须在发布事务与全局发布锁内调用，经资格行锁与撤销串行）。
     * 任一成员资格缺失、已撤销、区间未完整覆盖行程、等级不足或与其他已发布计划
     * 占用窗口重叠，均抛出 422，整单不占线路时隙或乘务资源。
     *
     * @return 校验通过后的发布快照（待随发布事务一并写入）
     */
    public List<PublishedCrewAssignment> validateCrewForPublish(
            DayPlan plan, List<Occupancy> occupancies, List<String> crewMemberIds) {
        if (crewMemberIds.isEmpty()) {
            return List.of();
        }
        Instant tripStart = occupancies.stream().map(Occupancy::startUtc)
                .min(Comparator.naturalOrder()).orElseThrow();
        Instant tripEnd = occupancies.stream().map(Occupancy::endUtc)
                .max(Comparator.naturalOrder()).orElseThrow();
        int requiredGrade = requiredLineGrade(occupancies);
        Map<String, CrewQualification> qualifications = new LinkedHashMap<>();
        for (CrewQualification q : crewRepo.findQualificationsForUpdate(crewMemberIds)) {
            qualifications.put(q.crewMemberId(), q);
        }
        List<Map<String, Object>> violations = new ArrayList<>();
        List<PublishedCrewAssignment> snapshot = new ArrayList<>();
        for (int i = 0; i < crewMemberIds.size(); i++) {
            String memberId = crewMemberIds.get(i);
            CrewQualification qual = qualifications.get(memberId);
            if (qual == null) {
                violations.add(violation("QUALIFICATION_MISSING", memberId, null));
                continue;
            }
            if (!CrewQualification.STATUS_ACTIVE.equals(qual.status())) {
                violations.add(violation("QUALIFICATION_REVOKED", memberId, null));
                continue;
            }
            if (qual.validFromUtc().isAfter(tripStart) || qual.validToUtc().isBefore(tripEnd)) {
                violations.add(violation("QUALIFICATION_WINDOW_INSUFFICIENT", memberId, null));
                continue;
            }
            if (qual.lineGrade() < requiredGrade) {
                violations.add(violation("LINE_GRADE_INSUFFICIENT", memberId, null));
                continue;
            }
            snapshot.add(new PublishedCrewAssignment(0L, plan.id(), i, memberId,
                    qual.id(), qual.version(), qual.lineGrade(),
                    qual.validFromUtc(), qual.validToUtc(), tripStart, tripEnd));
        }
        if (violations.isEmpty()) {
            for (CrewRepository.CrewOccupancy occupied
                    : crewRepo.findPublishedCrewOccupancies(crewMemberIds, plan.id())) {
                boolean overlap = tripStart.isBefore(occupied.tripEndUtc())
                        && occupied.tripStartUtc().isBefore(tripEnd);
                if (overlap) {
                    violations.add(violation("CREW_CONFLICT", occupied.crewMemberId(),
                            occupied.scheduleKey()));
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, CODE_CREW_DENIED,
                    "乘务资格校验未通过，计划保持草稿", violations);
        }
        return snapshot;
    }

    /**
     * 逐人资格覆盖结果查询：基于发布快照展示资格版本、占用窗口与当前冲突计划，
     * 按乘务员 ID 稳定升序。未登记乘务组的计划返回 409。
     */
    public CrewCheckResponse getCrewCheck(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                        "计划不存在: " + scheduleKey));
        List<PublishedCrewAssignment> snapshot = crewRepo.findCrewAssignments(plan.id());
        if (snapshot.isEmpty()) {
            throw conflict("NO_CREW_SNAPSHOT", "计划未登记乘务组: " + scheduleKey);
        }
        int requiredGrade = requiredLineGrade(planRepo.findOccupancies(plan.id()));
        List<String> memberIds = snapshot.stream()
                .map(PublishedCrewAssignment::crewMemberId).toList();
        Map<String, List<String>> conflictsByMember = new LinkedHashMap<>();
        for (CrewRepository.CrewOccupancy occupied
                : crewRepo.findPublishedCrewOccupancies(memberIds, plan.id())) {
            PublishedCrewAssignment self = snapshot.stream()
                    .filter(a -> a.crewMemberId().equals(occupied.crewMemberId()))
                    .findFirst().orElseThrow();
            boolean overlap = self.tripStartUtc().isBefore(occupied.tripEndUtc())
                    && occupied.tripStartUtc().isBefore(self.tripEndUtc());
            if (overlap) {
                conflictsByMember.computeIfAbsent(occupied.crewMemberId(),
                        k -> new ArrayList<>()).add(occupied.scheduleKey());
            }
        }
        List<CrewCheckItem> items = snapshot.stream()
                .sorted(Comparator.comparing(PublishedCrewAssignment::crewMemberId))
                .map(a -> new CrewCheckItem(a.crewMemberId(), a.qualificationId(),
                        a.qualificationVersion(), a.lineGrade(),
                        a.validFromUtc(), a.validToUtc(), a.tripStartUtc(), a.tripEndUtc(),
                        true,
                        conflictsByMember.getOrDefault(a.crewMemberId(), List.of())
                                .stream().sorted().distinct().toList()))
                .toList();
        return new CrewCheckResponse(plan.scheduleKey(), plan.status().name(),
                requiredGrade, items);
    }

    // ---------- 内部实现 ----------

    /**
     * 计划经过区段的最高登记等级；未登记区段按 0 处理。
     */
    private int requiredLineGrade(List<Occupancy> occupancies) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        return crewRepo.findSectionGrades(sectionIds).values().stream()
                .mapToInt(Integer::intValue).max().orElse(0);
    }

    private Map<String, Object> violation(String type, String crewMemberId,
                                          String conflictingScheduleKey) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("crewMemberId", crewMemberId);
        if (conflictingScheduleKey != null) {
            detail.put("conflictingScheduleKey", conflictingScheduleKey);
        }
        return detail;
    }

    private QualificationResponse toResponse(CrewQualification qual) {
        return new QualificationResponse(qual.id(), qual.crewMemberId(), qual.lineGrade(),
                qual.validFromUtc(), qual.validToUtc(), qual.version(), qual.status());
    }

    private Optional<QualificationResponse> replayIfPresent(String opType, String requestKey,
                                                            String hash) {
        return replayIfPresent(opType, requestKey, hash, QualificationResponse.class);
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
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

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}