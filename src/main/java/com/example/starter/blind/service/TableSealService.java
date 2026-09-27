package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.ExperimentDiagnosticsView;
import com.example.starter.blind.dto.TableHistoryEventView;
import com.example.starter.blind.dto.TableSealView;
import com.example.starter.blind.dto.TableVersionView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import com.example.starter.blind.repo.RandomTableVersionRepository;
import com.example.starter.blind.repo.RandomTableVersionRepository.RandomTableVersionRow;
import com.example.starter.blind.repo.TableSealRepository;
import com.example.starter.blind.repo.TableSealRepository.TableSealRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 随机表封存与扩容业务：
 * <ul>
 *   <li>区组首次分配前可提交 sealKey 与表摘要封存当前随机表版本，
 *       封存记录只保存摘要、容量、处理代码集合与时刻，不保存具体序列；</li>
 *   <li>已有任一分配、已揭盲或摘要不匹配时拒绝封存（409/422）；封存不可撤销；</li>
 *   <li>扩容只能新建后继版本，必须显式引用区组已封存的锚点版本（sealKey），
 *       关闭当前版本的未分配余量并把后继挂在版本链末尾，
 *       且实际未分配名额必须与要求值守恒，否则整次 422、不留半成品；</li>
 *   <li>历史、诊断均为只读派生，不改变任何状态，旧证据永不被改写。</li>
 * </ul>
 * 时间统一使用 Unix 毫秒 UTC。
 */
@Service
public class TableSealService {

    private final RandomTableVersionRepository versionRepository;
    private final TableSealRepository sealRepository;
    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final RandomTableGenerator tableGenerator;
    private final Clock clock;

    public TableSealService(RandomTableVersionRepository versionRepository,
                            TableSealRepository sealRepository,
                            ExperimentRepository experimentRepository,
                            AllocationRepository allocationRepository,
                            UnblindRequestRepository unblindRequestRepository,
                            RandomTableGenerator tableGenerator,
                            Clock clock) {
        this.versionRepository = versionRepository;
        this.sealRepository = sealRepository;
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.tableGenerator = tableGenerator;
        this.clock = clock;
    }

    /**
     * 封存区组当前随机表版本。整个过程一个事务，按实验行级锁与其他裁决型写操作串行。
     */
    @Transactional
    public TableSealView seal(String experimentId, int blockNo, String sealKey,
                              String submittedDigest) {
        if (sealKey == null || sealKey.isBlank()) {
            throw ApiException.badRequest("sealKey 不能为空");
        }
        if (sealKey.length() > 64) {
            throw ApiException.badRequest("sealKey 长度不能超过 64 个字符");
        }
        if (submittedDigest == null || submittedDigest.isBlank()) {
            throw ApiException.badRequest("sequenceDigest 不能为空");
        }
        requireExperimentAndBlock(experimentId, blockNo);
        // 与分配、扩容、揭盲申请共用实验行级锁：所有同实验裁决型写操作按事务提交顺序串行，
        // 且版本链查询在拿到锁之后才执行，避免并发新版本的幻读导致误判。
        lockExperiment(experimentId);
        List<RandomTableVersionRow> chain = versionRepository.lockByBlock(experimentId, blockNo);
        RandomTableVersionRow latest = latestVersion(chain, experimentId, blockNo);

        // 已进入揭盲流程（含待审申请）优先判定，保证失败原因可区分。
        if (unblindRequestRepository.countAnyByBlock(experimentId, blockNo) > 0) {
            throw ApiException.conflict("区组已存在揭盲申请（含待审）或已揭盲，随机表不可再封存");
        }
        if (countAssignedAcrossChain(chain) > 0) {
            throw ApiException.conflict("区组已有任一分配，随机表只能在首次分配前封存");
        }
        if (sealRepository.findByVersionId(latest.id()) != null) {
            throw ApiException.conflict("该区组当前随机表版本已封存，封存不可撤销");
        }

        // 服务端用库内序列复核摘要；不匹配返回 422，序列本身不进入任何响应。
        List<SeatRow> seats = experimentRepository.findSeatsByVersion(latest.id());
        List<String> sequence = seats.stream().map(SeatRow::treatment).toList();
        String actualDigest = SequenceDigest.compute(sealKey, experimentId, blockNo,
                latest.versionNo(), latest.capacity(), latest.treatmentCodes(), sequence);
        if (!actualDigest.equalsIgnoreCase(submittedDigest.trim())) {
            throw ApiException.unprocessable(
                    "随机表摘要不匹配：拒绝封存（提交摘要与库内版本实际序列不一致）");
        }

        long now = clock.nowMillis();
        TableSealRow row = new TableSealRow(0L, experimentId, blockNo, latest.id(), sealKey,
                actualDigest, latest.capacity(), latest.treatmentCodes(), now);
        try {
            sealRepository.insert(row);
        } catch (DuplicateKeyException e) {
            if (sealRepository.isDuplicateSealKey(e)) {
                throw ApiException.conflict("sealKey 已被其他封存使用，必须全局唯一");
            }
            if (sealRepository.isDuplicateVersion(e)) {
                throw ApiException.conflict("该区组当前随机表版本已封存，封存不可撤销");
            }
            throw e;
        }
        return new TableSealView(experimentId, blockNo, latest.versionNo(), sealKey,
                actualDigest, latest.capacity(), latest.treatmentCodes(), now);
    }

