package com.example.starter.plan.service;

import com.example.starter.plan.model.CrewMember;
import com.example.starter.plan.model.CrewQualification;
import com.example.starter.plan.model.CrewWindow;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.LineSection;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanCrew;
import com.example.starter.plan.model.PlanCrewMember;
import com.example.starter.plan.repo.CrewRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CrewAssignmentView;
import com.example.starter.plan.web.dto.CrewCheckResponse;
import com.example.starter.plan.web.dto.CrewConflictView;
import com.example.starter.plan.web.dto.CrewCoverageView;
import com.example.starter.plan.web.dto.CrewMemberView;
import com.example.starter.plan.web.dto.CrewSnapshotView;
import com.example.starter.plan.web.dto.GrantQualificationRequest;
import com.example.starter.plan.web.dto.LineSectionView;
import com.example.starter.plan.web.dto.QualificationView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 乘务领域服务：乘务员/区段登记、资格版本授予与撤销、发布乘务资格门禁评估、发布快照装配。
 *
 * <p>门禁规则（时间一律按 UTC 比较，区间左闭右开）：
 * 每位成员在行程所属线路上的当前 ACTIVE 资格版本须完整覆盖行程窗口
 * {@code [tripStart, tripEnd)}（validFrom &lt;= tripStart 且 validTo 为 null 或 &gt;= tripEnd），
 * 资格等级不小于行程区段要求的最高等级；同一成员与其他已发布计划的占用窗口重叠即冲突，
 * 端点相邻（前序 end == 后序 start）可交接。撤销/换发与发布并发时，双方在资格行上以
 * SELECT ... FOR UPDATE 串行裁决，发布提交后撤销的资格不影响已发布快照。
 */
@Service
public class CrewService {

    private final CrewRepository crewRepo;
    private final PlanRepository planRepo;
    private final TransactionTemplate tx;

    public CrewService(CrewRepository crewRepo, PlanRepository planRepo,
                       PlatformTransactionManager txManager) {
        this.crewRepo = crewRepo;
        this.planRepo = planRepo;
        this.tx = new TransactionTemplate(txManager);
    }

    // ---------- 乘务员与区段登记 ----------

    /**
     * 登记乘务员；工号重复返回 409。
     */
    public CrewMemberView registerMember(String memberKey, String name) {
        try {
            return tx.execute(status -> {
                long now = System.currentTimeMillis();
                crewRepo.insertMember(memberKey, name, now);
                return new CrewMemberView(memberKey, name);
            });
        } catch (DuplicateKeyException e) {
            throw conflict("MEMBER_EXISTS", "乘务员工号已存在: " + memberKey);
        }
    }

    public List<CrewMemberView> listMembers() {
        return crewRepo.findAllMembers().stream()
                .map(m -> new CrewMemberView(m.memberKey(), m.name()))
                .toList();
    }

    /**
     * 登记线路区段；区段已登记返回 409。
     */
    public LineSectionView registerSection(String sectionId, String lineId, int requiredGrade) {
        try {
            return tx.execute(status -> {
                crewRepo.insertSection(sectionId, lineId, requiredGrade, System.currentTimeMillis());
                return new LineSectionView(sectionId, lineId, requiredGrade);
            });
        } catch (DuplicateKeyException e) {
            throw conflict("SECTION_EXISTS", "区段已登记: " + sectionId);
        }
    }

    // ---------- 资格版本 ----------

    /**
     * 授予新版本资格：同人员同线路上一 ACTIVE 版本置为 SUPERSEDED，新版本号在最大版本号上加一。
     */
    public QualificationView grantQualification(String memberKey, GrantQualificationRequest req) {
        if (req.validToUtc() != null && !req.validToUtc().isAfter(req.validFromUtc())) {
            throw badRequest("资格结束时刻必须晚于生效时刻");
        }
        try {
            return tx.execute(status -> {
                CrewMember member = crewRepo.findMemberByKey(memberKey)
                        .orElseThrow(() -> notFound("乘务员不存在: " + memberKey));
                List<CrewQualification> existing =
                        crewRepo.findQualifications(member.id(), req.lineId());
                int nextVersion = existing.stream()
                        .mapToInt(CrewQualification::version).max().orElse(0) + 1;
                crewRepo.markActiveSuperseded(member.id(), req.lineId());
                long now = System.currentTimeMillis();
                long id = crewRepo.insertQualification(member.id(), req.lineId(), req.gradeLevel(),
                        req.validFromUtc(), req.validToUtc(), nextVersion, now);
                CrewQualification saved = crewRepo.findQualifications(member.id(), req.lineId())
                        .stream().filter(q -> q.id() == id).findFirst()
                        .orElseThrow(() -> new IllegalStateException("新授予资格回读失败: " + id));
                return toView(memberKey, saved);
            });
        } catch (DuplicateKeyException e) {
            throw conflict("QUALIFICATION_VERSION_EXISTS",
                    "资格版本冲突（并发授予）: " + memberKey + " / " + req.lineId());
        }
    }

