package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.BlockAvailabilityView;
import com.example.starter.blind.dto.BlockQuotaView;
import com.example.starter.blind.dto.CodeQuotaView;
import com.example.starter.blind.dto.RetiredCodeView;
import com.example.starter.blind.dto.RetirementDiagnosticsView;
import com.example.starter.blind.dto.RetirementView;
import com.example.starter.blind.dto.SealedSeatView;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.RetirementRepository;
import com.example.starter.blind.repo.RetirementRepository.BlockAvailabilityAggregate;
import com.example.starter.blind.repo.RetirementRepository.BlockQuotaRow;
import com.example.starter.blind.repo.RetirementRepository.CodeQuotaAggregate;
import com.example.starter.blind.repo.RetirementRepository.RetiredCodeRow;
import com.example.starter.blind.repo.RetirementRepository.RetirementRow;
import com.example.starter.blind.repo.RetirementRepository.SealedSeatRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import com.example.starter.blind.service.RetirementPlanner.BlockPlan;
import com.example.starter.blind.service.RetirementPlanner.CodePlan;
import com.example.starter.blind.service.RetirementPlanner.RetirementPlan;
import com.example.starter.blind.service.RetirementPlanner.SeatState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 盲态代码退役业务：
 * <ul>
 *   <li>退役、分配、揭盲申请、关闭均以实验行锁按提交顺序串行裁决；</li>
 *   <li>多代码退役先由 {@link RetirementPlanner} 校验完整后态，任一失败整次回滚不留半成品；</li>
 *   <li>提交时一次事务写入批次头、代码配额、封存序号与区组配额重算四类只追加记录；</li>
 *   <li>既有参与者分配保持有效且继续不暴露处理代码；退役、封存、配额与理由均不可变；</li>
 *   <li>历史、明细与诊断查询只读，排序稳定。</li>
 * </ul>
 */
@Service
public class RetirementService {

    /** 实验中合法的处理代码集合（每区组固定两个 A、两个 B）。 */
    static final Set<String> VALID_TREATMENTS = Set.of("A", "B");

    private final RetirementRepository retirementRepository;
    private final ExperimentRepository experimentRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final Clock clock;

    public RetirementService(RetirementRepository retirementRepository,
                             ExperimentRepository experimentRepository,
                             UnblindRequestRepository unblindRequestRepository,
                             Clock clock) {
        this.retirementRepository = retirementRepository;
        this.experimentRepository = experimentRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.clock = clock;
    }

