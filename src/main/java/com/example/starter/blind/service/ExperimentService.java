package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.AllocationView;
import com.example.starter.blind.dto.ExperimentView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.AllocationRepository.VacantSeat;
import com.example.starter.blind.repo.CenterRepository;
import com.example.starter.blind.repo.CenterRepository.CenterRow;
import com.example.starter.blind.repo.CenterSequenceRepository;
import com.example.starter.blind.repo.CenterSequenceRepository.SequenceRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import com.example.starter.blind.repo.ProtocolSeatRepository;
import com.example.starter.blind.repo.ProtocolVersionRepository;
import com.example.starter.blind.repo.ProtocolVersionRepository.ProtocolVersionRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 实验与分配核心业务：
 * 创建实验时固定初始协议 V1（50:50）与每区组两 A 两 B 的席位内容；
 * 全局登记按 V1 区组、席位顺序领取空位；中心登记按中心当前协议版本消耗预留盲码序列。
 * 退组保留席位不重排；关闭后拒绝新增分配。普通视图不含席位号与处理代码。
 */
@Service
public class ExperimentService {

    static final int SEATS_PER_BLOCK = 4;
    static final int INITIAL_VERSION = 1;
    static final String INITIAL_RATIO = "50:50";
    static final String SYSTEM_ACTOR = "SYSTEM";

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final ProtocolVersionRepository protocolVersionRepository;
    private final ProtocolSeatRepository protocolSeatRepository;
    private final CenterRepository centerRepository;
    private final CenterSequenceRepository centerSequenceRepository;
    private final BlindCodeGenerator blindCodeGenerator;
    private final Clock clock;
    private final ProtocolAmendmentService protocolAmendmentService;

