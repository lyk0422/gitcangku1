package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.ActivationConfirmView;
import com.example.starter.blind.dto.SiteActivationView;
import com.example.starter.blind.dto.SiteView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.SiteActivationRepository;
import com.example.starter.blind.repo.SiteActivationRepository.SiteActivationRow;
import com.example.starter.blind.repo.SiteRepository;
import com.example.starter.blind.repo.SiteRepository.SiteRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 试验中心激活门禁业务：
 * <ul>
 *   <li>中心创建后为 PENDING，须两名不同未盲法管理人员（COORDINATOR）提交同一
 *       activationKey 双人确认方可激活；第二人确认时校验中心未关闭且上限大于零；</li>
 *   <li>激活成功在同一事务内写入不可变双人激活记录并使中心 ACTIVE，代次 +1；</li>
 *   <li>暂停（SUSPENDED）后不接受新分配，既有受试者盲态、区组容量与揭盲权限不变；
 *       恢复须再次双人确认并产生新激活代次；</li>
 *   <li>关闭前必须不存在待处理揭盲申请，否则 422；关闭为终态，不可恢复。</li>
 * </ul>
 */
@Service
public class SiteService {

    private final SiteRepository siteRepository;
    private final SiteActivationRepository siteActivationRepository;
    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final Clock clock;

    public SiteService(SiteRepository siteRepository,
                       SiteActivationRepository siteActivationRepository,
                       ExperimentRepository experimentRepository,
                       AllocationRepository allocationRepository,
                       UnblindRequestRepository unblindRequestRepository,
                       Clock clock) {
        this.siteRepository = siteRepository;
        this.siteActivationRepository = siteActivationRepository;
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.clock = clock;
    }

    /**
     * 创建中心：初始 PENDING（未激活）、代次 0；targetCap 允许为 0，激活时再校验大于零。
     */
    @Transactional
    public SiteView createSite(String experimentId, String siteCode, int targetCap) {
        if (targetCap < 0) {
            throw ApiException.badRequest("targetCap 不能为负数");
        }
        mustFindExperiment(experimentId);
        if (siteRepository.findById(experimentId, siteCode) != null) {
            throw ApiException.conflict("中心已存在: " + siteCode);
        }
        long now = clock.nowMillis();
        try {
            siteRepository.insert(new SiteRow(experimentId, siteCode, "PENDING", targetCap, 0,
                    null, null, null, now, null));
        } catch (DuplicateKeyException e) {
            // 并发创建同中心：主键兜底。
            throw ApiException.conflict("中心已存在: " + siteCode);
        }
        return getSite(experimentId, siteCode);
    }

    /**
     * 双人确认激活：第一人记录待确认，第二人（不同操作者、同一 activationKey）确认时
     * 校验中心未关闭且上限大于零，同事务写入不可变激活记录并置 ACTIVE、代次 +1。
     * 同操作者同键重放首次确认响应；已激活时同键同操作者重放激活结果。
     */
    @Transactional
    public ActivationConfirmView confirmActivation(String experimentId, String siteCode,
                                                   String activationKey, String actorId) {
        if (activationKey == null || activationKey.isBlank()) {
            throw ApiException.badRequest("activationKey 不能为空");
        }
        mustFindExperiment(experimentId);
        SiteRow site = siteRepository.lockById(experimentId, siteCode);
        if (site == null) {
            throw ApiException.notFound("中心不存在: " + siteCode);
        }
        if ("CLOSED".equals(site.status())) {
            throw ApiException.conflict("中心已关闭，不可恢复");
        }
        if ("ACTIVE".equals(site.status())) {
            // 业务键重放：最新一代激活记录同键且操作者为两名确认人之一 → 重放首次响应。
            SiteActivationRow latest =
                    siteActivationRepository.findLatest(experimentId, siteCode);
            if (latest != null && latest.activationKey().equals(activationKey)
                    && (latest.firstActor().equals(actorId) || latest.secondActor().equals(actorId))) {
                return new ActivationConfirmView(experimentId, siteCode, "ACTIVE",
                        latest.generation(), activationKey, latest.firstActor(),
                        latest.firstConfirmedAt(), latest.secondActor(),
                        latest.secondConfirmedAt(), true);
            }
            throw ApiException.conflict("中心已激活，重复确认被拒绝");
        }
        long now = clock.nowMillis();
        if (site.pendingActor() == null) {
            int recorded = siteRepository.recordFirstConfirmation(
                    experimentId, siteCode, activationKey, actorId, now);
            if (recorded == 0) {
                throw ApiException.conflict("中心确认状态并发变化，请重试");
            }
            return new ActivationConfirmView(experimentId, siteCode, site.status(),
                    site.generation(), activationKey, actorId, now, null, null, false);
        }
        if (site.pendingActor().equals(actorId)) {
            if (site.pendingActivationKey().equals(activationKey)) {
                // 同操作者同键：重放首次确认响应，不产生第二确认。
                return new ActivationConfirmView(experimentId, siteCode, site.status(),
                        site.generation(), activationKey, site.pendingActor(),
                        site.pendingConfirmedAt(), null, null, false);
            }
            throw ApiException.conflict("须由两名不同的未盲法管理人员确认同一 activationKey");
        }
        if (!site.pendingActivationKey().equals(activationKey)) {
            throw ApiException.conflict("activationKey 与首次确认不一致");
        }
        // 第二人确认：中心尚未关闭（前面已校验）且上限大于零。
        if (site.targetCap() <= 0) {
            throw ApiException.conflict("目标入组上限必须大于零，禁止激活");
        }
        int newGeneration = site.generation() + 1;
        siteActivationRepository.insert(new SiteActivationRow(0L, experimentId, siteCode,
                newGeneration, activationKey, site.pendingActor(), site.pendingConfirmedAt(),
                actorId, now, site.targetCap()));
        int activated = siteRepository.markActive(experimentId, siteCode, newGeneration);
        if (activated == 0) {
            throw ApiException.conflict("中心状态已变化，请重试");
        }
        return new ActivationConfirmView(experimentId, siteCode, "ACTIVE", newGeneration,
                activationKey, site.pendingActor(), site.pendingConfirmedAt(),
                actorId, now, true);
    }