    /**
     * 退役一批处理代码；整批先校验完整后态，再一次事务提交。
     *
     * @param experimentId 实验编号
     * @param reason       退役原因，非空
     * @param codes        待退役代码（请求原始集合，重复元素按规范化集合处理）
     * @param operatorActor 操作者编号（COORDINATOR）
     */
    @Transactional
    public RetirementView retire(String experimentId, String reason, List<String> codes,
                                 String operatorActor) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason 不能为空");
        }
        if (reason.length() > 500) {
            throw ApiException.badRequest("reason 最长 500 字符");
        }
        // 规范化对象集合：去空白、去重、按代码升序；禁用分隔符拼接。
        TreeSet<String> normalized = new TreeSet<>();
        for (String code : codes == null ? List.<String>of() : codes) {
            if (code == null || code.isBlank()) {
                throw ApiException.badRequest("codes 不能含空白代码");
            }
            String value = code.trim();
            if (!VALID_TREATMENTS.contains(value)) {
                // 实验仅含 A/B 两种处理代码，其余代码不存在。
                throw ApiException.conflict("CODE_NOT_FOUND",
                        "处理代码不存在: " + value + "；实验仅含处理代码 A、B");
            }
            normalized.add(value);
        }
        if (normalized.isEmpty()) {
            throw ApiException.badRequest("codes 不能为空");
        }

        // 行锁：与同实验分配、关闭、揭盲申请按提交顺序串行裁决。
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        // 存在待揭盲紧急申请时拒绝退役（409，可区分原因）。
        long pending = unblindRequestRepository.countPendingByExperiment(experimentId);
        if (pending > 0) {
            throw ApiException.conflict("PENDING_UNBLIND_REQUEST",
                    "实验存在 " + pending + " 个待审揭盲申请，实际要求 0 个，差额 "
                            + pending + " 个；处理完待揭盲紧急申请后方可退役代码");
        }

        List<SeatState> seatStates = retirementRepository.loadSeatStates(experimentId);
        // 先核对整批最终状态；任一代码/区组不满足即抛异常，事务回滚不留半成品。
        RetirementPlan plan = RetirementPlanner.plan(List.copyOf(normalized), seatStates,
                experiment.blockCount());

        long now = clock.nowMillis();
        String retirementId = "RT-" + UUID.randomUUID().toString().replace("-", "");
        retirementRepository.insertRetirement(new RetirementRow(
                retirementId, experimentId, reason, operatorActor, "COMMITTED", now, now));

        for (CodePlan codePlan : plan.codes()) {
            retirementRepository.insertRetiredCode(new RetiredCodeRow(
                    0L, retirementId, experimentId, codePlan.treatment(),
                    codePlan.quotaBefore(), codePlan.sealedNow(), codePlan.quotaAfter(),
                    codePlan.assignedCount(), now));
        }
        for (SeatState seat : seatStates) {
            if (!seat.occupied() && !seat.sealed() && normalized.contains(seat.treatment())) {
                retirementRepository.insertSealedSeat(new SealedSeatRow(
                        0L, retirementId, experimentId, seat.blockNo(), seat.seatNo(),
                        seat.treatment(), now));
            }
        }
        for (BlockPlan blockPlan : plan.blocks()) {
            retirementRepository.insertBlockQuota(new BlockQuotaRow(
                    0L, retirementId, experimentId, blockPlan.blockNo(),
                    blockPlan.availableBefore(), blockPlan.sealedInBlock(),
                    blockPlan.availableAfter(), blockPlan.quotaAAfter(),
                    blockPlan.quotaBAfter(), now));
        }
        return loadView(retirementId);
    }

    /** 查询单个退役批次完整不可变快照；不存在 404。 */
    public RetirementView getRetirement(String retirementId) {
        RetirementRow row = retirementRepository.findRetirementById(retirementId);
        if (row == null) {
            throw ApiException.notFound("退役批次不存在: " + retirementId);
        }
        return toView(row);
    }

    /**
     * 退役批次历史（只读）：时间区间左闭右开 [fromInclusive, toExclusive)，
     * 端点为 null 时该侧不设界；按提交时间、批次编号稳定升序。
     */
    public List<RetirementView> listHistory(String experimentId, Long fromInclusive, Long toExclusive) {
        ExperimentRow experiment = experimentRepository.findById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if (fromInclusive != null && toExclusive != null && fromInclusive > toExclusive) {
            throw ApiException.badRequest("时间区间必须满足 from <= to（左闭右开）");
        }
        return retirementRepository.findRetirements(experimentId, fromInclusive, toExclusive).stream()
                .map(this::toView)
                .toList();
    }

    /** 封存序号明细（只读）；遵循盲态纪律，不返回席位号与处理代码。 */
    public List<SealedSeatView> listSealedSeats(String retirementId) {
        RetirementRow row = retirementRepository.findRetirementById(retirementId);
        if (row == null) {
            throw ApiException.notFound("退役批次不存在: " + retirementId);
        }
        return retirementRepository.findSealedSeats(retirementId).stream()
                .map(s -> new SealedSeatView(s.id(), s.retirementId(), s.experimentId(),
                        s.blockNo(), s.createdAt()))
                .toList();
    }

    /** 代码/区组配额诊断（只读），返回查询时刻实际值。 */
    public RetirementDiagnosticsView diagnostics(String experimentId) {
        ExperimentRow experiment = experimentRepository.findById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        List<CodeQuotaView> codes = retirementRepository.aggregateCodeQuotas(experimentId).stream()
                .map(this::toCodeView)
                .toList();
        List<BlockAvailabilityView> blocks =
                retirementRepository.aggregateBlockAvailability(experimentId).stream()
                        .map(this::toBlockView)
                        .toList();
        return new RetirementDiagnosticsView(experimentId, codes, blocks);
    }

    private RetirementView loadView(String retirementId) {
        RetirementRow row = retirementRepository.findRetirementById(retirementId);
        if (row == null) {
            throw new IllegalStateException("退役批次写入后读取缺失: " + retirementId);
        }
        return toView(row);
    }

    private RetirementView toView(RetirementRow row) {
        List<RetiredCodeView> codes = retirementRepository.findCodesByRetirement(row.id()).stream()
                .map(c -> new RetiredCodeView(c.treatment(), c.quotaBefore(), c.assignedCount(),
                        c.sealedCount(), c.quotaAfter()))
                .toList();
        List<BlockQuotaView> blocks =
                retirementRepository.findBlockQuotasByRetirement(row.id()).stream()
                        .map((BlockQuotaRow b) -> new BlockQuotaView(b.blockNo(),
                                b.availableBefore(), b.sealedInBlock(), b.availableAfter(),
                                b.quotaAAfter(), b.quotaBAfter()))
                        .toList();
        return new RetirementView(row.id(), row.experimentId(), row.reason(), row.operatorActor(),
                row.status(), row.createdAt(), row.committedAt(), codes, blocks);
    }

    private CodeQuotaView toCodeView(CodeQuotaAggregate agg) {
        int available = agg.quotaTotal() - agg.assignedCount() - agg.sealedCount();
        return new CodeQuotaView(agg.treatment(), agg.quotaTotal(), agg.assignedCount(),
                agg.sealedCount(), available);
    }

    private BlockAvailabilityView toBlockView(BlockAvailabilityAggregate agg) {
        return new BlockAvailabilityView(agg.blockNo(), agg.capacity(), agg.occupiedCount(),
                agg.sealedCount(), agg.availableCount(), agg.quotaA(), agg.quotaB());
    }
}
