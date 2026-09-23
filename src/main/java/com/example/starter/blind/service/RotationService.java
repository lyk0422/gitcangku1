package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.DutyRole;
import com.example.starter.blind.dto.RoleRotationRequest;
import com.example.starter.blind.dto.RoleRotationView;
import com.example.starter.blind.dto.RosterView;
import com.example.starter.blind.dto.RotationPreviewView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.RotationRepository;
import com.example.starter.blind.repo.RotationRepository.GenerationRow;
import com.example.starter.blind.repo.RotationRepository.RotationRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import com.example.starter.blind.repo.UnblindRequestRepository.UnblindRequestRow;
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
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 盲态职责轮换与最小知情授权代次：
 * <ul>
 *   <li>预览只读：基于不可删除的揭盲知情历史，计算目标名册对全部未结束受试者的可见范围与冲突；</li>
 *   <li>激活在一个事务内重读实验状态、名册、揭盲记录、受试者状态与现有代次；
 *       版本变化、新增揭盲、受试者范围变化或名册冲突均整单失败，旧授权原样保留；</li>
 *   <li>成功时原子结束全部旧活动授权并签发新代次，每人仅获目标职责所需最小字段；</li>
 *   <li>实验行锁串行化轮换、关闭、揭盲批准、数据提交与另一轮换，杜绝两代同时有效。</li>
 * </ul>
 */
