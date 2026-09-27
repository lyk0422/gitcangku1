package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.BlockDiagnosticsView;
import com.example.starter.blind.dto.ExpandBlockRequest;
import com.example.starter.blind.dto.RandomTableVersionView;
import com.example.starter.blind.dto.SealRandomTableRequest;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import com.example.starter.blind.repo.RandomTableVersionRepository;
import com.example.starter.blind.repo.RandomTableVersionRepository.RandomTableVersionRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 随机表封存与区组扩容业务：
 * <ul>
 *   <li>区组首次分配前可提交 sealKey 封存随机表版本；封存记录只保存表摘要、容量、
 *       处理代码集合与生成时刻，具体序列与 sealKey 不向普通角色暴露；</li>
 *   <li>区组已有任一分配、已揭盲或摘要不匹配时封存失败（409/422）；封存不可撤销；</li>
 *   <li>扩容只能新建后继版本，必须显式引用当前最新已封存版本，既有已分配序号与
 *       处理代码不变；未分配名额不守恒时整次 422 回滚；</li>
 *   <li>发现表错误只能关闭未分配余量并以新版本扩容，不重排既有参与者。</li>
 * </ul>
 */
@Service
public class RandomTableService {

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final RandomTableVersionRepository versionRepository;
    private final Clock clock;

    public RandomTableService(ExperimentRepository experimentRepository,
                              AllocationRepository allocationRepository,
                              UnblindRequestRepository unblindRequestRepository,
                              RandomTableVersionRepository versionRepository,
                              Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.versionRepository = versionRepository;
        this.clock = clock;
    }

    /**
     * 封存区组随机表版本 1。行级锁串行化同实验的封存/分配/扩容并发。
     */
    @Transactional
    public RandomTableVersionView seal(String experimentId, int blockNo,
                                       SealRandomTableRequest request) {
        ExperimentRow experiment = lockExperiment(experimentId);
        requireBlock(experiment, blockNo);
        if (versionRepository.findLatest(experimentId, blockNo) != null) {
            throw ApiException.conflict("区组已封存随机表版本，封存不可撤销");
        }
        if (allocationRepository.countByBlock(experimentId, blockNo) > 0) {
            throw ApiException.conflict("区组已存在分配，禁止封存随机表");
        }
        if (unblindRequestRepository.countApprovedByBlock(experimentId, blockNo) > 0) {
            throw ApiException.conflict("区组已揭盲，禁止封存随机表");
        }
        List<SeatRow> seats = experimentRepository.findSeatsByBlock(experimentId, blockNo);
        if (seats.isEmpty()) {
            throw new IllegalStateException("区组席位缺失，数据不一致");
        }
        int seatTo = seats.get(seats.size() - 1).seatNo();
        String digest = digest(experimentId, blockNo, 1, seatTo, sequence(seats),
                request.sealKey());
        if (request.expectedDigest() != null && !request.expectedDigest().isBlank()
                && !request.expectedDigest().equals(digest)) {
            throw ApiException.unprocessable("随机表摘要不匹配：封存被拒绝，"
                    + "要求=" + request.expectedDigest() + "，实际=" + digest);
        }
        RandomTableVersionRow row = new RandomTableVersionRow(experimentId, blockNo, 1,
                digest, 1, seatTo, seatTo, treatmentCodes(seats),
                null, "SEALED", clock.nowMillis(), null);
        try {
            versionRepository.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发封存同一区组：主键兜底，按已封存冲突处理。
            throw ApiException.conflict("区组已封存随机表版本，封存不可撤销");
        }
        return toView(row);
    }

    /**
     * 区组扩容：显式引用当前最新已封存版本，新建后继版本并追加席位；
     * 先校验完整最终状态（含未分配名额守恒），再在同一事务提交。
     */
    @Transactional
    public RandomTableVersionView expand(String experimentId, int blockNo,
                                         ExpandBlockRequest request) {
        if (request.addedSeats() % 2 != 0) {
            throw ApiException.badRequest("addedSeats 必须为偶数，以保持处理代码各半");
        }
        ExperimentRow experiment = lockExperiment(experimentId);
        requireBlock(experiment, blockNo);
        RandomTableVersionRow latest = versionRepository.findLatest(experimentId, blockNo);
        if (latest == null) {
            throw ApiException.conflict("区组尚未封存，扩容必须显式引用已封存版本");
        }
        if (!request.baseVersion().equals(latest.versionNo())) {
            throw ApiException.conflict("baseVersion 必须引用当前最新已封存版本："
                    + "要求=" + request.baseVersion() + "，实际=" + latest.versionNo());
        }
        // 守恒校验：调用方声明的未分配名额必须等于实际可分配余量，否则整次 422。
        long actualUnallocated =
                allocationRepository.countRemainingAllocatable(experimentId, blockNo);
        if (request.expectedUnallocated() != actualUnallocated) {
            throw ApiException.unprocessable("未分配名额不守恒，整次扩容拒绝："
                    + "要求=" + request.expectedUnallocated()
                    + "，实际=" + actualUnallocated
                    + "，差额=" + (actualUnallocated - request.expectedUnallocated()));
        }
        int newVersionNo = latest.versionNo() + 1;
        int seatFrom = latest.seatTo() + 1;
        int seatTo = latest.seatTo() + request.addedSeats();
        // 追加席位：前半 A 后半 B，与初始区组风格一致；既有席位与处理代码不变。
        for (int seatNo = seatFrom; seatNo <= seatTo; seatNo++) {
            String treatment = seatNo < seatFrom + request.addedSeats() / 2 ? "A" : "B";
            experimentRepository.insertSeat(
                    new SeatRow(experimentId, blockNo, seatNo, treatment));
        }
        List<SeatRow> seats = experimentRepository.findSeatsByBlock(experimentId, blockNo);
        String digest = digest(experimentId, blockNo, newVersionNo, seatTo, sequence(seats),
                request.sealKey());
        RandomTableVersionRow row = new RandomTableVersionRow(experimentId, blockNo, newVersionNo,
                digest, seatFrom, seatTo, request.addedSeats(),
                treatmentCodes(seats), latest.versionNo(), "SEALED", clock.nowMillis(), null);
        try {
            versionRepository.insert(row);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("后继版本已存在，请刷新后重试");
        }
        return toView(row);
    }

