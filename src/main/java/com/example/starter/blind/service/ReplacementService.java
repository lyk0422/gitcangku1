package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.BlockQuotaView;
import com.example.starter.blind.dto.ReplacementView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ReplacementRepository;
import com.example.starter.blind.repo.ReplacementRepository.ReplacementRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 受试者替补业务：
 * 仅对已退组且从未揭盲的参与者可替补；同一事务内把新参与者登记到原参与者所属区组、
 * 继承其分配序号与处理代码（不向操作人返回处理代码），原参与者转入 REPLACED 终态。
 * 替补不新建分配序号、不改变区组各处理代码配额计数，区组可用名额（总席位 − 已占用分配序号）
 * 在替补前后守恒。已揭盲、未退组或已替补的参与者发起替补返回 409。
 */
@Service
public class ReplacementService {

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final ReplacementRepository replacementRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final Clock clock;

    public ReplacementService(ExperimentRepository experimentRepository,
                              AllocationRepository allocationRepository,
                              ReplacementRepository replacementRepository,
                              UnblindRequestRepository unblindRequestRepository,
                              Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.replacementRepository = replacementRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.clock = clock;
    }

    /**
     * 提交替补。实验行锁 + 分配行锁串行化与登记、退组、揭盲申请的并发，
     * 按事务提交顺序裁决。
     */
    @Transactional
    public ReplacementView replace(String experimentId, String participantId,
                                   String replaceKey, String newParticipantId, String actorId) {
        if (replaceKey == null || replaceKey.isBlank()) {
            throw ApiException.badRequest("replaceKey 不能为空");
        }
        if (newParticipantId == null || newParticipantId.isBlank()) {
            throw ApiException.badRequest("newParticipantId 不能为空");
        }
        // 实验行锁：与登记/关闭/其他替补串行。
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        // 分配行锁：与退组、揭盲申请按提交顺序裁决。
        AllocationRow allocation =
                allocationRepository.lockByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            if (replacementRepository.findByOriginal(experimentId, participantId) != null) {
                throw ApiException.conflict("参与者已被替补，处于 REPLACED 终态");
            }
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        if (!"WITHDRAWN".equals(allocation.status())) {
            throw ApiException.conflict("仅已退组的参与者可替补");
        }
        if (unblindRequestRepository.existsApprovedByAllocation(allocation.id())) {
            throw ApiException.conflict("参与者已揭盲，禁止替补");
        }
        if (newParticipantId.equals(participantId)) {
            throw ApiException.conflict("新参与者不能与被替补参与者相同");
        }
        // 新参与者不得存在于任何区组或拥有历史分配（跨实验全局查重）。
        if (allocationRepository.existsAnywhereByParticipant(newParticipantId)
                || replacementRepository.existsAnywhereByParticipant(newParticipantId)) {
            throw ApiException.conflict("新参与者已存在或拥有历史分配: " + newParticipantId);
        }
        long now = clock.nowMillis();
        int updated = allocationRepository.reassignToReplacement(
                allocation.id(), newParticipantId, actorId, now);
        if (updated == 0) {
            // 并发下状态已被修改（如被其他事务回滚/变更），按冲突处理。
            throw ApiException.conflict("参与者状态已变化，替补失败，请重试");
        }
        ReplacementRow record = new ReplacementRow(0L, replaceKey, experimentId,
                allocation.id(), allocation.blockNo(), participantId, newParticipantId,
                actorId, allocation.assignedAt(),
                allocation.withdrawnAt() == null ? 0L : allocation.withdrawnAt(), now);
        try {
            replacementRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 唯一约束兜底：原参与者已替补或新参与者已被使用。
            throw ApiException.conflict("原参与者已替补或新参与者已被使用");
        }
        return toView(record);
    }

    /** 区组替补历史（不含处理代码）；blockNo 为 null 时返回全实验。 */
    public List<ReplacementView> history(String experimentId, Integer blockNo) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        if (blockNo != null) {
            requireValidBlock(experiment, blockNo);
        }
        return replacementRepository.findHistory(experimentId, blockNo).stream()
                .map(this::toView)
                .toList();
    }

    /** 区组名额统计（不含处理代码）；可用名额在替补前后守恒。 */
    public BlockQuotaView quota(String experimentId, int blockNo) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        requireValidBlock(experiment, blockNo);
        long allocated = allocationRepository.countByBlock(experimentId, blockNo);
        long active = allocationRepository.countByBlockAndStatus(experimentId, blockNo, "ASSIGNED");
        long withdrawn =
                allocationRepository.countByBlockAndStatus(experimentId, blockNo, "WITHDRAWN");
        long replaced = replacementRepository.countByBlock(experimentId, blockNo);
        return new BlockQuotaView(experimentId, blockNo, ExperimentService.SEATS_PER_BLOCK,
                allocated, ExperimentService.SEATS_PER_BLOCK - allocated,
                active, withdrawn, replaced);
    }

    private ExperimentRow mustFindExperiment(String experimentId) {
        ExperimentRow row = experimentRepository.findById(experimentId);
        if (row == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        return row;
    }

    private void requireValidBlock(ExperimentRow experiment, int blockNo) {
        if (blockNo < 1 || blockNo > experiment.blockCount()) {
            throw ApiException.notFound(
                    "区组不存在: " + experiment.id() + " block " + blockNo);
        }
    }

    private ReplacementView toView(ReplacementRow row) {
        return new ReplacementView(row.experimentId(), row.replaceKey(), row.allocationId(),
                row.blockNo(), row.originalParticipantId(), row.newParticipantId(),
                row.operatorActor(), row.replacedAt());
    }
}
