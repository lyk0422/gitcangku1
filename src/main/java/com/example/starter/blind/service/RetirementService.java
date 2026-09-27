package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.BusinessException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.BlockCapacityView;
import com.example.starter.blind.dto.CapacityView;
import com.example.starter.blind.dto.CodeCapacityView;
import com.example.starter.blind.dto.RetiredCodeView;
import com.example.starter.blind.dto.RetirementDetailView;
import com.example.starter.blind.dto.RetirementListView;
import com.example.starter.blind.dto.RetirementView;
import com.example.starter.blind.dto.SealedSeatView;
import com.example.starter.blind.dto.ViolationView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.VacantSeat;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.BlockCapacityStat;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.TreatmentCapacityStat;
import com.example.starter.blind.repo.RetirementRepository;
import com.example.starter.blind.repo.RetirementRepository.RetiredCodeRow;
import com.example.starter.blind.repo.RetirementRepository.RetirementRow;
import com.example.starter.blind.repo.RetirementRepository.SealedSeatRow;
import com.example.starter.blind.repo.UnblindRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 盲态处理代码退役与容量诊断：
 * <ul>
 *   <li>退役整批先校验完整后态（代码存在、未退役、未耗尽、无待揭盲紧急申请、
 *       退役后仍剩有效代码），任一失败整次回滚并返回可区分原因；</li>
 *   <li>校验通过后原子封存该代码全部未分配序号，配额快照与理由写入后不可变；</li>
 *   <li>与分配、揭盲申请、区组扩容共用实验行锁，按提交顺序裁决，
 *       同一序号不能同时被分配和封存；</li>
 *   <li>明细、历史与诊断查询只读，排序稳定，时间按 UTC 毫秒、区间左闭右开。</li>
 * </ul>
 */
@Service
public class RetirementService {

    /** 违规原因码：处理代码不存在于该实验。 */
    public static final String CODE_NOT_FOUND = "CODE_NOT_FOUND";
    /** 违规原因码：处理代码已退役。 */
    public static final String CODE_ALREADY_RETIRED = "CODE_ALREADY_RETIRED";
    /** 违规原因码：处理代码已全部分配，无未分配序号可封存。 */
    public static final String CODE_EXHAUSTED = "CODE_EXHAUSTED";
    /** 违规原因码：该代码的分配存在待揭盲紧急申请。 */
    public static final String PENDING_UNBLIND_REQUEST = "PENDING_UNBLIND_REQUEST";
    /** 违规原因码：退役后剩余有效代码不足以满足区组配额。 */
    public static final String INSUFFICIENT_REMAINING_CODES = "INSUFFICIENT_REMAINING_CODES";

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final RetirementRepository retirementRepository;
    private final Clock clock;

    public RetirementService(ExperimentRepository experimentRepository,
                             AllocationRepository allocationRepository,
                             UnblindRequestRepository unblindRequestRepository,
                             RetirementRepository retirementRepository,
                             Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.retirementRepository = retirementRepository;
        this.clock = clock;
    }

