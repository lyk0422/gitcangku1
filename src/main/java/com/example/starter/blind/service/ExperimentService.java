package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.AllocationView;
import com.example.starter.blind.dto.ExperimentView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.AllocationRepository.BalanceRow;
import com.example.starter.blind.repo.AllocationRepository.VacantSeat;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 实验与分配核心业务：
 * BLOCK 模式创建时固定区组与每区组两 A 两 B 的席位内容，按区组、席位顺序领取第一个空位；
 * MINIMIZE 模式容量为创建时给定的偶数（4~20，A/B 各半），区组统一为 1，
 * 登记时按协变量最小化算法选择处理后领取该处理的第一个空位。
 * 退组保留席位不重排、仍计入历史平衡；关闭后拒绝新增分配。普通视图不含席位号与处理代码。
 */
@Service
public class ExperimentService {

    static final int SEATS_PER_BLOCK = 4;
    /** MINIMIZE 模式容量下限（含）。 */
    static final int MIN_MINIMIZE_CAPACITY = 4;
    /** MINIMIZE 模式容量上限（含）。 */
    static final int MAX_MINIMIZE_CAPACITY = 20;

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final BlindCodeGenerator blindCodeGenerator;
    private final Clock clock;

    public ExperimentService(ExperimentRepository experimentRepository,
                             AllocationRepository allocationRepository,
                             BlindCodeGenerator blindCodeGenerator,
                             Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.blindCodeGenerator = blindCodeGenerator;
        this.clock = clock;
    }

    /**
     * 创建实验并固定席位。BLOCK：每区组按席位顺序两个 A、两个 B；
     * MINIMIZE：区组统一为 1，前一半席位 A、后一半席位 B，容量为 4~20 的偶数。
     * 不记录任何处理映射到日志。
     */
    @Transactional
    public ExperimentView createExperiment(String experimentId, String mode,
                                           Integer blockCount, Integer capacity) {
        if (experimentId == null || experimentId.isBlank()) {
            throw ApiException.badRequest("experimentId 不能为空");
        }
        String normalizedMode = (mode == null || mode.isBlank())
                ? "BLOCK" : mode.trim().toUpperCase(Locale.ROOT);
        final int blocks;
        final int totalCapacity;
        if ("BLOCK".equals(normalizedMode)) {
            if (blockCount == null || blockCount < 2 || blockCount > 8) {
                throw ApiException.badRequest("区组数量必须在 2~8 之间");
            }
            blocks = blockCount;
            totalCapacity = blockCount * SEATS_PER_BLOCK;
        } else if ("MINIMIZE".equals(normalizedMode)) {
            if (capacity == null || capacity < MIN_MINIMIZE_CAPACITY
                    || capacity > MAX_MINIMIZE_CAPACITY || capacity % 2 != 0) {
                throw ApiException.badRequest("MINIMIZE 容量必须为 4~20 的偶数");
            }
            blocks = 1;
            totalCapacity = capacity;
        } else {
            throw ApiException.badRequest("mode 仅支持 BLOCK 或 MINIMIZE");
        }
        if (experimentRepository.findById(experimentId) != null) {
            throw ApiException.conflict("实验已存在: " + experimentId);
        }
        long now = clock.nowMillis();
        ExperimentRow row = new ExperimentRow(experimentId, normalizedMode, blocks,
                totalCapacity, "OPEN", now);
        experimentRepository.insertExperiment(row);
        List<SeatRow> seats = new ArrayList<>(totalCapacity);
        if ("BLOCK".equals(normalizedMode)) {
            for (int blockNo = 1; blockNo <= blocks; blockNo++) {
                for (int seatNo = 1; seatNo <= SEATS_PER_BLOCK; seatNo++) {
                    // 按提交顺序：每区组前两席 A，后两席 B；内容创建后不可改。
                    String treatment = seatNo <= 2 ? "A" : "B";
                    seats.add(new SeatRow(experimentId, blockNo, seatNo, treatment));
                }
            }
        } else {
            // MINIMIZE：单区组，前一半席位 A、后一半席位 B；内容创建后不可改。
            int half = totalCapacity / 2;
            for (int seatNo = 1; seatNo <= totalCapacity; seatNo++) {
                seats.add(new SeatRow(experimentId, 1, seatNo, seatNo <= half ? "A" : "B"));
            }
        }
        seats.forEach(experimentRepository::insertSeat);
        return toExperimentView(row);
    }

    public ExperimentView getExperiment(String experimentId) {
        return toExperimentView(mustFindExperiment(experimentId));
    }

