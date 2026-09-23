package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.CollectorScopePreviewView;
import com.example.starter.blind.dto.ConflictEvidenceView;
import com.example.starter.blind.dto.GenerationView;
import com.example.starter.blind.dto.RosterView;
import com.example.starter.blind.dto.RotationActivateRequest;
import com.example.starter.blind.dto.RotationOrderView;
import com.example.starter.blind.dto.RotationPreviewView;
import com.example.starter.blind.dto.RotationRosterRequest;
import com.example.starter.blind.repo.AccessGenerationRepository;
import com.example.starter.blind.repo.AccessGenerationRepository.AccessGenerationRow;
import com.example.starter.blind.repo.AccessGenerationRepository.RoleAssignmentRow;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.RotationOrderRepository;
import com.example.starter.blind.repo.RotationOrderRepository.RotationOrderRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import com.example.starter.blind.repo.UnblindRequestRepository.KnowledgeRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 盲态职责轮换与最小知情授权代次核心业务。
 *
 * <p>激活在单事务内行锁实验后重新读取实验状态、角色名册、揭盲记录、受试者状态与现有代次：
 * 版本不符 409；采集/保管同人、目标采集者对未结束受试者已有揭盲知情等名册冲突 422；
 * 成功则原子结束旧 ACTIVE 代次、生成新代次与最小字段名册、推进实验指针与版本并落轮换单，
 * 失败整单回滚，旧授权继续有效，绝不撤一半。</p>
 */
@Service
public class RotationService {

    public static final String ROLE_DATA_COLLECTOR = "DATA_COLLECTOR";
    public static final String ROLE_RANDOMIZATION_CUSTODIAN = "RANDOMIZATION_CUSTODIAN";
    public static final String ROLE_SAFETY_REVIEWER = "SAFETY_REVIEWER";

    /** 各职责角色完成目标工作所需的最小知情字段；不多给。 */
    public static final Map<String, List<String>> GRANTED_FIELDS = Map.of(
            ROLE_DATA_COLLECTOR, List.of("META", "BLIND_CODE"),
            ROLE_RANDOMIZATION_CUSTODIAN, List.of("BLOCK_NO", "SEAT_NO"),
            ROLE_SAFETY_REVIEWER, List.of("TREATMENT", "SAFETY"));

    private static final TypeReference<List<ConflictEvidenceView>> CONFLICT_LIST_TYPE =
            new TypeReference<>() {
            };

