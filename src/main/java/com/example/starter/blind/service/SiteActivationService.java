package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.RemainingQuotaView;
import com.example.starter.blind.dto.SiteActivationView;
import com.example.starter.blind.dto.SiteAllocationView;
import com.example.starter.blind.dto.SiteView;
import com.example.starter.blind.dto.SubmitSiteConfigRequest;
import com.example.starter.blind.dto.SubmitSiteConfigRequest.BlockQuotaRequest;
import com.example.starter.blind.repo.SiteActivationRepository;
import com.example.starter.blind.repo.SiteActivationRepository.ActivationRow;
import com.example.starter.blind.repo.SiteActivationRepository.QuotaRow;
import com.example.starter.blind.repo.SiteActivationRepository.SnapshotRow;
import com.example.starter.blind.repo.SiteAllocationRepository;
import com.example.starter.blind.repo.SiteAllocationRepository.SiteAllocationRow;
import com.example.starter.blind.repo.SiteRepository;
import com.example.starter.blind.repo.SiteRepository.SiteRow;
import com.example.starter.blind.service.BlockQuotaRules.FrozenBlock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 研究中心启用门禁与中心分配业务：
 * <ul>
 *   <li>中心初始 INACTIVE，冻结一切分配；负责人提交配置版本并首先确认；</li>
 *   <li>另一名具有独立确认角色（REVIEWER）且不同于负责人的人员核对区组及名额后二次确认；</li>
 *   <li>两人确认同一配置版本后同事务一次性启用：冻结区组名额快照、中心置 ACTIVE；</li>
 *   <li>已启用中心提交新版本时，任何区组名额低于旧冻结量或已分配量均 409；</li>
 *   <li>分配只读取已启用快照，与启用争用同一中心行锁：启用提交前不可能消耗名额，也不会超出冻结量；</li>
 *   <li>任何失败随事务回滚，不改变中心与区组状态；响应不包含处理代码。</li>
 * </ul>
 */
@Service
public class SiteActivationService {

    private final SiteRepository siteRepository;
    private final SiteActivationRepository activationRepository;
    private final SiteAllocationRepository allocationRepository;
    private final BlindCodeGenerator blindCodeGenerator;
    private final Clock clock;

    public SiteActivationService(SiteRepository siteRepository,
                                 SiteActivationRepository activationRepository,
                                 SiteAllocationRepository allocationRepository,
                                 BlindCodeGenerator blindCodeGenerator,
                                 Clock clock) {
        this.siteRepository = siteRepository;
        this.activationRepository = activationRepository;
        this.allocationRepository = allocationRepository;
        this.blindCodeGenerator = blindCodeGenerator;
        this.clock = clock;
    }

    /** 创建研究中心（COORDINATOR 管理操作），初始 INACTIVE。 */
    @Transactional
    public SiteView createSite(String siteId, String leadActorId) {
        if (leadActorId == null || leadActorId.isBlank()) {
            throw ApiException.badRequest("leadActorId 不能为空");
        }
        if (siteRepository.findById(siteId) != null) {
            throw ApiException.conflict("研究中心已存在: " + siteId);
        }
        long now = clock.nowMillis();
        String lead = leadActorId.trim();
        siteRepository.insert(new SiteRow(siteId, lead, "INACTIVE", null, now, null));
        return new SiteView(siteId, lead, "INACTIVE", null, now, null);
    }

    public SiteView getSite(String siteId) {
        return toSiteView(mustFindSite(siteId));
    }