    /**
     * 暂停中心：仅 ACTIVE 可暂停；既有受试者盲态、区组容量与揭盲权限不变。
     */
    @Transactional
    public SiteView suspend(String experimentId, String siteCode) {
        mustFindExperiment(experimentId);
        SiteRow site = lockSite(experimentId, siteCode);
        if (!"ACTIVE".equals(site.status())) {
            throw ApiException.conflict("仅 ACTIVE 中心可暂停");
        }
        siteRepository.markSuspended(experimentId, siteCode);
        return getSite(experimentId, siteCode);
    }

    /**
     * 关闭中心：必须不存在待处理揭盲申请，否则 422；关闭为终态，不可恢复。
     */
    @Transactional
    public SiteView closeSite(String experimentId, String siteCode) {
        mustFindExperiment(experimentId);
        SiteRow site = lockSite(experimentId, siteCode);
        if ("CLOSED".equals(site.status())) {
            throw ApiException.conflict("中心已关闭");
        }
        long pending = unblindRequestRepository.countPendingBySite(experimentId, siteCode);
        if (pending > 0) {
            throw ApiException.unprocessable("中心存在待处理揭盲申请，禁止关闭");
        }
        siteRepository.markClosed(experimentId, siteCode, clock.nowMillis());
        return getSite(experimentId, siteCode);
    }

    /**
     * 查询中心：代次、双人激活记录、累计分配数（含已退组）与状态门禁原因。
     */
    public SiteView getSite(String experimentId, String siteCode) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        SiteRow site = siteRepository.findById(experimentId, siteCode);
        if (site == null) {
            throw ApiException.notFound("中心不存在: " + siteCode);
        }
        long allocated = allocationRepository.countBySite(experimentId, siteCode);
        List<SiteActivationView> activations = siteActivationRepository
                .findBySite(experimentId, siteCode).stream()
                .map(row -> new SiteActivationView(row.generation(), row.activationKey(),
                        row.firstActor(), row.firstConfirmedAt(),
                        row.secondActor(), row.secondConfirmedAt(), row.targetCap()))
                .toList();
        return new SiteView(site.experimentId(), site.siteCode(), site.status(),
                site.generation(), site.targetCap(), allocated,
                gateReason(experiment, site, allocated),
                site.createdAt(), site.closedAt(), activations);
    }

    /**
     * 状态门禁原因：null 表示当前允许新分配。
     */
    private String gateReason(ExperimentRow experiment, SiteRow site, long allocated) {
        if ("CLOSED".equals(experiment.status())) {
            return "EXPERIMENT_CLOSED";
        }
        switch (site.status()) {
            case "PENDING":
                return "SITE_NOT_ACTIVE";
            case "SUSPENDED":
                return "SITE_SUSPENDED";
            case "CLOSED":
                return "SITE_CLOSED";
            default:
                return allocated >= site.targetCap() ? "SITE_CAP_REACHED" : null;
        }
    }

    private SiteRow lockSite(String experimentId, String siteCode) {
        SiteRow site = siteRepository.lockById(experimentId, siteCode);
        if (site == null) {
            throw ApiException.notFound("中心不存在: " + siteCode);
        }
        return site;
    }

    private ExperimentRow mustFindExperiment(String experimentId) {
        ExperimentRow row = experimentRepository.findById(experimentId);
        if (row == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        return row;
    }
}
