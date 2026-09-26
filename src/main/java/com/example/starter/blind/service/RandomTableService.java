package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.RandomTableDiagnosticsView;
import com.example.starter.blind.dto.RandomTableVersionView;
import com.example.starter.blind.dto.SealView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import com.example.starter.blind.repo.RandomTableRepository;
import com.example.starter.blind.repo.RandomTableRepository.SealRow;
import com.example.starter.blind.repo.RandomTableRepository.VersionRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 随机表封存与区组扩容业务：
 * <ul>
 *   <li>区组首次分配前可提交 sealKey 封存当前随机表版本；封存记录只保存表摘要、
 *       区组容量、处理代码集合与封存时刻，不保存 sealKey 与具体序列；</li>
 *   <li>区组已有任一分配、已批准揭盲或表摘要不匹配时封存失败（409/422）；封存不可撤销；</li>
 *   <li>扩容只能新建后继版本，必须显式引用已封存的当前最新版本；
 *       既有已分配序号与处理代码不变，容量与未分配名额不守恒时整次 422 回滚；</li>
 *   <li>所有查询为只读，不改变任何状态。</li>
 * </ul>
 */
@Service
public class RandomTableService {

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final RandomTableRepository randomTableRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final Clock clock;

    public RandomTableService(ExperimentRepository experimentRepository,
                              AllocationRepository allocationRepository,
                              RandomTableRepository randomTableRepository,
                              UnblindRequestRepository unblindRequestRepository,
                              Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.randomTableRepository = randomTableRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.clock = clock;
    }

