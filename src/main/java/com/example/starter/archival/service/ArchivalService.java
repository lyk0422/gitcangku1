package com.example.starter.archival.service;

import com.example.starter.archival.domain.ArchivePage;
import com.example.starter.archival.domain.BatchMember;
import com.example.starter.archival.domain.BatchRejection;
import com.example.starter.archival.domain.BatchStatus;
import com.example.starter.archival.domain.DeacidificationBatch;
import com.example.starter.archival.domain.MemberStatus;
import com.example.starter.archival.domain.PageOccupation;
import com.example.starter.archival.domain.TreatmentRound;
import com.example.starter.archival.repo.BatchRepository;
import com.example.starter.archival.repo.IdempotentRequestRepository;
import com.example.starter.archival.repo.MemberRepository;
import com.example.starter.archival.repo.OccupationRepository;
import com.example.starter.archival.repo.PageRepository;
import com.example.starter.archival.repo.RejectionRepository;
import com.example.starter.archival.repo.RoundRepository;
import com.example.starter.archival.support.AppClock;
import com.example.starter.archival.web.ApiConflictException;
import com.example.starter.archival.web.ApiNotFoundException;
import com.example.starter.archival.web.dto.BatchResponse;
import com.example.starter.archival.web.dto.EnrollResult;
import com.example.starter.archival.web.dto.EnrollRequest;
import com.example.starter.archival.web.dto.PageHistoryResponse;
import com.example.starter.archival.web.dto.PageResponse;
import com.example.starter.archival.web.dto.ReadingRequest;
import com.example.starter.archival.web.dto.ReadingResponse;
import com.example.starter.archival.web.dto.RejectRequest;
import com.example.starter.archival.web.dto.RejectResult;
import com.example.starter.archival.web.dto.ReleaseRequest;
import com.example.starter.archival.web.dto.ReleaseResult;
import com.example.starter.archival.web.dto.RoundView;
import com.example.starter.archival.web.dto.SealProblem;
import com.example.starter.archival.web.dto.SealRequest;
import com.example.starter.archival.web.dto.SealResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 档案脱酸流程核心服务：登记、建批、入组、读数、质检退回、归还调拨、封存与历史查询。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>同一页同一时刻只能处于一个进行中批次——page_occupation 以 page_id 为主键，数据库唯一约束兜底；</li>
 *   <li>封存按整批校验，任一在组页缺读数、pH 超范围（6.50-8.50）或后值低于前值即整批拒绝，列出全部问题页；</li>
 *   <li>处理轮次与退回理由只追加，旧读数/旧理由永不覆盖；修正方式是追加合规复处理轮次；</li>
 *   <li>已封存批次只读；封存成功释放全部占用，归还（调拨）与置 RELEASED 同一事务完成；</li>
 *   <li>入组/封存以 requestId 幂等：同参重放首次结果，异参 409。</li>
 * </ul>
 */
@Service
public class ArchivalService {

    /** pH 允许下限（无量纲）。 */
    static final BigDecimal PH_MIN = new BigDecimal("6.50");
    /** pH 允许上限（无量纲）。 */
    static final BigDecimal PH_MAX = new BigDecimal("8.50");

    private static final String OP_ENROLL = "ENROLL";
    private static final String OP_SEAL = "SEAL";

    private final PageRepository pageRepository;
    private final BatchRepository batchRepository;
    private final MemberRepository memberRepository;
    private final OccupationRepository occupationRepository;
    private final RoundRepository roundRepository;
    private final RejectionRepository rejectionRepository;
    private final IdempotentRequestRepository idempotentRequestRepository;
    private final AppClock clock;
    private final ObjectMapper objectMapper;

