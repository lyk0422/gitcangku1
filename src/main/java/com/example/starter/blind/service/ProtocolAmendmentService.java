package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.CreateAmendmentRequest;
import com.example.starter.blind.dto.ProtocolVersionView;
import com.example.starter.blind.repo.CenterRepository;
import com.example.starter.blind.repo.CenterRepository.CenterRow;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ProtocolVersionRepository;
import com.example.starter.blind.repo.ProtocolVersionRepository.ProtocolVersionRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 盲法协议修订业务：
 * 创建待生效修订（比例正整数且和为100、生效时刻不早于当前时刻），每实验至多一条待生效版本；
 * 生效时为每个 ACTIVE 中心按“目标入组上限减累计已分配数”的剩余容量预留独立盲码序列与中心席位池，
 * 任一中心容量不足、序列生成失败或存在待处理揭盲申请即 422，整体回滚，所有中心协议与序列不变；
 * 修订只影响生效后新登记，既有盲码、分组与揭盲永远归属旧版本；未生效可撤销并保留记录，已生效不可撤销。
 * 调用方须已持有实验行级锁以保证创建/生效/分配/暂停按提交顺序裁决。
 */
@Service
public class ProtocolAmendmentService {

    private final ProtocolVersionRepository protocolVersionRepository;
    private final ExperimentRepository experimentRepository;
    private final CenterRepository centerRepository;
    private final AllocationRepository allocationRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final ProvisioningService provisioningService;
    private final Clock clock;

    public ProtocolAmendmentService(ProtocolVersionRepository protocolVersionRepository,
                                    ExperimentRepository experimentRepository,
                                    CenterRepository centerRepository,
                                    AllocationRepository allocationRepository,
                                    UnblindRequestRepository unblindRequestRepository,
                                    ProvisioningService provisioningService,
                                    Clock clock) {
        this.protocolVersionRepository = protocolVersionRepository;
        this.experimentRepository = experimentRepository;
        this.centerRepository = centerRepository;
        this.allocationRepository = allocationRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.provisioningService = provisioningService;
        this.clock = clock;
    }

    /**
     * 创建单条修订（调用方已做幂等与权限编排）；实验行级锁由本方法获取。
     */
    @Transactional
    public ProtocolVersionView create(String experimentId, int ratioA, int ratioB,
                                      long effectiveAt, String actorId) {
        validateRatios(ratioA, ratioB);
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        long now = clock.nowMillis();
        if (effectiveAt < now) {
            throw ApiException.unprocessable("生效时刻不得早于当前时刻");
        }
        if (protocolVersionRepository.findPending(experimentId) != null) {
            throw ApiException.unprocessable("已存在待生效协议修订，批量创建只允许一条最终待生效版本");
        }
        int nextVersion = nextVersion(experimentId);
        ProtocolVersionRow row = new ProtocolVersionRow(experimentId, nextVersion, ratioA, ratioB,
                effectiveAt, "PENDING", actorId, now, null, null, null, experimentId);
        try {
            protocolVersionRepository.insert(row);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发创建：唯一待生效占位兜底。
            throw ApiException.unprocessable("已存在待生效协议修订，批量创建只允许一条最终待生效版本");
        }
        return toView(protocolVersionRepository.find(experimentId, nextVersion));
    }

    /**
     * 批量创建修订：只允许一条最终待生效版本。每实验全局至多一条 PENDING，
     * 因此批量中最终处于待生效的修订数加上既有待生效数不得超过 1；
     * 不满足即 422，整批不落任何记录。
     */
    @Transactional
    public List<ProtocolVersionView> createBatch(String experimentId,
                                                 List<CreateAmendmentRequest> amendments,
                                                 String actorId) {
        if (amendments == null || amendments.isEmpty()) {
            throw ApiException.badRequest("amendments 不能为空");
        }
        for (CreateAmendmentRequest a : amendments) {
            validateRatios(a.ratioA(), a.ratioB());
        }
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        long now = clock.nowMillis();
        boolean hasPending = protocolVersionRepository.findPending(experimentId) != null;
        // 每条新建修订均为待生效；只允许一条最终待生效版本：
        // 既有已有待生效时本批必须为空，否则本批至多一条。
        long pendingAfter = (hasPending ? 1L : 0L) + amendments.size();
        if (pendingAfter > 1) {
            throw ApiException.unprocessable("批量创建只允许一条最终待生效版本");
        }
        for (CreateAmendmentRequest a : amendments) {
            if (a.effectiveAt() < now) {
                throw ApiException.unprocessable("生效时刻不得早于当前时刻");
            }
        }
        int version = nextVersion(experimentId);
        List<ProtocolVersionView> views = new java.util.ArrayList<>();
        for (CreateAmendmentRequest a : amendments) {
            ProtocolVersionRow row = new ProtocolVersionRow(experimentId, version,
                    a.ratioA(), a.ratioB(), a.effectiveAt(), "PENDING", actorId, now,
                    null, null, null, experimentId);
            protocolVersionRepository.insert(row);
            views.add(toView(protocolVersionRepository.find(experimentId, version)));
            version++;
        }
        return views;
    }

    private void validateRatios(int ratioA, int ratioB) {
        if (ratioA < 1 || ratioB < 1) {
            throw ApiException.badRequest("比例必须为正整数");
        }
        if (ratioA + ratioB != 100) {
            throw ApiException.badRequest("比例之和必须为 100");
        }
    }

    private int nextVersion(String experimentId) {
        return protocolVersionRepository.findByExperiment(experimentId).stream()
                .mapToInt(ProtocolVersionRow::version).max().orElse(0) + 1;
    }