    private final ExperimentRepository experimentRepository;
    private final AccessGenerationRepository generationRepository;
    private final AllocationRepository allocationRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final RotationOrderRepository rotationOrderRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RotationService(ExperimentRepository experimentRepository,
                           AccessGenerationRepository generationRepository,
                           AllocationRepository allocationRepository,
                           UnblindRequestRepository unblindRequestRepository,
                           RotationOrderRepository rotationOrderRepository,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.experimentRepository = experimentRepository;
        this.generationRepository = generationRepository;
        this.allocationRepository = allocationRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.rotationOrderRepository = rotationOrderRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 规范化目标名册：去空白、去重并按字典序排序。名册换序 / 同类重复列举均视为同参。
     */
    public CanonicalRoster canonicalize(RotationRosterRequest request) {
        if (request == null) {
            throw ApiException.badRequest("roster 不能为空");
        }
        TreeSet<String> collectors = normalize(ROLE_DATA_COLLECTOR, request.dataCollectors());
        TreeSet<String> custodians =
                normalize(ROLE_RANDOMIZATION_CUSTODIAN, request.randomizationCustodians());
        TreeSet<String> reviewers = normalize(ROLE_SAFETY_REVIEWER, request.safetyReviewers());
        return new CanonicalRoster(collectors, custodians, reviewers);
    }

    private TreeSet<String> normalize(String role, List<String> actors) {
        if (actors == null || actors.isEmpty()) {
            throw ApiException.badRequest(role + " 至少需要一人");
        }
        TreeSet<String> set = new TreeSet<>();
        for (String raw : actors) {
            set.add(RequestTokens.requireId(role + " 人员编号", raw));
        }
        return set;
    }

    /** 规范化名册（升序、去重）；同名册不同顺序相等。 */
    public record CanonicalRoster(
            TreeSet<String> dataCollectors,
            TreeSet<String> randomizationCustodians,
            TreeSet<String> safetyReviewers) {

        /** 同一人同时承担采集与随机化保管的冲突人员（升序）。 */
        public List<String> roleOverlap() {
            TreeSet<String> overlap = new TreeSet<>(dataCollectors);
            overlap.retainAll(randomizationCustodians);
            return List.copyOf(overlap);
        }

        /** 转为可序列化视图。 */
        public RosterView toView() {
            return new RosterView(List.copyOf(dataCollectors),
                    List.copyOf(randomizationCustodians), List.copyOf(safetyReviewers));
        }
    }

    /**
     * 只读预览：基于当前不可删除的知情历史，计算目标名册对全部未结束受试者的可见范围与冲突。
     * 不写任何数据。
     */
    public RotationPreviewView preview(String experimentId, long effectiveAt,
                                       RotationRosterRequest request) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        CanonicalRoster roster = canonicalize(request);
        if (effectiveAt > clock.nowMillis()) {
            throw ApiException.unprocessable("生效时刻不能晚于当前时刻");
        }

        List<String> activeParticipants = allocationRepository.findActiveParticipantIds(experimentId);
        Map<String, List<ConflictEvidenceView>> blockedByCollector =
                computeKnowledgeConflicts(experimentId, roster, activeParticipants);

        List<CollectorScopePreviewView> scopes = new ArrayList<>();
        for (String collector : roster.dataCollectors()) {
            List<ConflictEvidenceView> blocked =
                    blockedByCollector.getOrDefault(collector, List.of());
            TreeSet<String> blockedParticipants = new TreeSet<>();
            blocked.forEach(e -> blockedParticipants.add(e.participantId()));
            List<String> visible = activeParticipants.stream()
                    .filter(p -> !blockedParticipants.contains(p))
                    .toList();
            scopes.add(new CollectorScopePreviewView(collector, visible, blocked));
        }

        List<ConflictEvidenceView> allConflicts = blockedByCollector.values().stream()
                .flatMap(List::stream)
                .sorted((a, b) -> {
                    int byActor = a.actorId().compareTo(b.actorId());
                    if (byActor != 0) {
                        return byActor;
                    }
                    int byParticipant = a.participantId().compareTo(b.participantId());
                    return byParticipant != 0 ? byParticipant
                            : a.unblindRequestId().compareTo(b.unblindRequestId());
                })
                .toList();
        List<String> overlap = roster.roleOverlap();
        boolean valid = allConflicts.isEmpty() && overlap.isEmpty();

        return new RotationPreviewView(experimentId, experiment.roleVersion(), roster.toView(),
                scopes, allConflicts, overlap, valid);
    }

