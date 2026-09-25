package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.CenterSequenceSummaryView;
import com.example.starter.blind.dto.CenterView;
import com.example.starter.blind.dto.ProtocolVersionView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.CenterCodeSequenceRepository;
import com.example.starter.blind.repo.CenterCodeSequenceRepository.SequenceCount;
import com.example.starter.blind.repo.CenterCodeSequenceRepository.SequenceRow;
import com.example.starter.blind.repo.CenterRepository;
import com.example.starter.blind.repo.CenterRepository.CenterRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ProtocolVersionRepository;
import com.example.starter.blind.repo.ProtocolVersionRepository.ProtocolVersionRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 中心激活/暂停、盲法协议修订与中心独立盲码序列核心业务：
 * <ul>
 *   <li>实验创建即有初始协议版本 1（沿用既有区组比例 50:50）；</li>
 *   <li>修订含新区组比例（正整数且和为 100）与不早于提交时刻的生效时刻，同时刻至多一条待生效版本；</li>
 *   <li>生效按提交顺序在实验行锁内懒裁决：为每个 ACTIVE 中心按剩余容量预留独立盲码序列，
 *       任一中心容量不足、序列生成失败或存在待处理揭盲申请即整体失败（422），协议与序列均不变；</li>
 *   <li>修订只影响生效后新受试者；既有盲码、分组与揭盲权限永久归属旧协议版本；</li>
 *   <li>未生效修订可撤销并保留记录；已生效不可撤销；暂停中心恢复后补建当时有效版本序列。</li>
 * </ul>
 */
@Service
public class ProtocolAmendmentService {

    /** 初始协议版本号与初始区组比例（沿用既有每区组两 A 两 B，即 50:50）。 */
    public static final int INITIAL_VERSION = 1;
    public static final int INITIAL_RATIO_A = 50;
    public static final int INITIAL_RATIO_B = 50;

    private final ExperimentRepository experimentRepository;
    private final CenterRepository centerRepository;
    private final ProtocolVersionRepository protocolVersionRepository;
    private final CenterCodeSequenceRepository sequenceRepository;
    private final AllocationRepository allocationRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final BlindCodeGenerator blindCodeGenerator;
    private final SequenceBlockGenerator sequenceBlockGenerator;
    private final Clock clock;

    public ProtocolAmendmentService(ExperimentRepository experimentRepository,
                                    CenterRepository centerRepository,
                                    ProtocolVersionRepository protocolVersionRepository,
                                    CenterCodeSequenceRepository sequenceRepository,
                                    AllocationRepository allocationRepository,
                                    UnblindRequestRepository unblindRequestRepository,
                                    BlindCodeGenerator blindCodeGenerator,
                                    SequenceBlockGenerator sequenceBlockGenerator,
                                    Clock clock) {
        this.experimentRepository = experimentRepository;
        this.centerRepository = centerRepository;
        this.protocolVersionRepository = protocolVersionRepository;
        this.sequenceRepository = sequenceRepository;
        this.allocationRepository = allocationRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.blindCodeGenerator = blindCodeGenerator;
        this.sequenceBlockGenerator = sequenceBlockGenerator;
        this.clock = clock;
    }

    /**
     * 建实验事务内建立初始协议版本 1；版本 1 不预生成中心序列（中心尚未激活）。
     * 由 {@link ExperimentService#createExperiment} 在同一事务调用。
     */
    public void bootstrapInitialVersion(String experimentId, long now) {
        protocolVersionRepository.insert(new ProtocolVersionRow(
                experimentId, INITIAL_VERSION, INITIAL_RATIO_A, INITIAL_RATIO_B,
                now, "ACTIVE", "SYSTEM", now, now, null));
    }

    // ---------------- 中心 ----------------

    /**
     * 激活（创建）研究中心，并按当前有效协议版本为其预留覆盖全部目标上限的独立盲码序列。
     * 同时裁决已到期修订，保证新中心首条序列即遵循最新生效协议。
     */
    @Transactional
    public CenterView createCenter(String experimentId, String centerId, int targetCap) {
        long now = clock.nowMillis();
        ExperimentRow experiment = lockExperiment(experimentId);
        enforceExperimentOpen(experiment);
        settleDueAmendments(experimentId, now);
        if (centerRepository.find(experimentId, centerId) != null) {
            throw ApiException.conflict("中心已存在: " + centerId);
        }
        try {
            centerRepository.insert(new CenterRow(experimentId, centerId, targetCap,
                    "ACTIVE", now, now));
        } catch (DuplicateKeyException e) {
            // 并发创建同一中心：主键约束兜底，返回可区分冲突，事务回滚不留半成品。
            throw ApiException.conflict("中心已存在: " + centerId);
        }
        ProtocolVersionRow effective = currentEffectiveVersion(experimentId, now);
        // 新中心尚无分配，剩余容量即目标上限；为整个目标上限预留序列。
        reserveSequence(experimentId, centerId, effective, targetCap);
        return centerView(experimentId, centerId, targetCap, 0L, "ACTIVE", now, now);
    }

