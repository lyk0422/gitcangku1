package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.CenterSequenceView;
import com.example.starter.blind.dto.CenterView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.CenterRepository;
import com.example.starter.blind.repo.CenterRepository.CenterRow;
import com.example.starter.blind.repo.CenterSequenceRepository;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ProtocolVersionRepository;
import com.example.starter.blind.repo.ProtocolVersionRepository.ProtocolVersionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 中心激活、暂停、恢复与查询：
 * 激活时按目标入组上限为当前有效协议预留独立盲码序列与中心席位池；
 * 暂停中心不参与修订生效；恢复时切换到当时有效版本并补足该版本容量与序列。
 * 剩余容量 = 目标入组上限 - 累计已分配数（含退组，席位不释放）。
 */
@Service
public class CenterService {

    private final CenterRepository centerRepository;
    private final ExperimentRepository experimentRepository;
    private final ProtocolVersionRepository protocolVersionRepository;
    private final AllocationRepository allocationRepository;
    private final CenterSequenceRepository centerSequenceRepository;
    private final ProvisioningService provisioningService;
    private final ProtocolAmendmentService protocolAmendmentService;
    private final Clock clock;

    public CenterService(CenterRepository centerRepository,
                         ExperimentRepository experimentRepository,
                         ProtocolVersionRepository protocolVersionRepository,
                         AllocationRepository allocationRepository,
                         CenterSequenceRepository centerSequenceRepository,
                         ProvisioningService provisioningService,
                         ProtocolAmendmentService protocolAmendmentService,
                         Clock clock) {
        this.centerRepository = centerRepository;
        this.experimentRepository = experimentRepository;
        this.protocolVersionRepository = protocolVersionRepository;
        this.allocationRepository = allocationRepository;
        this.centerSequenceRepository = centerSequenceRepository;
        this.provisioningService = provisioningService;
        this.protocolAmendmentService = protocolAmendmentService;
        this.clock = clock;
    }

    /**
     * 激活中心：按当时有效协议版本与目标入组上限预留序列与席位池。
     * 已存在（激活或暂停）的中心编号返回 409。
     */
    @Transactional
    public CenterView activate(String experimentId, String centerId, int targetCap) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if (centerRepository.find(experimentId, centerId) != null) {
            throw ApiException.conflict("中心已存在: " + centerId);
        }
        ProtocolVersionRow effective = protocolVersionRepository.findEffective(experimentId);
        if (effective == null) {
            throw new IllegalStateException("实验缺少有效协议版本，数据不一致");
        }
        long now = clock.nowMillis();
        int version = effective.version();
        // 新中心尚无分配，剩余容量即目标上限：预留 targetCap 条盲码与足够席位。
        provisioningService.ensureSeatCapacity(experimentId, version,
                effective.ratioA(), effective.ratioB(), targetCap);
        provisioningService.generateSequences(experimentId, centerId, version, targetCap);
        try {
            centerRepository.insert(new CenterRow(experimentId, centerId, targetCap, "ACTIVE",
                    version, now, null, null));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发激活同一中心：主键兜底，事务回滚已生成序列。
            throw ApiException.conflict("中心已存在: " + centerId);
        }
        return toView(centerRepository.find(experimentId, centerId), 0L);
    }

    /**
     * 暂停中心；已暂停返回 409，不存在返回 404。暂停中心不参与修订生效。
     * 先取实验行级锁，使暂停与创建/生效/登记按提交顺序裁决。
     */
    @Transactional
    public CenterView suspend(String experimentId, String centerId) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        CenterRow center = centerRepository.lock(experimentId, centerId);
        if (center == null) {
            throw ApiException.notFound("中心不存在或未激活: " + centerId);
        }
        if ("SUSPENDED".equals(center.status())) {
            throw ApiException.conflict("中心已暂停");
        }
        long now = clock.nowMillis();
        if (centerRepository.markSuspended(experimentId, centerId, now) != 1) {
            throw ApiException.conflict("中心暂停失败，请重试");
        }
        return getCenter(experimentId, centerId);
    }

    /**
     * 恢复暂停中心：使用当时有效版本；若有效版本较新，补足该版本剩余容量序列与席位池后切换。
     */
    @Transactional
    public CenterView resume(String experimentId, String centerId) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        // 提交顺序裁决：恢复前若有待生效修订已到点，先原子生效，恢复后使用当时有效版本。
        protocolAmendmentService.effectIfDue(experimentId);
        CenterRow center = centerRepository.lock(experimentId, centerId);
        if (center == null) {
            throw ApiException.notFound("中心不存在或未激活: " + centerId);
        }
        if ("ACTIVE".equals(center.status())) {
            throw ApiException.conflict("中心未暂停，无需恢复");
        }
        ProtocolVersionRow effective = protocolVersionRepository.findEffective(experimentId);
        if (effective == null) {
            throw new IllegalStateException("实验缺少有效协议版本，数据不一致");
        }
        long now = clock.nowMillis();
        long allocated = allocationRepository.countByCenter(experimentId, centerId);
        int targetVersion = effective.version();
        if (targetVersion != center.currentVersion()) {
            // 暂停期间发生过修订生效：恢复时按当时有效版本与剩余容量预留。
            long remaining = center.targetCap() - allocated;
            if (remaining < 0) {
                remaining = 0;
            }
            provisioningService.ensureSeatCapacity(experimentId, targetVersion,
                    effective.ratioA(), effective.ratioB(), remaining);
            provisioningService.generateSequences(experimentId, centerId, targetVersion, remaining);
        }
        if (centerRepository.markResumed(experimentId, centerId, targetVersion, now) != 1) {
            throw ApiException.conflict("中心恢复失败，请重试");
        }
        return getCenter(experimentId, centerId);
    }

    public CenterView getCenter(String experimentId, String centerId) {
        mustFindExperiment(experimentId);
        CenterRow center = centerRepository.find(experimentId, centerId);
        if (center == null) {
            throw ApiException.notFound("中心不存在或未激活: " + centerId);
        }
        long allocated = allocationRepository.countByCenter(experimentId, centerId);
        return toView(center, allocated);
    }

    /** 查询中心在某协议版本下的盲码序列容量与消耗进度。 */
    public CenterSequenceView getSequence(String experimentId, String centerId, int version) {
        mustFindExperiment(experimentId);
        CenterRow center = centerRepository.find(experimentId, centerId);
        if (center == null) {
            throw ApiException.notFound("中心不存在或未激活: " + centerId);
        }
        if (version < 1) {
            throw ApiException.badRequest("协议版本号必须为正整数");
        }
        long total = centerSequenceRepository.countTotal(experimentId, centerId, version);
        if (total == 0) {
            throw ApiException.notFound("中心在协议版本 " + version + " 无预留序列");
        }
        long consumed = centerSequenceRepository.countConsumed(experimentId, centerId, version);
        return new CenterSequenceView(experimentId, centerId, version, total, consumed,
                total - consumed);
    }

    private void mustFindExperiment(String experimentId) {
        if (experimentRepository.findById(experimentId) == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
    }

    private CenterView toView(CenterRow center, long allocated) {
        return new CenterView(center.experimentId(), center.centerId(), center.targetCap(),
                allocated, center.targetCap() - allocated, center.status(),
                center.currentVersion(), center.createdAt(), center.suspendedAt(),
                center.resumedAt());
    }
}