    /**
     * 关闭当前最新版本的未分配余量；不可撤销，既有分配与序号不变。
     */
    @Transactional
    public RandomTableVersionView closeRemainder(String experimentId, int blockNo, int versionNo) {
        ExperimentRow experiment = lockExperiment(experimentId);
        requireBlock(experiment, blockNo);
        RandomTableVersionRow version =
                versionRepository.findVersion(experimentId, blockNo, versionNo);
        if (version == null) {
            throw ApiException.notFound("随机表版本不存在: " + versionNo);
        }
        RandomTableVersionRow latest = versionRepository.findLatest(experimentId, blockNo);
        if (latest == null || latest.versionNo() != versionNo) {
            throw ApiException.conflict("仅可关闭当前最新版本的未分配余量");
        }
        if (!"SEALED".equals(version.status())) {
            throw ApiException.conflict("版本未分配余量已关闭，关闭不可撤销");
        }
        long now = clock.nowMillis();
        versionRepository.closeRemainder(experimentId, blockNo, versionNo, now);
        return toView(versionRepository.findVersion(experimentId, blockNo, versionNo));
    }

    /**
     * 版本历史查询（只读）：按版本号升序，不含具体序列与 sealKey。
     */
    public List<RandomTableVersionView> listVersions(String experimentId, int blockNo) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        requireBlock(experiment, blockNo);
        return versionRepository.findByBlock(experimentId, blockNo).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 版本明细查询（只读）。
     */
    public RandomTableVersionView getVersion(String experimentId, int blockNo, int versionNo) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        requireBlock(experiment, blockNo);
        RandomTableVersionRow row = versionRepository.findVersion(experimentId, blockNo, versionNo);
        if (row == null) {
            throw ApiException.notFound("随机表版本不存在: " + versionNo);
        }
        return toView(row);
    }

    /**
     * 区组诊断查询（只读）：返回容量与名额的实际值，供调用方核对守恒。
     */
    public BlockDiagnosticsView getDiagnostics(String experimentId, int blockNo) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        requireBlock(experiment, blockNo);
        RandomTableVersionRow latest = versionRepository.findLatest(experimentId, blockNo);
        int capacity = latest != null
                ? latest.seatTo()
                : experimentRepository.findSeatsByBlock(experimentId, blockNo).size();
        long allocated = allocationRepository.countByBlock(experimentId, blockNo);
        long remaining = allocationRepository.countRemainingAllocatable(experimentId, blockNo);
        return new BlockDiagnosticsView(experimentId, blockNo, latest != null,
                latest == null ? null : latest.versionNo(), capacity,
                Math.toIntExact(allocated), Math.toIntExact(remaining));
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
            throw ApiException.notFound("区组不存在: " + blockNo
                    + "（实验区组范围 1~" + experiment.blockCount() + "）");
        }
    }

    /** 拼接席位处理序列（盲底，仅用于摘要计算，不输出）。 */
    private String sequence(List<SeatRow> seats) {
        return seats.stream().map(SeatRow::treatment).collect(Collectors.joining());
    }

    /** 处理代码集合：去重排序拼接，如 AB。 */
    private String treatmentCodes(List<SeatRow> seats) {
        return seats.stream().map(SeatRow::treatment)
                .collect(Collectors.toCollection(TreeSet::new)).stream()
                .collect(Collectors.joining());
    }

    /**
     * 随机表摘要：绑定实验、区组、版本、累计容量、完整处理序列与封存密钥。
     */
    private String digest(String experimentId, int blockNo, int versionNo, int seatTo,
                          String sequence, String sealKey) {
        String raw = experimentId + "|" + blockNo + "|" + versionNo + "|" + seatTo
                + "|" + sequence + "|" + sealKey;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private RandomTableVersionView toView(RandomTableVersionRow row) {
        return new RandomTableVersionView(row.experimentId(), row.blockNo(), row.versionNo(),
                row.tableDigest(), row.seatTo(), row.addedSeats(), row.treatmentCodes(),
                row.predecessorVersion(), row.status(), row.sealedAt(), row.closedAt());
    }
}