    public ArchivalService(PageRepository pageRepository,
                           BatchRepository batchRepository,
                           MemberRepository memberRepository,
                           OccupationRepository occupationRepository,
                           RoundRepository roundRepository,
                           RejectionRepository rejectionRepository,
                           IdempotentRequestRepository idempotentRequestRepository,
                           AppClock clock,
                           ObjectMapper objectMapper) {
        this.pageRepository = pageRepository;
        this.batchRepository = batchRepository;
        this.memberRepository = memberRepository;
        this.occupationRepository = occupationRepository;
        this.roundRepository = roundRepository;
        this.rejectionRepository = rejectionRepository;
        this.idempotentRequestRepository = idempotentRequestRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** 登记档案页，馆藏号重复时 409。 */
    @Transactional
    public PageResponse registerPage(String accessionNo) {
        java.time.LocalDateTime createdAt = clock.now();
        try {
            pageRepository.insert(accessionNo, createdAt);
        } catch (DuplicateKeyException e) {
            throw new ApiConflictException("馆藏号已存在: " + accessionNo);
        }
        return new PageResponse(accessionNo, createdAt);
    }

    /** 建脱酸批次，批次号重复时 409。 */
    @Transactional
    public BatchResponse createBatch(String batchNo) {
        java.time.LocalDateTime createdAt = clock.now();
        try {
            batchRepository.insert(batchNo, createdAt);
        } catch (DuplicateKeyException e) {
            throw new ApiConflictException("批次号已存在: " + batchNo);
        }
        DeacidificationBatch batch = batchRepository.findByBatchNo(batchNo).orElseThrow();
        return toBatchResponse(batch);
    }

    /**
     * 入组：加批次行锁与封存/读数/退回串行化；占用表主键冲突说明并发双入组或跨批次占用。
     */
    @Transactional
    public EnrollResult enroll(EnrollRequest request) {
        String fingerprint = OP_ENROLL + "|" + request.batchNo() + "|" + request.accessionNo();
        Optional<EnrollResult> replayed = replayIfSame(request.requestId(), OP_ENROLL,
                fingerprint, EnrollResult.class);
        if (replayed.isPresent()) {
            EnrollResult first = replayed.get();
            return new EnrollResult(first.batchNo(), first.accessionNo(),
                    first.reactivated(), true);
        }

        DeacidificationBatch batch = lockBatch(request.batchNo());
        if (batch.status() == BatchStatus.SEALED) {
            throw new ApiConflictException("批次已封存，只读不可入组: " + request.batchNo());
        }
        ArchivePage page = requirePage(request.accessionNo());

        BatchMember member = memberRepository.find(batch.id(), page.id()).orElse(null);
        if (member != null && member.memberStatus() == MemberStatus.ACTIVE) {
            throw new ApiConflictException(
                    "页面已在该进行中批次内，禁止重复入组: " + request.accessionNo());
        }

        PageOccupation occupation = occupationRepository.findByPage(page.id()).orElse(null);
        if (occupation != null && occupation.batchId() != batch.id()) {
            throw new ApiConflictException(
                    "页面正被其他进行中批次占用，不能跨批次入组: " + request.accessionNo());
        }

        boolean reactivated;
        if (member == null) {
            try {
                memberRepository.insert(batch.id(), page.id(), clock.now(), request.requestId());
            } catch (DuplicateKeyException e) {
                throw new ApiConflictException("并发入组冲突，页面已被加入该批次: " + request.accessionNo());
            }
            reactivated = false;
        } else {
            int rows = memberRepository.reactivate(batch.id(), page.id(), clock.now(), request.requestId());
            if (rows == 0) {
                throw new ApiConflictException("并发入组冲突，页面在组状态已变化: " + request.accessionNo());
            }
            reactivated = true;
        }

        try {
            occupationRepository.tryAcquire(page.id(), batch.id(), clock.now());
        } catch (DuplicateKeyException e) {
            // 并发双入组：唯一约束阻止双重占用，事务回滚；客户端可用原 requestId 重试得到确定结果。
            throw new ApiConflictException(
                    "页面已被其他进行中批次占用（并发双入组被唯一约束阻止）: " + request.accessionNo());
        }

        EnrollResult result = new EnrollResult(request.batchNo(), request.accessionNo(),
                reactivated, false);
        saveIdempotent(request.requestId(), OP_ENROLL, fingerprint, result, batch.id());
        return result;
    }

    /**
     * 追加处理读数（新轮次）。读数允许暂缺或超范围落库（如后读数 6.40），由封存统一校验；
     * 旧轮次永不修改。
     */
    @Transactional
    public ReadingResponse recordReading(ReadingRequest request) {
        DeacidificationBatch batch = lockBatch(request.batchNo());
        if (batch.status() == BatchStatus.SEALED) {
            throw new ApiConflictException("批次已封存，只读不可登记读数: " + request.batchNo());
        }
        ArchivePage page = requirePage(request.accessionNo());
        BatchMember member = memberRepository.find(batch.id(), page.id())
                .orElseThrow(() -> new ApiConflictException(
                        "页面未入组该批次，不能登记读数: " + request.accessionNo()));
        if (member.memberStatus() != MemberStatus.ACTIVE) {
            throw new ApiConflictException(
                    "页面已从该批次归还，不能再登记读数: " + request.accessionNo());
        }

        List<TreatmentRound> existing = roundRepository.findByBatchAndPage(batch.id(), page.id());
        int roundNo = existing.stream().mapToInt(TreatmentRound::roundNo).max().orElse(0) + 1;
        BigDecimal phBefore = normalize(request.phBefore());
        BigDecimal phAfter = normalize(request.phAfter());
        java.time.LocalDateTime createdAt = clock.now();
        try {
            roundRepository.insert(batch.id(), page.id(), roundNo,
                    phBefore, phAfter, request.reagentLot(), createdAt);
        } catch (DuplicateKeyException e) {
            throw new ApiConflictException("并发登记读数冲突，轮次序号冲突，请重试: " + request.accessionNo());
        }
        return new ReadingResponse(request.batchNo(), request.accessionNo(), roundNo,
                phBefore, phAfter, request.reagentLot(), createdAt);
    }

    /**
     * 质检退回：批次置 REJECTED 并追加退回理由；历史理由与历史读数保留，之后可追加新轮次复处理。
     */
    @Transactional
    public RejectResult reject(RejectRequest request) {
        DeacidificationBatch batch = lockBatch(request.batchNo());
        if (batch.status() == BatchStatus.SEALED) {
            throw new ApiConflictException("批次已封存，只读不可退回: " + request.batchNo());
        }
        rejectionRepository.insert(batch.id(), request.reason(), clock.now());
        batchRepository.updateStatus(batch.id(), BatchStatus.REJECTED, null);
        List<String> reasons = rejectionRepository.findByBatch(batch.id()).stream()
                .map(BatchRejection::reason).toList();
        return new RejectResult(request.batchNo(), BatchStatus.REJECTED.name(),
                reasons, clock.now());
    }

    /**
     * 从未封存批次归还（调拨出库）档案页：在组行置 RELEASED 与删除处理占用同一事务完成，
     * 不可能出现“已归还但仍被进行中占用”。与封存并发时按批次行锁的提交顺序确定归属。
     */
    @Transactional
    public ReleaseResult release(ReleaseRequest request) {
        DeacidificationBatch batch = lockBatch(request.batchNo());
        if (batch.status() == BatchStatus.SEALED) {
            throw new ApiConflictException("批次已封存只读，封存批次页面不能调拨: " + request.batchNo());
        }
        ArchivePage page = requirePage(request.accessionNo());
        BatchMember member = memberRepository.find(batch.id(), page.id())
                .orElseThrow(() -> new ApiNotFoundException(
                        "页面未入组该批次: " + request.accessionNo()));
        if (member.memberStatus() == MemberStatus.RELEASED) {
            throw new ApiConflictException("页面已从该批次归还，不能重复归还: " + request.accessionNo());
        }
        int marked = memberRepository.markReleased(batch.id(), page.id(), clock.now());
        if (marked == 0) {
            throw new ApiConflictException("并发调拨冲突，页面在组状态已变化: " + request.accessionNo());
        }
        int deleted = occupationRepository.release(page.id(), batch.id());
        if (deleted == 0) {
            // 占用缺失属于数据不一致：绝不允许带着 RELEASED 状态留下残留占用，直接回滚暴露问题。
            throw new IllegalStateException("归还时未找到页面处理占用，数据不一致: " + request.accessionNo());
        }
        return new ReleaseResult(request.batchNo(), request.accessionNo());
    }

    /**
     * 封存整批：对每个在组页取最新轮次校验；任一问题即整批拒绝（不封存合格子集），
     * 批次置 REJECTED、占用保留以便复处理；全部合格才置 SEALED 并释放全部占用。
     * requestId 同参重放首次结果（含失败结果），修复后重新封存须使用新 requestId。
     */
    @Transactional
    public SealResult seal(SealRequest request) {
        String fingerprint = OP_SEAL + "|" + request.batchNo();
        Optional<SealResult> replayed = replayIfSame(request.requestId(), OP_SEAL,
                fingerprint, SealResult.class);
        if (replayed.isPresent()) {
            SealResult first = replayed.get();
            return new SealResult(first.batchNo(), first.sealed(), first.problems(),
                    first.sealedAt(), true);
        }

        DeacidificationBatch batch = lockBatch(request.batchNo());
        if (batch.status() == BatchStatus.SEALED) {
            throw new ApiConflictException("批次已封存，禁止重复封存: " + request.batchNo());
        }

        List<BatchMember> activeMembers = memberRepository.findActiveByBatch(batch.id());
        if (activeMembers.isEmpty()) {
            // 页面已全部归还（调拨）的批次按提交顺序不再属于封存方，禁止封存空批。
            throw new ApiConflictException("批次没有在组页面，不能封存（可能与归还调拨并发）: " + request.batchNo());
        }
        List<SealProblem> problems = new ArrayList<>();
        for (BatchMember member : activeMembers) {
            ArchivePage page = pageRepository.findById(member.pageId()).orElseThrow();
            problems.addAll(validateMemberForSeal(batch.id(), page));
        }

        SealResult result;
        if (!problems.isEmpty()) {
            // 整批拒绝：保留读数与占用，批次转 REJECTED，可追加轮次后用新 requestId 再封存。
            batchRepository.updateStatus(batch.id(), BatchStatus.REJECTED, null);
            result = new SealResult(request.batchNo(), false, List.copyOf(problems), null, false);
        } else {
            batchRepository.updateStatus(batch.id(), BatchStatus.SEALED, clock.now());
            occupationRepository.releaseByBatch(batch.id());
            result = new SealResult(request.batchNo(), true, List.of(), clock.now(), false);
        }
        saveIdempotent(request.requestId(), OP_SEAL, fingerprint, result, batch.id());
        return result;
    }

    /** 查询批次当前状态。 */
    @Transactional(readOnly = true)
    public BatchResponse getBatch(String batchNo) {
        return toBatchResponse(requireBatch(batchNo));
    }

    /** 查询档案页全部处理轮次：批次状态、轮次、pH 前后值、药剂来源。 */
    @Transactional(readOnly = true)
    public PageHistoryResponse getHistory(String accessionNo) {
        ArchivePage page = requirePage(accessionNo);
        List<TreatmentRound> rounds = roundRepository.findByPage(page.id());
        Map<Long, DeacidificationBatch> batchCache = new LinkedHashMap<>();
        List<RoundView> views = new ArrayList<>();
        for (TreatmentRound round : rounds) {
            DeacidificationBatch batch = batchCache.computeIfAbsent(round.batchId(),
                    id -> batchRepository.findById(id)
                            .orElseThrow(() -> new IllegalStateException("处理轮次引用的批次不存在: " + id)));
            views.add(new RoundView(batch.batchNo(), batch.status().name(), round.roundNo(),
                    round.phBefore(), round.phAfter(), round.reagentLot(), round.createdAt()));
        }
        return new PageHistoryResponse(accessionNo, views);
    }

    // ---------------------------------------------------------------------
    // 内部辅助
    // ---------------------------------------------------------------------

    /**
     * 校验单个在组页是否满足封存条件，返回该页全部问题（每页可能多条）。
     * 取该页在本批次的最新轮次作为最终处理结果；旧轮次保留但不阻断（修正靠追加合规轮次）。
     * 封存条件：前后读数齐全；前后 pH 均在允许窗口 6.50-8.50；处理后不低于处理前。
     * 6.40 失败后的合规复处理轮次以窗口内读数登记（如前 6.50、后 7.00），第一轮原值原样保留。
     */
    private List<SealProblem> validateMemberForSeal(long batchId, ArchivePage page) {
        List<TreatmentRound> rounds = roundRepository.findByBatchAndPage(batchId, page.id());
        List<SealProblem> problems = new ArrayList<>();
        if (rounds.isEmpty()) {
            problems.add(new SealProblem(page.accessionNo(), "缺少处理轮次：无任何处理前后读数"));
            return problems;
        }
        TreatmentRound latest = rounds.get(rounds.size() - 1);
        BigDecimal before = latest.phBefore();
        BigDecimal after = latest.phAfter();
        if (before == null) {
            problems.add(new SealProblem(page.accessionNo(), "处理前读数缺失（第" + latest.roundNo() + "轮）"));
        } else if (outOfRange(before)) {
            problems.add(new SealProblem(page.accessionNo(),
                    "处理前pH " + before.toPlainString() + " 超出允许范围 6.50-8.50（第" + latest.roundNo() + "轮）"));
        }
        if (after == null) {
            problems.add(new SealProblem(page.accessionNo(), "处理后读数缺失（第" + latest.roundNo() + "轮）"));
        } else if (outOfRange(after)) {
            problems.add(new SealProblem(page.accessionNo(),
                    "处理后pH " + after.toPlainString() + " 超出允许范围 6.50-8.50（第" + latest.roundNo() + "轮）"));
        }
        if (before != null && after != null && after.compareTo(before) < 0) {
            problems.add(new SealProblem(page.accessionNo(),
                    "处理后pH " + after.toPlainString() + " 低于处理前pH " + before.toPlainString()
                            + "（第" + latest.roundNo() + "轮）"));
        }
        return problems;
    }

    private boolean outOfRange(BigDecimal ph) {
        return ph.compareTo(PH_MIN) < 0 || ph.compareTo(PH_MAX) > 0;
    }

    private DeacidificationBatch lockBatch(String batchNo) {
        return batchRepository.findByBatchNoForUpdate(batchNo)
                .orElseThrow(() -> new ApiNotFoundException("批次不存在: " + batchNo));
    }

    private DeacidificationBatch requireBatch(String batchNo) {
        return batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> new ApiNotFoundException("批次不存在: " + batchNo));
    }

