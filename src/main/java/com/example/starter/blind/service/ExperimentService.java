package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.AllocationView;
import com.example.starter.blind.dto.ExperimentView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.AllocationRepository.VacantSeat;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import com.example.starter.blind.repo.ReplacementRepository;
import com.example.starter.blind.repo.ReplacementRepository.ReplacementRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 实验与分配核心业务：
 * 创建实验时固定区组与每区组两 A 两 B 的席位内容；分配按区组、席位顺序领取第一个空位；
 * 退组保留席位不重排；关闭后拒绝新增分配。普通视图不含席位号与处理代码。
 */
@Service
public class ExperimentService {

    static final int SEATS_PER_BLOCK = 4;

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final ReplacementRepository replacementRepository;
    private final BlindCodeGenerator blindCodeGenerator;
    private final Clock clock;

    public ExperimentService(ExperimentRepository experimentRepository,
                             AllocationRepository allocationRepository,
                             ReplacementRepository replacementRepository,
                             BlindCodeGenerator blindCodeGenerator,
                             Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.replacementRepository = replacementRepository;
        this.blindCodeGenerator = blindCodeGenerator;
        this.clock = clock;
    }

    /**
     * 创建实验并固定席位：每区组按席位顺序两个 A、两个 B。
     * 不记录任何处理映射到日志。
     */
    @Transactional
    public ExperimentView createExperiment(String experimentId, int blockCount) {
        if (experimentId == null || experimentId.isBlank()) {
            throw ApiException.badRequest("experimentId 不能为空");
        }
        if (experimentRepository.findById(experimentId) != null) {
            throw ApiException.conflict("实验已存在: " + experimentId);
        }
        long now = clock.nowMillis();
        experimentRepository.insertExperiment(
                new ExperimentRow(experimentId, blockCount, "OPEN", now));
        List<SeatRow> seats = new ArrayList<>(blockCount * SEATS_PER_BLOCK);
        for (int blockNo = 1; blockNo <= blockCount; blockNo++) {
            for (int seatNo = 1; seatNo <= SEATS_PER_BLOCK; seatNo++) {
                // 按提交顺序：每区组前两席 A，后两席 B；内容创建后不可改。
                String treatment = seatNo <= 2 ? "A" : "B";
                seats.add(new SeatRow(experimentId, blockNo, seatNo, treatment));
            }
        }
        seats.forEach(experimentRepository::insertSeat);
        return new ExperimentView(experimentId, blockCount, SEATS_PER_BLOCK,
                blockCount * SEATS_PER_BLOCK, "OPEN", now);
    }

    public ExperimentView getExperiment(String experimentId) {
        ExperimentRow row = mustFindExperiment(experimentId);
        return new ExperimentView(row.id(), row.blockCount(), SEATS_PER_BLOCK,
                row.blockCount() * SEATS_PER_BLOCK, row.status(), row.createdAt());
    }

