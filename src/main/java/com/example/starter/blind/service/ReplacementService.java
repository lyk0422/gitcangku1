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
 * 仅对已退组且从未揭盲的参与者可替补；同一事务内把原分配行原地转移给替补参与者
 * （不新建分配序号、不改变区组席位与处理代码配额），原参与者转为 REPLACED 终态。
 * 替补记录不可变且不包含处理代码；处理代码不返回给操作人。
 * 区组可用名额 = 总席位 - 未退组且未替补的参与者数，退组释放的名额恰被替补填回，账目守恒。
 */
@Service
public class ReplacementService {

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final ReplacementRepository replacementRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final BlindCodeGenerator blindCodeGenerator;
    private final Clock clock;

    public ReplacementService(ExperimentRepository experimentRepository,
                              AllocationRepository allocationRepository,
                              ReplacementRepository replacementRepository,
                              UnblindRequestRepository unblindRequestRepository,
                              BlindCodeGenerator blindCodeGenerator,
                              Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.replacementRepository = replacementRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.blindCodeGenerator = blindCodeGenerator;
        this.clock = clock;
    }

    /**
     * 替补登记：实验行锁串行化同实验并发，分配行锁与揭盲批准互斥，
     * 按事务提交顺序裁决（替补先提交则后续对原参与者揭盲 409，反之替补 409）。
     */
    @Transactional
    public ReplacementView replace(String experimentId, String originalParticipantId,
                                   String replaceKey, String newParticipantId, String actorId) {
        if (replaceKey == null || replaceKey.isBlank()) {
            throw ApiException.badRequest("replaceKey 不能为空");
        }
        if (newParticipantId == null || newParticipantId.isBlank()) {
            throw ApiException.badRequest("newParticipantId 不能为空");
        }
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        AllocationRow allocation =
                allocationRepository.lockByExperimentAndParticipant(experimentId, originalParticipantId);
        if (allocation == null) {
            if (replacementRepository.findByOriginal(experimentId, originalParticipantId) != null) {
                throw ApiException.conflict("参与者已被替补，处于 REPLACED 终态");
            }
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        if (!"WITHDRAWN".equals(allocation.status())) {
            // 未退组（在组）不可替补。
            throw ApiException.conflict("仅已退组且未揭盲的参与者可替补");
        }
        if (unblindRequestRepository.existsApprovedByAllocation(allocation.id())) {
            throw ApiException.conflict("参与者已揭盲，不可替补");
        }
        if (allocationRepository.findByExperimentAndParticipant(experimentId, newParticipantId) != null
                || replacementRepository.findByOriginal(experimentId, newParticipantId) != null
                || replacementRepository.findByNew(experimentId, newParticipantId) != null) {
            // 新参与者已存在于区组或拥有历史分配（含曾被替补、曾作为替补者）。
            throw ApiException.conflict("新参与者已存在或拥有历史分配: " + newParticipantId);
        }
        long now = clock.nowMillis();
        transferWithUniqueBlindCode(allocation, newParticipantId, actorId, now);
        // 原参与者的待审揭盲申请保留且仍指向原标识（不可再批准），
        // 但释放其待审去重占位，替补参与者可独立申请揭盲。
        unblindRequestRepository.releasePendingByAllocation(allocation.id());
        ReplacementRow record = new ReplacementRow(0L, replaceKey, experimentId,
                allocation.blockNo(), allocation.id(), originalParticipantId,
                newParticipantId, actorId, now);
        try {
            replacementRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // replaceKey 重复或并发替补同一参与者，由唯一约束兜底。
            throw ApiException.conflict("replaceKey 已被使用或参与者替补冲突");
        }
        return toView(record);
    }

    /**
     * 原地转移分配行并更换盲码；盲码全局唯一冲突时重试，状态守卫失败按并发冲突处理。
     */
    private void transferWithUniqueBlindCode(AllocationRow allocation, String newParticipantId,
                                             String actorId, long now) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String blindCode = blindCodeGenerator.nextCode();
            try {
                int updated = allocationRepository.transferToReplacement(
                        allocation.id(), newParticipantId, blindCode, actorId, now);
                if (updated == 0) {
                    throw ApiException.conflict("参与者状态已变化，替补冲突，请重试");
                }
                return;
            } catch (DuplicateKeyException e) {
                if (allocationRepository.isDuplicateBlindCode(e)) {
                    continue;
                }
                // 新参与者占位或席位冲突：唯一约束兜底。
                throw ApiException.conflict("新参与者已存在或席位冲突，请重试");
            }
        }
        throw ApiException.conflict("盲码生成冲突，请重试");
    }

    /**
     * 区组替补历史（不含处理代码与席位号）。
     */
    public List<ReplacementView> listBlockReplacements(String experimentId, int blockNo) {
        mustFindBlock(experimentId, blockNo);
        return replacementRepository.listByBlock(experimentId, blockNo).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 区组名额统计（不含处理代码）：可用名额 = 总席位 - 未退组且未替补的参与者数。
     */
    public BlockQuotaView getBlockQuota(String experimentId, int blockNo) {
        mustFindBlock(experimentId, blockNo);
        long active = allocationRepository.countActiveByBlock(experimentId, blockNo);
        int total = ExperimentService.SEATS_PER_BLOCK;
        return new BlockQuotaView(experimentId, blockNo, total, active, total - active);
    }

    private ExperimentRow mustFindBlock(String experimentId, int blockNo) {
        ExperimentRow experiment = experimentRepository.findById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if (blockNo < 1 || blockNo > experiment.blockCount()) {
            throw ApiException.notFound("区组不存在: " + blockNo);
        }
        return experiment;
    }

    private ReplacementView toView(ReplacementRow row) {
        return new ReplacementView(row.replaceKey(), row.experimentId(), row.blockNo(),
                row.allocationId(), row.originalParticipantId(), row.newParticipantId(),
                row.actorId(), row.replacedAt());
    }
}