@Service
public class RotationService {

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final RotationRepository rotationRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RotationService(ExperimentRepository experimentRepository,
                           AllocationRepository allocationRepository,
                           UnblindRequestRepository unblindRequestRepository,
                           RotationRepository rotationRepository,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.rotationRepository = rotationRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 归一化名册：去空白、去重、排序；名册换序视为同参。 */
    record Roster(List<String> dataCollectors, List<String> randomizationCustodians,
                  List<String> safetyReviewers) {

        static Roster of(RoleRotationRequest request) {
            return new Roster(
                    normalize(request.dataCollectors()),
                    normalize(request.randomizationCustodians()),
                    normalize(request.safetyReviewers()));
        }

        private static List<String> normalize(List<String> raw) {
            if (raw == null) {
                return List.of();
            }
            TreeSet<String> set = new TreeSet<>();
            for (String actor : raw) {
                if (actor != null) {
                    set.add(actor.trim());
                }
            }
            return List.copyOf(set);
        }

        List<String> actorsOf(DutyRole role) {
            return switch (role) {
                case DATA_COLLECTOR -> dataCollectors;
                case RANDOMIZATION_CUSTODIAN -> randomizationCustodians;
                case SAFETY_REVIEWER -> safetyReviewers;
            };
        }

        RosterView toView() {
            return new RosterView(dataCollectors, randomizationCustodians, safetyReviewers);
        }
    }

    /** 当前名册校验与知情分析结果，预览与激活共用同一计算。 */
    private record Analysis(Roster roster, List<String> violations,
                            List<RotationPreviewView.KnowledgeConflict> conflicts,
                            List<RotationPreviewView.VisibilityEntry> visibility,
                            List<RoleRotationView.KnowledgeFact> knowledgeBasis) {
    }

    // ---------------- 预览（只读，不写数据） ----------------

    /**
     * 预览目标名册：计算可见范围与知情冲突，不写任何数据。
     */
    public RotationPreviewView preview(String experimentId, RoleRotationRequest request,
                                       String actorId) {
        ExperimentRow experiment = experimentRepository.findById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        requireOwner(experiment, actorId);
        Analysis analysis = analyze(experimentId, request);
        boolean versionMatch = request.expectedExperimentVersion() != null
                && request.expectedExperimentVersion() == experiment.version();
        boolean effectiveAtUsable = request.effectiveAt() != null
                && request.effectiveAt() >= clock.nowMillis();
        boolean activatable = "OPEN".equals(experiment.status())
                && versionMatch && effectiveAtUsable
                && analysis.violations().isEmpty() && analysis.conflicts().isEmpty();
        return new RotationPreviewView(experimentId, experiment.version(),
                request.expectedExperimentVersion(), versionMatch, request.effectiveAt(),
                analysis.violations(), analysis.conflicts(), analysis.visibility(), activatable);
    }

    // ---------------- 激活（单事务，整单成败） ----------------

    /**
     * 激活轮换单：事务内重读全部相关状态；任一校验失败整单回滚，旧授权继续有效。
     */
    @Transactional
    public RoleRotationView activate(String experimentId, RoleRotationRequest request,
                                     String requestId, String actorId) {
        if (request.rotationKey() == null || request.rotationKey().isBlank()) {
            throw ApiException.badRequest("rotationKey 不能为空");
        }
        if (request.rotationKey().length() > 64) {
            throw ApiException.badRequest("rotationKey 长度不能超过 64 个字符");
        }
        if (request.expectedExperimentVersion() == null) {
            throw ApiException.badRequest("expectedExperimentVersion 不能为空");
        }
        if (request.effectiveAt() == null) {
            throw ApiException.badRequest("effectiveAt 不能为空");
        }
        long now = clock.nowMillis();
        if (request.effectiveAt() < now) {
            throw ApiException.badRequest("effectiveAt 不能早于当前时刻");
        }
        // 事务内重读并锁定实验：与关闭、揭盲批准、数据提交、另一轮换串行化。
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        requireOwner(experiment, actorId);
        if (!"OPEN".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，拒绝职责轮换");
        }
        if (request.expectedExperimentVersion() != experiment.version()) {
            throw ApiException.conflict("实验版本已变化，请重新预览后提交");
        }
        Analysis analysis = analyze(experimentId, request);
        if (!analysis.violations().isEmpty()) {
            throw ApiException.unprocessable(
                    "目标名册不合法: " + String.join("；", analysis.violations()));
        }
        if (!analysis.conflicts().isEmpty()) {
            List<String> details = analysis.conflicts().stream()
                    .map(c -> c.actorId() + " 已知悉受试者 " + c.participantId() + " 分组")
                    .toList();
            throw ApiException.unprocessable("目标名册存在知情冲突: " + String.join("；", details));
        }
        // 原子结束全部旧活动授权；0 行说明并发轮换已抢先，整单失败。
        GenerationRow before = rotationRepository.findActiveGeneration(experimentId);
        if (before == null) {
            throw ApiException.conflict("当前无活动授权代次");
        }
        if (rotationRepository.endActiveGeneration(experimentId, now) != 1) {
            throw ApiException.conflict("授权代次已被并发轮换变更，请重试");
        }
        int afterGenerationNo = before.generationNo() + 1;
        long generationId = rotationRepository.insertGeneration(experimentId, afterGenerationNo,
                request.rotationKey(), "ACTIVE", now, request.effectiveAt());
        // 每人仅获目标职责所需最小字段；历史知情事实不扩大新接口权限。
        for (DutyRole role : DutyRole.values()) {
            for (String actor : analysis.roster().actorsOf(role)) {
                rotationRepository.insertGrant(generationId, experimentId, actor, role.name(),
                        role.minimalFieldList(), now);
            }
        }
        RotationRow rotation = new RotationRow(request.rotationKey(), experimentId, requestId,
                actorId, request.expectedExperimentVersion().intValue(),
                before.generationNo(), afterGenerationNo,
                toJson(currentRosterView(before)), toJson(analysis.roster().toView()),
                toJson(analysis.knowledgeBasis()), request.effectiveAt(), now);
        try {
            rotationRepository.insertRotation(rotation);
        } catch (DuplicateKeyException e) {
            // rotationKey 全局唯一；整单回滚，旧授权不受影响。
            throw ApiException.conflict("rotationKey 已存在: " + request.rotationKey());
        }
        experimentRepository.incrementVersion(experimentId);
        return toView(rotation);
    }

    // ---------------- 查询（只读） ----------------

    /**
     * 查询轮换单：前后名册、授权代次与知情冲突依据；只读。
     */
    public RoleRotationView getRotation(String experimentId, String rotationKey) {
        RotationRow row = rotationRepository.findRotation(experimentId, rotationKey);
        if (row == null) {
            throw ApiException.notFound("轮换单不存在: " + rotationKey);
        }
        return toView(row);
    }

    // ---------------- 内部计算 ----------------

    private void requireOwner(ExperimentRow experiment, String actorId) {
        if (!experiment.ownerActor().equals(actorId)) {
            throw ApiException.forbidden("仅实验负责人可执行职责轮换");
        }
    }

    /**
     * 基于当前不可删除的知情历史，计算名册违规、知情冲突与可见范围。
     */
    private Analysis analyze(String experimentId, RoleRotationRequest request) {
        Roster roster = Roster.of(request);
        List<String> violations = validateRoster(roster);
        List<AllocationRow> allocations = allocationRepository.findByExperiment(experimentId);
        List<String> unfinished = allocations.stream()
                .filter(a -> "ASSIGNED".equals(a.status()))
                .map(AllocationRow::participantId)
                .sorted()
                .toList();
        List<RoleRotationView.KnowledgeFact> knowledgeBasis = knowledgeBasis(experimentId);
        Map<String, List<String>> knownByParticipant = new TreeMap<>();
        for (RoleRotationView.KnowledgeFact fact : knowledgeBasis) {
            knownByParticipant.put(fact.participantId(), fact.knownBy());
        }
        List<RotationPreviewView.KnowledgeConflict> conflicts = new ArrayList<>();
        for (String collector : roster.dataCollectors()) {
            for (String participantId : unfinished) {
                List<String> knownBy = knownByParticipant.get(participantId);
                if (knownBy != null && knownBy.contains(collector)) {
                    conflicts.add(new RotationPreviewView.KnowledgeConflict(collector, participantId,
                            collector + " 已通过批准揭盲知悉受试者 " + participantId
                                    + " 的分组，不得承担其数据采集"));
                }
            }
        }
        List<RotationPreviewView.VisibilityEntry> visibility = new ArrayList<>();
        for (DutyRole role : DutyRole.values()) {
            for (String actor : roster.actorsOf(role)) {
                visibility.add(new RotationPreviewView.VisibilityEntry(actor, role.name(),
                        unfinished, List.of(role.minimalFieldList().split(","))));
            }
        }
        return new Analysis(roster, violations, conflicts, visibility, knowledgeBasis);
    }

    private List<String> validateRoster(Roster roster) {
        List<String> violations = new ArrayList<>();
        for (DutyRole role : DutyRole.values()) {
            List<String> actors = roster.actorsOf(role);
            if (actors.isEmpty()) {
                violations.add(role.name() + " 名册至少一人");
            }
            for (String actor : actors) {
                if (actor.isBlank()) {
                    violations.add(role.name() + " 名册包含空白人员编号");
                } else if (actor.length() > 64) {
                    violations.add(role.name() + " 人员编号长度不能超过 64 个字符");
                }
            }
        }
        List<String> both = new ArrayList<>(roster.dataCollectors());
        both.retainAll(roster.randomizationCustodians());
        if (!both.isEmpty()) {
            violations.add("同一人员不能同时承担数据采集与随机化保管: " + String.join(",", both));
        }
        return violations;
    }

    /**
     * 不可删除的知情历史：已批准揭盲的申请人与批准人知悉对应受试者分组。
     */
    private List<RoleRotationView.KnowledgeFact> knowledgeBasis(String experimentId) {
        Map<String, TreeSet<String>> knownBy = new TreeMap<>();
        for (UnblindRequestRow row : unblindRequestRepository.findApprovedByExperiment(experimentId)) {
            TreeSet<String> actors = knownBy.computeIfAbsent(row.participantId(), k -> new TreeSet<>());
            actors.add(row.applicantActor());
            if (row.reviewerActor() != null) {
                actors.add(row.reviewerActor());
            }
        }
        List<RoleRotationView.KnowledgeFact> facts = new ArrayList<>();
        knownBy.forEach((participantId, actors) ->
                facts.add(new RoleRotationView.KnowledgeFact(participantId, List.copyOf(actors))));
        return facts;
    }

    /** 轮换前名册：取当前活动代次的授权集合；初始代次为空名册。 */
    private RosterView currentRosterView(GenerationRow activeGeneration) {
        Map<String, List<String>> byRole = new LinkedHashMap<>();
        for (DutyRole role : DutyRole.values()) {
            byRole.put(role.name(), new ArrayList<>());
        }
        rotationRepository.findGrantsByGeneration(activeGeneration.id()).forEach(grant ->
                byRole.computeIfAbsent(grant.roleType(), k -> new ArrayList<>()).add(grant.actorId()));
        return new RosterView(
                byRole.get(DutyRole.DATA_COLLECTOR.name()),
                byRole.get(DutyRole.RANDOMIZATION_CUSTODIAN.name()),
                byRole.get(DutyRole.SAFETY_REVIEWER.name()));
    }

    private RoleRotationView toView(RotationRow row) {
        return new RoleRotationView(row.rotationKey(), row.experimentId(), row.actorId(),
                row.expectedExperimentVersion(),
                fromJson(row.beforeRosterJson(), RosterView.class),
                fromJson(row.afterRosterJson(), RosterView.class),
                row.beforeGenerationNo(), row.afterGenerationNo(),
                fromJsonList(row.knowledgeBasisJson()),
                row.effectiveAt(), row.activatedAt());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("名册快照无法序列化", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("名册快照无法解析", e);
        }
    }

    private List<RoleRotationView.KnowledgeFact> fromJsonList(String json) {
        try {
            return objectMapper.readValue(json,
                    new TypeReference<List<RoleRotationView.KnowledgeFact>>() {
                    });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("知情依据快照无法解析", e);
        }
    }
}
