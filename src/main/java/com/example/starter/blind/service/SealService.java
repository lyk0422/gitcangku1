package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.BlockDiagnosticView;
import com.example.starter.blind.dto.BlockDiagnosticView.DiagnosticEvent;
import com.example.starter.blind.dto.BlockDiagnosticView.VersionQuota;
import com.example.starter.blind.dto.BlockSealHistoryView;
import com.example.starter.blind.dto.SealAllocationItem;
import com.example.starter.blind.dto.SealDetailView;
import com.example.starter.blind.dto.SealView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import com.example.starter.blind.repo.RandomTableSealRepository;
import com.example.starter.blind.repo.RandomTableSealRepository.SealRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 盲法随机表封存业务：
 * <ul>
 *   <li>区组首次分配前可封存版本1随机表，封存记录只保存摘要、容量、处理代码集合与时刻；</li>
 *   <li>封存后新增分配按该版本剩余序列进行，禁止修改代码集合、顺序或替换随机表；</li>
 *   <li>扩容只能显式引用最新已封存版本新建后继版本，保持既有序号与处理代码不变；</li>
 *   <li>封存不可撤销；发现表错误只能关闭未分配余量再以新版本扩容；</li>
 *   <li>所有裁决在实验行级锁内进行，多对象写入先完整校验再在同一事务提交。</li>
 * </ul>
 */
@Service
public class SealService {

    /** 处理代码集合（字典序），与建实验时的 A/B 两处理一致。 */
    static final String TREATMENT_CODES = "A,B";
    static final List<String> TREATMENT_CODE_LIST = List.of("A", "B");

    /** 容量上限，防止异常请求生成超长序列。 */
    private static final int MAX_CAPACITY = 1024;

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final RandomTableSealRepository sealRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final Clock clock;

    public SealService(ExperimentRepository experimentRepository,
                       AllocationRepository allocationRepository,
                       RandomTableSealRepository sealRepository,
                       UnblindRequestRepository unblindRequestRepository,
                       Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.sealRepository = sealRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.clock = clock;
    }

    /**
     * 封存区组版本1随机表；仅允许在区组尚无任何分配、未发生揭盲且未封存时进行。
     */
    @Transactional
    public SealView seal(String experimentId, int blockNo, String sealId,
                         String expectedDigest, String actorId) {
        ExperimentRow experiment = lockExperiment(experimentId);
        validateBlockNo(experiment, blockNo);
        if (sealRepository.findById(sealId) != null) {
            throw ApiException.conflict("sealKey 已存在: " + sealId);
        }
        List<SealRow> seals = sealRepository.findByBlock(experimentId, blockNo);
        if (!seals.isEmpty()) {
            throw ApiException.conflict("区组已封存随机表版本，封存不可撤销或替换");
        }
        long allocated = allocationRepository.countByBlock(experimentId, blockNo);
        if (allocated > 0) {
            throw ApiException.conflict("区组已有 " + allocated + " 个分配，禁止首次封存");
        }
        if (unblindRequestRepository.countByBlock(experimentId, blockNo) > 0) {
            throw ApiException.conflict("区组已发生揭盲，禁止封存");
        }
        List<SeatRow> seats =
                experimentRepository.findSeatsByVersion(experimentId, blockNo, 1);
        if (seats.size() != 4) {
            throw ApiException.unprocessable("初始随机表缺失或容量异常，实际席位 " + seats.size()
                    + "，要求 4");
        }
        int capacity = seats.size();
        String actualDigest = digestOf(experimentId, blockNo, 1, capacity, seats);
        if (expectedDigest != null && !expectedDigest.isBlank()
                && !expectedDigest.equals(actualDigest)) {
            throw ApiException.unprocessable("随机表摘要不匹配，拒绝封存");
        }
        long now = clock.nowMillis();
        try {
            sealRepository.insert(new SealRow(sealId, experimentId, blockNo, 1, capacity,
                    TREATMENT_CODES, actualDigest, null, "SEALED", actorId, now, null));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("sealKey 已存在: " + sealId);
        }
        return new SealView(sealId, experimentId, blockNo, 1, capacity, TREATMENT_CODE_LIST,
                actualDigest, null, "SEALED", 0, capacity, 0, actorId, now, null);
    }