    private ArchivePage requirePage(String accessionNo) {
        return pageRepository.findByAccessionNo(accessionNo)
                .orElseThrow(() -> new ApiNotFoundException("档案页不存在: " + accessionNo));
    }

    private BatchResponse toBatchResponse(DeacidificationBatch batch) {
        return new BatchResponse(batch.batchNo(), batch.status(), batch.sealedAt(), batch.createdAt());
    }

    /**
     * 同 requestId 重放：操作或参数指纹不一致返回 409；一致则反序列化首次结果。
     */
    private <T> Optional<T> replayIfSame(String requestId, String operation,
                                         String fingerprint, Class<T> resultType) {
        return idempotentRequestRepository.find(requestId).map(record -> {
            if (!record.operation().equals(operation) || !record.fingerprint().equals(fingerprint)) {
                throw new ApiConflictException(
                        "requestId 已用于不同参数的请求，异参重放冲突: " + requestId);
            }
            try {
                return objectMapper.readValue(record.resultJson(), resultType);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("幂等结果反序列化失败: " + requestId, e);
            }
        });
    }

    private void saveIdempotent(String requestId, String operation, String fingerprint,
                                Object result, Long batchId) {
        try {
            String json = objectMapper.writeValueAsString(result);
            idempotentRequestRepository.insert(requestId, operation, fingerprint, json,
                    batchId, clock.now());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等结果序列化失败: " + requestId, e);
        } catch (DuplicateKeyException e) {
            // 并发同 requestId：对方事务先提交，本事务回滚；客户端重放即可，不允许异参覆盖。
            throw new ApiConflictException("requestId 并发冲突，请使用原参数重放: " + requestId);
        }
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }
}
