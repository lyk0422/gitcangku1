package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.AllocationView;
import com.example.starter.blind.dto.CapacityView;
import com.example.starter.blind.dto.ExperimentView;
import com.example.starter.blind.dto.ExtensionHistoryItem;
import com.example.starter.blind.dto.ExtensionHistoryView;
import com.example.starter.blind.dto.ExtensionView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.AllocationRepository.VacantSeat;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import com.example.starter.blind.repo.ExtensionRepository;
import com.example.starter.blind.repo.ExtensionRepository.ExtensionRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 实验与分配核心业务：
 * 创建实验时固定区组与每区组两 A 两 B 的席位内容；分配按区组、席位顺序领取第一个空位；
 * 退组保留席位不重排；关闭后拒绝新增分配；OPEN 实验可由协调员追加 1~4 个区组（扩容），
 * 扩容在一个事务内追加席位、区组总数与版本加一。普通视图不含席位号与处理代码。
 */
@Service
public class ExperimentService {

    static final int SEATS_PER_BLOCK = 4;
    /** 创建时区组数量范围。 */
    static final int INITIAL_MIN_BLOCKS = 2;
    static final int INITIAL_MAX_BLOCKS = 8;
    /** 扩容后区组总数硬上限。 */
    static final int MAX_TOTAL_BLOCKS = 16;
    /** 单次扩容追加区组数量范围。 */
    static final int MAX_BLOCKS_PER_EXTENSION = 4;

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final ExtensionRepository extensionRepository;
    private final BlindCodeGenerator blindCodeGenerator;
    private final Clock clock;

    public ExperimentService(ExperimentRepository experimentRepository,
                             AllocationRepository allocationRepository,
                             ExtensionRepository extensionRepository,
                             BlindCodeGenerator blindCodeGenerator,
                             Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.extensionRepository = extensionRepository;
        this.blindCodeGenerator = blindCodeGenerator;
        this.clock = clock;
    }

    /**
     * 创建实验并固定席位：每区组按席位顺序两个 A、两个 B。初始版本为 1。
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
                new ExperimentRow(experimentId, blockCount, 1, "OPEN", now));
        insertFixedSeats(experimentId, 1, blockCount);
        return experimentView(experimentId, blockCount, 1, "OPEN", now);
    }

    public ExperimentView getExperiment(String experimentId) {
        ExperimentRow row = mustFindExperiment(experimentId);
        return toView(row);
    }

    /**
     * 区组扩容：在一个事务内追加区组并使实验版本加一。
     * <ul>
     *   <li>仅 OPEN 实验可扩容，CLOSED 返回 409；</li>
     *   <li>expectedVersion 与库内版本不一致返回 409；</li>
     *   <li>每个追加区组必须恰好含两个 A、两个 B，否则整次 422；</li>
     *   <li>追加后区组总数超过 16 返回 422；</li>
     *   <li>新区组号从现有最大区组号加一起连续编号，已有区组、席位与分配不改写；</li>
     *   <li>extensionKey 全局唯一，命中他处唯一键返回 409（失败不占键）。</li>
     * </ul>
     */
    @Transactional
    public ExtensionView extendBlocks(String experimentId, String extensionKey,
                                      int expectedVersion, List<List<String>> blocks,
                                      String operatorActor) {
        if (extensionKey == null || extensionKey.isBlank()) {
            throw ApiException.badRequest("extensionKey 不能为空");
        }
        if (blocks == null || blocks.isEmpty()
                || blocks.size() > MAX_BLOCKS_PER_EXTENSION) {
            throw ApiException.unprocessable("单次仅可追加 1~4 个区组");
        }
        // 行级锁串行化扩容与登记、退组、关闭的同实验并发，按事务提交顺序裁决。
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if ("CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，拒绝扩容");
        }
        if (expectedVersion != experiment.version()) {
            throw ApiException.conflict("实验版本已变化，expectedVersion 与当前版本不一致");
        }
        // 配比校验先于任何写入，不符整次失败（事务回滚，不占 extensionKey）。
        validateBlockTreatments(blocks);
        int fromBlockCount = experiment.blockCount();
        int added = blocks.size();
        int newBlockCount = fromBlockCount + added;
        if (newBlockCount > MAX_TOTAL_BLOCKS) {
            throw ApiException.unprocessable(
                    "扩容后区组总数不得超过 " + MAX_TOTAL_BLOCKS);
        }
        int fromBlockNo = fromBlockCount + 1;
        int toBlockNo = newBlockCount;
        int resultingVersion = experiment.version() + 1;
        long now = clock.nowMillis();

