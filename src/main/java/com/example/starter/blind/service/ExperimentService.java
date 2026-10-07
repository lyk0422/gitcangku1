package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.AllocationView;
import com.example.starter.blind.dto.ExperimentView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.AllocationRepository.CellCount;
import com.example.starter.blind.repo.AllocationRepository.VacantSeat;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 实验与分配核心业务：
 * BLOCK 模式创建时固定区组与每区组两 A 两 B 的席位内容，按区组、席位顺序领取第一个空位；
 * MINIMIZE 模式容量为偶数 4~20、A/B 各半、区组统一为 1，登记时携带两种 0/1 枚举特征，
 * 对每个还有席位的处理模拟分配并计算四个边际格 |A-B| 之和，选分数最小者，同分选 A。
 * 退组保留席位不重排且仍计入历史平衡；关闭后拒绝新增分配。
 * 普通视图不含席位号、处理代码、边际格计数与分数。
 */
@Service
public class ExperimentService {

    static final int SEATS_PER_BLOCK = 4;
    static final String MODE_BLOCK = "BLOCK";
    static final String MODE_MINIMIZE = "MINIMIZE";
    static final int MINIMIZE_MIN_CAPACITY = 4;
    static final int MINIMIZE_MAX_CAPACITY = 20;

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
     * MINIMIZE：区组统一为 1，前一半席位 A、后一半席位 B，容量为偶数 4~20。
     * 不记录任何处理映射到日志。
     */
    @Transactional
    public ExperimentView createExperiment(String experimentId, Integer blockCount,
                                           String mode, Integer capacity) {
        if (experimentId == null || experimentId.isBlank()) {
            throw ApiException.badRequest("experimentId 不能为空");
        }
        String normalizedMode = normalizeMode(mode);
        int blocks;
        int totalCapacity;
        if (MODE_MINIMIZE.equals(normalizedMode)) {
            if (blockCount != null) {
                throw ApiException.badRequest("MINIMIZE 模式区组统一为 1，不得携带 blockCount");
            }
            if (capacity == null || capacity < MINIMIZE_MIN_CAPACITY
                    || capacity > MINIMIZE_MAX_CAPACITY || capacity % 2 != 0) {
                throw ApiException.badRequest("MINIMIZE 模式容量必须为 4~20 的偶数");
            }
            blocks = 1;
            totalCapacity = capacity;
        } else {
            if (capacity != null) {
                throw ApiException.badRequest("BLOCK 模式容量由区组数决定，不得携带 capacity");
            }
            if (blockCount == null || blockCount < 2 || blockCount > 8) {
                throw ApiException.badRequest("区组数量必须在 2~8 之间");
            }
            blocks = blockCount;
            totalCapacity = blockCount * SEATS_PER_BLOCK;
        }
        if (experimentRepository.findById(experimentId) != null) {
            throw ApiException.conflict("实验已存在: " + experimentId);
        }
        long now = clock.nowMillis();
        experimentRepository.insertExperiment(
                new ExperimentRow(experimentId, normalizedMode, blocks, totalCapacity, "OPEN", now));
        List<SeatRow> seats = new ArrayList<>(totalCapacity);
        for (int blockNo = 1; blockNo <= blocks; blockNo++) {
            int seatsInBlock = MODE_MINIMIZE.equals(normalizedMode)
                    ? totalCapacity : SEATS_PER_BLOCK;
            for (int seatNo = 1; seatNo <= seatsInBlock; seatNo++) {
                // 按提交顺序：每区组前一半席位 A，后一半席位 B；内容创建后不可改。
                String treatment = seatNo <= seatsInBlock / 2 ? "A" : "B";
                seats.add(new SeatRow(experimentId, blockNo, seatNo, treatment));
            }
        }
        seats.forEach(experimentRepository::insertSeat);
        return toView(new ExperimentRow(experimentId, normalizedMode, blocks, totalCapacity,
                "OPEN", now));
    }

    public ExperimentView getExperiment(String experimentId) {
        return toView(mustFindExperiment(experimentId));
    }