    /**
     * 暂停中心：暂停期间拒绝登记，且不参与修订生效预留。暂停幂等冲突返回 409。
     */
    @Transactional
    public CenterView suspendCenter(String experimentId, String centerId) {
        long now = clock.nowMillis();
        lockExperiment(experimentId);
        // 暂停提交也按提交顺序裁决到期修订（该中心此刻仍 ACTIVE，参与本轮预留）。
        settleDueAmendments(experimentId, now);
        CenterRow center = lockCenter(experimentId, centerId);
        if ("SUSPENDED".equals(center.status())) {
            throw ApiException.conflict("中心已暂停");
        }
        centerRepository.markSuspended(experimentId, centerId, now);
        return toView(new CenterRow(experimentId, centerId, center.targetCap(),
                "SUSPENDED", center.createdAt(), now));
    }

    /**
     * 恢复暂停中心：置 ACTIVE，并按当时有效协议版本补足剩余容量序列。
     * 同时裁决已到期修订，使恢复后使用当时有效版本。
     */
    @Transactional
    public CenterView resumeCenter(String experimentId, String centerId) {
        long now = clock.nowMillis();
        lockExperiment(experimentId);
        settleDueAmendments(experimentId, now);
        CenterRow center = lockCenter(experimentId, centerId);
        if ("ACTIVE".equals(center.status())) {
            throw ApiException.conflict("中心已激活");
        }
        centerRepository.markActive(experimentId, centerId, now);
        ProtocolVersionRow effective = currentEffectiveVersion(experimentId, now);
        ensureSequenceForRemaining(experimentId, centerId, center.targetCap(), effective);
        return toView(new CenterRow(experimentId, centerId, center.targetCap(),
                "ACTIVE", center.createdAt(), now));
    }

    public CenterView getCenter(String experimentId, String centerId) {
        mustFindExperiment(experimentId);
        return toView(mustFindCenter(experimentId, centerId));
    }

    public List<CenterView> listCenters(String experimentId) {
        mustFindExperiment(experimentId);
        return centerRepository.findAll(experimentId).stream().map(this::toView).toList();
    }

    // ---------------- 修订 ----------------

    /**
     * 创建协议修订：比例正整数且和为 100；生效时刻不得早于当前时刻；
     * 同一实验同时刻至多一条待生效版本（唯一约束兜底）。
     */
    @Transactional
    public ProtocolVersionView createAmendment(String experimentId, int ratioA, int ratioB,
                                               long effectiveAt, String actorId) {
        long now = clock.nowMillis();
        if (ratioA < 1 || ratioB < 1 || ratioA + ratioB != 100) {
            throw ApiException.badRequest("区组比例必须均为正整数且总和为 100");
        }
        if (effectiveAt < now) {
            throw ApiException.unprocessable("生效时刻不得早于当前时刻");
        }
        lockExperiment(experimentId);
        ProtocolVersionRow pending = protocolVersionRepository.findPending(experimentId);
        if (pending != null) {
            throw ApiException.conflict("已存在待生效协议修订（版本 " + pending.versionNo()
                    + "），批量创建只允许一条最终待生效版本");
        }
        int nextVersion = protocolVersionRepository.maxVersionNo(experimentId) + 1;
        ProtocolVersionRow row = new ProtocolVersionRow(experimentId, nextVersion, ratioA, ratioB,
                effectiveAt, "PENDING", actorId, now, null, null);
        try {
            protocolVersionRepository.insert(row);
        } catch (DuplicateKeyException e) {
            if (protocolVersionRepository.isDuplicatePending(e)) {
                throw ApiException.conflict("已存在待生效协议修订，只允许一条最终待生效版本");
            }
            throw e;
        }
        // 生效时刻已到（等于当前时刻）则在同一提交事务内立即裁决。
        settleDueAmendments(experimentId, clock.nowMillis());
        ProtocolVersionRow stored = protocolVersionRepository.find(experimentId, nextVersion);
        return toVersionView(stored);
    }