    /**
     * 原子领取第一个空位；实验行级锁串行化同实验并发分配。
     */
    @Transactional
    public AllocationView register(String experimentId, String participantId, String actorId) {
        if (participantId == null || participantId.isBlank()) {
            throw ApiException.badRequest("participantId 不能为空");
        }
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if ("CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，拒绝新增分配");
        }
        AllocationRow existing =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (existing != null) {
            // 同实验同参与者只占一席；已退组也不重新占席。
            throw ApiException.conflict("参与者已在该实验登记，仅可占一席");
        }
        if (replacementRepository.existsInExperimentByParticipant(experimentId, participantId)) {
            // 已进入过替补流程（被替补或作为替补）的参与者处于流程终态，不可重新登记。
            throw ApiException.conflict("参与者已进入替补流程，不可重新登记");
        }
        VacantSeat vacant = allocationRepository.takeFirstVacantSeat(experimentId);
        if (vacant == null) {
            throw ApiException.full("实验席位已满");
        }
        long now = clock.nowMillis();
        AllocationRow inserted = insertWithUniqueBlindCode(experimentId, participantId, actorId,
                vacant, now);
        return toView(inserted);
    }

    private AllocationRow insertWithUniqueBlindCode(String experimentId, String participantId,
                                                    String actorId, VacantSeat vacant, long now) {
        // 盲码随机冲突概率极低，仍由唯一索引兜底并重试。
        for (int attempt = 0; attempt < 5; attempt++) {
            String blindCode = blindCodeGenerator.nextCode();
            AllocationRow row = new AllocationRow(0L, experimentId, participantId,
                    vacant.blockNo(), vacant.seatNo(), blindCode, "ASSIGNED",
                    actorId, now, null);
            try {
                allocationRepository.insert(row);
                return allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
            } catch (DuplicateKeyException e) {
                if (allocationRepository.isDuplicateBlindCode(e)) {
                    continue;
                }
                if (allocationRepository.isDuplicateSeat(e)
                        || allocationRepository.isDuplicateParticipant(e)) {
                    // 极端并发下由唯一约束兜底，按冲突处理，事务随幂等占位一并回滚。
                    throw ApiException.conflict("席位或参与者已被占用，请重试");
                }
                throw e;
            }
        }
        throw ApiException.conflict("盲码生成冲突，请重试");
    }

    /**
     * 退组：状态置为 WITHDRAWN 并记录时间；席位不释放、已有分配不重排。
     */
    @Transactional
    public AllocationView withdraw(String experimentId, String participantId) {
        AllocationRow allocation =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            mustFindExperiment(experimentId);
            if (replacementRepository.findByOriginal(experimentId, participantId) != null) {
                throw ApiException.conflict("参与者已被替补，处于 REPLACED 终态");
            }
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        if ("WITHDRAWN".equals(allocation.status())) {
            throw ApiException.conflict("参与者已退组");
        }
        allocationRepository.markWithdrawn(allocation.id(), clock.nowMillis());
        AllocationRow refreshed =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        return toView(refreshed);
    }

    /**
     * 关闭实验；已关闭再次关闭返回 409。
     */
    @Transactional
    public ExperimentView close(String experimentId) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if ("CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭");
        }
        experimentRepository.markClosed(experimentId);
        return new ExperimentView(experiment.id(), experiment.blockCount(), SEATS_PER_BLOCK,
                experiment.blockCount() * SEATS_PER_BLOCK, "CLOSED", experiment.createdAt());
    }

    /**
     * 普通查询：只返回盲码、区组号、参与者编号与退组状态。
     */
    public AllocationView getAllocation(String experimentId, String participantId) {
        AllocationRow row =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (row != null) {
            return toView(row);
        }
        mustFindExperiment(experimentId);
        ReplacementRow replaced = replacementRepository.findByOriginal(experimentId, participantId);
        if (replaced == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        // 分配序号被继承但记录保留：原参与者查询返回 REPLACED 终态视图。
        AllocationRow slot = allocationRepository.findById(replaced.allocationId());
        String blindCode = slot == null ? null : slot.blindCode();
        return new AllocationView(experimentId, participantId, blindCode, replaced.blockNo(),
                "REPLACED", replaced.originalAssignedAt(), replaced.originalWithdrawnAt(),
                replaced.replacedAt());
    }

    /** 内部使用的完整分配行（含盲底字段），禁止直接透出到普通响应。 */
    public AllocationRow mustFindAllocationRow(String experimentId, String participantId) {
        return mustFindAllocation(experimentId, participantId);
    }

    /** 校验实验存在，不存在抛 404。 */
    public void requireExperiment(String experimentId) {
        mustFindExperiment(experimentId);
    }

    private AllocationRow mustFindAllocation(String experimentId, String participantId) {
        mustFindExperiment(experimentId);
        AllocationRow row =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (row == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        return row;
    }

    private ExperimentRow mustFindExperiment(String experimentId) {
        ExperimentRow row = experimentRepository.findById(experimentId);
        if (row == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        return row;
    }

    private AllocationView toView(AllocationRow row) {
        return new AllocationView(row.experimentId(), row.participantId(), row.blindCode(),
                row.blockNo(), row.status(), row.assignedAt(), row.withdrawnAt(), null);
    }
}
