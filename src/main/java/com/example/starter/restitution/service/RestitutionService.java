package com.example.starter.restitution.service;

import com.example.starter.restitution.data.RestitutionRepository;
import com.example.starter.restitution.domain.ApprovalRow;
import com.example.starter.restitution.domain.CaseRow;
import com.example.starter.restitution.domain.ClaimRow;
import com.example.starter.restitution.domain.EvidenceRow;
import com.example.starter.restitution.domain.FrozenApprovalRow;
import com.example.starter.restitution.domain.FrozenClaimRow;
import com.example.starter.restitution.domain.FrozenEvidenceRow;
import com.example.starter.restitution.domain.FrozenItemRow;
import com.example.starter.restitution.domain.TimeService;
import com.example.starter.restitution.dto.AddEvidenceRequest;
import com.example.starter.restitution.dto.ApprovalView;
import com.example.starter.restitution.dto.CaseDetailResponse;
import com.example.starter.restitution.dto.CaseResponse;
import com.example.starter.restitution.dto.ClaimDetail;
import com.example.starter.restitution.dto.CreateCaseRequest;
import com.example.starter.restitution.dto.DecisionRequest;
import com.example.starter.restitution.dto.DecisionResponse;
import com.example.starter.restitution.dto.EvidenceView;
import com.example.starter.restitution.dto.FrozenItemView;
import com.example.starter.restitution.dto.RegisterClaimRequest;
import com.example.starter.restitution.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 藏品归还裁决领域服务：案件/主张/证据/批准/裁决全部写规则与版本语义集中于此。
 * 写方法均在 {@link IdempotentExecutor} 的事务内调用，先对案件行加 FOR UPDATE 行锁。
 */
@Service
public class RestitutionService {

    private static final int MAX_EVIDENCE_PER_CLAIM = 20;
    private static final int REQUIRED_REVIEWERS = 2;

    private final RestitutionRepository repository;
    private final TimeService timeService;

    public RestitutionService(RestitutionRepository repository, TimeService timeService) {
        this.repository = repository;
        this.timeService = timeService;
    }

    @Transactional
    public CaseResponse createCase(CreateCaseRequest request) {
        List<String> items = normalizedUnique(request.items(), "items");
        String caseId = UUID.randomUUID().toString().replace("-", "");
        long now = timeService.nowMillis();
        repository.insertCase(caseId, now);
        for (int i = 0; i < items.size(); i++) {
            repository.insertCaseItem(caseId, items.get(i), i);
        }
        return new CaseResponse(caseId, CaseRow.OPEN, 1, items);
    }

    @Transactional
    public CaseResponse registerClaim(String caseId, RegisterClaimRequest request) {
        CaseRow caseRow = lockOpenCase(caseId);
        List<String> caseItems = repository.findCaseItems(caseId);

        List<String> subset = normalizedUnique(request.items(), "items");
        if (!caseItems.containsAll(subset)) {
            throw badRequest(Map.of("error", "ITEM_OUT_OF_CASE",
                    "unknownItems", subset.stream().filter(i -> !caseItems.contains(i)).toList()));
        }
        if (repository.findClaim(caseId, request.claimKey()) != null) {
            throw conflict(Map.of("error", "CLAIM_KEY_EXISTS", "claimKey", request.claimKey()));
        }

        long claimId = repository.insertClaim(caseId, request.claimKey(),
                request.applicant(), request.statement(), timeService.nowMillis());
        for (int i = 0; i < subset.size(); i++) {
            repository.insertClaimItem(claimId, subset.get(i), i);
        }
        long newVersion = bumpCaseVersion(caseRow);
        return new CaseResponse(caseId, CaseRow.OPEN, newVersion, caseItems);
    }