    /**
     * 中心负责人提交新配置版本：生成递增版本号与待确认申请，写入版本名额。
     * 不改变中心当前启用状态；已启用中心的新版本缩容低于旧冻结量或已分配量时 409。
     */
    @Transactional
    public SiteActivationView submitConfig(String siteId, SubmitSiteConfigRequest request,
                                           String leadActorId, String requestId) {
        List<BlockQuotaRequest> blocks = request.blocks();
        BlockQuotaRules.validateContiguous(blocks);

        // 中心行锁：串行化版本号分配、缩容校验与启用/分配。
        SiteRow site = siteRepository.lockById(siteId);
        if (site == null) {
            throw ApiException.notFound("研究中心不存在: " + siteId);
        }
        if (!site.leadActorId().equals(leadActorId)) {
            throw ApiException.forbidden("仅中心负责人可提交启用配置");
        }
        ActivationRow latest = activationRepository.findLatestBySite(siteId);
        if (latest != null && "PENDING".equals(latest.status())) {
            // 上一版本尚未完成双人确认，不允许挂出新配置，保证两人确认的是同一版本。
            throw ApiException.conflict("存在待双人确认的配置版本，请先完成或放弃该版本");
        }
        int version = latest == null ? 1 : latest.version() + 1;

        Map<Integer, Integer> newQuotaByBlock = new HashMap<>();
        for (BlockQuotaRequest block : blocks) {
            newQuotaByBlock.put(block.blockNo(), block.quota());
        }
        if ("ACTIVE".equals(site.status()) && site.activeVersion() != null) {
            // 缩容保护：新版本每个旧区组名额不得低于旧冻结量，也不得低于已分配量。
            List<SnapshotRow> frozen =
                    activationRepository.listSnapshots(siteId, site.activeVersion());
            for (SnapshotRow snapshot : frozen) {
                Integer newQuota = newQuotaByBlock.get(snapshot.blockNo());
                int used = allocationRepository.countByBlock(
                        siteId, site.activeVersion(), snapshot.blockNo());
                if (newQuota == null || newQuota < snapshot.frozenQuota()) {
                    throw ApiException.conflict(
                            "新区组名额不得低于当前已冻结量，不能缩减启用名额");
                }
                if (newQuota < used) {
                    throw ApiException.conflict(
                            "新区组名额不得低于当前已分配量，不能缩减启用名额");
                }
            }
        }

        long now = clock.nowMillis();
        activationRepository.insertActivation(new ActivationRow(
                requestId, siteId, version, "PENDING", leadActorId,
                false, null, null, false, null, now, null));
        for (BlockQuotaRequest block : blocks) {
            activationRepository.insertQuota(
                    new QuotaRow(requestId, siteId, version, block.blockNo(), block.quota()));
        }
        return buildView(new ActivationRow(
                requestId, siteId, version, "PENDING", leadActorId,
                false, null, null, false, null, now, null));
    }

    /**
     * 负责人确认本版本；重复确认返回当前版本视图。
     * 若独立复核人已先确认，则本次确认即为第二人确认，同事务一次性启用。
     */
    @Transactional
    public SiteActivationView confirmByLead(String activationRequestId, String leadActorId) {
        ActivationRow row = activationRepository.lockById(activationRequestId);
        if (row == null) {
            throw ApiException.notFound("启用申请不存在: " + activationRequestId);
        }
        if (!row.leadActorId().equals(leadActorId)) {
            throw ApiException.forbidden("仅该中心负责人可进行负责人确认");
        }
        if ("ACTIVATED".equals(row.status())) {
            // 重复确认：历史确认结果稳定，直接回放当前版本视图。
            return buildView(row);
        }
        SiteRow site = siteRepository.lockById(row.siteId());
        if (site == null) {
            throw new IllegalStateException("启用申请存在但中心缺失，数据不一致");
        }
        if (!row.leadConfirmed()) {
            activationRepository.markLeadConfirmed(activationRequestId, clock.nowMillis());
        }
        ActivationRow refreshed = activationRepository.lockById(activationRequestId);
        if (refreshed.reviewerConfirmed()) {
            return activate(refreshed, site);
        }
        return buildView(refreshed);
    }

    /**
     * 独立复核人（REVIEWER 且不同于负责人）核对区组及名额后确认同一版本。
     * 复核可早于负责人：先记录复核确认，返回待确认视图；负责人之后确认即触发启用。
     * 若负责人已先确认，则本次确认为第二人确认，同事务一次性启用。
     */
    @Transactional
    public SiteActivationView confirmByReviewer(String activationRequestId, String reviewerActorId) {
        // 加锁顺序固定为“申请行 → 中心行”；分配只持有中心行锁，工程内不存在反向持锁，避免死锁。
        ActivationRow row = activationRepository.lockById(activationRequestId);
        if (row == null) {
            throw ApiException.notFound("启用申请不存在: " + activationRequestId);
        }
        if (row.leadActorId().equals(reviewerActorId)) {
            throw ApiException.forbidden("复核人必须是不同于中心负责人的另一名独立确认人员");
        }
        if ("ACTIVATED".equals(row.status())) {
            if (!reviewerActorId.equals(row.reviewerActorId())) {
                throw ApiException.conflict("该版本已由其他复核人确认并启用");
            }
            // 同一复核人重复确认：回放当前版本视图。
            return buildView(row);
        }
        SiteRow site = siteRepository.lockById(row.siteId());
        if (site == null) {
            throw new IllegalStateException("启用申请存在但中心缺失，数据不一致");
        }
        if (row.reviewerConfirmed() && !reviewerActorId.equals(row.reviewerActorId())) {
            throw ApiException.conflict("该版本已由其他复核人确认，请使用原确认请求或新版本");
        }
        if (!row.reviewerConfirmed()) {
            int marked = activationRepository.markReviewerConfirmed(
                    activationRequestId, reviewerActorId, clock.nowMillis());
            if (marked == 0) {
                throw ApiException.conflict("复核确认冲突，请重试");
            }
        }
        ActivationRow refreshed = activationRepository.lockById(activationRequestId);
        if (refreshed.leadConfirmed()) {
            return activate(refreshed, site);
        }
        // 负责人尚未确认：复核已留痕，等待负责人确认，中心仍 INACTIVE/原版本不变。
        return buildView(refreshed);
    }