    /**
     * 裁决实验内所有已到期待生效修订（按提交/生效顺序）。
     * 任一版本生效失败即抛 422，整个外层事务回滚，所有中心当前协议与序列均不改变。
     */
    private void settleDueAmendments(String experimentId, long now) {
        List<ProtocolVersionRow> due = protocolVersionRepository.findDuePending(experimentId, now);
        for (ProtocolVersionRow version : due) {
            activateVersion(experimentId, version, now);
        }
    }

    /**
     * 单个版本原子生效：校验待处理揭盲申请为零，为每个 ACTIVE 中心预留剩余容量序列。
     */
    private void activateVersion(String experimentId, ProtocolVersionRow version, long now) {
        long pendingUnblind = unblindRequestRepository.countPendingByExperiment(experimentId);
        if (pendingUnblind > 0) {
            throw ApiException.unprocessable(
                    "存在 " + pendingUnblind + " 条待处理揭盲申请，协议修订不能生效");
        }
        List<CenterRow> activeCenters = centerRepository.lockAll(experimentId).stream()
                .filter(c -> "ACTIVE".equals(c.status()))
                .toList();
        // 先为所有中心计算并生成序列内容，任一中心容量不足即整体失败（事务回滚）。
        record Planned(String centerId, List<String> treatments) {
        }
        List<Planned> plans = new ArrayList<>();
        for (CenterRow center : activeCenters) {
            long allocated = allocationRepository.countByCenter(experimentId, center.centerId());
            long remaining = (long) center.targetCap() - allocated;
            if (remaining <= 0) {
                // 必须为每个 ACTIVE 中心预留至少其剩余容量条序列；已满中心无容量可预留，
                // 判定容量不足，整个生效事务回滚，当前协议与所有中心序列均不改变。
                throw ApiException.unprocessable(
                        "中心 " + center.centerId() + " 剩余容量不足，协议修订不能生效");
            }
            if (remaining > Integer.MAX_VALUE) {
                throw ApiException.unprocessable("中心 " + center.centerId() + " 剩余容量超出范围");
            }
            List<String> treatments = sequenceBlockGenerator.generateTreatments(
                    version.ratioA(), version.ratioB(), (int) remaining);
            plans.add(new Planned(center.centerId(), treatments));
        }
        // 全部中心序列内容就绪后再写库；盲码冲突由唯一索引兜底并随事务回滚。
        for (Planned plan : plans) {
            persistGeneratedSequence(experimentId, plan.centerId(), version.versionNo(),
                    plan.treatments());
        }
        int updated = protocolVersionRepository.markEffective(experimentId, version.versionNo(), now);
        if (updated == 0) {
            // 并发下版本已被裁决：交由锁顺序避免，理论不可达，保守失败回滚。
            throw ApiException.conflict("协议版本已被并发裁决: " + version.versionNo());
        }
    }

    /**
     * 为中心×版本生成并写入盲码序列；盲码全局唯一冲突时抛错使事务回滚（不留半成品）。
     */
    private void persistGeneratedSequence(String experimentId, String centerId, int versionNo,
                                          List<String> treatments) {
        int seqNo = 1;
        for (String treatment : treatments) {
            String blindCode = blindCodeGenerator.nextCode();
            try {
                sequenceRepository.insert(new SequenceRow(0L, experimentId, centerId, versionNo,
                        seqNo, blindCode, treatment, "RESERVED", null));
            } catch (DuplicateKeyException e) {
                // 唯一盲码冲突概率极低；冲突即视为序列生成失败，整体回滚，不部分生效。
                throw ApiException.unprocessable("中心盲码序列生成失败，请重试");
            }
            seqNo++;
        }
    }

    /**
     * 恢复/激活中心时，为指定有效版本补足“剩余容量 - 该版本已预留数”条序列。
     */
    private void ensureSequenceForRemaining(String experimentId, String centerId, int targetCap,
                                            ProtocolVersionRow effective) {
        long allocated = allocationRepository.countByCenter(experimentId, centerId);
        long remaining = targetCap - allocated;
        if (remaining < 0) {
            throw ApiException.unprocessable("中心累计分配已超过目标上限，容量不足");
        }
        long existing = sequenceRepository.countByVersion(experimentId, centerId,
                effective.versionNo());
        long need = remaining - existing;
        if (need < 0) {
            // 已预留不少于剩余容量（容量只减不增），无需补充。
            return;
        }
        if (need > Integer.MAX_VALUE) {
            throw ApiException.unprocessable("中心剩余容量超出范围");
        }
        List<String> treatments = sequenceBlockGenerator.generateTreatments(
                effective.ratioA(), effective.ratioB(), (int) need);
        appendSequence(experimentId, centerId, effective.versionNo(), existing, treatments);
    }