    @Transactional
    public CaseResponse withdrawClaim(String caseId, String claimKey) {
        CaseRow caseRow = lockOpenCase(caseId);
        ClaimRow claim = requireClaim(caseId, claimKey);
        if (claim.withdrawn()) {
            throw conflict(Map.of("error", "CLAIM_ALREADY_WITHDRAWN", "claimKey", claimKey));
        }
        if (repository.markClaimWithdrawn(claim.id(), timeService.nowMillis()) == 0) {
            throw conflict(Map.of("error", "CLAIM_ALREADY_WITHDRAWN", "claimKey", claimKey));
        }
        long newVersion = bumpCaseVersion(caseRow);
        return new CaseResponse(caseId, CaseRow.OPEN, newVersion, repository.findCaseItems(caseId));
    }

    @Transactional
    public CaseResponse addEvidence(String caseId, String claimKey, AddEvidenceRequest request) {
        CaseRow caseRow = lockOpenCase(caseId);
        ClaimRow claim = requireActiveClaim(caseId, claimKey);

        if (repository.countEvidenceForClaim(claim.id()) >= MAX_EVIDENCE_PER_CLAIM) {
            throw conflict(Map.of("error", "EVIDENCE_LIMIT_EXCEEDED",
                    "claimKey", claimKey, "limit", MAX_EVIDENCE_PER_CLAIM));
        }
        if (repository.findEvidenceByCaseKey(caseId, request.evidenceKey()) != null) {
            throw conflict(Map.of("error", "EVIDENCE_KEY_EXISTS",
                    "evidenceKey", request.evidenceKey()));
        }

        long newEvidenceVersion = claim.version() + 1;
        repository.insertEvidence(caseId, claim.id(), request.evidenceKey(),
                request.summary(), newEvidenceVersion, timeService.nowMillis());
        repository.updateClaimEvidenceVersion(claim.id(), newEvidenceVersion);
        long newCaseVersion = bumpCaseVersion(caseRow);
        return new CaseResponse(caseId, CaseRow.OPEN, newCaseVersion, repository.findCaseItems(caseId));
    }

    @Transactional
    public CaseResponse revokeEvidence(String caseId, String claimKey, String evidenceKey) {
        CaseRow caseRow = lockOpenCase(caseId);
        ClaimRow claim = requireActiveClaim(caseId, claimKey);
        EvidenceRow evidence = repository.findEvidenceByCaseKey(caseId, evidenceKey);
        if (evidence == null || evidence.claimId() != claim.id()) {
            throw notFound(Map.of("error", "EVIDENCE_NOT_FOUND", "evidenceKey", evidenceKey));
        }
        if (!evidence.active()) {
            throw conflict(Map.of("error", "EVIDENCE_ALREADY_REVOKED", "evidenceKey", evidenceKey));
        }
        if (repository.revokeEvidence(evidence.id(), timeService.nowMillis()) == 0) {
            throw conflict(Map.of("error", "EVIDENCE_ALREADY_REVOKED", "evidenceKey", evidenceKey));
        }
        repository.updateClaimEvidenceVersion(claim.id(), claim.version() + 1);
        long newCaseVersion = bumpCaseVersion(caseRow);
        return new CaseResponse(caseId, CaseRow.OPEN, newCaseVersion, repository.findCaseItems(caseId));
    }

    @Transactional
    public CaseResponse approve(String caseId, String claimKey, String reviewer) {
        CaseRow caseRow = lockOpenCase(caseId);
        ClaimRow claim = requireActiveClaim(caseId, claimKey);

        if (reviewer.equals(claim.applicant())) {
            throw new ApiException(403, Map.of("error", "SELF_APPROVAL_FORBIDDEN",
                    "claimKey", claimKey, "reviewer", reviewer));
        }

        long activeEvidence = repository.findEvidencesByClaim(claim.id()).stream()
                .filter(EvidenceRow::active)
                .count();
        if (activeEvidence == 0) {
            throw conflict(Map.of("error", "NO_ACTIVE_EVIDENCE", "claimKey", claimKey));
        }

        // 同一人同版本重复批准：不计数、不加版本，原样返回案件视图
        if (repository.findApproval(claim.id(), claim.version(), reviewer) != null) {
            return new CaseResponse(caseId, CaseRow.OPEN, caseRow.version(),
                    repository.findCaseItems(caseId));
        }

        long currentVersionApprovals = repository.findApprovalsByClaim(claim.id()).stream()
                .filter(a -> a.evidenceVersion() == claim.version())
                .map(ApprovalRow::reviewer)
                .distinct()
                .count();

        repository.insertApproval(caseId, claim.id(), claim.version(),
                reviewer, timeService.nowMillis());

        long responseVersion = caseRow.version();
        if (currentVersionApprovals == 0) {
            // 该证据版本的首次批准：案件版本加一
            responseVersion = bumpCaseVersion(caseRow);
        }
        return new CaseResponse(caseId, CaseRow.OPEN, responseVersion, repository.findCaseItems(caseId));
    }

