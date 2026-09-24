package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.BlockExtensionHistoryView;
import com.example.starter.blind.dto.BlockExtensionView;
import com.example.starter.blind.dto.CapacityStatsView;
import com.example.starter.blind.dto.ExtendBlocksRequest;
import com.example.starter.blind.dto.ExtensionBlockRequest;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.BlockExtensionRepository;
import com.example.starter.blind.repo.BlockExtensionRepository.ExtensionRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 盲法区组扩容业务：
 * <ul>
 *   <li>仅 OPEN 实验可由 COORDINATOR 追加 1~4 个区组，每区组 4 席且按提交顺序两个 A 两个 B；</li>
 *   <li>扩容在一个事务内追加席位、区组总数与版本（+1）并写扩容记录；</li>
 *   <li>新区组号从现有最大区组号 + 1 起连续编号，已有区组、席位内容、分配与退组席位均不改写；</li>
 *   <li>实验行级锁与登记/退组/关闭按事务提交顺序串行裁决；</li>
 *   <li>扩容视图、统计与历史均不含处理代码或席位序号。</li>
 * </ul>
 */
@Service
public class BlockExtensionService {

    static final int MAX_BLOCK_COUNT = 16;
    static final int SEATS_PER_BLOCK = 4;

    private final ExperimentRepository experimentRepository;
    private final BlockExtensionRepository blockExtensionRepository;
    private final AllocationRepository allocationRepository;
    private final Clock clock;

    public BlockExtensionService(ExperimentRepository experimentRepository,
                                 BlockExtensionRepository blockExtensionRepository,
                                 AllocationRepository allocationRepository,
                                 Clock clock) {
        this.experimentRepository = experimentRepository;
        this.blockExtensionRepository = blockExtensionRepository;
        this.allocationRepository = allocationRepository;
        this.clock = clock;
    }

    /**
     * 扩容；在一个事务内校验配比/上限/状态/版本，追加席位、区组计数与版本，写扩容记录。
     */
    @Transactional
    public BlockExtensionView extend(String experimentId, ExtendBlocksRequest request,
                                     String operatorActor) {
        // 请求级配比校验：每个区组必须恰好 4 席、仅含 A/B 且两个 A 两个 B，否则整次 422。
        List<ExtensionBlockRequest> blocks = request.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            validateBlockRatio(blocks.get(i), i + 1);
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 1) {
            throw ApiException.badRequest("expectedVersion 必须为不小于 1 的整数");
        }

        // 行级锁：与登记、关闭及其他扩容按事务提交顺序串行裁决。
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if ("CLOSED".equals(experiment.status())) {
            // 关闭先提交则扩容 409。
            throw ApiException.conflict("实验已关闭，不可扩容");
        }
        if (experiment.version() != request.expectedVersion()) {
            // 乐观版本不符（已被其他扩容抢先提交）：409，失败不占键。
            throw ApiException.conflict("expectedVersion 与实验当前版本不一致");
        }
        int addedBlockCount = blocks.size();
        if (experiment.blockCount() + addedBlockCount > MAX_BLOCK_COUNT) {
            // 追加后区组总数超过 16：422。
            throw ApiException.unprocessable("扩容后区组总数不得超过 " + MAX_BLOCK_COUNT);
        }

        int firstBlockNo = experiment.blockCount() + 1;
        int lastBlockNo = experiment.blockCount() + addedBlockCount;
        for (int i = 0; i < addedBlockCount; i++) {
            int blockNo = firstBlockNo + i;
            List<String> treatments = blocks.get(i).treatments();
            for (int seatNo = 1; seatNo <= SEATS_PER_BLOCK; seatNo++) {
                // 处理映射只落库，不进入任何响应或日志。
                experimentRepository.insertSeat(
                        new SeatRow(experimentId, blockNo, seatNo, treatments.get(seatNo - 1)));
            }
        }

        // 条件更新兜底：状态仍为 OPEN 且版本仍为期望版本时才追加并使版本加一。
        int updated = experimentRepository.applyExtension(
                experimentId, addedBlockCount, experiment.version());
        if (updated == 0) {
            throw ApiException.conflict("扩容与关闭/其他扩容竞争失败，请刷新版本后重试");
        }

        long now = clock.nowMillis();
        ExtensionRow record = new ExtensionRow(0L, experimentId, request.extensionKey(),
                request.expectedVersion(), experiment.version(), experiment.version() + 1,
                addedBlockCount, firstBlockNo, lastBlockNo, operatorActor, now);
        try {
            blockExtensionRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // extensionKey 全局唯一：换 requestId 复用同一扩容键同样冲突，事务回滚不占键。
            throw ApiException.conflict("extensionKey 已被使用: " + request.extensionKey());
        }

        return new BlockExtensionView(experimentId, request.extensionKey(),
                request.expectedVersion(), experiment.version(), experiment.version() + 1,
                addedBlockCount, firstBlockNo, lastBlockNo, lastBlockNo, SEATS_PER_BLOCK,
                lastBlockNo * SEATS_PER_BLOCK, "OPEN", now);
    }

    /** 区组容量与已用席位统计；退组席位仍计入已占用。 */
    public CapacityStatsView stats(String experimentId) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        int occupied = (int) allocationRepository.countOccupied(experimentId);
        int withdrawn = (int) allocationRepository.countWithdrawn(experimentId);
        int totalSeats = experiment.blockCount() * SEATS_PER_BLOCK;
        return new CapacityStatsView(experimentId, experiment.version(), experiment.status(),
                experiment.blockCount(), SEATS_PER_BLOCK, totalSeats, occupied, withdrawn,
                totalSeats - occupied);
    }

    /** 扩容历史；关闭后仍可查。记录按提交顺序排列，不含处理映射。 */
    public BlockExtensionHistoryView history(String experimentId) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        List<BlockExtensionView> views = blockExtensionRepository.findByExperiment(experimentId)
                .stream()
                .map(this::toView)
                .toList();
        return new BlockExtensionHistoryView(experimentId, experiment.version(), views);
    }

    private void validateBlockRatio(ExtensionBlockRequest block, int blockIndex) {
        List<String> treatments = block == null ? null : block.treatments();
        if (treatments == null || treatments.size() != SEATS_PER_BLOCK) {
            throw ApiException.unprocessable("第 " + blockIndex + " 个追加区组必须包含恰好 "
                    + SEATS_PER_BLOCK + " 个席位");
        }
        long countA = treatments.stream().filter("A"::equals).count();
        long countB = treatments.stream().filter("B"::equals).count();
        if (countA != 2 || countB != 2) {
            // 配比不符整次 422（含非法处理代码）。
            throw ApiException.unprocessable("第 " + blockIndex
                    + " 个追加区组配比不符：按提交顺序必须恰好两个 A 和两个 B");
        }
    }

    private ExperimentRow mustFindExperiment(String experimentId) {
        ExperimentRow row = experimentRepository.findById(experimentId);
        if (row == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        return row;
    }

    private BlockExtensionView toView(ExtensionRow row) {
        // 扩容只可能发生在 OPEN 状态；记录不保存当前实验状态，避免把关闭后的状态倒灌到历史条目。
        return new BlockExtensionView(row.experimentId(), row.extensionKey(),
                row.expectedVersion(), row.fromVersion(), row.toVersion(), row.blockCountAdded(),
                row.firstBlockNo(), row.lastBlockNo(), row.lastBlockNo(), SEATS_PER_BLOCK,
                row.lastBlockNo() * SEATS_PER_BLOCK, "OPEN", row.createdAt());
    }
}