    /**
     * 区组扩容：显式引用已封存版本，关闭其未分配余量并新建后继版本。
     * 先在事务内完成全部最终状态校验，再写入；任一校验失败整次回滚。
     */
    @Transactional
    public TableVersionView expand(String experimentId, int blockNo, String sealKey,
                                   int expectedRemaining, int newCapacity) {
        if (sealKey == null || sealKey.isBlank()) {
            throw ApiException.badRequest("sealKey 不能为空");
        }
        if (newCapacity <= 0 || newCapacity > 64) {
            throw ApiException.badRequest("后继版本容量必须在 1~64 之间，实际要求: " + newCapacity);
        }
        if (newCapacity % 2 != 0) {
            // 处理代码集合固定 A/B 且区组内均衡，奇数容量无法满足。
            throw ApiException.badRequest("后继版本容量必须为偶数，实际要求: " + newCapacity);
        }
        requireExperimentAndBlock(experimentId, blockNo);
        // 与封存、分配共用实验行级锁：扩容与并发分配/封存按事务提交顺序裁决。
        lockExperiment(experimentId);
        List<RandomTableVersionRow> chain = versionRepository.lockByBlock(experimentId, blockNo);
        RandomTableVersionRow latest = latestVersion(chain, experimentId, blockNo);

        TableSealRow seal = sealRepository.findBySealKey(sealKey);
        if (seal == null) {
            throw ApiException.unprocessable("sealKey 未对应任何已封存随机表版本，扩容必须显式引用已封存版本");
        }
        if (!seal.experimentId().equals(experimentId) || seal.blockNo() != blockNo) {
            throw ApiException.unprocessable("sealKey 引用的封存版本不属于本区组，无法用于本次扩容");
        }
        boolean anchorInChain = chain.stream().anyMatch(v -> v.id() == seal.tableVersionId());
        if (!anchorInChain) {
            // 引用的封存版本不属于本区组版本链，拒绝跨链扩容。
            throw ApiException.unprocessable("sealKey 引用的已封存版本不在本区组版本链中");
        }
        if ("CLOSED".equals(latest.status())) {
            throw ApiException.conflict("区组当前最新随机表版本余量已关闭且尚无后继版本，不能重复关闭");
        }

        // 守恒核对：被关闭的当前版本实际未分配名额必须与研究侧要求值一致；
        // 差额以实际值、要求值、差额给出，可区分失败原因。
        int assigned = allocationRepository.countAssignedByVersion(latest.id());
        int actualRemaining = latest.capacity() - assigned;
        if (actualRemaining != expectedRemaining) {
            int diff = actualRemaining - expectedRemaining;
            throw ApiException.unprocessable("新旧版本容量与未分配名额不守恒，整次扩容拒绝："
                    + "实际未分配名额=" + actualRemaining + "，要求值=" + expectedRemaining
                    + "，差额=" + (diff > 0 ? "+" + diff : Integer.toString(diff)));
        }

        String codes = tableGenerator.treatmentCodesSnapshot();
        if (!codes.equals(seal.treatmentCodes()) || !codes.equals(latest.treatmentCodes())) {
            // 后继版本处理代码集合必须与已封存锚点版本保持一致，禁止修改集合。
            throw ApiException.unprocessable("后继版本处理代码集合与已封存版本不一致，禁止修改处理代码集合");
        }

        // 校验通过后才进行多对象写入：关当前版本余量、建后继版本与序号映射，同一事务提交。
        long now = clock.nowMillis();
        int closed = versionRepository.markClosed(latest.id(), now);
        if (closed != 1) {
            // 并发下被其他事务抢先关闭：行锁内应不会发生，仍显式失败回滚，杜绝半成品。
            throw ApiException.conflict("当前版本状态已变化，扩容拒绝，请重新核对未分配名额");
        }
        List<String> sequence = tableGenerator.generateBalanced(newCapacity);
        RandomTableVersionRow successor = versionRepository.insert(
                new RandomTableVersionRow(0L, experimentId, blockNo, latest.versionNo() + 1,
                        latest.id(), newCapacity, codes, "ACTIVE", now, null));
        for (int seatNo = 1; seatNo <= newCapacity; seatNo++) {
            experimentRepository.insertSeat(new SeatRow(successor.id(), experimentId, blockNo,
                    seatNo, sequence.get(seatNo - 1)));
        }
        return toVersionView(successor, 0);
    }