        // 严格按提交顺序写入新区组席位，处理代码只落库、不入视图与日志。
        for (int i = 0; i < added; i++) {
            int blockNo = fromBlockNo + i;
            List<String> treatments = blocks.get(i);
            for (int seatNo = 1; seatNo <= SEATS_PER_BLOCK; seatNo++) {
                experimentRepository.insertSeat(
                        new SeatRow(experimentId, blockNo, seatNo, treatments.get(seatNo - 1)));
            }
        }
        experimentRepository.applyExtension(experimentId, newBlockCount);
        try {
            extensionRepository.insert(new ExtensionRow(0L, extensionKey, experimentId,
                    expectedVersion, fromBlockCount, added, fromBlockNo, toBlockNo,
                    resultingVersion, operatorActor, now));
        } catch (DuplicateKeyException e) {
            if (extensionRepository.isDuplicateKey(e)) {
                // 键已被其他扩容（或其他实验）占用：冲突失败，整个事务回滚不占键。
                throw ApiException.conflict("extensionKey 已被使用: " + extensionKey);
            }
            throw e;
        }
        return new ExtensionView(experimentId, extensionKey, expectedVersion, resultingVersion,
                newBlockCount, SEATS_PER_BLOCK, newBlockCount * SEATS_PER_BLOCK, added, "OPEN");
    }

    /**
     * 区组容量与已用席位统计；已用席位含已退组（退组不释放席位）。
     */
    public CapacityView getCapacity(String experimentId) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        int totalSeats = experiment.blockCount() * SEATS_PER_BLOCK;
        int occupied = (int) allocationRepository.countOccupied(experimentId);
        return new CapacityView(experimentId, experiment.version(), experiment.status(),
                SEATS_PER_BLOCK, experiment.blockCount(), totalSeats, occupied,
                totalSeats - occupied);
    }

    /**
     * 扩容历史查询；实验关闭后记录仍保留可查，不泄露处理映射。
     */
    public ExtensionHistoryView getExtensionHistory(String experimentId) {
        mustFindExperiment(experimentId);
        List<ExtensionRow> rows = extensionRepository.findByExperiment(experimentId);
        List<ExtensionHistoryItem> items = new ArrayList<>(rows.size());
        for (ExtensionRow row : rows) {
            items.add(new ExtensionHistoryItem(row.extensionKey(), row.expectedVersion(),
                    row.resultingVersion(), row.fromBlockCount(), row.addedBlockCount(),
                    row.fromBlockNo(), row.toBlockNo(), row.operatorActor(), row.createdAt()));
        }
        return new ExtensionHistoryView(experimentId, items);
    }

    private void validateBlockTreatments(List<List<String>> blocks) {
        for (int b = 0; b < blocks.size(); b++) {
            List<String> treatments = blocks.get(b);
            if (treatments == null || treatments.size() != SEATS_PER_BLOCK) {
                throw ApiException.unprocessable("每个区组必须恰好包含 4 个席位处理代码");
            }
            long countA = treatments.stream().filter("A"::equals).count();
            long countB = treatments.stream().filter("B"::equals).count();
            if (countA != 2 || countB != 2) {
                // 错误信息不含具体处理序列，避免在错误通道暴露映射。
                throw ApiException.unprocessable(
                        "第 " + (b + 1) + " 个追加区组配比不符，每个区组必须含两个 A 和两个 B");
            }
        }
    }

    private void insertFixedSeats(String experimentId, int firstBlockNo, int blockCount) {
        for (int blockNo = firstBlockNo; blockNo < firstBlockNo + blockCount; blockNo++) {
            for (int seatNo = 1; seatNo <= SEATS_PER_BLOCK; seatNo++) {
                // 按提交顺序：每区组前两席 A，后两席 B；内容创建后不可改。
                String treatment = seatNo <= 2 ? "A" : "B";
                experimentRepository.insertSeat(new SeatRow(experimentId, blockNo, seatNo, treatment));
            }
        }
    }

    /**
     * 原子领取第一个空位；实验行级锁串行化同实验并发分配。
     * 扩容后新席位排在区组顺序末尾，退组席位不参与 LEFT JOIN 空位判定，故绝不回填。
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
        return toView(new ExperimentRow(experiment.id(), experiment.blockCount(),
                experiment.version(), "CLOSED", experiment.createdAt()));
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
        return experimentView(row.id(), row.blockCount(), row.version(), row.status(),
                row.createdAt());
    }

    private ExperimentView experimentView(String experimentId, int blockCount, int version,
                                          String status, long createdAt) {
        return new ExperimentView(experimentId, blockCount, version, SEATS_PER_BLOCK,
                blockCount * SEATS_PER_BLOCK, status, createdAt);
    }

    private AllocationView toView(AllocationRow row) {
        return new AllocationView(row.experimentId(), row.participantId(), row.blindCode(),
                row.blockNo(), row.status(), row.assignedAt(), row.withdrawnAt());
    }
}