    /**
     * 手动生效指定修订：必须存在、处于 PENDING、且当前时刻已到生效时刻。
     */
    @Transactional
    public ProtocolVersionView effect(String experimentId, int version) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        ProtocolVersionRow pending = protocolVersionRepository.lock(experimentId, version);
        if (pending == null) {
            throw ApiException.notFound("协议修订不存在: V" + version);
        }
        if (!"PENDING".equals(pending.status())) {
            throw ApiException.conflict("协议修订非待生效状态，当前状态: " + pending.status());
        }
        if (clock.nowMillis() < pending.effectiveAt()) {
            throw ApiException.unprocessable("尚未到生效时刻");
        }
        applyEffect(experimentId, pending);
        return toView(protocolVersionRepository.find(experimentId, version));
    }

    /**
     * 若存在已到生效时刻的待生效修订，则在当前事务内原子生效；否则不做任何变更。
     * 供登记/恢复等操作在持有实验行级锁后惰性触发，保证“生效时”自动切换。
     *
     * @return 是否实际发生生效
     */
    public boolean effectIfDue(String experimentId) {
        ProtocolVersionRow pending = protocolVersionRepository.findPending(experimentId);
        if (pending == null || clock.nowMillis() < pending.effectiveAt()) {
            return false;
        }
        applyEffect(experimentId, pending);
        return true;
    }

    /**
     * 原子生效核心：校验待审揭盲与各 ACTIVE 中心剩余容量，生成版本席位池与每中心独立盲码序列，
     * 随后一次性切换版本状态与中心当前版本。任一步失败抛出异常，整体回滚不留半成品。
     */
    private void applyEffect(String experimentId, ProtocolVersionRow pending) {
        if (unblindRequestRepository.countPendingByExperiment(experimentId) > 0) {
            throw ApiException.unprocessable("存在待处理揭盲申请，协议修订不得生效");
        }
        List<CenterRow> centers = centerRepository.lockByExperiment(experimentId);
        // 先做全部容量校验，再做任何写入，保证失败时所有中心协议与序列不变。
        long totalRemaining = 0L;
        for (CenterRow center : centers) {
            if (!"ACTIVE".equals(center.status())) {
                continue;
            }
            long allocated = allocationRepository.countByCenter(experimentId, center.centerId());
            long remaining = center.targetCap() - allocated;
            if (remaining <= 0) {
                // 容量不足：ACTIVE 中心已达目标入组上限，无法为其预留任何新版本盲码序列。
                throw ApiException.unprocessable(
                        "中心 " + center.centerId() + " 容量不足，剩余容量为 " + Math.max(remaining, 0));
            }
            totalRemaining += remaining;
        }
        int newVersion = pending.version();
        // 1) 版本中心席位池（跨 ACTIVE 中心共享，按规范化区组向上取整）。
        provisioningService.ensureSeatCapacity(experimentId, newVersion,
                pending.ratioA(), pending.ratioB(), totalRemaining);
        // 2) 每个 ACTIVE 中心预留恰好剩余容量条独立盲码序列；暂停中心不预留、不切换。
        for (CenterRow center : centers) {
            if (!"ACTIVE".equals(center.status())) {
                continue;
            }
            long allocated = allocationRepository.countByCenter(experimentId, center.centerId());
            long remaining = center.targetCap() - allocated;
            provisioningService.generateSequences(experimentId, center.centerId(),
                    newVersion, remaining);
        }
        long now = clock.nowMillis();
        ProtocolVersionRow currentEffective = protocolVersionRepository.findEffective(experimentId);
        // 3) 状态与中心版本切换（同一事务原子提交）。
        if (protocolVersionRepository.markEffective(experimentId, newVersion) != 1) {
            throw ApiException.unprocessable("协议修订生效失败，状态已变更");
        }
        if (currentEffective != null
                && protocolVersionRepository.markSuperseded(
                        experimentId, currentEffective.version(), now) != 1) {
            throw ApiException.unprocessable("旧协议版本归档失败");
        }
        centerRepository.updateVersionForActiveCenters(experimentId, newVersion);
    }

    /**
     * 撤销未生效修订：仅 PENDING 可撤销，记录保留为 REVOKED；已生效不可撤销。
     */
    @Transactional
    public ProtocolVersionView revoke(String experimentId, int version, String actorId) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        ProtocolVersionRow row = protocolVersionRepository.lock(experimentId, version);
        if (row == null) {
            throw ApiException.notFound("协议修订不存在: V" + version);
        }
        if ("EFFECTIVE".equals(row.status()) || "SUPERSEDED".equals(row.status())) {
            throw ApiException.conflict("已生效修订不可撤销");
        }
        if ("REVOKED".equals(row.status())) {
            throw ApiException.conflict("协议修订已撤销");
        }
        long now = clock.nowMillis();
        if (protocolVersionRepository.markRevoked(experimentId, version, now, actorId) != 1) {
            throw ApiException.unprocessable("协议修订撤销失败，状态已变更");
        }
        return toView(protocolVersionRepository.find(experimentId, version));
    }

    public List<ProtocolVersionView> listVersions(String experimentId) {
        if (experimentRepository.findById(experimentId) == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        return protocolVersionRepository.findByExperiment(experimentId).stream()
                .map(this::toView).toList();
    }

    private ProtocolVersionView toView(ProtocolVersionRow row) {
        return new ProtocolVersionView(row.experimentId(), row.version(), row.ratioA(),
                row.ratioB(), row.effectiveAt(), row.status(), row.createdBy(), row.createdAt(),
                row.revokedAt(), row.revokedBy(), row.supersededAt());
    }
}
