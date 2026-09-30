package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.ActivationView;
import com.example.starter.blind.dto.BlockQuotaView;
import com.example.starter.blind.dto.BlockRemainingView;
import com.example.starter.blind.dto.RemainingQuotaView;
import com.example.starter.blind.dto.SiteAllocationView;
import com.example.starter.blind.dto.SiteBlockConfigRequest;
import com.example.starter.blind.dto.SiteConfigView;
import com.example.starter.blind.dto.SiteView;
import com.example.starter.blind.repo.ActivationRepository;
import com.example.starter.blind.repo.ActivationRepository.ActivationBlockRow;
import com.example.starter.blind.repo.ActivationRepository.ActivationRow;
import com.example.starter.blind.repo.ActivationRepository.ConfirmationRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.SiteAllocationRepository;
import com.example.starter.blind.repo.SiteAllocationRepository.SiteAllocationRow;
import com.example.starter.blind.repo.SiteRepository;
import com.example.starter.blind.repo.SiteRepository.BlockRow;
import com.example.starter.blind.repo.SiteRepository.SiteBlockRow;
import com.example.starter.blind.repo.SiteRepository.SiteRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 研究中心启用门禁与名额分配业务：
 * <ul>
 *   <li>启用前配置版本仅为草稿，中心 INACTIVE，冻结名额未生效，禁止分配；</li>
 *   <li>中心负责人 LEAD 提交启用申请（即 LEAD 确认），独立 CONFIRMER 核对区组及名额，
 *       两人确认同一配置版本后一次性启用，并冻结各区组名额；</li>
 *   <li>分配方 COORDINATOR 只读取已启用快照，只能看到剩余额度；未启用中心不得分配；</li>
 *   <li>启用后缩减名额不得低于启用冻结量或已分配量，否则 409 且中心、区组状态不变；</li>
 *   <li>本服务不读写 seat/treatment，任何对外视图都不含处理代码或席位号。</li>
 * </ul>
 */
@Service
public class SiteService {

    /** 启用确认角色标记，与 Role 枚举的角色名称独立，避免与既有 REVIEWER 混淆。 */
    static final String CONFIRM_ROLE_LEAD = "LEAD";
    static final String CONFIRM_ROLE_REVIEWER = "REVIEWER";

    private final SiteRepository siteRepository;
    private final ActivationRepository activationRepository;
    private final SiteAllocationRepository siteAllocationRepository;
    private final ExperimentRepository experimentRepository;
    private final Clock clock;

    public SiteService(SiteRepository siteRepository,
                       ActivationRepository activationRepository,
                       SiteAllocationRepository siteAllocationRepository,
                       ExperimentRepository experimentRepository,
                       Clock clock) {
        this.siteRepository = siteRepository;
        this.activationRepository = activationRepository;
        this.siteAllocationRepository = siteAllocationRepository;
        this.experimentRepository = experimentRepository;
        this.clock = clock;
    }

