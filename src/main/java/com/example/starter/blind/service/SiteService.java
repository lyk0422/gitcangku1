package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.SiteStatus;
import com.example.starter.blind.dto.SiteActivationPendingView;
import com.example.starter.blind.dto.SiteActivationRecordView;
import com.example.starter.blind.dto.SiteView;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.SiteRepository;
import com.example.starter.blind.repo.SiteRepository.ActivationRecordRow;
import com.example.starter.blind.repo.SiteRepository.PendingRow;
import com.example.starter.blind.repo.SiteRepository.SiteRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 试验中心激活门禁业务：
 * <ul>
 *   <li>中心状态机 INACTIVE -&gt; ACTIVE &lt;-&gt; SUSPENDED -&gt; CLOSED（终态）；</li>
 *   <li>激活/恢复须两名不同未盲管理人员以同一 activationKey 先后确认；
 *       第二人确认时校验中心未关闭且目标入组上限大于零；</li>
 *   <li>激活成功在同一事务内写入不可变双人激活记录并将中心置为 ACTIVE（新代次）；</li>
 *   <li>暂停拒绝新分配，但既有盲态、区组容量与揭盲权限不变；</li>
 *   <li>累计分配（含已退组）达到上限后新分配返回 422，退组不回收容量；</li>
 *   <li>关闭前必须不存在待审揭盲申请，否则 422；关闭后不可恢复。</li>
 * </ul>
 * 同中心的激活、暂停、恢复、关闭与分配均先取中心行级锁，按事务提交顺序裁决。
 */
@Service
public class SiteService {

    /** 门禁原因：允许分配。 */
    static final String GATE_ALLOWED = "ALLOWED";

    private final SiteRepository siteRepository;
    private final ExperimentRepository experimentRepository;
    private final Clock clock;

    public SiteService(SiteRepository siteRepository,
                       ExperimentRepository experimentRepository,
                       Clock clock) {
        this.siteRepository = siteRepository;
        this.experimentRepository = experimentRepository;
        this.clock = clock;
    }

    /**
     * 创建中心：初始 INACTIVE、代次 0；同实验中心编号唯一。
     */
    @Transactional
    public SiteView createSite(String experimentId, String siteCode, int targetEnrollmentLimit) {
        if (siteCode == null || siteCode.isBlank()) {
            throw ApiException.badRequest("siteCode 不能为空");
        }
        if (targetEnrollmentLimit < 0) {
            throw ApiException.badRequest("目标入组上限不能为负数");
        }
        mustFindExperiment(experimentId);
        if (siteRepository.find(experimentId, siteCode) != null) {
            throw ApiException.conflict("中心已存在: " + siteCode);
        }
        long now = clock.nowMillis();
        siteRepository.insertSite(new SiteRow(0L, experimentId, siteCode,
                SiteStatus.INACTIVE.name(), targetEnrollmentLimit, 0, now, now));
        return toView(mustFindSite(experimentId, siteCode));
    }