    /**
     * 扩容：显式引用最新已封存版本，在其后新建后继随机表版本；
     * 既有版本的已分配序号与处理代码保持不变，未关闭且仍有名额的旧版本不允许分叉扩容。
     */
    @Transactional
    public SealView expand(String experimentId, int blockNo, String newSealId,
                           String predecessorSealId, int capacity,
                           String expectedDigest, String actorId) {
        ExperimentRow experiment = lockExperiment(experimentId);
        validateBlockNo(experiment, blockNo);
        if (capacity <= 0 || capacity % RandomTableGenerator.GROUP_SIZE != 0) {
            throw ApiException.unprocessable("新增容量必须为 " + RandomTableGenerator.GROUP_SIZE
                    + " 的正整数倍，实际要求 " + capacity);
        }
        if (capacity > MAX_CAPACITY) {
            throw ApiException.unprocessable("新增容量超过上限 " + MAX_CAPACITY
                    + "，实际要求 " + capacity);
        }
        if (sealRepository.findById(newSealId) != null) {
            throw ApiException.conflict("sealKey 已存在: " + newSealId);
        }
        SealRow predecessor = sealRepository.lockById(predecessorSealId);
        if (predecessor == null
                || !predecessor.experimentId().equals(experimentId)
                || predecessor.blockNo() != blockNo) {
            throw ApiException.unprocessable("引用的前驱封存版本不存在或不属于该区组");
        }
        List<SealRow> seals = sealRepository.findByBlock(experimentId, blockNo);
        SealRow latest = seals.get(seals.size() - 1);
        if (!latest.id().equals(predecessorSealId)) {
            throw ApiException.unprocessable("必须显式引用最新已封存版本 " + latest.id()
                    + "，实际引用 " + predecessorSealId);
        }
        if (expectedDigest != null && !expectedDigest.isBlank()
                && !expectedDigest.equals(predecessor.tableDigest())) {
            throw ApiException.unprocessable("引用版本的随机表摘要不匹配，拒绝扩容");
        }
        long predecessorAllocated =
                allocationRepository.countByVersion(experimentId, blockNo, predecessor.versionNo());
        int predecessorRemaining =
                predecessor.status().equals("CLOSED") ? 0
                        : predecessor.capacity() - (int) predecessorAllocated;
        if (predecessorRemaining > 0) {
            throw ApiException.unprocessable("前驱版本仍有未分配名额 " + predecessorRemaining
                    + " 个；应先按其剩余序列分配或关闭余量，差额要求 0");
        }

        int newVersion = predecessor.versionNo() + 1;
        int startSeatNo = experimentRepository.findMaxSeatNo(experimentId, blockNo);
        String seed = experimentId + '|' + blockNo + '|' + newVersion + '|' + newSealId;
        List<String> sequence = RandomTableGenerator.generate(seed, capacity);
        String newDigest = digestOfSequence(experimentId, blockNo, newVersion, capacity, sequence);

        // 多对象写入：全部前置校验完成后，席位与封存记录在同一事务落库；失败整体回滚不留半成品。
        List<SeatRow> newSeats = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            newSeats.add(new SeatRow(experimentId, blockNo, newVersion,
                    startSeatNo + i + 1, sequence.get(i)));
        }
        long now = clock.nowMillis();
        try {
            newSeats.forEach(experimentRepository::insertSeat);
            sealRepository.insert(new SealRow(newSealId, experimentId, blockNo, newVersion,
                    capacity, TREATMENT_CODES, newDigest, predecessorSealId, "SEALED",
                    actorId, now, null));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("sealKey 或版本序号冲突: " + newSealId);
        }
        return new SealView(newSealId, experimentId, blockNo, newVersion, capacity,
                TREATMENT_CODE_LIST, newDigest, predecessorSealId, "SEALED", 0, capacity, 0,
                actorId, now, null);
    }

    /**
     * 关闭指定封存版本的未分配余量；封存不可撤销，发现表错误只能关闭后以新版本扩容。
     */
    @Transactional
    public SealView closeRemainder(String experimentId, int blockNo, String sealId,
                                   String actorId) {
        ExperimentRow experiment = lockExperiment(experimentId);
        validateBlockNo(experiment, blockNo);
        SealRow seal = sealRepository.lockById(sealId);
        if (seal == null || !seal.experimentId().equals(experimentId)
                || seal.blockNo() != blockNo) {
            throw ApiException.unprocessable("封存记录不存在或不属于该区组");
        }
        if ("CLOSED".equals(seal.status())) {
            throw ApiException.conflict("该版本未分配余量已关闭，关闭不可撤销");
        }
        long now = clock.nowMillis();
        int updated = sealRepository.markClosed(sealId, now);
        if (updated == 0) {
            throw ApiException.conflict("该版本未分配余量已关闭，关闭不可撤销");
        }
        return toView(new SealRow(seal.id(), seal.experimentId(), seal.blockNo(),
                seal.versionNo(), seal.capacity(), seal.treatmentCodes(), seal.tableDigest(),
                seal.predecessorId(), "CLOSED", seal.sealedBy(), seal.sealedAt(), now),
                (int) allocationRepository.countByVersion(
                        experimentId, blockNo, seal.versionNo()));
    }

    /**
     * 封存明细：封存元信息与该版本上的分配明细（不含具体序列）；读取不改变状态。
     */
    public SealDetailView getDetail(String experimentId, int blockNo, String sealId) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        validateBlockNo(experiment, blockNo);
        SealRow seal = sealRepository.findById(sealId);
        if (seal == null || !seal.experimentId().equals(experimentId)
                || seal.blockNo() != blockNo) {
            throw ApiException.notFound("封存记录不存在: " + sealId);
        }
        List<AllocationRow> allocations =
                allocationRepository.findByVersion(experimentId, blockNo, seal.versionNo());
        List<SealAllocationItem> items = allocations.stream()
                .map(a -> new SealAllocationItem(a.participantId(), a.blindCode(),
                        a.versionNo(), a.sealId(), a.status(), a.assignedAt(), a.withdrawnAt()))
                .toList();
        int allocated = allocations.size();
        return new SealDetailView(toView(seal, allocated), items);
    }

    /**
     * 区组封存版本链历史，按版本号升序；封存不可撤销、不删除。
     */
    public BlockSealHistoryView getHistory(String experimentId, int blockNo) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        validateBlockNo(experiment, blockNo);
        List<SealView> views = new ArrayList<>();
        for (SealRow seal : sealRepository.findByBlock(experimentId, blockNo)) {
            int allocated = (int) allocationRepository.countByVersion(
                    experimentId, blockNo, seal.versionNo());
            views.add(toView(seal, allocated));
        }
        return new BlockSealHistoryView(experimentId, blockNo, views);
    }

    /**
     * 区组诊断：版本链名额守恒核对与 [from, to) UTC 时间窗内事件；只读。
     */
    public BlockDiagnosticView getDiagnostic(String experimentId, int blockNo, Long from, Long to) {
        if (from != null && to != null && from > to) {
            throw ApiException.badRequest("时间窗起点不能晚于终点");
        }
        ExperimentRow experiment = mustFindExperiment(experimentId);
        validateBlockNo(experiment, blockNo);

        List<SeatRow> seats = experimentRepository.findSeats(experimentId).stream()
                .filter(s -> s.blockNo() == blockNo)
                .toList();
        List<SealRow> seals = sealRepository.findByBlock(experimentId, blockNo);
        int maxVersion = seats.stream().mapToInt(SeatRow::versionNo).max().orElse(1);
        // 分配按固化版本分组；封存前历史分配 version_no 为 NULL，归属版本1。
        Map<Integer, Integer> allocatedByVersion = new HashMap<>();
        for (AllocationRow allocation : allocationRepository.findByBlock(experimentId, blockNo)) {
            int version = allocation.versionNo() == null ? 1 : allocation.versionNo();
            allocatedByVersion.merge(version, 1, Integer::sum);
        }

        List<VersionQuota> quotas = new ArrayList<>();
        int totalCapacity = 0;
        int totalAllocated = 0;
        int totalRemaining = 0;
        int totalAbandoned = 0;
        for (int version = 1; version <= maxVersion; version++) {
            final int currentVersion = version;
            int capacity = (int) seats.stream()
                    .filter(s -> s.versionNo() == currentVersion).count();
            SealRow seal = seals.stream()
                    .filter(s -> s.versionNo() == currentVersion).findFirst().orElse(null);
            int allocated = allocatedByVersion.getOrDefault(version, 0);
            int remaining;
            int abandoned;
            if (seal != null && "CLOSED".equals(seal.status())) {
                remaining = 0;
                abandoned = capacity - allocated;
            } else {
                remaining = capacity - allocated;
                abandoned = 0;
            }
            quotas.add(new VersionQuota(version, seal != null, seal == null ? null : seal.id(),
                    seal == null ? null : seal.status(), capacity, allocated, remaining,
                    abandoned));
            totalCapacity += capacity;
            totalAllocated += allocated;
            totalRemaining += remaining;
            totalAbandoned += abandoned;
        }
        int difference = totalCapacity - totalAllocated - totalRemaining - totalAbandoned;

        List<DiagnosticEvent> events = collectEvents(experimentId, blockNo, from, to, seals);
        return new BlockDiagnosticView(experimentId, blockNo, quotas, totalCapacity,
                totalAllocated, totalRemaining, totalAbandoned, difference,
                difference == 0, events, from, to);
    }

    // ---------------- 内部辅助 ----------------

    private List<DiagnosticEvent> collectEvents(String experimentId, int blockNo,
                                                Long from, Long to, List<SealRow> seals) {
        List<DiagnosticEvent> events = new ArrayList<>();
        for (SealRow seal : seals) {
            addEvent(events, from, to, new DiagnosticEvent(
                    seal.versionNo() == 1 ? "SEAL" : "EXPAND",
                    seal.sealedAt(), seal.id(), seal.versionNo(),
                    "容量 " + seal.capacity() + "，处理代码集合 " + seal.treatmentCodes()));
            if (seal.closedAt() != null) {
                addEvent(events, from, to, new DiagnosticEvent("CLOSE", seal.closedAt(),
                        seal.id(), seal.versionNo(), "关闭未分配余量"));
            }
        }
        for (AllocationRow allocation : allocationRepository.findByBlock(experimentId, blockNo)) {
            int version = allocation.versionNo() == null ? 1 : allocation.versionNo();
            addEvent(events, from, to, new DiagnosticEvent("ALLOCATE",
                    allocation.assignedAt(), allocation.sealId(), version,
                    "参与者 " + allocation.participantId() + " 分配到区组 " + blockNo));
        }
        events.sort(Comparator.comparingLong(DiagnosticEvent::at)
                .thenComparing(DiagnosticEvent::type));
        return events;
    }

    private void addEvent(List<DiagnosticEvent> events, Long from, Long to, DiagnosticEvent event) {
        if (from != null && event.at() < from) {
            return;
        }
        if (to != null && event.at() >= to) {
            return;
        }
        events.add(event);
    }

    private SealView toView(SealRow seal, int allocated) {
        int remaining;
        int abandoned;
        if ("CLOSED".equals(seal.status())) {
            remaining = 0;
            abandoned = seal.capacity() - allocated;
        } else {
            remaining = seal.capacity() - allocated;
            abandoned = 0;
        }
        return new SealView(seal.id(), seal.experimentId(), seal.blockNo(), seal.versionNo(),
                seal.capacity(), List.of(seal.treatmentCodes().split(",")), seal.tableDigest(),
                seal.predecessorId(), seal.status(), allocated, remaining, abandoned,
                seal.sealedBy(), seal.sealedAt(), seal.closedAt());
    }

    private String digestOf(String experimentId, int blockNo, int versionNo, int capacity,
                            List<SeatRow> seats) {
        StringBuilder sequence = new StringBuilder(capacity);
        seats.stream().map(SeatRow::treatment).forEach(sequence::append);
        return RandomTableDigester.digest(experimentId, blockNo, versionNo, capacity,
                TREATMENT_CODES, sequence.toString());
    }

    private String digestOfSequence(String experimentId, int blockNo, int versionNo,
                                    int capacity, List<String> sequence) {
        return RandomTableDigester.digest(experimentId, blockNo, versionNo, capacity,
                TREATMENT_CODES, String.join("", sequence));
    }

    private ExperimentRow lockExperiment(String experimentId) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        return experiment;
    }

    private ExperimentRow mustFindExperiment(String experimentId) {
        ExperimentRow experiment = experimentRepository.findById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        return experiment;
    }

    private void validateBlockNo(ExperimentRow experiment, int blockNo) {
        if (blockNo < 1 || blockNo > experiment.blockCount()) {
            throw ApiException.unprocessable("区组号超出范围，实际 " + blockNo + "，要求 1~"
                    + experiment.blockCount());
        }
    }
}