    /** 区组版本链明细（只读）：版本、容量、已分配与未分配名额，均为实际值。 */
    public List<TableVersionView> getBlockVersions(String experimentId, int blockNo) {
        requireExperimentAndBlock(experimentId, blockNo);
        List<RandomTableVersionRow> chain = versionRepository.findByBlock(experimentId, blockNo);
        if (chain.isEmpty()) {
            throw ApiException.notFound("区组随机表不存在: blockNo=" + blockNo);
        }
        List<TableVersionView> views = new ArrayList<>(chain.size());
        for (RandomTableVersionRow version : chain) {
            int assigned = allocationRepository.countAssignedByVersion(version.id());
            views.add(toVersionView(version, assigned));
        }
        return views;
    }

    /**
     * 区组随机表历史（只读，不改状态）：由不可变版本行与封存记录派生，
     * 按事件时刻与事件顺序稳定升序；读取结果不写入任何新证据。
     */
    public List<TableHistoryEventView> getBlockHistory(String experimentId, int blockNo) {
        requireExperimentAndBlock(experimentId, blockNo);
        List<RandomTableVersionRow> chain = versionRepository.findByBlock(experimentId, blockNo);
        if (chain.isEmpty()) {
            throw ApiException.notFound("区组随机表不存在: blockNo=" + blockNo);
        }
        List<TableHistoryEventView> events = new ArrayList<>(chain.size() * 3);
        for (RandomTableVersionRow version : chain) {
            Integer parentVersionNo = version.parentId() == null ? null
                    : versionNoOf(chain, version.parentId());
            events.add(new TableHistoryEventView("VERSION_CREATED", experimentId, blockNo,
                    version.versionNo(), parentVersionNo, version.capacity(),
                    version.treatmentCodes(), null, null, version.createdAt()));
            TableSealRow seal = sealRepository.findByVersionId(version.id());
            if (seal != null) {
                events.add(new TableHistoryEventView("VERSION_SEALED", experimentId, blockNo,
                        version.versionNo(), parentVersionNo, version.capacity(),
                        version.treatmentCodes(), seal.sealKey(), seal.sequenceDigest(),
                        seal.sealedAt()));
            }
            if (version.closedAt() != null) {
                events.add(new TableHistoryEventView("VERSION_CLOSED", experimentId, blockNo,
                        version.versionNo(), parentVersionNo, version.capacity(),
                        version.treatmentCodes(), null, null, version.closedAt()));
            }
        }
        events.sort(Comparator.comparingLong(TableHistoryEventView::eventTime)
                .thenComparing(TableHistoryEventView::versionNo)
                .thenComparingInt(TableSealService::eventOrder));
        return events;
    }