    /**
     * 规范化待退役代码集合：去空白、转大写、去重、升序。
     * 规范化后的集合同时用于业务裁决与幂等指纹，保证同一逻辑操作指纹一致。
     *
     * @throws ApiException 400，任一元素不是单个字母
     */
    public static List<String> normalizeCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            throw ApiException.badRequest("codes 不能为空");
        }
        Set<String> normalized = new TreeSet<>();
        for (String raw : codes) {
            String code = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (code.length() != 1 || code.charAt(0) < 'A' || code.charAt(0) > 'Z') {
                throw ApiException.badRequest("处理代码必须为单个字母: " + raw);
            }
            normalized.add(code);
        }
        return List.copyOf(normalized);
    }

    /**
     * 退役一批处理代码：整批校验完整后态后一次事务提交。
     * 既有参与者分配保持有效且继续不暴露代码；退役代码不再参与新分配与扩容。
     */
    @Transactional
    public RetirementDetailView retire(String experimentId, List<String> codes,
                                       String reason, String actorId) {
        // 实验行锁：与分配、揭盲申请、区组扩容按提交顺序串行裁决。
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        List<String> treatments = experimentRepository.findTreatments(experimentId);
        Set<String> alreadyRetired =
                new HashSet<>(retirementRepository.findRetiredTreatments(experimentId));
        Map<String, Long> unallocated =
                allocationRepository.countUnallocatedActiveByTreatment(experimentId);
        Map<String, Long> pendingUnblind =
                unblindRequestRepository.countPendingByTreatment(experimentId);

        // 第一阶段：整批校验完整后态，收集全部违规，任一失败不留半成品。
        List<ViolationView> violations = new ArrayList<>();
        for (String code : codes) {
            if (!treatments.contains(code)) {
                violations.add(new ViolationView(code, CODE_NOT_FOUND,
                        "处理代码不存在于该实验: " + code, null, null, null));
                continue;
            }
            if (alreadyRetired.contains(code)) {
                violations.add(new ViolationView(code, CODE_ALREADY_RETIRED,
                        "处理代码已退役: " + code, null, null, null));
                continue;
            }
            long free = unallocated.getOrDefault(code, 0L);
            if (free == 0) {
                violations.add(new ViolationView(code, CODE_EXHAUSTED,
                        "处理代码已全部分配，无未分配序号可封存: " + code, 0L, 1L, 1L));
                continue;
            }
            long pending = pendingUnblind.getOrDefault(code, 0L);
            if (pending > 0) {
                violations.add(new ViolationView(code, PENDING_UNBLIND_REQUEST,
                        "该代码的分配存在待揭盲紧急申请，需先处理: " + code,
                        pending, 0L, pending));
            }
        }
        Set<String> remaining = new TreeSet<>(treatments);
        remaining.removeAll(alreadyRetired);
        remaining.removeAll(codes);
        if (remaining.isEmpty()) {
            violations.add(new ViolationView(null, INSUFFICIENT_REMAINING_CODES,
                    "退役后剩余有效代码不足以满足区组配额", 0L, 1L, 1L));
        }
        if (!violations.isEmpty()) {
            // 状态冲突(409)优先于业务不可处理(422)，整体状态可区分。
            boolean hasConflict = violations.stream().anyMatch(v ->
                    CODE_ALREADY_RETIRED.equals(v.reasonCode())
                            || CODE_EXHAUSTED.equals(v.reasonCode())
                            || PENDING_UNBLIND_REQUEST.equals(v.reasonCode()));
            HttpStatus status = hasConflict ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY;
            throw new BusinessException(status, "代码退役校验失败，整次操作已回滚", violations);
        }

        // 第二阶段：一次事务内完成批次、快照与封存；席位条件更新兜底并发。
        String retirementId = "RET-" + UUID.randomUUID().toString().replace("-", "");
        long now = clock.nowMillis();
        retirementRepository.insertRetirement(
                new RetirementRow(retirementId, experimentId, reason, actorId, now));
        List<RetiredCodeView> codeViews = new ArrayList<>();
        List<SealedSeatView> sealedViews = new ArrayList<>();
        for (String code : codes) {
            List<VacantSeat> seats =
                    allocationRepository.findUnallocatedActiveSeats(experimentId, code);
            long allocated = allocationRepository.countAllocatedByTreatment(experimentId, code);
            long quotaBefore = experimentRepository.countSeatsByTreatment(experimentId, code);
            retirementRepository.insertRetiredCode(new RetiredCodeRow(
                    retirementId, experimentId, code, (int) allocated, seats.size(),
                    (int) quotaBefore, 0));
            codeViews.add(new RetiredCodeView(code, (int) allocated, seats.size(),
                    (int) quotaBefore, 0));
            for (VacantSeat seat : seats) {
                int updated = experimentRepository.sealSeat(
                        experimentId, seat.blockNo(), seat.seatNo(), retirementId, now);
                if (updated != 1) {
                    // 实验行锁下不应发生；唯一约束兜底时整批回滚，不留半成品。
                    throw ApiException.conflict("席位状态并发变化，退役已回滚，请重试");
                }
                retirementRepository.insertSealedSeat(new SealedSeatRow(
                        0L, retirementId, experimentId, code,
                        seat.blockNo(), seat.seatNo(), now));
                sealedViews.add(new SealedSeatView(code, seat.blockNo(), seat.seatNo(), now));
            }
        }
        return new RetirementDetailView(retirementId, experimentId, reason, actorId, now,
                codeViews, sealedViews);
    }

    /**
     * 退役历史：只读，按退役时间、批次编号升序；from/to 为 UTC 毫秒，左闭右开。
     */
    @Transactional(readOnly = true)
    public RetirementListView listHistory(String experimentId, Long from, Long to) {
        mustExist(experimentId);
        if (from != null && to != null && from > to) {
            throw ApiException.badRequest("from 不得大于 to（区间为左闭右开）");
        }
        List<RetirementView> items = new ArrayList<>();
        for (RetirementRow row : retirementRepository.findRetirements(experimentId, from, to)) {
            items.add(toSummary(row));
        }
        return new RetirementListView(items);
    }

    /**
     * 退役明细：只读，含封存序号；仅供 COORDINATOR（控制器已校验角色）。
     */
    @Transactional(readOnly = true)
    public RetirementDetailView getDetail(String experimentId, String retirementId) {
        mustExist(experimentId);
        RetirementRow row = retirementRepository.findRetirementById(retirementId);
        if (row == null || !row.experimentId().equals(experimentId)) {
            throw ApiException.notFound("退役批次不存在: " + retirementId);
        }
        List<SealedSeatView> sealed = new ArrayList<>();
        for (SealedSeatRow seat : retirementRepository.findSealedSeats(retirementId)) {
            sealed.add(new SealedSeatView(seat.treatment(), seat.blockNo(),
                    seat.seatNo(), seat.sealedAt()));
        }
        RetirementView summary = toSummary(row);
        return new RetirementDetailView(summary.retirementId(), summary.experimentId(),
                summary.reason(), summary.actorId(), summary.createdAt(), summary.codes(), sealed);
    }

    /**
     * 容量诊断：只读，区组可用名额与代码配额按封存数量实时重算。
     */
    @Transactional(readOnly = true)
    public CapacityView getCapacity(String experimentId) {
        ExperimentRow experiment = mustExist(experimentId);
        Set<String> retired =
                new HashSet<>(retirementRepository.findRetiredTreatments(experimentId));
        List<BlockCapacityView> blocks = new ArrayList<>();
        for (BlockCapacityStat stat : experimentRepository.blockCapacityStats(experimentId)) {
            blocks.add(new BlockCapacityView(stat.blockNo(), stat.totalSeats(),
                    stat.occupiedSeats(), stat.sealedSeats(), stat.availableSeats()));
        }
        List<CodeCapacityView> codes = new ArrayList<>();
        for (TreatmentCapacityStat stat : experimentRepository.treatmentCapacityStats(experimentId)) {
            boolean isRetired = retired.contains(stat.treatment());
            codes.add(new CodeCapacityView(stat.treatment(),
                    isRetired ? "RETIRED" : "ACTIVE",
                    stat.totalSeats(), stat.allocatedCount(), stat.sealedCount(),
                    isRetired ? 0 : stat.availableSeats()));
        }
        return new CapacityView(experimentId, experiment.blockCount(), blocks, codes);
    }

    private RetirementView toSummary(RetirementRow row) {
        List<RetiredCodeView> codes = new ArrayList<>();
        for (RetiredCodeRow code : retirementRepository.findRetiredCodes(row.id())) {
            codes.add(new RetiredCodeView(code.treatment(), code.allocatedCount(),
                    code.sealedCount(), code.quotaBefore(), code.quotaAfter()));
        }
        return new RetirementView(row.id(), row.experimentId(), row.reason(),
                row.actorId(), row.createdAt(), codes);
    }

    private ExperimentRow mustExist(String experimentId) {
        ExperimentRow row = experimentRepository.findById(experimentId);
        if (row == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        return row;
    }
}