    /**
     * 封存区组当前随机表版本。与分配共用实验行级锁，按事务提交顺序裁决并发。
     */
    @Transactional
    public SealView seal(String experimentId, int blockNo, String tableDigest, String actorId) {
        ExperimentRow experiment = lockExperiment(experimentId);
        requireBlock(experiment, blockNo);
        if ("CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，禁止封存随机表");
        }
        VersionRow version = mustFindLatestVersion(experimentId, blockNo);
        if (allocationRepository.countOccupiedInBlock(experimentId, blockNo) > 0) {
            throw ApiException.conflict("区组已有分配，禁止封存随机表");
        }
        if (unblindRequestRepository.countApprovedInBlock(experimentId, blockNo) > 0) {
            throw ApiException.conflict("区组已存在批准的揭盲，禁止封存随机表");
        }
        if (!version.tableDigest().equals(tableDigest)) {
            throw ApiException.full("随机表摘要不匹配：封存前请重新读取当前版本摘要");
        }
        if (randomTableRepository.findSealByVersion(version.id()) != null) {
            throw ApiException.conflict("该随机表版本已封存，封存不可撤销");
        }
        long now = clock.nowMillis();
        SealRow row = new SealRow(0L, experimentId, blockNo, version.id(),
                version.tableDigest(), version.capacity(), version.treatmentCodes(),
                actorId, now);
        try {
            SealRow inserted = randomTableRepository.insertSeal(row);
            return toSealView(inserted);
        } catch (DuplicateKeyException e) {
            // 并发封存同一版本：唯一索引兜底，按事务提交顺序只胜出一次。
            throw ApiException.conflict("该随机表版本已封存，封存不可撤销");
        }
    }

    /**
     * 区组扩容：新建后继随机表版本并追加席位；既有版本、席位与分配一行不改。
     * 先校验完整最终状态（含守恒），再在同一事务提交；失败整次回滚，查询不到半成品。
     */
    @Transactional
    public RandomTableVersionView expand(String experimentId, int blockNo,
                                         long predecessorVersionId, int addedSeats,
                                         long expectedUnallocated) {
        if (addedSeats % 2 != 0) {
            throw ApiException.badRequest("addedSeats 必须为不小于 2 的正偶数");
        }
        ExperimentRow experiment = lockExperiment(experimentId);
        requireBlock(experiment, blockNo);
        if ("CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，禁止扩容区组");
        }
        VersionRow predecessor = randomTableRepository.findVersionById(predecessorVersionId);
        if (predecessor == null || !predecessor.experimentId().equals(experimentId)
                || predecessor.blockNo() != blockNo) {
            throw ApiException.notFound("前驱随机表版本不存在于该区组: " + predecessorVersionId);
        }
        VersionRow latest = mustFindLatestVersion(experimentId, blockNo);
        if (latest.id() != predecessor.id()) {
            throw ApiException.conflict("前驱版本已被后继版本取代，只能引用当前最新版本扩容");
        }
        if (randomTableRepository.findSealByVersion(predecessor.id()) == null) {
            throw ApiException.conflict("扩容必须显式引用已封存的随机表版本");
        }
        // 守恒校验：新旧版本容量合计 - 已分配 = 未分配名额；不守恒整次 422。
        long allocated = allocationRepository.countOccupiedInBlock(experimentId, blockNo);
        long newCapacity = (long) predecessor.capacity() + addedSeats;
        long actualUnallocated = newCapacity - allocated;
        if (expectedUnallocated != actualUnallocated) {
            throw ApiException.full("扩容守恒校验失败：声明未分配名额=" + expectedUnallocated
                    + "，实际未分配名额=" + actualUnallocated
                    + "（差额=" + (expectedUnallocated - actualUnallocated)
                    + "；新容量=" + newCapacity + "，已分配=" + allocated + "）");
        }
        // 追加席位：处理代码集合不变，新增席位前半 A 后半 B，与初始版本约定一致。
        List<SeatRow> existingSeats = experimentRepository.findBlockSeats(experimentId, blockNo);
        List<SeatRow> allSeats = new ArrayList<>(existingSeats);
        long now = clock.nowMillis();
        List<SeatRow> newSeats = new ArrayList<>(addedSeats);
        for (int i = 1; i <= addedSeats; i++) {
            int seatNo = predecessor.capacity() + i;
            String treatment = i <= addedSeats / 2 ? "A" : "B";
            newSeats.add(new SeatRow(experimentId, blockNo, seatNo, treatment, 0L));
        }
        allSeats.addAll(newSeats);
        String digest = RandomTableDigest.sha256(
                experimentId, blockNo, (int) newCapacity, allSeats);
        VersionRow newVersion = randomTableRepository.insertVersion(new VersionRow(0L,
                experimentId, blockNo, predecessor.versionNo() + 1, (int) newCapacity,
                predecessor.treatmentCodes(), digest, predecessor.id(), now));
        for (SeatRow seat : newSeats) {
            experimentRepository.insertSeat(new SeatRow(seat.experimentId(), seat.blockNo(),
                    seat.seatNo(), seat.treatment(), newVersion.id()));
        }
        return toVersionView(newVersion, false, allocated);
    }

    /**
     * 查询区组当前随机表版本明细（只读）：摘要、容量、处理代码集合与计数，不含具体序列。
     */
    @Transactional(readOnly = true)
    public RandomTableVersionView getCurrentVersion(String experimentId, int blockNo) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        requireBlock(experiment, blockNo);
        VersionRow version = mustFindLatestVersion(experimentId, blockNo);
        return toVersionView(version);
    }

    /**
     * 区组随机表版本历史（只读），按版本号升序。
     */
    @Transactional(readOnly = true)
    public List<RandomTableVersionView> listVersions(String experimentId, int blockNo) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        requireBlock(experiment, blockNo);
        long allocated = allocationRepository.countOccupiedInBlock(experimentId, blockNo);
        return randomTableRepository.listVersions(experimentId, blockNo).stream()
                .map(v -> toVersionView(v,
                        randomTableRepository.findSealByVersion(v.id()) != null, allocated))
                .toList();
    }

    /**
     * 区组封存历史（只读），按封存时刻升序；不含 sealKey 与具体序列。
     */
    @Transactional(readOnly = true)
    public List<SealView> listSeals(String experimentId, int blockNo) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        requireBlock(experiment, blockNo);
        return randomTableRepository.listSeals(experimentId, blockNo).stream()
                .map(this::toSealView)
                .toList();
    }

    /**
     * 区组随机表诊断（只读）：交叉核对版本容量、实际席位行数与分配计数。
     */
    @Transactional(readOnly = true)
    public RandomTableDiagnosticsView diagnostics(String experimentId, int blockNo) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        requireBlock(experiment, blockNo);
        VersionRow latest = mustFindLatestVersion(experimentId, blockNo);
        long seatRows = experimentRepository.findBlockSeats(experimentId, blockNo).size();
        long allocated = allocationRepository.countOccupiedInBlock(experimentId, blockNo);
        long remaining = seatRows - allocated;
        int versionCount = randomTableRepository.listVersions(experimentId, blockNo).size();
        int sealCount = randomTableRepository.listSeals(experimentId, blockNo).size();
        boolean sealed = randomTableRepository.findSealByVersion(latest.id()) != null;
        boolean conserved = latest.capacity() == seatRows && remaining >= 0;
        return new RandomTableDiagnosticsView(experimentId, blockNo, latest.id(), versionCount,
                latest.capacity(), seatRows, allocated, remaining, sealed, sealCount, conserved);
    }

    private RandomTableVersionView toVersionView(VersionRow version) {
        boolean sealed = randomTableRepository.findSealByVersion(version.id()) != null;
        long allocated = allocationRepository.countOccupiedInBlock(
                version.experimentId(), version.blockNo());
        return toVersionView(version, sealed, allocated);
    }

    private RandomTableVersionView toVersionView(VersionRow version, boolean sealed,
                                                 long allocated) {
        return new RandomTableVersionView(version.id(), version.experimentId(), version.blockNo(),
                version.versionNo(), version.capacity(), version.treatmentCodes(),
                version.tableDigest(), version.predecessorId(), sealed, allocated,
                version.capacity() - allocated, version.createdAt());
    }

    private SealView toSealView(SealRow row) {
        return new SealView(row.id(), row.experimentId(), row.blockNo(), row.versionId(),
                row.tableDigest(), row.capacity(), row.treatmentCodes(),
                row.sealedActor(), row.sealedAt());
    }

    private ExperimentRow lockExperiment(String experimentId) {
        ExperimentRow row = experimentRepository.lockById(experimentId);
        if (row == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
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

    private void requireBlock(ExperimentRow experiment, int blockNo) {
        if (blockNo < 1 || blockNo > experiment.blockCount()) {
            throw ApiException.notFound(
                    "区组不存在: " + experiment.id() + " blockNo=" + blockNo);
        }
    }

    private VersionRow mustFindLatestVersion(String experimentId, int blockNo) {
        VersionRow version = randomTableRepository.findLatestVersion(experimentId, blockNo);
        if (version == null) {
            throw new IllegalStateException("区组随机表版本缺失，数据不一致");
        }
        return version;
    }
}