    /**
     * 双人激活/恢复确认。
     * 首确认：暂存操作者、密钥与当时代次，返回 202 暂存视图；
     * 第二人（不同操作者、相同密钥、同一代次）确认时校验中心未关闭且上限大于零，
     * 同一事务写入不可变激活记录、删除暂存并将中心置为 ACTIVE（代次加 1）。
     */
    @Transactional
    public Object confirmActivation(String experimentId, String siteCode,
                                    String activationKey, String actorId) {
        if (activationKey == null || activationKey.isBlank()) {
            throw ApiException.badRequest("activationKey 不能为空");
        }
        if (activationKey.length() > 128) {
            throw ApiException.badRequest("activationKey 长度不能超过 128 个字符");
        }
        mustFindExperiment(experimentId);
        SiteRow site = siteRepository.lock(experimentId, siteCode);
        if (site == null) {
            throw ApiException.notFound("中心不存在: " + siteCode);
        }
        if (SiteStatus.CLOSED.name().equals(site.status())) {
            throw ApiException.conflict("中心已关闭，不可激活或恢复");
        }
        if (SiteStatus.ACTIVE.name().equals(site.status())) {
            throw ApiException.conflict("中心已处于激活状态");
        }
        long now = clock.nowMillis();
        PendingRow pending = siteRepository.findPending(experimentId, siteCode);
        if (pending == null) {
            // 首确认：仅暂存，不改变中心状态；同中心并发由行锁串行，主键兜底。
            siteRepository.insertPending(new PendingRow(experimentId, siteCode,
                    site.generation(), activationKey, actorId, now));
            return new SiteActivationPendingView("AWAITING_SECOND_CONFIRMATION",
                    experimentId, siteCode, site.generation(), actorId, now);
        }
        if (pending.firstConfirmerActor().equals(actorId)) {
            // 同一首确认人：同键重放原暂存响应，异键冲突。
            if (!pending.activationKey().equals(activationKey)) {
                throw ApiException.conflict("同一人员已提交不同 activationKey 的首确认");
            }
            return new SiteActivationPendingView("AWAITING_SECOND_CONFIRMATION",
                    experimentId, siteCode, pending.generation(), actorId, pending.createdAt());
        }
        // 第二人确认：必须相同密钥、同一代次。
        if (!pending.activationKey().equals(activationKey)) {
            throw ApiException.conflict("两次确认的 activationKey 不一致");
        }
        if (pending.generation() != site.generation()) {
            // 首确认后中心代次已变化（如被并发恢复），本次确认按提交顺序作废。
            throw ApiException.conflict("中心代次已变化，请重新进行双人确认");
        }
        // 第二人确认时的强制校验：中心未关闭（上方已校验）且上限大于零。
        if (site.targetEnrollmentLimit() <= 0) {
            throw ApiException.full("目标入组上限必须大于 0 才能激活中心");
        }
        int newGeneration = site.generation() + 1;
        int updated = siteRepository.activate(experimentId, siteCode, newGeneration, now);
        if (updated == 0) {
            throw ApiException.conflict("中心状态已变化，请重新进行双人确认");
        }
        siteRepository.insertActivationRecord(new ActivationRecordRow(0L, experimentId, siteCode,
                newGeneration, activationKey, pending.firstConfirmerActor(), actorId,
                site.targetEnrollmentLimit(), now));
        siteRepository.deletePending(experimentId, siteCode);
        return new SiteActivationRecordView(experimentId, siteCode, newGeneration,
                pending.firstConfirmerActor(), actorId, site.targetEnrollmentLimit(), now);
    }

    /**
     * 暂停中心：仅 ACTIVE 可暂停；既有受试者盲态、区组容量与揭盲权限不变。
     */
    @Transactional
    public SiteView suspend(String experimentId, String siteCode) {
        mustFindExperiment(experimentId);
        SiteRow site = mustLockSite(experimentId, siteCode);
        if (!SiteStatus.ACTIVE.name().equals(site.status())) {
            throw ApiException.conflict("仅 ACTIVE 状态的中心可暂停");
        }
        siteRepository.suspend(experimentId, siteCode, clock.nowMillis());
        return toView(mustFindSite(experimentId, siteCode));
    }

    /**
     * 关闭中心：必须不存在该中心名下分配的待审揭盲申请，否则 422；关闭后不可恢复。
     */
    @Transactional
    public SiteView close(String experimentId, String siteCode) {
        mustFindExperiment(experimentId);
        SiteRow site = mustLockSite(experimentId, siteCode);
        if (SiteStatus.CLOSED.name().equals(site.status())) {
            throw ApiException.conflict("中心已关闭");
        }
        long pendingUnblind = siteRepository.countPendingUnblindRequests(experimentId, siteCode);
        if (pendingUnblind > 0) {
            throw ApiException.full("中心存在待审揭盲申请，不能关闭");
        }
        siteRepository.close(experimentId, siteCode, clock.nowMillis());
        // 关闭时清除可能存在的首确认暂存，避免残留误导后续查询。
        siteRepository.deletePending(experimentId, siteCode);
        return toView(mustFindSite(experimentId, siteCode));
    }