    /**
     * 提交配置版本：首个版本同时创建 INACTIVE 中心；版本号中心内自增、内容不可变。
     * 中心已启用后，新版本只能在启用快照的区组范围内调整名额，
     * 且每个区组新名额不得低于启用冻结量与已分配量，否则整体失败不改变任何状态。
     */
    @Transactional
    public SiteConfigView submitConfig(String experimentId, String siteId,
                                       List<SiteBlockConfigRequest> blocks, String leadActor) {
        mustFindExperiment(experimentId);
        SiteQuotaPolicy.validateConfigBlocks(blocks);
        long now = clock.nowMillis();

        SiteRow site = siteRepository.lockSite(experimentId, siteId);
        boolean active = site != null && "ACTIVE".equals(site.status());

        // 启用后调整：逐区组校验冻结量与已分配量，任一不满足整体回滚。
        Map<String, SiteBlockRow> activeBlocks = new LinkedHashMap<>();
        if (active) {
            List<SiteBlockRow> snapshot = siteRepository.findSiteBlocks(experimentId, siteId);
            for (SiteBlockRow row : snapshot) {
                activeBlocks.put(row.blockCode(), row);
            }
            if (activeBlocks.size() != blocks.size()) {
                throw ApiException.badRequest("启用后仅可在启用快照的区组范围内调整名额，不得增删区组");
            }
            for (SiteBlockConfigRequest request : blocks) {
                SiteBlockRow current = activeBlocks.get(request.blockCode());
                if (current == null) {
                    throw ApiException.badRequest("启用后仅可在启用快照的区组范围内调整名额，不得增删区组");
                }
                int allocated = siteAllocationRepository.countByBlock(
                        experimentId, siteId, request.blockCode());
                if (!SiteQuotaPolicy.canReduceTo(request.quota(), current.frozenQuota(), allocated)) {
                    throw ApiException.conflict(
                            "调整后名额低于启用冻结量或已分配量，拒绝调整");
                }
            }
        } else if (site == null) {
            siteRepository.insertSite(new SiteRow(experimentId, siteId, "INACTIVE",
                    null, now, null));
        }

        Integer maxVersion = siteRepository.findMaxConfigVersion(experimentId, siteId);
        int version = maxVersion == null ? 1 : maxVersion + 1;
        siteRepository.insertConfigVersion(new com.example.starter.blind.repo.SiteRepository
                .ConfigVersionRow(experimentId, siteId, version, leadActor, now));
        List<BlockRow> blockRows = new ArrayList<>(blocks.size());
        for (SiteBlockConfigRequest request : blocks) {
            blockRows.add(new BlockRow(experimentId, siteId, version,
                    request.blockCode(), request.quota()));
        }
        blockRows.forEach(siteRepository::insertConfigBlock);

        if (active) {
            // 已在上面完成全部校验，这里逐区组生效；SQL 再以冻结量兜底。
            for (SiteBlockConfigRequest request : blocks) {
                int updated = siteRepository.updateQuota(
                        experimentId, siteId, request.blockCode(), request.quota());
                if (updated != 1) {
                    throw ApiException.conflict("调整后名额低于启用冻结量，拒绝调整");
                }
            }
        }
        return new SiteConfigView(experimentId, siteId, version, toQuotaViews(blockRows), now);
    }

    /**
     * 中心负责人提交启用申请：申请即 LEAD 确认，同时冻结所引用配置版本的区组名额快照。
     */
    @Transactional
    public ActivationView applyActivation(String experimentId, String siteId,
                                          int configVersion, String leadActor) {
        mustFindExperiment(experimentId);
        SiteRow site = siteRepository.lockSite(experimentId, siteId);
        if (site == null) {
            throw ApiException.notFound("研究中心不存在: " + siteId);
        }
        if ("ACTIVE".equals(site.status())) {
            throw ApiException.conflict("研究中心已启用，不可重复提交启用申请");
        }
        ActivationRow existing = activationRepository.lockBySite(experimentId, siteId);
        if (existing != null) {
            throw ApiException.conflict("研究中心已存在待复核启用申请");
        }
        if (siteRepository.findConfigVersion(experimentId, siteId, configVersion) == null) {
            throw ApiException.notFound("配置版本不存在: " + configVersion);
        }
        List<BlockRow> configBlocks =
                siteRepository.findConfigBlocks(experimentId, siteId, configVersion);

        String activationId = "SA-" + UUID.randomUUID().toString().replace("-", "");
        long now = clock.nowMillis();
        try {
            activationRepository.insertPending(new ActivationRow(
                    activationId, experimentId, siteId, configVersion, leadActor,
                    null, "PENDING_CONFIRMATION", now, null));
        } catch (DuplicateKeyException e) {
            // 并发重复申请：中心唯一约束兜底，事务回滚不改变中心状态。
            if (activationRepository.isDuplicateSite(e)) {
                throw ApiException.conflict("研究中心已存在启用申请");
            }
            throw e;
        }
        List<ActivationBlockRow> frozen = new ArrayList<>(configBlocks.size());
        for (BlockRow block : configBlocks) {
            frozen.add(new ActivationBlockRow(activationId, block.blockCode(), block.quota()));
        }
        activationRepository.insertActivationBlocks(frozen);
        activationRepository.insertConfirmation(new ConfirmationRow(
                0L, activationId, leadActor, CONFIRM_ROLE_LEAD, configVersion, now));
        return toActivationView(new ActivationRow(
                activationId, experimentId, siteId, configVersion, leadActor,
                null, "PENDING_CONFIRMATION", now, null), frozen);
    }

