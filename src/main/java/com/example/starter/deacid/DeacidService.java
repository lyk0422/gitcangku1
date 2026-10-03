package com.example.starter.deacid;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 脱酸业务链：档案登记、批次入组、处理读数、质检封存、归还释放。
 * pH 允许范围 6.5 至 8.5，处理后不得低于处理前；封存时整批校验，任一页不合格即整批退回。
 */
@Service
public class DeacidService {

    static final BigDecimal PH_MIN = new BigDecimal("6.5");
    static final BigDecimal PH_MAX = new BigDecimal("8.5");

    private final DeacidRepository repository;

    public DeacidService(DeacidRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public long registerPage(String collectionNo) {
        try {
            return repository.insertPage(collectionNo);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "馆藏号已登记: " + collectionNo);
        }
    }

    @Transactional
    public long createBatch(String batchNo) {
        try {
            return repository.insertBatch(batchNo);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "批次编号已存在: " + batchNo);
        }
    }

    /** 入组：requestId 同参重放返回原状态，异参 409；并发双入组由 page_occupancy 唯一约束拦截。 */
    @Transactional
    public EnrollResult enroll(long batchId, String requestId, String collectionNo) {
        long pageId = repository.findPageId(collectionNo)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "档案页不存在: " + collectionNo));
        Optional<long[]> seen = repository.findEnrollRequest(requestId);
        if (seen.isPresent()) {
            long[] original = seen.get();
            if (original[0] == batchId && original[1] == pageId) {
                return new EnrollResult("ENROLLED", batchId, pageId);
            }
            throw new BusinessException(HttpStatus.CONFLICT, "requestId 已用于其他入组参数: " + requestId);
        }
        String batchStatus = repository.lockBatchStatus(batchId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId));
        if (!"IN_PROGRESS".equals(batchStatus)) {
            throw new BusinessException(HttpStatus.CONFLICT, "批次已封存，只读: " + batchId);
        }
        if (repository.memberExists(batchId, pageId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "档案页已在该批次中: " + collectionNo);
        }
        try {
            repository.insertOccupancy(pageId, batchId);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "档案页正被其他进行中批次占用: " + collectionNo);
        }
        repository.insertMember(batchId, pageId);
        repository.updatePageStatus(pageId, "IN_PROCESS");
        try {
            repository.insertEnrollRequest(requestId, batchId, pageId, "ENROLLED");
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "requestId 已用于其他入组参数: " + requestId);
        }
        return new EnrollResult("ENROLLED", batchId, pageId);
    }

    /** 追加处理轮次：旧轮次只读，已封存批次拒绝写入。 */
    @Transactional
    public TreatmentRoundView addTreatment(long batchId, long pageId, BigDecimal phBefore,
                                           BigDecimal phAfter, String reagentBatchNo) {
        String batchStatus = repository.lockBatchStatus(batchId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId));
        if (!"IN_PROGRESS".equals(batchStatus)) {
            throw new BusinessException(HttpStatus.CONFLICT, "批次已封存，只读: " + batchId);
        }
        if (!repository.memberExists(batchId, pageId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "档案页不在该批次中: " + pageId);
        }
        int roundNo = repository.nextRoundNo(batchId, pageId);
        repository.insertTreatment(batchId, pageId, roundNo, phBefore, phAfter, reagentBatchNo);
        return repository.latestTreatment(batchId, pageId)
                .orElseThrow(() -> new IllegalStateException("treatment insert not visible"));
    }

    /** 封存：任一页不合格则整批退回（outcome=REJECTED）并记录问题页，不可只封存合格子集。 */
    @Transactional
    public SealResult seal(long batchId, String requestId) {
        String batchStatus = repository.lockBatchStatus(batchId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId));
        Optional<SealResult> seen = repository.findSealRequest(requestId, batchId);
        if (seen.isPresent()) {
            return seen.get();
        }
        Optional<Long> bound = repository.batchIdOfSealRequest(requestId);
        if (bound.isPresent() && bound.get() != batchId) {
            throw new BusinessException(HttpStatus.CONFLICT, "requestId 已用于其他封存参数: " + requestId);
        }
        if ("SEALED".equals(batchStatus)) {
            throw new BusinessException(HttpStatus.CONFLICT, "批次已封存，只读: " + batchId);
        }
        List<ProblemPage> problems = validate(batchId);
        if (!problems.isEmpty()) {
            String detail = serializeProblems(problems);
            repository.insertRejection(batchId, repository.nextRejectionAttempt(batchId), detail);
            repository.insertSealRequest(requestId, batchId, "REJECTED", detail);
            return new SealResult("REJECTED", problems);
        }
        repository.sealBatch(batchId);
        for (long pageId : repository.memberPageIds(batchId)) {
            repository.deleteOccupancy(pageId);
            repository.updatePageStatus(pageId, "AVAILABLE");
        }
        repository.insertSealRequest(requestId, batchId, "SEALED", "");
        return new SealResult("SEALED", List.of());
    }

    private List<ProblemPage> validate(long batchId) {
        List<ProblemPage> problems = new ArrayList<>();
        for (long pageId : repository.memberPageIds(batchId)) {
            String collectionNo = repository.collectionNoOf(pageId);
            Optional<TreatmentRoundView> latest = repository.latestTreatment(batchId, pageId);
            if (latest.isEmpty()) {
                problems.add(new ProblemPage(collectionNo, "缺少处理读数"));
                continue;
            }
            TreatmentRoundView round = latest.get();
            if (round.phAfter().compareTo(PH_MIN) < 0 || round.phAfter().compareTo(PH_MAX) > 0) {
                problems.add(new ProblemPage(collectionNo,
                        "处理后pH超范围: " + round.phAfter() + "，允许 6.5 至 8.5"));
            }
            if (round.phAfter().compareTo(round.phBefore()) < 0) {
                problems.add(new ProblemPage(collectionNo,
                        "处理后pH低于处理前: " + round.phAfter() + " < " + round.phBefore()));
            }
            Optional<Long> occupant = repository.occupancyBatchId(pageId);
            if (occupant.isPresent() && occupant.get() != batchId) {
                problems.add(new ProblemPage(collectionNo, "跨批次占用: 正被批次 " + occupant.get() + " 占用"));
            }
        }
        return problems;
    }

    /** 归还档案页：释放处理占用；已封存批次只读。与封存并发时按事务提交顺序确定归属。 */
    @Transactional
    public void releasePage(long batchId, long pageId) {
        String batchStatus = repository.lockBatchStatus(batchId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId));
        if (!"IN_PROGRESS".equals(batchStatus)) {
            throw new BusinessException(HttpStatus.CONFLICT, "批次已封存，只读: " + batchId);
        }
        if (!repository.memberExists(batchId, pageId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "档案页不在该批次中: " + pageId);
        }
        repository.deleteMember(batchId, pageId);
        repository.deleteOccupancy(pageId);
        repository.updatePageStatus(pageId, "AVAILABLE");
    }

    @Transactional(readOnly = true)
    public List<TreatmentRoundView> pageTreatments(String collectionNo) {
        long pageId = repository.findPageId(collectionNo)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "档案页不存在: " + collectionNo));
        return repository.treatmentsOfPage(pageId);
    }

    @Transactional(readOnly = true)
    public BatchView batchView(long batchId) {
        BatchView view = repository.batchView(batchId);
        if (view == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId);
        }
        return view;
    }

    static String serializeProblems(List<ProblemPage> problems) {
        StringBuilder sb = new StringBuilder();
        for (ProblemPage p : problems) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(p.collectionNo()).append('\t').append(p.reason());
        }
        return sb.toString();
    }

    /** 反序列化问题页列表，空串返回空列表。 */
    public static List<ProblemPage> deserializeProblems(String detail) {
        List<ProblemPage> problems = new ArrayList<>();
        if (detail == null || detail.isEmpty()) {
            return problems;
        }
        for (String line : detail.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                problems.add(new ProblemPage(line.substring(0, tab), line.substring(tab + 1)));
            }
        }
        return problems;
    }
}