    /**
     * 中心详情：含代次、累计分配、剩余容量与门禁原因。
     */
    public SiteView getSite(String experimentId, String siteCode) {
        mustFindExperiment(experimentId);
        return toView(mustFindSite(experimentId, siteCode));
    }

    /**
     * 该中心全部不可变双人激活记录（按代次升序）。
     */
    public List<SiteActivationRecordView> getActivationRecords(String experimentId, String siteCode) {
        mustFindExperiment(experimentId);
        mustFindSite(experimentId, siteCode);
        return siteRepository.findActivationRecords(experimentId, siteCode).stream()
                .map(SiteService::toRecordView)
                .toList();
    }

    /**
     * 分配门禁判定（供登记流程调用，调用方须已持有中心行锁所在事务）：
     * 未激活/暂停/关闭返回 409；累计分配达上限返回 422。
     */
    public void assertAssignable(SiteRow site) {
        if (!SiteStatus.ACTIVE.name().equals(site.status())) {
            throw ApiException.conflict("中心未激活或已暂停/关闭，不得生成盲码或分配区组: "
                    + site.status());
        }
        long cumulative = siteRepository.countAssignments(site.experimentId(), site.siteCode());
        if (cumulative >= site.targetEnrollmentLimit()) {
            throw ApiException.full("中心累计分配已达目标入组上限");
        }
    }

    /** 供登记流程在中心行锁下读取中心（不存在返回 null）。 */
    public SiteRow lockSite(String experimentId, String siteCode) {
        return siteRepository.lock(experimentId, siteCode);
    }

    private SiteRow mustLockSite(String experimentId, String siteCode) {
        SiteRow site = siteRepository.lock(experimentId, siteCode);
        if (site == null) {
            throw ApiException.notFound("中心不存在: " + siteCode);
        }
        return site;
    }

    private SiteRow mustFindSite(String experimentId, String siteCode) {
        SiteRow site = siteRepository.find(experimentId, siteCode);
        if (site == null) {
            throw ApiException.notFound("中心不存在: " + siteCode);
        }
        return site;
    }

    private void mustFindExperiment(String experimentId) {
        ExperimentRow row = experimentRepository.findById(experimentId);
        if (row == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
    }

    private SiteView toView(SiteRow site) {
        long cumulative = siteRepository.countAssignments(site.experimentId(), site.siteCode());
        long remaining = Math.max(0L, site.targetEnrollmentLimit() - cumulative);
        return new SiteView(site.experimentId(), site.siteCode(), site.status(),
                site.targetEnrollmentLimit(), site.generation(), cumulative, remaining,
                gateReason(site, cumulative), site.createdAt(), site.updatedAt());
    }

    private static String gateReason(SiteRow site, long cumulative) {
        if (SiteStatus.INACTIVE.name().equals(site.status())) {
            return "SITE_NOT_ACTIVE";
        }
        if (SiteStatus.SUSPENDED.name().equals(site.status())) {
            return "SITE_SUSPENDED";
        }
        if (SiteStatus.CLOSED.name().equals(site.status())) {
            return "SITE_CLOSED";
        }
        if (cumulative >= site.targetEnrollmentLimit()) {
            return "ENROLLMENT_LIMIT_REACHED";
        }
        return GATE_ALLOWED;
    }

    private static SiteActivationRecordView toRecordView(ActivationRecordRow row) {
        return new SiteActivationRecordView(row.experimentId(), row.siteCode(), row.generation(),
                row.firstConfirmerActor(), row.secondConfirmerActor(),
                row.targetEnrollmentLimit(), row.activatedAt());
    }
}