    /**
     * 激活轮换单。整单事务：任一回滚条件触发则抛异常，旧授权继续有效。
     */
    @Transactional
    public RotationOrderView activate(String experimentId, String rotationKey,
                                      RotationActivateRequest request,
                                      String actorId, String requestId) {
        long now = clock.nowMillis();
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if (!"OPEN".equals(experiment.status())) {
            // 仅 ACTIVE（OPEN）实验可轮换；实验关闭并发时随版本/状态检查拒绝。
            throw ApiException.conflict("实验非 ACTIVE 状态，不能轮换职责");
        }
        int expectedVersion = request.expectedExperimentVersion().intValue();
        if (expectedVersion != experiment.roleVersion()) {
            // 版本变化：目标名册基于旧版本，整单 409。
            throw ApiException.conflict("实验职责版本已变化，expectedExperimentVersion="
                    + expectedVersion + "，当前版本=" + experiment.roleVersion());
        }
        if (request.effectiveAt() > now) {
            throw ApiException.unprocessable("生效时刻不能晚于当前时刻");
        }

        CanonicalRoster roster = canonicalize(request.roster());
        List<String> overlap = roster.roleOverlap();
        if (!overlap.isEmpty()) {
            // 同一人不能同时承担采集与随机化保管：目标名册冲突 422。
            throw ApiException.unprocessable("同一人员不能同时承担数据采集与随机化保管: " + overlap);
        }

        // 重新读取受试者状态与不可删除的揭盲知情历史。
        List<String> activeParticipants = allocationRepository.findActiveParticipantIds(experimentId);
        Map<String, List<ConflictEvidenceView>> conflicts =
                computeKnowledgeConflicts(experimentId, roster, activeParticipants);
        List<ConflictEvidenceView> allConflicts = conflicts.values().stream()
                .flatMap(List::stream).toList();
        if (!allConflicts.isEmpty()) {
            // 新增揭盲/受试者范围变化后，目标采集者对未结束受试者已有知情：整单 422。
            throw ApiException.unprocessable("目标采集者已通过揭盲获知未结束受试者分组，"
                    + "不得分配其数据采集范围");
        }

        // 现有访问代次与名册（激活前快照）。
        Long beforeGenerationId = experiment.activeGenerationId();
        RosterView beforeRoster = beforeGenerationId == null
                ? new RosterView(List.of(), List.of(), List.of())
                : readRoster(beforeGenerationId);

        int newGenerationNo = generationRepository.nextGenerationNo(experimentId);
        if (beforeGenerationId != null
                && generationRepository.supersede(beforeGenerationId, now) != 1) {
            // 行锁内不应发生；防御性拒绝，避免出现两代 ACTIVE。
            throw ApiException.conflict("结束旧授权代次失败，请重试");
        }
        long newGenerationId = generationRepository.insertGeneration(new AccessGenerationRow(
                0L, experimentId, newGenerationNo, "ACTIVE", request.effectiveAt(),
                null, rotationKey, now));

        insertAssignments(newGenerationId, experimentId, roster);
        // 有效名册下不存在知情冲突：每个采集者获得全部未结束受试者范围。
        for (String collector : roster.dataCollectors()) {
            for (String participantId : activeParticipants) {
                generationRepository.insertCollectorScope(newGenerationId, experimentId,
                        collector, participantId);
            }
        }

        int updated = experimentRepository.activateGeneration(
                experimentId, expectedVersion, newGenerationId);
        if (updated != 1) {
            // 乐观锁兜底：版本在锁内被改动，触发事务回滚，不得留下两代授权。
            throw ApiException.conflict("实验职责版本已变化，轮换失败");
        }

        RotationOrderRow order = new RotationOrderRow(
                rotationKey, experimentId, expectedVersion, expectedVersion + 1,
                newGenerationId, beforeGenerationId, requestId,
                writeJson(beforeRoster), writeJson(roster.toView()), writeJson(List.of()),
                "ACTIVATED", actorId, now, now);
        try {
            rotationOrderRepository.insert(order);
        } catch (DuplicateKeyException e) {
            // rotationKey 唯一：已被另一成功轮换单占用，整单回滚，不撤旧授权。
            throw ApiException.conflict("rotationKey 已存在: " + rotationKey);
        }

        AccessGenerationRow newRow = generationRepository.findById(newGenerationId);
        GenerationView generationView = toGenerationView(newRow, roster.toView());
        return new RotationOrderView(rotationKey, experimentId, "ACTIVATED",
                expectedVersion, expectedVersion + 1, beforeRoster, roster.toView(),
                beforeGenerationId, generationView, List.of(), actorId, now, now);
    }

    /**
     * 查询轮换单（只读）：返回前后名册、授权代次与知情冲突依据。
     */
    public RotationOrderView getOrder(String rotationKey) {
        RotationOrderRow row = rotationOrderRepository.findByKey(rotationKey);
        if (row == null) {
            throw ApiException.notFound("轮换单不存在: " + rotationKey);
        }
        RosterView before = readJson(row.beforeRoster(), RosterView.class);
        RosterView target = readJson(row.targetRoster(), RosterView.class);
        List<ConflictEvidenceView> evidence = readJsonList(row.conflictEvidence());
        AccessGenerationRow genRow = generationRepository.findById(row.generationId());
        GenerationView generation = toGenerationView(genRow,
                genRow == null ? target : readRoster(genRow.id()));
        return new RotationOrderView(row.rotationKey(), row.experimentId(), row.status(),
                row.expectedExperimentVersion(), row.newExperimentVersion(), before, target,
                row.beforeGenerationId(), generation, evidence, row.createdBy(),
                row.createdAt(), row.activatedAt());
    }