    /**
     * 列出人员在指定线路上的全部资格版本（按版本号升序）；lineId 为 null 时列出全部线路。
     */
    public List<QualificationView> listQualifications(String memberKey, String lineId) {
        CrewMember member = crewRepo.findMemberByKey(memberKey)
                .orElseThrow(() -> notFound("乘务员不存在: " + memberKey));
        List<CrewQualification> all = new ArrayList<>();
        if (lineId != null) {
            all.addAll(crewRepo.findQualifications(member.id(), lineId));
        } else {
            all.addAll(crewRepo.findQualificationsAnyLine(member.id()));
        }
        return all.stream()
                .sorted(Comparator.comparing(CrewQualification::lineId)
                        .thenComparingInt(CrewQualification::version))
                .map(q -> toView(memberKey, q)).toList();
    }

    /**
     * 撤销指定版本资格，仅 ACTIVE 可撤销；并发撤销/换发已改变状态时返回 409。
     */
    public QualificationView revokeQualification(String memberKey, String lineId, int version) {
        return tx.execute(status -> {
            CrewMember member = crewRepo.findMemberByKey(memberKey)
                    .orElseThrow(() -> notFound("乘务员不存在: " + memberKey));
            CrewQualification qual = crewRepo.findQualificationForUpdate(member.id(), lineId, version)
                    .orElseThrow(() -> notFound("资格版本不存在: " + memberKey + " / " + lineId
                            + " / v" + version));
            if (crewRepo.revokeQualification(qual.id(), System.currentTimeMillis()) == 0) {
                throw conflict("QUALIFICATION_NOT_ACTIVE",
                        "资格当前状态不可撤销: " + qual.status());
            }
            CrewQualification refreshed = crewRepo.findQualificationForUpdate(qual.id())
                    .orElseThrow(() -> new IllegalStateException("撤销后资格回读失败: " + qual.id()));
            return toView(memberKey, refreshed);
        });
    }

    // ---------- 门禁评估与快照 ----------