    /**
     * 原子领取席位；实验行级锁串行化同实验并发分配，等价于某个合法串行顺序。
     * BLOCK 模式按区组、席位顺序领取第一个空位；MINIMIZE 模式先按协变量最小化
     * 选择处理，再领取该处理的第一个空位。MINIMIZE 下同参与者同特征重复登记
     * 按原分配重放，异特征冲突 409。
     */
    @Transactional
    public AllocationView register(String experimentId, String participantId, String actorId,
                                   Integer feature1, Integer feature2) {
        if (participantId == null || participantId.isBlank()) {
            throw ApiException.badRequest("participantId 不能为空");
        }
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        boolean minimize = "MINIMIZE".equals(experiment.mode());
        validateFeatures(minimize, feature1, feature2);
        if ("CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，拒绝新增分配");
        }
        AllocationRow existing =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (existing != null) {
            if (minimize && existing.feature1().equals(feature1)
                    && existing.feature2().equals(feature2)) {
                // 同参与者同特征：按原分配重放（含已退组的原视图），不新增席位。
                return toView(existing);
            }
            // 同实验同参与者只占一席；MINIMIZE 异特征、BLOCK 重复登记、已退组重新登记均冲突。
            throw ApiException.conflict(minimize
                    ? "参与者已在该实验登记，特征不一致" : "参与者已在该实验登记，仅可占一席");
        }
        VacantSeat vacant = minimize
                ? chooseMinimizeSeat(experiment, feature1, feature2)
                : allocationRepository.takeFirstVacantSeat(experimentId);
        if (vacant == null) {
            throw ApiException.full("实验席位已满");
        }
        long now = clock.nowMillis();
        AllocationRow inserted = insertWithUniqueBlindCode(experimentId, participantId, actorId,
                feature1, feature2, vacant, now);
        return toView(inserted);
    }

    private AllocationRow insertWithUniqueBlindCode(String experimentId, String participantId,
                                                    String actorId, Integer feature1,
                                                    Integer feature2, VacantSeat vacant, long now) {
        // 盲码随机冲突概率极低，仍由唯一索引兜底并重试。
        for (int attempt = 0; attempt < 5; attempt++) {
            String blindCode = blindCodeGenerator.nextCode();
            AllocationRow row = new AllocationRow(0L, experimentId, participantId,
                    vacant.blockNo(), vacant.seatNo(), feature1, feature2, blindCode, "ASSIGNED",
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
        return new ExperimentView(experiment.id(), experiment.blockCount(),
                experiment.capacity() / experiment.blockCount(), experiment.capacity(),
                "CLOSED", experiment.createdAt(), experiment.mode());
    }

    /**
     * 普通查询：只返回盲码、区组号、参与者编号与退组状态。
     */
    public AllocationView getAllocation(String experimentId, String participantId) {
        return toView(mustFindAllocation(experimentId, participantId));
    }

    /** 内部使用的完整分配行（含盲底字段），禁止直接透出到普通响应。 */
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
     * 特征参数校验：MINIMIZE 必须提供取值 0/1 的两个特征；BLOCK 不接受特征参数。
     */
    private void validateFeatures(boolean minimize, Integer feature1, Integer feature2) {
        if (minimize) {
            if (!isBinaryFeature(feature1) || !isBinaryFeature(feature2)) {
                throw ApiException.badRequest("MINIMIZE 实验登记必须提供取值 0 或 1 的 feature1 与 feature2");
            }
        } else if (feature1 != null || feature2 != null) {
            throw ApiException.badRequest("固定区组实验不接受特征参数");
        }
    }

    private boolean isBinaryFeature(Integer value) {
        return value != null && (value == 0 || value == 1);
    }

    /**
     * MINIMIZE 选位：读取含已退组的全部历史分配，按剩余席位对 A/B 模拟分配并比较
     * 失衡分数，选中处理后领取该处理的第一个空位；两种处理均满时返回 null（满额 422）。
     */
    private VacantSeat chooseMinimizeSeat(ExperimentRow experiment, int feature1, int feature2) {
        List<BalanceRow> rows = allocationRepository.findBalanceRows(experiment.id());
        int half = experiment.capacity() / 2;
        int aUsed = 0;
        for (BalanceRow row : rows) {
            if (MinimizationAllocator.TREATMENT_A.equals(row.treatment())) {
                aUsed++;
            }
        }
        int bUsed = rows.size() - aUsed;
        String chosen = MinimizationAllocator.chooseTreatment(rows, feature1, feature2,
                half - aUsed, half - bUsed);
        if (chosen == null) {
            return null;
        }
        return allocationRepository.takeFirstVacantSeatByTreatment(experiment.id(), chosen);
    }

    private ExperimentView toExperimentView(ExperimentRow row) {
        return new ExperimentView(row.id(), row.blockCount(), row.capacity() / row.blockCount(),
                row.capacity(), row.status(), row.createdAt(), row.mode());
    }
}