    /**
     * 新中心：从 seqNo=1 起为整个目标容量生成当前版本序列。
     */
    private void reserveSequence(String experimentId, String centerId,
                                 ProtocolVersionRow effective, int capacity) {
        List<String> treatments = sequenceBlockGenerator.generateTreatments(
                effective.ratioA(), effective.ratioB(), capacity);
        appendSequence(experimentId, centerId, effective.versionNo(), 0L, treatments);
    }

    private void appendSequence(String experimentId, String centerId, int versionNo,
                                long existingCount, List<String> treatments) {
        int seqNo = (int) existingCount + 1;
        for (String treatment : treatments) {
            String blindCode = blindCodeGenerator.nextCode();
            try {
                sequenceRepository.insert(new SequenceRow(0L, experimentId, centerId, versionNo,
                        seqNo, blindCode, treatment, "RESERVED", null));
            } catch (DuplicateKeyException e) {
                throw ApiException.unprocessable("中心盲码序列生成失败，请重试");
            }
            seqNo++;
        }
    }

    /**
     * 撤销未生效修订：记录保留；已生效版本不可撤销。
     */
    @Transactional
    public ProtocolVersionView revokeAmendment(String experimentId, int versionNo) {
        long now = clock.nowMillis();
        lockExperiment(experimentId);
        ProtocolVersionRow row = mustFindVersion(experimentId, versionNo);
        if ("ACTIVE".equals(row.status())) {
            throw ApiException.conflict("已生效协议修订不可撤销");
        }
        if ("REVOKED".equals(row.status())) {
            throw ApiException.conflict("协议修订已撤销");
        }
        int updated = protocolVersionRepository.revoke(experimentId, versionNo, now);
        if (updated == 0) {
            throw ApiException.conflict("协议修订状态已变化，撤销失败");
        }
        return toVersionView(protocolVersionRepository.find(experimentId, versionNo));
    }

    // ---------------- 中心登记 ----------------

    /**
     * 中心登记：在实验锁+中心锁内裁决到期修订，按当前有效版本领取该中心下一条序列盲码，
     * 固化协议版本归属。暂停中心、无剩余序列均 422/409，不产生半成品。
     */
    @Transactional
    public com.example.starter.blind.dto.AllocationView registerAtCenter(
            String experimentId, String centerId, String participantId, String actorId) {
        if (participantId == null || participantId.isBlank()) {
            throw ApiException.badRequest("participantId 不能为空");
        }
        long now = clock.nowMillis();
        ExperimentRow experiment = lockExperiment(experimentId);
        enforceExperimentOpen(experiment);
        settleDueAmendments(experimentId, now);
        CenterRow center = lockCenter(experimentId, centerId);
        if ("SUSPENDED".equals(center.status())) {
            throw ApiException.unprocessable("中心已暂停，暂不接受登记");
        }
        AllocationRow existing =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (existing != null) {
            throw ApiException.conflict("参与者已在该实验登记，仅可占一席");
        }
        ProtocolVersionRow effective = currentEffectiveVersion(experimentId, now);
        SequenceRow next = sequenceRepository.lockFirstReserved(
                experimentId, centerId, effective.versionNo());
        if (next == null) {
            throw ApiException.unprocessable("中心剩余容量不足，无可用盲码序列");
        }
        AllocationRow inserted = insertCenterAllocation(experimentId, centerId, participantId,
                actorId, effective.versionNo(), next, now);
        int issued = sequenceRepository.markIssued(next.id(), inserted.id());
        if (issued == 0) {
            // 并发下该序列已被发放：唯一参与者约束外的二次兜底，回滚重试由调用方决定。
            throw ApiException.conflict("盲码序列已被并发领取，请重试");
        }
        AllocationRow refreshed =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        return new com.example.starter.blind.dto.AllocationView(
                refreshed.experimentId(), refreshed.participantId(), refreshed.blindCode(),
                refreshed.blockNo(), refreshed.centerId(), refreshed.versionNo(),
                refreshed.status(), refreshed.assignedAt(), refreshed.withdrawnAt());
    }

