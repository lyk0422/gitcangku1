package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.AllocationDetailView;
import com.example.starter.blind.dto.AllocationView;
import com.example.starter.blind.dto.ExperimentView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.AllocationRepository.VacantSeat;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import com.example.starter.blind.repo.RandomTableVersionRepository;
import com.example.starter.blind.repo.RandomTableVersionRepository.RandomTableVersionRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 实验与分配核心业务：
 * 创建实验时为每个区组生成 v1 随机表版本（每版 4 个序号、两 A 两 B）；
 * 分配按区组、版本、版本内序号领取 ACTIVE 版本的第一个空位并固化版本与序号；
 * 退组保留序号不重排；关闭后拒绝新增分配。普通视图不含序号处理代码。
 */
@Service
public class ExperimentService {

    static final int SEATS_PER_BLOCK = 4;

    private final ExperimentRepository experimentRepository;
    private final RandomTableVersionRepository randomTableVersionRepository;
    private final AllocationRepository allocationRepository;
    private final BlindCodeGenerator blindCodeGenerator;
    private final Clock clock;

    public ExperimentService(ExperimentRepository experimentRepository,
                             RandomTableVersionRepository randomTableVersionRepository,
                             AllocationRepository allocationRepository,
                             BlindCodeGenerator blindCodeGenerator,
                             Clock clock) {
        this.experimentRepository = experimentRepository;
        this.randomTableVersionRepository = randomTableVersionRepository;
        this.allocationRepository = allocationRepository;
        this.blindCodeGenerator = blindCodeGenerator;
        this.clock = clock;
    }

    /**
     * 创建实验并固定 v1 随机表：每区组一个 v1 版本，按序号两个 A、两个 B。
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
        String codes = RandomTableGenerator.TREATMENT_CODES.get(0) + ","
                + RandomTableGenerator.TREATMENT_CODES.get(1);
        for (int blockNo = 1; blockNo <= blockCount; blockNo++) {
            RandomTableVersionRow version = randomTableVersionRepository.insert(
                    new RandomTableVersionRow(0L, experimentId, blockNo, 1, null,
                            SEATS_PER_BLOCK, codes, "ACTIVE", now, null));
            for (int seatNo = 1; seatNo <= SEATS_PER_BLOCK; seatNo++) {
                // v1 按提交顺序：每版前两号 A，后两号 B；内容创建后不可改。
                String treatment = seatNo <= 2 ? "A" : "B";
                experimentRepository.insertSeat(new SeatRow(version.id(), experimentId, blockNo,
                        seatNo, treatment));
            }
        }
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
        VacantSeat vacant = allocationRepository.takeFirstVacantSeat(experimentId);
        if (vacant == null) {
            throw ApiException.full("实验当前随机表版本已无未分配名额");
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
                    actorId, now, null, vacant.tableVersionId());
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
                    throw ApiException.conflict("序号或参与者已被占用，请重试");
                }
                throw e;
            }
        }
        throw ApiException.conflict("盲码生成冲突，请重试");
    }

    /**
     * 退组：状态置为 WITHDRAWN 并记录时间；序号不释放、已有分配不重排。
     */
    @Transactional
    public AllocationView withdraw(String experimentId, String participantId) {
        AllocationRow allocation = mustFindAllocation(experimentId, participantId);
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
        return toView(mustFindAllocation(experimentId, participantId));
    }

    /** 内部使用的完整分配行（含固化版本字段），禁止直接透出到普通响应。 */
    public AllocationRow mustFindAllocationRow(String experimentId, String participantId) {
        return mustFindAllocation(experimentId, participantId);
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
                row.blockNo(), row.status(), row.assignedAt(), row.withdrawnAt());
    }

    /**
     * 分配明细（只读，不改状态）：返回每次分配固化的随机表版本号，
     * 不返回版本内序号与处理代码；按分配时刻、主键稳定升序。
     */
    public List<AllocationDetailView> listAllocationDetails(String experimentId) {
        mustFindExperiment(experimentId);
        return allocationRepository.findByExperimentOrdered(experimentId).stream()
                .map(row -> {
                    RandomTableVersionRow version =
                            randomTableVersionRepository.findById(row.tableVersionId());
                    if (version == null) {
                        throw new IllegalStateException("分配固化的随机表版本缺失，数据不一致");
                    }
                    return new AllocationDetailView(row.experimentId(), row.participantId(),
                            row.blindCode(), row.blockNo(), version.versionNo(), row.status(),
                            row.assignedAt(), row.withdrawnAt());
                })
                .toList();
    }
}
