package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.KitPickupView;
import com.example.starter.blind.dto.KitStatusView;
import com.example.starter.blind.dto.KitView;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import com.example.starter.blind.repo.KitPickupRepository;
import com.example.starter.blind.repo.KitPickupRepository.KitPickupRow;
import com.example.starter.blind.repo.KitRepository;
import com.example.starter.blind.repo.KitRepository.KitRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 处理包发放业务：
 * SUPPLY 登记处理包（含 A/B 盲底代码）；协调员为在组参与者领取，
 * 按匹配处理代码的包编号字典序发放第一个未发包；只回随机包装码与领取ID。
 * 领取与退组、关闭、其他领取通过实验行级锁一次提交，不双发、不发给已退组者。
 * 处理代码、匹配数量、包编号不进入普通响应与日志。
 */
@Service
public class KitService {

    /** 发放状态：在库。 */
    static final String STATUS_STOCK = "STOCK";
    /** 发放状态：已发放（不回库）。 */
    static final String STATUS_ISSUED = "ISSUED";

    private final KitRepository kitRepository;
    private final KitPickupRepository kitPickupRepository;
    private final ExperimentRepository experimentRepository;
    private final ExperimentService experimentService;
    private final BlindCodeGenerator blindCodeGenerator;
    private final Clock clock;

    public KitService(KitRepository kitRepository,
                      KitPickupRepository kitPickupRepository,
                      ExperimentRepository experimentRepository,
                      ExperimentService experimentService,
                      BlindCodeGenerator blindCodeGenerator,
                      Clock clock) {
        this.kitRepository = kitRepository;
        this.kitPickupRepository = kitPickupRepository;
        this.experimentRepository = experimentRepository;
        this.experimentService = experimentService;
        this.blindCodeGenerator = blindCodeGenerator;
        this.clock = clock;
    }

    /**
     * SUPPLY 登记处理包；生成随机包装码。同实验包编号重复 409。
     */
    @Transactional
    public KitView registerKit(String experimentId, String kitNo, String treatment, String actorId) {
        if (!"A".equals(treatment) && !"B".equals(treatment)) {
            throw ApiException.badRequest("treatment 仅允许 A 或 B");
        }
        if (experimentRepository.findById(experimentId) == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if (kitRepository.findByKitNo(experimentId, kitNo) != null) {
            throw ApiException.conflict("包编号已存在: " + kitNo);
        }
        long now = clock.nowMillis();
        // 包装码随机冲突概率极低，仍由唯一索引兜底并重试。
        for (int attempt = 0; attempt < 5; attempt++) {
            KitRow row = new KitRow(0L, experimentId, kitNo, treatment,
                    blindCodeGenerator.nextCode(), actorId, now);
            try {
                kitRepository.insert(row);
                KitRow stored = kitRepository.findByKitNo(experimentId, kitNo);
                return new KitView(stored.experimentId(), stored.kitNo(), stored.treatment(),
                        stored.packageCode(), STATUS_STOCK, stored.registeredAt());
            } catch (DuplicateKeyException e) {
                if (kitRepository.isDuplicatePackageCode(e)) {
                    continue;
                }
                if (kitRepository.isDuplicateKitNo(e)) {
                    // 并发登记同编号：唯一约束兜底，事务随幂等占位一并回滚。
                    throw ApiException.conflict("包编号已存在: " + kitNo);
                }
                throw e;
            }
        }
        throw ApiException.conflict("包装码生成冲突，请重试");
    }

    /**
     * SUPPLY 供应查询：只返回包的发放状态，不含处理代码、包装码与参与者信息。
     */
    public KitStatusView getKitStatus(String experimentId, String kitNo) {
        KitRow kit = kitRepository.findByKitNo(experimentId, kitNo);
        if (kit == null) {
            throw ApiException.notFound("处理包不存在: " + kitNo);
        }
        KitPickupRow pickup = kitPickupRepository.findByKitId(kit.id());
        if (pickup == null) {
            return new KitStatusView(kit.kitNo(), STATUS_STOCK, null);
        }
        return new KitStatusView(kit.kitNo(), STATUS_ISSUED, pickup.pickedAt());
    }

    /**
     * 协调员为参与者领取处理包：实验行级锁串行化领取、退组与关闭，一次提交；
     * 只返回随机包装码与领取ID，不返回处理代码、包编号或匹配数量。
     */
    @Transactional
    public KitPickupView pickup(String experimentId, String participantId, String actorId) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if ("CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，不能新领处理包");
        }
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(experimentId, participantId);
        if ("WITHDRAWN".equals(allocation.status())) {
            throw ApiException.conflict("参与者已退组，不能新领处理包");
        }
        if (kitPickupRepository.findByAllocationId(allocation.id()) != null) {
            // 同参与者同次分配只能领取一次。
            throw ApiException.conflict("该参与者已领取过处理包");
        }
        // 仅在库内读取处理映射（盲底）用于匹配，不输出到响应与日志。
        SeatRow seat = experimentRepository.findSeat(experimentId,
                allocation.blockNo(), allocation.seatNo());
        if (seat == null) {
            throw new IllegalStateException("席位映射缺失，数据不一致");
        }
        KitRow kit = kitRepository.findFirstUnissued(experimentId, seat.treatment());
        if (kit == null) {
            // 无匹配包：422 且不占包；提示不含处理代码与数量。
            throw ApiException.full("当前无可用处理包");
        }
        KitPickupRow pickup = new KitPickupRow(
                "KP-" + UUID.randomUUID().toString().replace("-", ""),
                experimentId, participantId, allocation.id(), kit.id(),
                kit.packageCode(), actorId, clock.nowMillis());
        try {
            kitPickupRepository.insert(pickup);
        } catch (DuplicateKeyException e) {
            if (kitPickupRepository.isDuplicateAllocation(e)
                    || kitPickupRepository.isDuplicateKit(e)) {
                // 极端并发下由唯一约束兜底，按冲突处理，事务随幂等占位一并回滚。
                throw ApiException.conflict("处理包或领取记录已被占用，请重试");
            }
            throw e;
        }
        return new KitPickupView(pickup.id(), experimentId, participantId,
                kit.packageCode(), pickup.pickedAt());
    }
}