    /** 查询实验当前活动代次（只读）；尚未轮换返回 409。 */
    public GenerationView getCurrentGeneration(String experimentId) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        if (experiment.activeGenerationId() == null) {
            throw ApiException.conflict("实验尚无活动授权代次，请先执行职责轮换");
        }
        return toGenerationView(generationRepository.findById(experiment.activeGenerationId()),
                readRoster(experiment.activeGenerationId()));
    }

    // ---------------- 内部辅助 ----------------

    /**
     * 计算知情冲突：目标采集者中，已通过批准揭盲获知某“未结束”受试者分组的人。
     * 退组（已结束）受试者不构成限制；知情历史不可删除。
     */
    private Map<String, List<ConflictEvidenceView>> computeKnowledgeConflicts(
            String experimentId, CanonicalRoster roster, List<String> activeParticipants) {
        Map<String, List<ConflictEvidenceView>> result = new LinkedHashMap<>();
        if (roster.dataCollectors().isEmpty() || activeParticipants.isEmpty()) {
            return result;
        }
        Set<String> activeSet = Set.copyOf(activeParticipants);
        List<KnowledgeRow> knowledge = unblindRequestRepository.findApprovedKnowledge(experimentId);
        for (KnowledgeRow k : knowledge) {
            if (!roster.dataCollectors().contains(k.actorId())) {
                continue;
            }
            if (!activeSet.contains(k.participantId())) {
                continue;
            }
            ConflictEvidenceView evidence = new ConflictEvidenceView(
                    k.actorId(), k.participantId(), k.unblindRequestId(), k.reviewedAt());
            result.computeIfAbsent(k.actorId(), a -> new ArrayList<>()).add(evidence);
        }
        return result;
    }

    private void insertAssignments(long generationId, String experimentId, CanonicalRoster roster) {
        insertRoleRows(generationId, experimentId, ROLE_DATA_COLLECTOR, roster.dataCollectors());
        insertRoleRows(generationId, experimentId, ROLE_RANDOMIZATION_CUSTODIAN,
                roster.randomizationCustodians());
        insertRoleRows(generationId, experimentId, ROLE_SAFETY_REVIEWER, roster.safetyReviewers());
    }

    private void insertRoleRows(long generationId, String experimentId, String role,
                                Set<String> actors) {
        String fields = String.join(",", GRANTED_FIELDS.get(role));
        for (String actor : actors) {
            generationRepository.insertRoleAssignment(new RoleAssignmentRow(
                    generationId, experimentId, role, actor, fields));
        }
    }

    private RosterView readRoster(long generationId) {
        TreeSet<String> collectors = new TreeSet<>();
        TreeSet<String> custodians = new TreeSet<>();
        TreeSet<String> reviewers = new TreeSet<>();
        for (RoleAssignmentRow assignment : generationRepository.findRoleAssignments(generationId)) {
            switch (assignment.roleName()) {
                case ROLE_DATA_COLLECTOR -> collectors.add(assignment.actorId());
                case ROLE_RANDOMIZATION_CUSTODIAN -> custodians.add(assignment.actorId());
                case ROLE_SAFETY_REVIEWER -> reviewers.add(assignment.actorId());
                default -> {
                }
            }
        }
        return new RosterView(List.copyOf(collectors), List.copyOf(custodians),
                List.copyOf(reviewers));
    }

    private GenerationView toGenerationView(AccessGenerationRow row, RosterView roster) {
        if (row == null) {
            throw new IllegalStateException("授权代次缺失，数据不一致");
        }
        return new GenerationView(row.id(), row.experimentId(), row.generationNo(), row.status(),
                row.effectiveAt(), row.supersededAt(), roster, GRANTED_FIELDS);
    }

    private ExperimentRow mustFindExperiment(String experimentId) {
        ExperimentRow row = experimentRepository.findById(experimentId);
        if (row == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        return row;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("轮换快照无法序列化", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("轮换快照无法反序列化", e);
        }
    }

    private List<ConflictEvidenceView> readJsonList(String json) {
        try {
            return objectMapper.readValue(json, CONFLICT_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("知情冲突依据无法反序列化", e);
        }
    }
}