    /**
     * 乘务门禁预检查询（不加锁、不改变状态）：逐人返回资格覆盖、占用窗口与冲突计划。
     */
    public CrewCheckResponse check(String scheduleKey, List<String> memberKeys) {
        validateCrewKeys(memberKeys);
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> planNotFound(scheduleKey));
        List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
        GateResult result = evaluateGate(plan, occupancies, memberKeys, false, List.of(plan.id()));
        return new CrewCheckResponse(scheduleKey, result.lineId(), result.requiredGrade(),
                result.tripStart(), result.tripEnd(), result.coverage());
    }

    /**
     * 发布事务内执行门禁（对成员 ACTIVE 资格行加写锁，保证与撤销/换发串行裁决）。
     * 不通过抛 422（结构化明细），通过返回解析结果供发布写入快照；调用方事务整体回滚即可保证
     * 时隙与乘务资源均不占用。
     */
    public GateResult gateForPublish(DayPlan plan, List<Occupancy> occupancies,
                                     List<String> memberKeys, List<Long> excludePlanIds) {
        GateResult result = evaluateGate(plan, occupancies, memberKeys, true, excludePlanIds);
        if (!result.passed()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CREW_GATE",
                    "乘务资格门禁未通过，计划保持草稿", result.violations());
        }
        return result;
    }

    /**
     * 读取计划发布时的乘务组快照；无快照（草稿或未登记乘务组发布）返回 null。
     */
    public CrewSnapshotView loadSnapshot(long planId) {
        Optional<PlanCrew> head = crewRepo.findPlanCrew(planId);
        if (head.isEmpty()) {
            return null;
        }
        List<CrewAssignmentView> members = crewRepo.findPlanCrewMembers(planId).stream()
                .map(m -> new CrewAssignmentView(m.memberKey(), m.memberName(),
                        m.qualificationVersion(), m.gradeLevel(), m.validFromUtc(), m.validToUtc()))
                .toList();
        return new CrewSnapshotView(head.get().lineId(), head.get().requiredGrade(), members);
    }

    /**
     * 发布通过后在同一事务内写入乘务快照。
     */
    public void saveSnapshot(long planId, GateResult gate, long nowMillis) {
        crewRepo.insertPlanCrew(new PlanCrew(planId, gate.lineId(), gate.requiredGrade(), nowMillis));
        List<PlanCrewMember> rows = new ArrayList<>(gate.entries().size());
        for (int i = 0; i < gate.entries().size(); i++) {
            GateEntry entry = gate.entries().get(i);
            CrewMember member = entry.member();
            CrewQualification qual = entry.qualification();
            rows.add(new PlanCrewMember(0L, planId, i, member.id(), member.memberKey(), member.name(),
                    qual.id(), qual.version(), qual.gradeLevel(), qual.validFromUtc(),
                    qual.validToUtc()));
        }
        crewRepo.insertPlanCrewMembers(planId, rows);
    }

    // ---------- 内部实现 ----------

    /**
     * 评估一次乘务门禁。
     *
     * @param lockQualifications true 时对每位成员的 ACTIVE 资格行加写锁（发布路径）
     * @param excludePlanIds     占用窗口检测排除的计划 id（发布排除自身；改签排除新旧计划）
     */
    private GateResult evaluateGate(DayPlan plan, List<Occupancy> occupancies,
                                    List<String> memberKeys, boolean lockQualifications,
                                    List<Long> excludePlanIds) {
        validateCrewKeys(memberKeys);
        if (occupancies.isEmpty()) {
            throw badRequest("计划无占用行程，无法校验乘务资格");
        }
        Instant tripStart = occupancies.stream().map(Occupancy::startUtc).min(Comparator.naturalOrder()).orElseThrow();
        Instant tripEnd = occupancies.stream().map(Occupancy::endUtc).max(Comparator.naturalOrder()).orElseThrow();

        List<Map<String, Object>> violations = new ArrayList<>();

        // 行程区段：必须全部已登记，且同属一条线路；要求等级取最大值
        String lineId = null;
        int requiredGrade = 0;
        for (Occupancy o : occupancies) {
            Optional<LineSection> section = crewRepo.findSection(o.sectionId());
            if (section.isEmpty()) {
                violations.add(violation("SECTION_NOT_REGISTERED",
                        "sectionId", o.sectionId(),
                        "scheduleKey", plan.scheduleKey()));
                continue;
            }
            LineSection ls = section.get();
            if (lineId == null) {
                lineId = ls.lineId();
            } else if (!lineId.equals(ls.lineId())) {
                violations.add(violation("SECTION_LINE_MISMATCH",
                        "sectionId", o.sectionId(),
                        "expectedLineId", lineId,
                        "actualLineId", ls.lineId(),
                        "scheduleKey", plan.scheduleKey()));
            }
            requiredGrade = Math.max(requiredGrade, ls.requiredGrade());
        }

        // 乘务员解析（按工号稳定排序输出）
        List<String> sortedKeys = memberKeys.stream().sorted().toList();
        Map<String, CrewMember> membersByKey = new LinkedHashMap<>();
        crewRepo.findMembersByKeys(sortedKeys).forEach(m -> membersByKey.put(m.memberKey(), m));

        List<Long> memberIds = new ArrayList<>();
        List<GateEntry> entries = new ArrayList<>();
        List<CrewCoverageView> coverage = new ArrayList<>();
        for (String key : sortedKeys) {
            CrewMember member = membersByKey.get(key);
            if (member == null) {
                violations.add(violation("MEMBER_NOT_FOUND", "memberKey", key));
                coverage.add(new CrewCoverageView(key, null, null, null, null, null,
                        false, false, false, tripStart, tripEnd, List.of()));
                continue;
            }
            memberIds.add(member.id());

            CrewQualification qual = null;
            if (lineId != null) {
                List<CrewQualification> actives = lockQualifications
                        ? crewRepo.findActiveQualificationsForUpdate(member.id(), lineId)
                        : crewRepo.findQualifications(member.id(), lineId).stream()
                            .filter(q -> q.status().name().equals("ACTIVE")).toList();
                qual = actives.stream()
                        .max(Comparator.comparingInt(CrewQualification::version)).orElse(null);
            }

            boolean intervalCovered = qual != null
                    && !qual.validFromUtc().isAfter(tripStart)
                    && (qual.validToUtc() == null || !qual.validToUtc().isBefore(tripEnd));
            boolean gradeSufficient = qual != null && qual.gradeLevel() >= requiredGrade && lineId != null;
            if (qual == null || !intervalCovered) {
                Map<String, Object> v = violation("QUALIFICATION_EXPIRED",
                        "memberKey", key,
                        "tripStartUtc", tripStart.toString(),
                        "tripEndUtc", tripEnd.toString());
                if (qual != null) {
                    v.put("validFromUtc", qual.validFromUtc().toString());
                    v.put("validToUtc", qual.validToUtc() == null ? null : qual.validToUtc().toString());
                }
                violations.add(v);
            }
            if (qual != null && !gradeSufficient) {
                violations.add(violation("GRADE_INSUFFICIENT",
                        "memberKey", key,
                        "gradeLevel", qual.gradeLevel(),
                        "requiredGrade", requiredGrade));
            }
            entries.add(new GateEntry(member, qual, intervalCovered, gradeSufficient));
        }

        // 占用窗口与人员冲突：仅其他已发布计划；左闭右开重叠判定，相邻端点可交接
        List<CrewWindow> windows = crewRepo.findCrewWindows(memberIds, excludePlanIds);
        Map<Long, List<CrewWindow>> windowsByMember = new LinkedHashMap<>();
        for (CrewWindow w : windows) {
            windowsByMember.computeIfAbsent(w.memberId(), k -> new ArrayList<>()).add(w);
        }
        for (GateEntry entry : entries) {
            List<CrewWindow> mine = windowsByMember.getOrDefault(entry.member().id(), List.of());
            List<CrewConflictView> conflicts = new ArrayList<>();
            boolean occupied = false;
            for (CrewWindow w : mine) {
                boolean overlap = w.startUtc().isBefore(tripEnd) && tripStart.isBefore(w.endUtc());
                if (overlap) {
                    occupied = true;
                    conflicts.add(new CrewConflictView(w.scheduleKey(), w.startUtc(), w.endUtc()));
                    violations.add(violation("CREW_CONFLICT",
                            "memberKey", entry.member().memberKey(),
                            "conflictingScheduleKey", w.scheduleKey(),
                            "startUtc", w.startUtc().toString(),
                            "endUtc", w.endUtc().toString()));
                }
            }
            coverage.add(new CrewCoverageView(
                    entry.member().memberKey(), entry.member().name(),
                    entry.qualification() == null ? null : entry.qualification().version(),
                    entry.qualification() == null ? null : entry.qualification().gradeLevel(),
                    entry.qualification() == null ? null : entry.qualification().validFromUtc(),
                    entry.qualification() == null ? null : entry.qualification().validToUtc(),
                    entry.intervalCovered(), entry.gradeSufficient(), occupied,
                    tripStart, tripEnd, conflicts));
        }

        // 仅在无区段级问题时，成员级通过才有效；资格缺失的条目不进入发布快照
        boolean memberLevelPassed = entries.size() == sortedKeys.size()
                && entries.stream().allMatch(GateEntry::passed)
                && violations.stream().noneMatch(v -> "CREW_CONFLICT".equals(v.get("type")));
        boolean passed = lineId != null
                && violations.stream().noneMatch(v -> v.get("type").toString().startsWith("SECTION_"))
                && memberLevelPassed;
        List<GateEntry> usable = entries.stream().filter(e -> e.qualification() != null).toList();
        return new GateResult(lineId, requiredGrade, tripStart, tripEnd, usable, coverage,
                violations, passed);
    }

    private void validateCrewKeys(List<String> memberKeys) {
        if (memberKeys == null || memberKeys.isEmpty()) {
            throw badRequest("乘务组不能为空");
        }
        Set<String> unique = new HashSet<>(memberKeys);
        if (unique.size() != memberKeys.size()) {
            Collection<String> duplicates = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String k : memberKeys) {
                if (!seen.add(k)) {
                    duplicates.add(k);
                }
            }
            throw badRequest("乘务组成员不可重复: " + duplicates);
        }
    }

    private Map<String, Object> violation(String type, Object... kv) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            detail.put(kv[i].toString(), kv[i + 1]);
        }
        return detail;
    }

    private QualificationView toView(String memberKey, CrewQualification q) {
        return new QualificationView(memberKey, q.lineId(), q.version(), q.gradeLevel(),
                q.validFromUtc(), q.validToUtc(), q.status().name(), q.revokedAt());
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "CREW_RESOURCE_NOT_FOUND", message);
    }

    private ApiException planNotFound(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "计划不存在: " + scheduleKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /**
     * 门禁逐人解析中间结果。
     *
     * @param member          乘务员
     * @param qualification   当前 ACTIVE 资格版本，缺失为 null
     * @param intervalCovered 资格区间是否完整覆盖行程
     * @param gradeSufficient 等级是否满足要求
     */
    public record GateEntry(CrewMember member, CrewQualification qualification,
                            boolean intervalCovered, boolean gradeSufficient) {
        boolean passed() {
            return intervalCovered && gradeSufficient;
        }
    }

    /**
     * 门禁评估结果。
     *
     * @param entries       通过解析的成员条目（含资格引用，供写入发布快照）
     * @param coverage      逐人覆盖/占用/冲突视图（按工号升序）
     * @param violations    结构化违例明细
     * @param passed        门禁是否整体通过
     */
    public record GateResult(String lineId, int requiredGrade, Instant tripStart, Instant tripEnd,
                             List<GateEntry> entries, List<CrewCoverageView> coverage,
                             List<Map<String, Object>> violations, boolean passed) {
    }
}