    /**
     * 双人确认齐备后的一次性启用：申请置 ACTIVATED、冻结区组名额快照、中心切换到新版本。
     * 任一步骤未达预期即抛错回滚，绝不留下“申请已启用但中心/快照未更新”的半成品。
     */
    private SiteActivationView activate(ActivationRow row, SiteRow site) {
        long now = clock.nowMillis();
        int activated = activationRepository.markActivated(row.id(), now);
        if (activated == 0) {
            throw ApiException.conflict("配置版本启用条件不满足，请重试");
        }
        List<QuotaRow> quotas = activationRepository.listQuotas(row.id());
        for (QuotaRow quota : quotas) {
            activationRepository.insertSnapshot(new SnapshotRow(
                    row.siteId(), row.version(), quota.blockNo(), quota.quota(), now));
        }
        int siteChanged;
        if ("INACTIVE".equals(site.status())) {
            siteChanged = siteRepository.markActivated(row.siteId(), row.version(), now);
        } else {
            siteChanged = siteRepository.switchActiveVersion(row.siteId(), row.version(), now);
        }
        if (siteChanged == 0) {
            throw ApiException.conflict("中心状态已变化，启用失败");
        }
        return buildView(activationRepository.lockById(row.id()));
    }

    /** 负责人查看版本与名额（含配额/冻结/已用/剩余）；仅负责人本人。 */
    public SiteActivationView getActivationForLead(String activationRequestId, String actorId) {
        ActivationRow row = activationRepository.findById(activationRequestId);
        if (row == null) {
            throw ApiException.notFound("启用申请不存在: " + activationRequestId);
        }
        if (!row.leadActorId().equals(actorId)) {
            throw ApiException.forbidden("仅中心负责人可查看启用版本与名额明细");
        }
        return buildView(row);
    }

    /** 分配方只看当前已启用快照的各区组剩余额度；未启用中心无快照可看，409。 */
    public RemainingQuotaView getRemainingQuota(String siteId) {
        SiteRow site = mustFindSite(siteId);
        if (!"ACTIVE".equals(site.status()) || site.activeVersion() == null) {
            throw ApiException.conflict("中心尚未启用，暂无可分配额度");
        }
        List<SnapshotRow> snapshots =
                activationRepository.listSnapshots(siteId, site.activeVersion());
        List<RemainingQuotaView.BlockRemaining> blocks = new ArrayList<>(snapshots.size());
        for (SnapshotRow snapshot : snapshots) {
            int used = allocationRepository.countByBlock(
                    siteId, site.activeVersion(), snapshot.blockNo());
            blocks.add(new RemainingQuotaView.BlockRemaining(
                    snapshot.blockNo(), Math.max(0, snapshot.frozenQuota() - used)));
        }
        return new RemainingQuotaView(siteId, blocks);
    }