    private AllocationRow insertCenterAllocation(String experimentId, String centerId,
                                                 String participantId, String actorId,
                                                 int versionNo, SequenceRow seq, long now) {
        for (int attempt = 0; attempt < 5; attempt++) {
            // 序列盲码已预生成且唯一；这里直接使用，并固化版本与处理归属。
            AllocationRow row = new AllocationRow(0L, experimentId, participantId, null, null,
                    seq.blindCode(), seq.treatment(), centerId, versionNo,
                    "ASSIGNED", actorId, now, null);
            try {
                allocationRepository.insertCentered(row);
                return allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
            } catch (DuplicateKeyException e) {
                if (allocationRepository.isDuplicateBlindCode(e)) {
                    // 理论不可达：序列盲码来自唯一库内行；保守按容量冲突失败回滚。
                    throw ApiException.unprocessable("盲码冲突，中心登记失败，请重试");
                }
                if (allocationRepository.isDuplicateParticipant(e)) {
                    throw ApiException.conflict("参与者已在该实验登记，仅可占一席");
                }
                throw e;
            }
        }
        throw ApiException.unprocessable("中心登记失败，请重试");
    }

    // ---------------- 查询 ----------------

    public List<ProtocolVersionView> listVersions(String experimentId) {
        mustFindExperiment(experimentId);
        return protocolVersionRepository.findAll(experimentId).stream()
                .map(this::toVersionView)
                .toList();
    }

    public List<CenterSequenceSummaryView> listSequences(String experimentId) {
        mustFindExperiment(experimentId);
        List<CenterSequenceSummaryView> views = new ArrayList<>();
        for (SequenceCount c : sequenceRepository.summarize(experimentId)) {
            views.add(new CenterSequenceSummaryView(experimentId, c.centerId(), c.versionNo(),
                    c.total(), c.reserved(), c.issued()));
        }
        return views;
    }

    // ---------------- 公共辅助 ----------------

    /**
     * 在任意写操作前裁决到期修订；获取实验行锁以按提交顺序串行化，供区组登记与揭盲申请入口复用。
     */
    @Transactional
    public void settleBeforeLegacyWrite(String experimentId) {
        lockExperiment(experimentId);
        settleDueAmendments(experimentId, clock.nowMillis());
    }

    // ---------------- 私有辅助 ----------------

    private ExperimentRow lockExperiment(String experimentId) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        return experiment;
    }

    private void mustFindExperiment(String experimentId) {
        if (experimentRepository.findById(experimentId) == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
    }

    private void enforceExperimentOpen(ExperimentRow experiment) {
        if ("CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，拒绝该操作");
        }
    }

    private CenterRow mustFindCenter(String experimentId, String centerId) {
        CenterRow center = centerRepository.find(experimentId, centerId);
        if (center == null) {
            throw ApiException.notFound("中心不存在: " + centerId);
        }
        return center;
    }

    private CenterRow lockCenter(String experimentId, String centerId) {
        CenterRow center = centerRepository.lock(experimentId, centerId);
        if (center == null) {
            throw ApiException.notFound("中心不存在: " + centerId);
        }
        return center;
    }

    private ProtocolVersionRow mustFindVersion(String experimentId, int versionNo) {
        ProtocolVersionRow row = protocolVersionRepository.find(experimentId, versionNo);
        if (row == null) {
            throw ApiException.notFound("协议版本不存在: " + versionNo);
        }
        return row;
    }

    private ProtocolVersionRow currentEffectiveVersion(String experimentId, long now) {
        ProtocolVersionRow effective = protocolVersionRepository.findEffective(experimentId, now);
        if (effective == null) {
            // 初始版本缺失属数据不一致；建实验事务已保证存在。
            throw new IllegalStateException("当前有效协议版本缺失，数据不一致");
        }
        return effective;
    }

    private CenterView toView(CenterRow c) {
        long allocated = allocationRepository.countByCenter(c.experimentId(), c.centerId());
        return centerView(c.experimentId(), c.centerId(), c.targetCap(), allocated, c.status(),
                c.createdAt(), c.updatedAt());
    }

    private CenterView centerView(String experimentId, String centerId, int targetCap,
                                  long allocated, String status, long createdAt, long updatedAt) {
        long remaining = (long) targetCap - allocated;
        return new CenterView(experimentId, centerId, targetCap, allocated, remaining, status,
                createdAt, updatedAt);
    }

    private ProtocolVersionView toVersionView(ProtocolVersionRow v) {
        return new ProtocolVersionView(v.experimentId(), v.versionNo(), v.ratioA(), v.ratioB(),
                v.effectiveAt(), v.status(), v.createdByActor(), v.createdAt(),
                v.effectiveEventAt(), v.revokedAt());
    }
}
