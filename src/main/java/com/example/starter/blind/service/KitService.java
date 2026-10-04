package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.KitPickupView;
import com.example.starter.blind.dto.KitView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import com.example.starter.blind.repo.KitRepository;
import com.example.starter.blind.repo.KitRepository.KitPickupRow;
import com.example.starter.blind.repo.KitRepository.KitRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 处理包供应与领取业务：
 * SUPPLY 登记处理包（处理代码仅落库，不回显）；协调员为在组参与者领取，
 * 按匹配处理代码的包编号字典序发第一个在库包；响应只含随机包装码与领取编号。
 * 领取与退组、关闭及其他领取通过实验行锁、分配行锁与条件更新保证原子提交；
 * 发过的包不回库、不转让，同一分配只能领取一次。处理代码不进入任何响应与日志。
 */
@Service
public class KitService {

    /** 包装码随机冲突时的最大重试次数，唯一索引兜底。 */
    private static final int MAX_WRAPPER_ATTEMPTS = 5;

    private final KitRepository kitRepository;
    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final BlindCodeGenerator blindCodeGenerator;
    private final Clock clock;

    public KitService(KitRepository kitRepository,
                      ExperimentRepository experimentRepository,
                      AllocationRepository allocationRepository,
                      BlindCodeGenerator blindCodeGenerator,
                      Clock clock) {
        this.kitRepository = kitRepository;
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.blindCodeGenerator = blindCodeGenerator;
        this.clock = clock;
    }

    /**
     * SUPPLY 登记处理包；处理代码只落库，响应不回显。实验不存在 404，编号重复 409。
     */
    @Transactional
    public KitView registerKit(String experimentId, String packageNo, String treatment,
                               String actorId) {
        mustFindExperiment(experimentId);
        if (kitRepository.findKit(experimentId, packageNo) != null) {
            throw ApiException.conflict("处理包编号已存在: " + packageNo);
        }
        long now = clock.nowMillis();
        KitRow row = new KitRow(0L, experimentId, packageNo, treatment, "STOCK",
                actorId, now, null, null);
        try {
            kitRepository.insertKit(row);
        } catch (DuplicateKeyException e) {
            // 并发登记同编号：唯一约束兜底，按冲突处理，不泄露数据库异常。
            throw ApiException.conflict("处理包编号已存在: " + packageNo);
        }
        return new KitView(experimentId, packageNo, "STOCK", now, null);
    }

    /**
     * 供应查询：只返回包编号与发放状态，不含处理代码与分配信息。
     */
    public KitView getKit(String experimentId, String packageNo) {
        mustFindExperiment(experimentId);
        KitRow row = kitRepository.findKit(experimentId, packageNo);
        if (row == null) {
            throw ApiException.notFound("处理包不存在: " + packageNo);
        }
        return new KitView(row.experimentId(), row.packageNo(), row.status(),
                row.registeredAt(), row.issuedAt());
    }

    /**
     * 协调员为参与者领取处理包：
     * 实验行锁串行化关闭与其他领取，分配行锁串行化退组；
     * 按匹配处理代码的包编号字典序原子发放第一个在库包；
     * 无匹配包 422 且不占包；退组或已关闭 409；同一分配只能领取一次。
     */
    @Transactional
    public KitPickupView pickup(String experimentId, String participantId, String actorId) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if ("CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，不能领取处理包");
        }
        AllocationRow allocation =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        // 行锁分配行：与并发退组串行，提交前对方只能等待。
        AllocationRow locked = allocationRepository.lockById(allocation.id());
        if (locked == null || !"ASSIGNED".equals(locked.status())) {
            throw ApiException.conflict("参与者已退组，不能领取处理包");
        }
        if (kitRepository.findPickupByAllocation(locked.id()) != null) {
            throw ApiException.conflict("该参与者已领取过处理包");
        }
        // 处理代码仅在库内读取，用于匹配库存，不进入响应与日志。
        SeatRow seat = experimentRepository.findSeat(experimentId,
                locked.blockNo(), locked.seatNo());
        if (seat == null) {
            throw new IllegalStateException("席位映射缺失，数据不一致");
        }
        KitRow kit = kitRepository.findFirstStockKit(experimentId, seat.treatment());
        if (kit == null) {
            // 提示不含处理代码与匹配数量；失败回滚，不占包、不占幂等键。
            throw ApiException.full("当前无可用处理包");
        }
        long now = clock.nowMillis();
        if (kitRepository.markIssued(kit.id(), locked.id(), now) != 1) {
            // 极端并发下条件更新兜底：包已被发出，按冲突处理。
            throw ApiException.conflict("处理包发放冲突，请重试");
        }
        KitPickupRow pickup = insertPickupWithUniqueWrapper(experimentId, participantId,
                locked.id(), kit.id(), actorId, now);
        return new KitPickupView(pickup.id(), experimentId, participantId,
                pickup.wrapperCode(), now);
    }

    private KitPickupRow insertPickupWithUniqueWrapper(String experimentId, String participantId,
                                                       long allocationId, long kitId,
                                                       String actorId, long now) {
        // 包装码随机冲突概率极低，仍由唯一索引兜底并重试。
        for (int attempt = 0; attempt < MAX_WRAPPER_ATTEMPTS; attempt++) {
            String pickupId = "KP-" + UUID.randomUUID().toString().replace("-", "");
            KitPickupRow row = new KitPickupRow(pickupId, experimentId, participantId,
                    allocationId, kitId, blindCodeGenerator.nextCode(), actorId, now);
            try {
                kitRepository.insertPickup(row);
                return row;
            } catch (DuplicateKeyException e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                if (message.contains("uq_pickup_wrapper")
                        || message.contains("pk_kit_pickup")) {
                    continue;
                }
                // 同一分配重复领取或同一包重复发放：唯一约束兜底，按冲突处理。
                throw ApiException.conflict("领取冲突，请重试");
            }
        }
        throw ApiException.conflict("包装码生成冲突，请重试");
    }

    private ExperimentRow mustFindExperiment(String experimentId) {
        ExperimentRow row = experimentRepository.findById(experimentId);
        if (row == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        return row;
    }
}