    public ExperimentService(ExperimentRepository experimentRepository,
                             AllocationRepository allocationRepository,
                             ProtocolVersionRepository protocolVersionRepository,
                             ProtocolSeatRepository protocolSeatRepository,
                             CenterRepository centerRepository,
                             CenterSequenceRepository centerSequenceRepository,
                             BlindCodeGenerator blindCodeGenerator,
                             Clock clock,
                             ProtocolAmendmentService protocolAmendmentService) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.protocolVersionRepository = protocolVersionRepository;
        this.protocolSeatRepository = protocolSeatRepository;
        this.centerRepository = centerRepository;
        this.centerSequenceRepository = centerSequenceRepository;
        this.blindCodeGenerator = blindCodeGenerator;
        this.clock = clock;
        this.protocolAmendmentService = protocolAmendmentService;
    }

    /**
     * 创建实验并固定初始协议 V1：每区组按席位顺序两个 A、两个 B。
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
        // 初始协议 V1 随实验创建即生效，50:50，无需修订流程。
        protocolVersionRepository.insert(new ProtocolVersionRow(
                experimentId, INITIAL_VERSION, 50, 50, now, "EFFECTIVE",
                SYSTEM_ACTOR, now, null, null, null, null));
        return new ExperimentView(experimentId, blockCount, SEATS_PER_BLOCK,
                blockCount * SEATS_PER_BLOCK, "OPEN", now);
    }

    public ExperimentView getExperiment(String experimentId) {
        ExperimentRow row = mustFindExperiment(experimentId);
        return new ExperimentView(row.id(), row.blockCount(), SEATS_PER_BLOCK,
                row.blockCount() * SEATS_PER_BLOCK, row.status(), row.createdAt());
    }

    /**
     * 旧的无中心全局登记：原子领取 V1 第一个空位；实验行级锁串行化同实验并发分配。
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
        // 提交顺序裁决：登记前若有待生效修订已到点，先在本事务内原子生效。
        protocolAmendmentService.effectIfDue(experimentId);
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
        AllocationRow inserted = insertWithUniqueBlindCode(experimentId, participantId, null,
                INITIAL_VERSION, actorId, vacant, now);
        return toView(inserted);
    }

    /**
     * 中心登记：中心须 ACTIVE，按中心当前协议版本顺序消耗该中心预留的独立盲码序列，
     * 并在该版本中心席位池中顺序占座。先锁实验行串行化跨中心取座，再锁中心行串行化同中心取码。
     */
    @Transactional
    public AllocationView registerAtCenter(String experimentId, String centerId,
                                           String participantId, String actorId) {
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
        // 提交顺序裁决：中心登记前若有待生效修订已到点，先在本事务内原子生效。
        protocolAmendmentService.effectIfDue(experimentId);
        CenterRow center = centerRepository.lock(experimentId, centerId);
        if (center == null) {
            throw ApiException.notFound("中心不存在或未激活: " + centerId);
        }
        if ("SUSPENDED".equals(center.status())) {
            throw ApiException.conflict("中心已暂停，暂停期间拒绝新分配");
        }
        AllocationRow existing =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (existing != null) {
            throw ApiException.conflict("参与者已在该实验登记，仅可占一席");
        }
        int version = center.currentVersion();
        SequenceRow code = centerSequenceRepository.takeFirstAvailable(
                experimentId, centerId, version);
        if (code == null) {
            // 预留盲码已耗尽（中心达剩余容量上限）。
            throw ApiException.full("中心当前协议盲码序列已耗尽");
        }
        VacantSeat vacant = allocationRepository.takeFirstCenterVacantSeat(experimentId, version);
        if (vacant == null) {
            // 容量预留保证不会发生；发生即数据不一致，按不可处理拒绝且不消耗盲码。
            throw ApiException.unprocessable("中心当前协议席位不足");
        }
        long now = clock.nowMillis();
        AllocationRow inserted;
        try {
            allocationRepository.insert(new AllocationRow(
                    0L, experimentId, participantId, centerId, version,
                    vacant.blockNo(), vacant.seatNo(), code.blindCode(), "ASSIGNED",
                    actorId, now, null));
            inserted = allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        } catch (DuplicateKeyException e) {
            if (allocationRepository.isDuplicateParticipant(e)) {
                throw ApiException.conflict("参与者已在该实验登记，仅可占一席");
            }
            // 盲码/席位冲突由预留容量与唯一约束兜底，按冲突处理，事务回滚不消耗盲码。
            throw ApiException.conflict("席位或盲码被并发占用，请重试");
        }
        if (centerSequenceRepository.consume(code.id(), inserted.id(), now) != 1) {
            // 盲码被并发消耗：理论上被中心行锁排除，发生即回滚。
            throw ApiException.conflict("盲码被并发消耗，请重试");
        }
        return toView(inserted);
    }

    private AllocationRow insertWithUniqueBlindCode(String experimentId, String participantId,
                                                    String centerId, int version, String actorId,
                                                    VacantSeat vacant, long now) {
        // 盲码随机冲突概率极低，仍由唯一索引兜底并重试。
        for (int attempt = 0; attempt < 5; attempt++) {
            String blindCode = blindCodeGenerator.nextCode();
            AllocationRow row = new AllocationRow(0L, experimentId, participantId, centerId,
                    version, vacant.blockNo(), vacant.seatNo(), blindCode, "ASSIGNED",
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
        return new ExperimentView(experiment.id(), experiment.blockCount(), SEATS_PER_BLOCK,
                experiment.blockCount() * SEATS_PER_BLOCK, "CLOSED", experiment.createdAt());
    }

    /**
     * 普通查询：只返回盲码、区组号、参与者编号、归属中心/版本与退组状态。
     */
    public AllocationView getAllocation(String experimentId, String participantId) {
        return toView(mustFindAllocation(experimentId, participantId));
    }

    /** 内部使用的完整分配行（含盲底字段），禁止直接透出到普通响应。 */
    public AllocationRow mustFindAllocationRow(String experimentId, String participantId) {
        return mustFindAllocation(experimentId, participantId);
    }

    /**
     * 获取实验行级锁以串行化提交顺序（创建修订/生效/登记/暂停/揭盲申请），
     * 供其他写业务在同一事务内先取得统一顺序锁；实验不存在抛 404。
     */
    public void lockExperimentForOrdering(String experimentId) {
        if (experimentRepository.lockById(experimentId) == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
    }

    /**
     * 按分配所属协议版本解析席位处理代码（盲底）：中心登记（含 V1 中心池与 V2+）查 protocol_seat，
     * 旧的无中心全局登记查 seat。既有盲态永远按其登记时版本解析，不受后续修订影响。
     */
    public String resolveTreatment(AllocationRow allocation) {
        String treatment;
        if (allocation.centerId() == null) {
            SeatRow seat = experimentRepository.findSeat(allocation.experimentId(),
                    allocation.blockNo(), allocation.seatNo());
            treatment = seat == null ? null : seat.treatment();
        } else {
            treatment = protocolSeatRepository.findTreatment(allocation.experimentId(),
                    allocation.protocolVersion(), allocation.blockNo(), allocation.seatNo());
        }
        if (treatment == null) {
            throw new IllegalStateException("席位映射缺失，数据不一致");
        }
        return treatment;
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
        return new AllocationView(row.experimentId(), row.participantId(), row.centerId(),
                row.protocolVersion(), row.blindCode(), row.blockNo(), row.status(),
                row.assignedAt(), row.withdrawnAt());
    }
}