    /**
     * 中心分配：中心必须已启用；按区组顺序在第一个仍有剩余的区组消耗一个冻结名额。
     * 与启用争用同一中心行锁：启用事务提交前的分配只见 INACTIVE，不消耗任何名额。
     * 响应只含盲码与区组号，不暴露处理组。
     */
    @Transactional
    public SiteAllocationView allocate(String siteId, String participantId, String actorId) {
        if (participantId == null || participantId.isBlank()) {
            throw ApiException.badRequest("participantId 不能为空");
        }
        SiteRow site = siteRepository.lockById(siteId);
        if (site == null) {
            throw ApiException.notFound("研究中心不存在: " + siteId);
        }
        if (!"ACTIVE".equals(site.status()) || site.activeVersion() == null) {
            throw ApiException.conflict("中心尚未启用，拒绝分配");
        }
        if (allocationRepository.findBySiteAndParticipant(siteId, participantId) != null) {
            throw ApiException.conflict("参与者已在该中心分配，仅可占一席");
        }
        int version = site.activeVersion();
        List<SnapshotRow> snapshots = activationRepository.listSnapshots(siteId, version);
        List<FrozenBlock> frozenBlocks = snapshots.stream()
                .map(s -> new FrozenBlock(s.blockNo(), s.frozenQuota()))
                .toList();
        int blockNo = BlockQuotaRules.selectFirstAvailable(frozenBlocks,
                b -> allocationRepository.countByBlock(siteId, version, b));
        if (blockNo < 0) {
            throw ApiException.conflict("各区组冻结名额已用尽，拒绝分配");
        }
        long now = clock.nowMillis();
        SiteAllocationRow inserted = insertWithUniqueBlindCode(
                siteId, version, participantId, blockNo, actorId, now);
        return new SiteAllocationView(inserted.id(), siteId, participantId,
                inserted.blindCode(), blockNo, now);
    }

    private SiteAllocationRow insertWithUniqueBlindCode(String siteId, int version,
                                                        String participantId, int blockNo,
                                                        String actorId, long now) {
        // 盲码随机冲突概率极低，仍由唯一索引兜底并重试；不涉及任何处理代码。
        for (int attempt = 0; attempt < 5; attempt++) {
            String blindCode = blindCodeGenerator.nextCode();
            SiteAllocationRow row = new SiteAllocationRow(0L, siteId, version, participantId,
                    blockNo, blindCode, actorId, now);
            try {
                allocationRepository.insert(row);
                return allocationRepository.findBySiteAndParticipant(siteId, participantId);
            } catch (DuplicateKeyException e) {
                if (allocationRepository.isDuplicateBlindCode(e)) {
                    continue;
                }
                if (allocationRepository.isDuplicateParticipant(e)) {
                    throw ApiException.conflict("参与者已在该中心分配，仅可占一席");
                }
                throw e;
            }
        }
        throw ApiException.conflict("盲码生成冲突，请重试");
    }

    private SiteRow mustFindSite(String siteId) {
        SiteRow site = siteRepository.findById(siteId);
        if (site == null) {
            throw ApiException.notFound("研究中心不存在: " + siteId);
        }
        return site;
    }

    private SiteView toSiteView(SiteRow row) {
        return new SiteView(row.id(), row.leadActorId(), row.status(),
                row.activeVersion(), row.createdAt(), row.activatedAt());
    }

    /**
     * 组装负责人版本视图：已启用版本展示冻结量/已用量/剩余量；待确认版本冻结量与用量均为 0。
     */
    private SiteActivationView buildView(ActivationRow row) {
        List<QuotaRow> quotas = activationRepository.listQuotas(row.id());
        boolean activated = "ACTIVATED".equals(row.status());
        List<SnapshotRow> snapshots = activated
                ? activationRepository.listSnapshots(row.siteId(), row.version())
                : List.of();
        Map<Integer, Integer> frozenByBlock = new HashMap<>();
        for (SnapshotRow snapshot : snapshots) {
            frozenByBlock.put(snapshot.blockNo(), snapshot.frozenQuota());
        }
        List<SiteActivationView.BlockQuotaView> blocks = new ArrayList<>(quotas.size());
        for (QuotaRow quota : quotas) {
            int frozen = activated ? frozenByBlock.getOrDefault(quota.blockNo(), 0) : 0;
            int used = activated
                    ? allocationRepository.countByBlock(row.siteId(), row.version(), quota.blockNo())
                    : 0;
            int remaining = activated ? Math.max(0, frozen - used) : 0;
            blocks.add(new SiteActivationView.BlockQuotaView(
                    quota.blockNo(), quota.quota(), frozen, used, remaining));
        }
        return new SiteActivationView(row.id(), row.siteId(), row.version(), row.status(),
                row.leadActorId(), row.leadConfirmed(), row.leadConfirmedAt(),
                row.reviewerActorId(), row.reviewerConfirmed(), row.reviewerConfirmedAt(),
                row.createdAt(), row.activatedAt(), blocks);
    }
}