    /**
     * 独立 CONFIRMER 核对区组及名额后确认：必须确认与申请相同的配置版本、且不能是申请人本人；
     * 两人确认同版本后一次性启用中心并写入启用快照。任何失败不改变中心与区组状态。
     */
    @Transactional
    public ActivationView confirmActivation(String activationId, int configVersion,
                                            String confirmerActor) {
        ActivationRow activation = activationRepository.lockById(activationId);
        if (activation == null) {
            throw ApiException.notFound("启用申请不存在: " + activationId);
        }
        if ("ACTIVE".equals(activation.status())) {
            throw ApiException.conflict("启用申请已确认，中心已启用");
        }
        if (activation.leadActor().equals(confirmerActor)) {
            throw ApiException.forbidden("复核确认人必须是不同于申请人的另一名 CONFIRMER");
        }
        if (activation.configVersion() != configVersion) {
            // 版本不一致：仅拒绝，不写入复核记录，不改中心状态。
            throw ApiException.conflict("确认的配置版本与启用申请不一致");
        }
        List<ActivationBlockRow> frozen =
                activationRepository.findActivationBlocks(activationId);
        long now = clock.nowMillis();
        activationRepository.insertConfirmation(new ConfirmationRow(
                0L, activationId, confirmerActor, CONFIRM_ROLE_REVIEWER, configVersion, now));

        // 先锁中心再更新，保证与并发分配互斥：未提交的启用不会让分配读到快照。
        SiteRow site = siteRepository.lockSite(
                activation.experimentId(), activation.siteId());
        if (site == null || "ACTIVE".equals(site.status())) {
            throw ApiException.conflict("研究中心状态已变化，启用失败");
        }
        int siteUpdated = siteRepository.markActive(
                activation.experimentId(), activation.siteId(), configVersion, now);
        if (siteUpdated != 1) {
            throw ApiException.conflict("研究中心状态已变化，启用失败");
        }
        int activationUpdated =
                activationRepository.markActive(activationId, confirmerActor, now);
        if (activationUpdated != 1) {
            throw ApiException.conflict("启用申请状态已变化，启用失败");
        }
        List<BlockRow> snapshotBlocks = new ArrayList<>(frozen.size());
        for (ActivationBlockRow block : frozen) {
            snapshotBlocks.add(new BlockRow(activation.experimentId(), activation.siteId(),
                    configVersion, block.blockCode(), block.quota()));
        }
        siteRepository.insertSiteBlocks(
                activation.experimentId(), activation.siteId(), snapshotBlocks);
        return toActivationView(new ActivationRow(
                activationId, activation.experimentId(), activation.siteId(), configVersion,
                activation.leadActor(), confirmerActor, "ACTIVE",
                activation.createdAt(), now), frozen);
    }

    /**
     * 中心分配：中心行锁串行化启用与分配；只读取已启用快照，额度不足拒绝。
     * 视图仅含中心、区组编号与顺序号，不泄露任何处理组信息。
     */
    @Transactional
    public SiteAllocationView allocate(String experimentId, String siteId, String blockCode,
                                       String participantId, String actorId) {
        if (participantId == null || participantId.isBlank()) {
            throw ApiException.badRequest("participantId 不能为空");
        }
        // 先锁中心：启用事务提交前，分配拿不到锁，不可能提前消耗名额。
        SiteRow site = siteRepository.lockSite(experimentId, siteId);
        if (site == null) {
            throw ApiException.notFound("研究中心不存在: " + siteId);
        }
        if (!"ACTIVE".equals(site.status())) {
            throw ApiException.conflict("研究中心尚未启用，拒绝分配");
        }
        SiteBlockRow block = siteRepository.lockSiteBlock(experimentId, siteId, blockCode);
        if (block == null) {
            throw ApiException.notFound("中心启用快照中不存在该区组: " + blockCode);
        }
        SiteAllocationRow duplicate =
                siteAllocationRepository.find(experimentId, siteId, participantId);
        if (duplicate != null) {
            throw ApiException.conflict("参与者已在该中心登记，仅可占一个名额");
        }
        int allocated = siteAllocationRepository.countByBlock(experimentId, siteId, blockCode);
        if (!SiteQuotaPolicy.canAllocate(block.quota(), allocated)) {
            throw ApiException.full("该区组剩余额度不足");
        }
        int serialNo = siteAllocationRepository.nextSerialNo(experimentId, siteId, blockCode);
        long now = clock.nowMillis();
        try {
            siteAllocationRepository.insert(new SiteAllocationRow(
                    0L, experimentId, siteId, blockCode, participantId, serialNo, actorId, now));
        } catch (DuplicateKeyException e) {
            if (siteAllocationRepository.isDuplicateParticipant(e)) {
                throw ApiException.conflict("参与者已在该中心登记，仅可占一个名额");
            }
            if (siteAllocationRepository.isDuplicateSerial(e)) {
                // 中心锁已串行化，唯一约束仅作极端兜底。
                throw ApiException.conflict("名额竞争冲突，请重试");
            }
            throw e;
        }
        return new SiteAllocationView(experimentId, siteId, blockCode, participantId,
                serialNo, now);
    }