    @Transactional
    public DecisionResponse decide(String caseId, DecisionRequest request) {
        CaseRow caseRow = lockCaseOr404(caseId);
        if (CaseRow.DECIDED.equals(caseRow.status())) {
            throw conflict(Map.of("error", "CASE_ALREADY_DECIDED", "caseId", caseId));
        }
        if (!request.expectedVersion().equals(caseRow.version())) {
            throw new ApiException(409, Map.of(
                    "error", "VERSION_CONFLICT",
                    "expectedVersion", request.expectedVersion(),
                    "currentVersion", caseRow.version()));
        }

        List<String> selectedKeys = normalizedUnique(request.claimKeys(), "claimKeys");
        List<String> caseItems = repository.findCaseItems(caseId);
        List<ClaimRow> allClaims = repository.findClaimsByCase(caseId);
        Map<String, ClaimRow> claimByKey = allClaims.stream()
                .collect(Collectors.toMap(ClaimRow::claimKey, c -> c, (a, b) -> a, LinkedHashMap::new));

        Map<String, List<String>> deficiencies = new LinkedHashMap<>();
        Map<String, List<String>> coverageByClaim = new LinkedHashMap<>();
        for (String key : selectedKeys) {
            List<String> reasons = new ArrayList<>();
            ClaimRow claim = claimByKey.get(key);
            if (claim == null) {
                reasons.add("CLAIM_NOT_FOUND");
            } else if (claim.withdrawn()) {
                reasons.add("WITHDRAWN");
            } else {
                coverageByClaim.put(key, repository.findClaimItems(claim.id()));
                long activeEvidence = repository.findEvidencesByClaim(claim.id()).stream()
                        .filter(EvidenceRow::active).count();
                if (activeEvidence == 0) {
                    reasons.add("NO_ACTIVE_EVIDENCE");
                }
                long distinctReviewers = repository.findApprovalsByClaim(claim.id()).stream()
                        .filter(a -> a.evidenceVersion() == claim.version())
                        .map(ApprovalRow::reviewer)
                        .filter(r -> !r.equals(claim.applicant()))
                        .distinct()
                        .count();
                if (distinctReviewers < REQUIRED_REVIEWERS) {
                    reasons.add("INSUFFICIENT_APPROVALS(current=" + distinctReviewers
                            + ",required=" + REQUIRED_REVIEWERS + ")");
                }
            }
            if (!reasons.isEmpty()) {
                deficiencies.put(key, reasons);
            }
        }

        // 藏品覆盖：恰好覆盖全案，不重复不遗漏
        Map<String, List<String>> itemToClaims = new LinkedHashMap<>();
        for (String item : caseItems) {
            itemToClaims.put(item, new ArrayList<>());
        }
        coverageByClaim.forEach((key, items) -> {
            for (String item : items) {
                itemToClaims.computeIfAbsent(item, k -> new ArrayList<>()).add(key);
            }
        });
        List<String> duplicateItems = itemToClaims.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        List<String> missingItems = itemToClaims.entrySet().stream()
                .filter(e -> e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        if (!deficiencies.isEmpty() || !duplicateItems.isEmpty() || !missingItems.isEmpty()) {
            throw new ApiException(422, Map.of(
                    "error", "DECISION_PRECONDITION_FAILED",
                    "claimDeficiencies", deficiencies,
                    "duplicateItems", duplicateItems,
                    "missingItems", missingItems));
        }

        long now = timeService.nowMillis();
        if (repository.decideCase(caseId, caseRow.version(), now) == 0) {
            // 行锁内理论不会发生；防御性版本冲突
            throw new ApiException(409, Map.of("error", "VERSION_CONFLICT",
                    "expectedVersion", caseRow.version()));
        }

        // 同一事务冻结：中选主张、藏品归属、有效证据、当前版本批准
        for (String key : selectedKeys) {
            ClaimRow claim = claimByKey.get(key);
            repository.insertFrozenClaim(caseId, claim);
            for (EvidenceRow evidence : repository.findEvidencesByClaim(claim.id())) {
                if (evidence.active()) {
                    repository.insertFrozenEvidence(caseId, key, evidence);
                }
            }
            for (ApprovalRow approval : repository.findApprovalsByClaim(claim.id())) {
                if (approval.evidenceVersion() == claim.version()) {
                    repository.insertFrozenApproval(caseId, key, approval);
                }
            }
        }
        for (int i = 0; i < caseItems.size(); i++) {
            String item = caseItems.get(i);
            String owner = itemToClaims.get(item).get(0);
            ClaimRow claim = claimByKey.get(owner);
            repository.insertFrozenItem(caseId, item, owner, claim.applicant(), i);
        }

        List<FrozenItemView> frozenViews = new ArrayList<>();
        for (FrozenItemRow row : repository.findFrozenItems(caseId)) {
            frozenViews.add(new FrozenItemView(row.itemNo(), row.claimKey(), row.applicant()));
        }
        return new DecisionResponse(caseId, CaseRow.DECIDED, caseRow.version() + 1,
                selectedKeys, frozenViews);
    }

    @Transactional(readOnly = true)
    public CaseDetailResponse getCase(String caseId) {
        CaseRow caseRow = repository.findCase(caseId);
        if (caseRow == null) {
            throw notFound(Map.of("error", "CASE_NOT_FOUND", "caseId", caseId));
        }
        List<String> items = repository.findCaseItems(caseId);
        if (CaseRow.DECIDED.equals(caseRow.status())) {
            return buildFrozenView(caseRow, items);
        }
        return buildLiveView(caseRow, items);
    }

    private CaseDetailResponse buildLiveView(CaseRow caseRow, List<String> items) {
        List<ClaimDetail> claims = new ArrayList<>();
        for (ClaimRow claim : repository.findClaimsByCase(caseRow.id())) {
            claims.add(buildLiveClaim(claim));
        }
        return new CaseDetailResponse(caseRow.id(), caseRow.status(), caseRow.version(), items,
                "LIVE", claims, List.of(), null);
    }

    private ClaimDetail buildLiveClaim(ClaimRow claim) {
        List<EvidenceView> evidences = repository.findEvidencesByClaim(claim.id()).stream()
                .map(e -> new EvidenceView(e.evidenceKey(), e.summary(), e.version(), e.active()))
                .toList();
        List<ApprovalView> approvals = repository.findApprovalsByClaim(claim.id()).stream()
                .map(a -> new ApprovalView(a.reviewer(), a.evidenceVersion(), a.createdAt()))
                .toList();
        return new ClaimDetail(claim.claimKey(), claim.applicant(), claim.statement(),
                claim.withdrawn(), claim.version(), repository.findClaimItems(claim.id()),
                evidences, approvals);
    }

    private CaseDetailResponse buildFrozenView(CaseRow caseRow, List<String> items) {
        Map<String, List<FrozenEvidenceRow>> evidencesByClaim = repository.findFrozenEvidences(caseRow.id())
                .stream().collect(Collectors.groupingBy(FrozenEvidenceRow::claimKey, LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, List<FrozenApprovalRow>> approvalsByClaim = repository.findFrozenApprovals(caseRow.id())
                .stream().collect(Collectors.groupingBy(FrozenApprovalRow::claimKey, LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, List<String>> itemsByClaim = repository.findFrozenItems(caseRow.id()).stream()
                .collect(Collectors.groupingBy(FrozenItemRow::claimKey, LinkedHashMap::new,
                        Collectors.mapping(FrozenItemRow::itemNo, Collectors.toList())));

        List<ClaimDetail> claims = new ArrayList<>();
        for (FrozenClaimRow frozen : repository.findFrozenClaims(caseRow.id())) {
            List<FrozenEvidenceRow> frozenEvidences = evidencesByClaim
                    .getOrDefault(frozen.claimKey(), List.of());
            List<FrozenApprovalRow> frozenApprovals = approvalsByClaim
                    .getOrDefault(frozen.claimKey(), List.of());
            List<EvidenceView> evidenceViews = frozenEvidences.stream()
                    .map(e -> new EvidenceView(e.evidenceKey(), e.summary(), e.version(), true))
                    .toList();
            List<ApprovalView> approvalViews = frozenApprovals.stream()
                    .map(a -> new ApprovalView(a.reviewer(), a.evidenceVersion(), a.createdAt()))
                    .toList();
            // 裁决时主张所处证据版本：以冻结批准版本为准，无批准时取证据最大版本
            long evidenceVersion = frozenApprovals.stream()
                    .mapToLong(FrozenApprovalRow::evidenceVersion).findFirst().orElse(
                            frozenEvidences.stream().mapToLong(FrozenEvidenceRow::version).max().orElse(0));
            claims.add(new ClaimDetail(frozen.claimKey(), frozen.applicant(), frozen.statement(),
                    false, evidenceVersion, itemsByClaim.getOrDefault(frozen.claimKey(), List.of()),
                    evidenceViews, approvalViews));
        }
        List<FrozenItemView> frozenItems = repository.findFrozenItems(caseRow.id()).stream()
                .map(i -> new FrozenItemView(i.itemNo(), i.claimKey(), i.applicant()))
                .toList();
        return new CaseDetailResponse(caseRow.id(), caseRow.status(), caseRow.version(), items,
                "FROZEN", claims, frozenItems, caseRow.decidedAt());
    }

    private CaseRow lockCaseOr404(String caseId) {
        CaseRow caseRow = repository.lockCase(caseId);
        if (caseRow == null) {
            throw notFound(Map.of("error", "CASE_NOT_FOUND", "caseId", caseId));
        }
        return caseRow;
    }

    private CaseRow lockOpenCase(String caseId) {
        CaseRow caseRow = lockCaseOr404(caseId);
        if (CaseRow.DECIDED.equals(caseRow.status())) {
            throw conflict(Map.of("error", "CASE_ALREADY_DECIDED", "caseId", caseId));
        }
        return caseRow;
    }

    private ClaimRow requireClaim(String caseId, String claimKey) {
        ClaimRow claim = repository.findClaim(caseId, claimKey);
        if (claim == null) {
            throw notFound(Map.of("error", "CLAIM_NOT_FOUND", "claimKey", claimKey));
        }
        return claim;
    }

    private ClaimRow requireActiveClaim(String caseId, String claimKey) {
        ClaimRow claim = requireClaim(caseId, claimKey);
        if (claim.withdrawn()) {
            throw conflict(Map.of("error", "CLAIM_WITHDRAWN", "claimKey", claimKey));
        }
        return claim;
    }

    private long bumpCaseVersion(CaseRow caseRow) {
        int updated = repository.bumpCaseVersion(caseRow.id(), caseRow.version());
        if (updated == 0) {
            throw conflict(Map.of("error", "VERSION_CONFLICT", "currentVersion", caseRow.version()));
        }
        return caseRow.version() + 1;
    }

    /**
     * 集合参数规范化：去空白并拒绝重复元素；重复视为参数非法 400。
     */
    private List<String> normalizedUnique(List<String> raw, String field) {
        List<String> values = raw.stream().map(String::trim).toList();
        Set<String> unique = new LinkedHashSet<>(values);
        if (unique.size() != values.size()) {
            throw badRequest(Map.of("error", "DUPLICATE_ELEMENT", "field", field));
        }
        return new ArrayList<>(values);
    }

    private ApiException badRequest(Object body) {
        return new ApiException(400, body);
    }

    private ApiException notFound(Object body) {
        return new ApiException(404, body);
    }

    private ApiException conflict(Object body) {
        return new ApiException(409, body);
    }
}