    /**
     * 登记参与者：BLOCK 原子领取第一个空位；MINIMIZE 按边际格不平衡度最小化选处理后领席。
     * 实验行级锁串行化同实验并发分配，并发结果等价某个合法串行顺序。
     * MINIMIZE 下同参与者同特征重复登记按原分配重放，异特征 409。
     */
    @Transactional
    public AllocationView register(String experimentId, String participantId,
                                   Integer feature1, Integer feature2, String actorId) {
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
            if (MODE_MINIMIZE.equals(experiment.mode())
                    && sameFeatures(existing, feature1, feature2)) {
                // 同参与者同特征：按原分配重放，不重新占席（已退组也返回原分配）。
                return toView(existing);
            }
            // 同实验同参与者只占一席；异特征或 BLOCK 模式重复登记均冲突。
            throw ApiException.conflict("参与者已在该实验登记，仅可占一席");
        }
        VacantSeat vacant;
        Integer storedFeature1 = null;
        Integer storedFeature2 = null;
        if (MODE_MINIMIZE.equals(experiment.mode())) {
            storedFeature1 = requireFeature("feature1", feature1);
            storedFeature2 = requireFeature("feature2", feature2);
            String treatment = chooseTreatment(experiment, storedFeature1, storedFeature2);
            vacant = allocationRepository.takeFirstVacantSeatByTreatment(experimentId, treatment);
        } else {
            if (feature1 != null || feature2 != null) {
                throw ApiException.badRequest("BLOCK 模式登记不携带特征");
            }
            vacant = allocationRepository.takeFirstVacantSeat(experimentId);
        }
        if (vacant == null) {
            throw ApiException.full("实验席位已满");
        }
        long now = clock.nowMillis();
        AllocationRow inserted = insertWithUniqueBlindCode(experimentId, participantId, actorId,
                vacant, storedFeature1, storedFeature2, now);
        return toView(inserted);
    }

    /**
     * 最小化分配：对每个还有席位的处理，模拟把新参与者放入其特征格后，
     * 计算四个边际格（feature1 × feature2）上 A 人数减 B 人数的绝对值总和，
     * 选分数最小的处理；同分选 A。历史平衡含已退组分配。
     */
    private String chooseTreatment(ExperimentRow experiment, int feature1, int feature2) {
        long[][] countA = new long[2][2];
        long[][] countB = new long[2][2];
        long totalA = 0;
        long totalB = 0;
        for (CellCount cell : allocationRepository.countByTreatmentAndCell(experiment.id())) {
            if ("A".equals(cell.treatment())) {
                countA[cell.feature1()][cell.feature2()] += cell.count();
                totalA += cell.count();
            } else {
                countB[cell.feature1()][cell.feature2()] += cell.count();
                totalB += cell.count();
            }
        }
        long halfCapacity = experiment.capacity() / 2L;
        boolean aAvailable = totalA < halfCapacity;
        boolean bAvailable = totalB < halfCapacity;
        if (!aAvailable && !bAvailable) {
            throw ApiException.full("实验席位已满");
        }
        if (!bAvailable) {
            return "A";
        }
        if (!aAvailable) {
            return "B";
        }
        long scoreA = imbalanceScore(countA, countB, "A", feature1, feature2);
        long scoreB = imbalanceScore(countA, countB, "B", feature1, feature2);
        // 同分选 A。
        return scoreA <= scoreB ? "A" : "B";
    }

    /** 模拟把新参与者加入 candidate 处理后的四个边际格 |A-B| 之和。 */
    private long imbalanceScore(long[][] countA, long[][] countB, String candidate,
                                int feature1, int feature2) {
        long score = 0;
        for (int f1 = 0; f1 < 2; f1++) {
            for (int f2 = 0; f2 < 2; f2++) {
                long a = countA[f1][f2];
                long b = countB[f1][f2];
                if (f1 == feature1 && f2 == feature2) {
                    if ("A".equals(candidate)) {
                        a++;
                    } else {
                        b++;
                    }
                }
                score += Math.abs(a - b);
            }
        }
        return score;
    }

    private Integer requireFeature(String name, Integer value) {
        if (value == null || (value != 0 && value != 1)) {
            throw ApiException.badRequest(name + " 仅允许 0 或 1");
        }
        return value;
    }

    private boolean sameFeatures(AllocationRow existing, Integer feature1, Integer feature2) {
        return existing.feature1() != null && existing.feature1().equals(feature1)
                && existing.feature2() != null && existing.feature2().equals(feature2);
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_BLOCK;
        }
        if (MODE_BLOCK.equals(mode) || MODE_MINIMIZE.equals(mode)) {
            return mode;
        }
        throw ApiException.badRequest("mode 仅允许 BLOCK 或 MINIMIZE");
    }

    private AllocationRow insertWithUniqueBlindCode(String experimentId, String participantId,
                                                    String actorId, VacantSeat vacant,
                                                    Integer feature1, Integer feature2, long now) {
        // 盲码随机冲突概率极低，仍由唯一索引兜底并重试。
        for (int attempt = 0; attempt < 5; attempt++) {
            String blindCode = blindCodeGenerator.nextCode();
            AllocationRow row = new AllocationRow(0L, experimentId, participantId,
                    vacant.blockNo(), vacant.seatNo(), blindCode, feature1, feature2, "ASSIGNED",
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
     * 退组：状态置为 WITHDRAWN 并记录时间；席位不释放、已有分配不重排，
     * 退组记录仍计入最小化分配的历史平衡。与登记/关闭共享实验行级锁。
     */
    @Transactional
    public AllocationView withdraw(String experimentId, String participantId) {
        if (experimentRepository.lockById(experimentId) == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        AllocationRow allocation =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
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
        return toView(new ExperimentRow(experiment.id(), experiment.mode(), experiment.blockCount(),
                experiment.capacity(), "CLOSED", experiment.createdAt()));
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

    private ExperimentView toView(ExperimentRow row) {
        int seatsPerBlock = MODE_MINIMIZE.equals(row.mode()) ? row.capacity() : SEATS_PER_BLOCK;
        return new ExperimentView(row.id(), row.mode(), row.blockCount(), seatsPerBlock,
                row.capacity(), row.status(), row.createdAt());
    }

    private AllocationView toView(AllocationRow row) {
        return new AllocationView(row.experimentId(), row.participantId(), row.blindCode(),
                row.blockNo(), row.status(), row.assignedAt(), row.withdrawnAt());
    }
}