    /** 中心负责人视图：中心状态、启用版本与冻结/当前名额；未启用时名额列表为空。 */
    public SiteView getSite(String experimentId, String siteId) {
        SiteRow site = mustFindSite(experimentId, siteId);
        List<BlockQuotaView> blocks = new ArrayList<>();
        if ("ACTIVE".equals(site.status())) {
            for (SiteBlockRow row : siteRepository.findSiteBlocks(experimentId, siteId)) {
                blocks.add(new BlockQuotaView(row.blockCode(), row.quota()));
            }
        }
        return new SiteView(experimentId, siteId, site.status(), site.activeVersion(),
                blocks, site.createdAt(), site.activatedAt());
    }

    /** 查看配置版本（负责人申请、确认人核对均使用）；包含版本号与区组名额。 */
    public SiteConfigView getConfig(String experimentId, String siteId, int version) {
        mustFindSite(experimentId, siteId);
        var configVersion = siteRepository.findConfigVersion(experimentId, siteId, version);
        if (configVersion == null) {
            throw ApiException.notFound("配置版本不存在: " + version);
        }
        List<BlockRow> blocks = siteRepository.findConfigBlocks(experimentId, siteId, version);
        return new SiteConfigView(experimentId, siteId, version, toQuotaViews(blocks),
                configVersion.createdAt());
    }

    /** 查看启用申请与其冻结快照（不含处理代码）；负责人与确认人可查。 */
    public ActivationView getActivation(String activationId) {
        ActivationRow activation = activationRepository.findById(activationId);
        if (activation == null) {
            throw ApiException.notFound("启用申请不存在: " + activationId);
        }
        return toActivationView(activation,
                activationRepository.findActivationBlocks(activationId));
    }

    /**
     * 分配方视图：只看各区组剩余额度；看不到配置版本、冻结总量，也看不到任何处理映射。
     */
    public RemainingQuotaView getRemainingQuota(String experimentId, String siteId) {
        SiteRow site = mustFindSite(experimentId, siteId);
        if (!"ACTIVE".equals(site.status())) {
            throw ApiException.conflict("研究中心尚未启用，暂无剩余额度");
        }
        List<BlockRemainingView> blocks = new ArrayList<>();
        for (SiteBlockRow row : siteRepository.findSiteBlocks(experimentId, siteId)) {
            int allocated =
                    siteAllocationRepository.countByBlock(experimentId, siteId, row.blockCode());
            blocks.add(new BlockRemainingView(row.blockCode(),
                    SiteQuotaPolicy.remaining(row.quota(), allocated)));
        }
        return new RemainingQuotaView(experimentId, siteId, blocks);
    }

    // ---------------- 内部辅助 ----------------

    private SiteRow mustFindSite(String experimentId, String siteId) {
        SiteRow site = siteRepository.findSite(experimentId, siteId);
        if (site == null) {
            throw ApiException.notFound("研究中心不存在: " + siteId);
        }
        return site;
    }

    private void mustFindExperiment(String experimentId) {
        ExperimentRow experiment = experimentRepository.findById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
    }

    private List<BlockQuotaView> toQuotaViews(List<BlockRow> rows) {
        return rows.stream().map(r -> new BlockQuotaView(r.blockCode(), r.quota())).toList();
    }

    private ActivationView toActivationView(ActivationRow row, List<ActivationBlockRow> blocks) {
        List<BlockQuotaView> views = blocks.stream()
                .map(b -> new BlockQuotaView(b.blockCode(), b.quota()))
                .toList();
        return new ActivationView(row.activationId(), row.experimentId(), row.siteId(),
                row.configVersion(), row.status(), row.leadActor(), row.reviewerActor(),
                views, row.createdAt(), row.confirmedAt());
    }
}