    /** 实验随机表诊断（只读）：聚合版本链、封存状态与容量/名额实际值。 */
    public ExperimentDiagnosticsView getDiagnostics(String experimentId) {
        ExperimentRow experiment = experimentRepository.findById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        List<RandomTableVersionRow> all = versionRepository.findByExperiment(experimentId);
        java.util.Map<Integer, Boolean> sealedBlocks = new java.util.HashMap<>();
        for (TableSealRow sealRow : sealRepository.findByExperiment(experimentId)) {
            // 封存对区组一次性生效；区组内存在任一封存记录即视为已建立封存锚点。
            sealedBlocks.put(sealRow.blockNo(), true);
        }
        List<ExperimentDiagnosticsView.BlockDiagnosticsView> blocks = new ArrayList<>();
        int totalCapacity = 0;
        int totalAssigned = 0;
        int totalRemaining = 0;
        int totalClosedRemaining = 0;
        for (int blockNo = 1; blockNo <= experiment.blockCount(); blockNo++) {
            final int currentBlock = blockNo;
            List<RandomTableVersionRow> chain = all.stream()
                    .filter(v -> v.blockNo() == currentBlock)
                    .toList();
            if (chain.isEmpty()) {
                continue;
            }
            RandomTableVersionRow latest = chain.get(chain.size() - 1);
            List<TableVersionView> versionViews = new ArrayList<>(chain.size());
            int blockCapacity = 0;
            int blockAssigned = 0;
            int blockRemaining = 0;
            int blockClosedRemaining = 0;
            for (RandomTableVersionRow version : chain) {
                int assigned = allocationRepository.countAssignedByVersion(version.id());
                int unused = version.capacity() - assigned;
                blockCapacity += version.capacity();
                blockAssigned += assigned;
                if ("ACTIVE".equals(version.status())) {
                    blockRemaining += unused;
                } else {
                    blockClosedRemaining += unused;
                }
                versionViews.add(toVersionView(version, assigned));
            }
            boolean sealed = sealedBlocks.getOrDefault(blockNo, false);
            blocks.add(new ExperimentDiagnosticsView.BlockDiagnosticsView(
                    blockNo, latest.versionNo(), sealed, latest.status(),
                    blockCapacity, blockAssigned, blockRemaining, blockClosedRemaining,
                    versionViews));
            totalCapacity += blockCapacity;
            totalAssigned += blockAssigned;
            totalRemaining += blockRemaining;
            totalClosedRemaining += blockClosedRemaining;
        }
        return new ExperimentDiagnosticsView(experimentId, experiment.status(),
                totalCapacity, totalAssigned, totalRemaining, totalClosedRemaining, blocks);
    }

    private ExperimentRow requireExperimentAndBlock(String experimentId, int blockNo) {
        ExperimentRow experiment = experimentRepository.findById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if (blockNo < 1 || blockNo > experiment.blockCount()) {
            throw ApiException.notFound("区组不存在: blockNo=" + blockNo
                    + "，实际区组数量=" + experiment.blockCount());
        }
        return experiment;
    }

    /**
     * 获取实验行级锁；封存/扩容/分配/揭盲申请等裁决型写操作统一在此串行，
     * 按事务提交顺序裁决同一实验的并发写。
     */
    private void lockExperiment(String experimentId) {
        ExperimentRow locked = experimentRepository.lockById(experimentId);
        if (locked == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
    }

    private RandomTableVersionRow latestVersion(List<RandomTableVersionRow> chain,
                                                String experimentId, int blockNo) {
        if (chain.isEmpty()) {
            throw ApiException.notFound("区组随机表不存在: experimentId=" + experimentId
                    + ", blockNo=" + blockNo);
        }
        return chain.get(chain.size() - 1);
    }

    private int countAssignedAcrossChain(List<RandomTableVersionRow> chain) {
        int total = 0;
        for (RandomTableVersionRow version : chain) {
            total += allocationRepository.countAssignedByVersion(version.id());
        }
        return total;
    }

    private int versionNoOf(List<RandomTableVersionRow> chain, long parentId) {
        return chain.stream()
                .filter(v -> v.id() == parentId)
                .findFirst()
                .map(RandomTableVersionRow::versionNo)
                .orElseThrow(() -> new IllegalStateException("前驱版本缺失，版本链不一致"));
    }

    private TableVersionView toVersionView(RandomTableVersionRow version, int assigned) {
        Integer parentVersionNo = null;
        if (version.parentId() != null) {
            RandomTableVersionRow parent = versionRepository.findById(version.parentId());
            if (parent != null) {
                parentVersionNo = parent.versionNo();
            }
        }
        int unused = version.capacity() - assigned;
        int remaining = "ACTIVE".equals(version.status()) ? unused : 0;
        int closedRemaining = "CLOSED".equals(version.status()) ? unused : 0;
        return new TableVersionView(version.id(), version.experimentId(), version.blockNo(),
                version.versionNo(), parentVersionNo, version.capacity(),
                version.treatmentCodes(), version.status(), assigned, remaining, closedRemaining,
                version.createdAt(), version.closedAt());
    }

    /** 历史事件在同一时刻的稳定排序：创建 → 封存 → 关闭。 */
    private static int eventOrder(TableHistoryEventView event) {
        return switch (event.eventType()) {
            case "VERSION_CREATED" -> 0;
            case "VERSION_SEALED" -> 1;
            case "VERSION_CLOSED" -> 2;
            default -> 3;
        };
    }
}
