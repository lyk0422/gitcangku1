package com.example.starter.restitution.service;

import com.example.starter.restitution.domain.CaseStatus;
import com.example.starter.restitution.error.ApiException;
import com.example.starter.restitution.error.IdempotentReplayException;
import com.example.starter.restitution.repo.ApprovalRepository;
import com.example.starter.restitution.repo.ApprovalRow;
import com.example.starter.restitution.repo.CaseRepository;
import com.example.starter.restitution.repo.CaseRow;
import com.example.starter.restitution.repo.ClaimRepository;
import com.example.starter.restitution.repo.ClaimRow;
import com.example.starter.restitution.repo.DecisionRepository;
import com.example.starter.restitution.repo.EvidenceRepository;
import com.example.starter.restitution.repo.EvidenceRow;
import com.example.starter.restitution.repo.RequestRecordRepository;
import com.example.starter.restitution.repo.RequestRecordRow;
import com.example.starter.restitution.web.dto.AddEvidenceRequest;
import com.example.starter.restitution.web.dto.CaseResponse;
import com.example.starter.restitution.web.dto.ClaimResponse;
import com.example.starter.restitution.web.dto.DecideRequest;
import com.example.starter.restitution.web.dto.DecisionResponse;
import com.example.starter.restitution.web.dto.EvidenceResponse;
import com.example.starter.restitution.web.dto.FrozenApprovalView;
import com.example.starter.restitution.web.dto.FrozenArtifactView;
import com.example.starter.restitution.web.dto.FrozenClaimView;
import com.example.starter.restitution.web.dto.FrozenEvidenceView;
import com.example.starter.restitution.web.dto.RegisterClaimRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 藏品归还裁决领域服务：案件/主张/证据/批准/裁决的全部业务规则入口。
 *
 * <p>所有写操作在同一事务内先锁全局 requestId 幂等行、再锁案件行；
 * 同 requestId 并发由幂等行串行，同案不同 requestId 由案件行锁串行，
 * 保证版本竞争、裁决与撤回/撤销互斥，且失败整体回滚、失败不占键。</p>
 */
@Service
public class RestitutionService {

    private static final int MAX_ARTIFACTS = 10;
    private static final int MAX_EVIDENCE_PER_CLAIM = 20;
    private static final int REQUIRED_APPROVERS = 2;
    private static final int MAX_ACTOR_LENGTH = 128;
    private static final int MAX_REQUEST_ID_LENGTH = 80;

    private final CaseRepository caseRepository;
    private final ClaimRepository claimRepository;
    private final EvidenceRepository evidenceRepository;
    private final ApprovalRepository approvalRepository;
    private final DecisionRepository decisionRepository;
    private final RequestRecordRepository requestRecordRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RestitutionService(CaseRepository caseRepository,
                              ClaimRepository claimRepository,
                              EvidenceRepository evidenceRepository,
                              ApprovalRepository approvalRepository,
                              DecisionRepository decisionRepository,
                              RequestRecordRepository requestRecordRepository,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.caseRepository = caseRepository;
        this.claimRepository = claimRepository;
        this.evidenceRepository = evidenceRepository;
        this.approvalRepository = approvalRepository;
        this.decisionRepository = decisionRepository;
        this.requestRecordRepository = requestRecordRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public CaseResponse createCase(String actorId, String requestId, List<String> artifactNos) {
        List<String> normalized = normalizeArtifactList(artifactNos, true);
        String fingerprint = fingerprint(Map.of("artifactNos", sortedCopy(normalized)));
        return withIdempotency(requestId, actorId, "create_case", fingerprint, 201, () -> {
            String caseKey = "C-" + UUID.randomUUID().toString().replace("-", "");
            long caseId = caseRepository.insertCase(caseKey);
            caseRepository.insertArtifacts(caseId, normalized);
            return buildCaseResponse(caseRepository.findByKey(caseKey));
        });
    }

    @Transactional(readOnly = true)
    public CaseResponse getCase(String caseKey) {
        return buildCaseResponse(requireCase(caseKey));
    }

    @Transactional
    public ClaimResponse registerClaim(String actorId, String requestId, String caseKey,
                                       RegisterClaimRequest req) {
        requireText(req.claimKey(), "claimKey");
        requireText(req.applicant(), "applicant");
        requireText(req.statement(), "statement");
        List<String> artifactSubset = normalizeArtifactList(req.artifactNos(), true);
        String fingerprint = fingerprint(java.util.Map.ofEntries(
                java.util.Map.entry("caseKey", caseKey),
                java.util.Map.entry("claimKey", req.claimKey().trim()),
                java.util.Map.entry("applicant", req.applicant().trim()),
                java.util.Map.entry("statement", req.statement().trim()),
                java.util.Map.entry("artifactNos", sortedCopy(artifactSubset))));
        return withIdempotency(requestId, actorId, "register_claim", fingerprint, 201, () -> {
            CaseRow caseRow = lockOpenCase(caseKey);
            List<String> caseArtifacts = caseRepository.findArtifactNos(caseRow.id());
            for (String no : artifactSubset) {
                if (!caseArtifacts.contains(no)) {
                    throw ApiException.badRequest("藏品不属于该案件: " + no);
                }
            }
            if (claimRepository.findByKey(caseRow.id(), req.claimKey().trim()) != null) {
                throw ApiException.conflict("claim_key_exists", "主张编号在案内已存在: " + req.claimKey());
            }
            long claimId;
            try {
                claimId = claimRepository.insertClaim(caseRow.id(), req.claimKey().trim(),
                        req.applicant().trim(), req.statement().trim());
                claimRepository.insertClaimArtifacts(claimId, caseRow.id(), artifactSubset);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("claim_key_exists", "主张编号在案内已存在: " + req.claimKey());
            }
            caseRepository.bumpVersion(caseRow.id());
            return buildClaimResponse(claimRepository.findByKey(caseRow.id(), req.claimKey().trim()));
        });
    }

    @Transactional
    public void withdrawClaim(String actorId, String requestId, String caseKey, String claimKey) {
        requireText(claimKey, "claimKey");
        String fingerprint = fingerprint(Map.of("caseKey", caseKey, "claimKey", claimKey));
        withIdempotency(requestId, actorId, "withdraw_claim", fingerprint, 204, () -> {
            CaseRow caseRow = lockOpenCase(caseKey);
            ClaimRow claim = claimRepository.lockByKey(caseRow.id(), claimKey);
            if (claim == null) {
                throw ApiException.notFound("主张不存在: " + claimKey);
            }
            if (com.example.starter.restitution.domain.ClaimStatus.WITHDRAWN.name().equals(claim.status())) {
                throw ApiException.conflict("claim_withdrawn", "主张已撤回，不可恢复或重复撤回");
            }
            claimRepository.withdraw(claim.id(), now());
            caseRepository.bumpVersion(caseRow.id());
            return null;
        });
    }

    @Transactional(readOnly = true)
    public List<ClaimResponse> listClaims(String caseKey) {
        CaseRow caseRow = requireCase(caseKey);
        List<ClaimRow> claims = claimRepository.findAllForCase(caseRow.id());
        List<ClaimResponse> result = new ArrayList<>();
        for (ClaimRow claim : claims) {
            result.add(buildClaimResponse(claim));
        }
        return result;
    }

    @Transactional
    public EvidenceResponse addEvidence(String actorId, String requestId, String caseKey, String claimKey,
                                        AddEvidenceRequest req) {
        requireText(claimKey, "claimKey");
        requireText(req.evidenceKey(), "evidenceKey");
        requireText(req.summary(), "summary");
        String fingerprint = fingerprint(java.util.Map.ofEntries(
                java.util.Map.entry("caseKey", caseKey),
                java.util.Map.entry("claimKey", claimKey),
                java.util.Map.entry("evidenceKey", req.evidenceKey().trim()),
                java.util.Map.entry("summary", req.summary().trim())));
        return withIdempotency(requestId, actorId, "add_evidence", fingerprint, 201, () -> {
            CaseRow caseRow = lockOpenCase(caseKey);
            ClaimRow claim = requireActiveClaim(caseRow, claimKey);
            if (evidenceRepository.findByKey(caseRow.id(), req.evidenceKey().trim()) != null) {
                throw ApiException.conflict("evidence_key_exists",
                        "证据编号在案内已存在: " + req.evidenceKey());
            }
            if (evidenceRepository.countAllForClaim(claim.id()) >= MAX_EVIDENCE_PER_CLAIM) {
                throw ApiException.conflict("evidence_limit",
                        "每项主张累计最多 " + MAX_EVIDENCE_PER_CLAIM + " 份证据");
            }
            try {
                evidenceRepository.insert(caseRow.id(), claim.id(),
                        req.evidenceKey().trim(), req.summary().trim());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("evidence_key_exists",
                        "证据编号在案内已存在: " + req.evidenceKey());
            }
            claimRepository.bumpEvidenceVersion(claim.id());
            caseRepository.bumpVersion(caseRow.id());
            EvidenceRow saved = evidenceRepository.findByKey(caseRow.id(), req.evidenceKey().trim());
            return buildEvidenceResponse(saved, claim.evidenceVersion() + 1);
        });
    }

    @Transactional
    public void revokeEvidence(String actorId, String requestId, String caseKey, String evidenceKey) {
        requireText(evidenceKey, "evidenceKey");
        String fingerprint = fingerprint(Map.of("caseKey", caseKey, "evidenceKey", evidenceKey));
        withIdempotency(requestId, actorId, "revoke_evidence", fingerprint, 204, () -> {
            CaseRow caseRow = lockOpenCase(caseKey);
            EvidenceRow evidence = evidenceRepository.lockByKey(caseRow.id(), evidenceKey);
            if (evidence == null) {
                throw ApiException.notFound("证据不存在: " + evidenceKey);
            }
            ClaimRow claim = claimRepository.lockById(evidence.claimId());
            ensureClaimNotWithdrawn(claim);
            if (com.example.starter.restitution.domain.EvidenceStatus.REVOKED.name().equals(evidence.status())) {
                throw ApiException.conflict("evidence_revoked", "证据已撤销，不可重复撤销");
            }
            evidenceRepository.revoke(evidence.id(), now());
            claimRepository.bumpEvidenceVersion(evidence.claimId());
            caseRepository.bumpVersion(caseRow.id());
            return null;
        });
    }

    @Transactional(readOnly = true)
    public List<EvidenceResponse> listEvidence(String caseKey, String claimKey) {
        requireText(claimKey, "claimKey");
        CaseRow caseRow = requireCase(caseKey);
        ClaimRow claim = claimRepository.findByKey(caseRow.id(), claimKey);
        if (claim == null) {
            throw ApiException.notFound("主张不存在: " + claimKey);
        }
        List<EvidenceResponse> result = new ArrayList<>();
        for (EvidenceRow evidence : evidenceRepository.findForClaim(claim.id())) {
            result.add(buildEvidenceResponse(evidence, claim.evidenceVersion()));
        }
        return result;
    }

    @Transactional
    public void approve(String actorId, String requestId, String caseKey, String claimKey) {
        requireText(claimKey, "claimKey");
        String fingerprint = fingerprint(Map.of("caseKey", caseKey, "claimKey", claimKey));
        withIdempotency(requestId, actorId, "approve", fingerprint, 204, () -> {
            CaseRow caseRow = lockOpenCase(caseKey);
            ClaimRow claim = claimRepository.lockByKey(caseRow.id(), claimKey);
            if (claim == null) {
                throw ApiException.notFound("主张不存在: " + claimKey);
            }
            ensureClaimNotWithdrawn(claim);
            if (actorId.equals(claim.applicant())) {
                throw ApiException.forbidden("评审人不能是主张申请人，禁止自批");
            }
            if (evidenceRepository.countActiveForClaim(claim.id()) < 1) {
                throw ApiException.conflict("evidence_required",
                        "至少存在一份有效证据才可批准: " + claimKey);
            }
            long version = claim.evidenceVersion();
            List<String> currentReviewers = approvalRepository.findReviewersAtVersion(claim.id(), version);
            if (currentReviewers.contains(actorId)) {
                // 同一人同版本不重复计数，也不再次推案件版本。
                return null;
            }
            boolean firstApprovalAtVersion = currentReviewers.isEmpty();
            try {
                approvalRepository.insert(caseRow.id(), claim.id(), actorId, version);
            } catch (DuplicateKeyException e) {
                // 并发下同人同版本：不重复计数，静默成功。
                return null;
            }
            // 仅该证据版本的首次批准推案件版本；同人同版本不重复计数。
            if (firstApprovalAtVersion) {
                caseRepository.bumpVersion(caseRow.id());
            }
            return null;
        });
    }

    @Transactional
    public DecisionResponse decide(String actorId, String requestId, String caseKey, DecideRequest req) {
        List<String> selectedKeys = normalizeSelectedClaimKeys(req.claimKeys());
        if (req.expectedVersion() == null || req.expectedVersion() <= 0) {
            throw ApiException.badRequest("expectedVersion 必须为正整数");
        }
        long expectedVersion = req.expectedVersion();
        String fingerprint = fingerprint(java.util.Map.ofEntries(
                java.util.Map.entry("caseKey", caseKey),
                java.util.Map.entry("expectedVersion", expectedVersion),
                java.util.Map.entry("claimKeys", sortedCopy(selectedKeys))));
        return withIdempotency(requestId, actorId, "decide", fingerprint, 201, () -> {
            CaseRow caseRow = lockOpenCase(caseKey);
            // 版本竞争优先按 409 裁决：证据撤销/主张撤回/另一裁决都会推高版本或落终态。
            if (caseRow.version() != expectedVersion) {
                throw ApiException.conflict("version_conflict",
                        "案件版本冲突: expected=" + expectedVersion + ", actual=" + caseRow.version());
            }

            List<String> caseArtifacts = caseRepository.findArtifactNos(caseRow.id());
            List<ClaimRow> selectedClaims = claimRepository.lockByKeys(caseRow.id(), selectedKeys);
            Map<String, ClaimRow> claimByKey = new LinkedHashMap<>();
            for (ClaimRow claim : selectedClaims) {
                claimByKey.put(claim.claimKey(), claim);
            }

            // 逐主张收集缺项：不存在 / 已撤回 / 当前版本批准不足两名不同评审人。
            Map<String, List<String>> claimDeficiencies = new LinkedHashMap<>();
            Map<String, List<String>> claimReviewers = new LinkedHashMap<>();
            for (String key : selectedKeys) {
                ClaimRow claim = claimByKey.get(key);
                List<String> missing = new ArrayList<>();
                if (claim == null) {
                    missing.add("claim_not_found");
                    claimDeficiencies.put(key, missing);
                    continue;
                }
                if (com.example.starter.restitution.domain.ClaimStatus.WITHDRAWN.name().equals(claim.status())) {
                    missing.add("withdrawn");
                }
                List<String> reviewers = approvalRepository.findReviewersAtVersion(
                        claim.id(), claim.evidenceVersion());
                claimReviewers.put(key, reviewers);
                if (reviewers.size() < REQUIRED_APPROVERS) {
                    missing.add("current_approval");
                }
                if (!missing.isEmpty()) {
                    claimDeficiencies.put(key, missing);
                }
            }

            // 藏品覆盖：仅统计存在且未撤回主张的覆盖范围；重复/缺失分别收集。
            Map<String, List<String>> coveredBy = new LinkedHashMap<>();
            for (String no : caseArtifacts) {
                coveredBy.put(no, new ArrayList<>());
            }
            for (String key : selectedKeys) {
                ClaimRow claim = claimByKey.get(key);
                if (claim == null
                        || com.example.starter.restitution.domain.ClaimStatus.WITHDRAWN.name().equals(claim.status())) {
                    continue;
                }
                for (String no : claimRepository.findClaimArtifactNos(claim.id())) {
                    coveredBy.computeIfAbsent(no, k -> new ArrayList<>()).add(key);
                }
            }
            List<String> duplicateArtifacts = new ArrayList<>();
            List<String> missingArtifacts = new ArrayList<>();
            for (String no : caseArtifacts) {
                List<String> owners = coveredBy.getOrDefault(no, List.of());
                if (owners.isEmpty()) {
                    missingArtifacts.add(no);
                } else if (owners.size() > 1) {
                    duplicateArtifacts.add(no);
                }
            }

            if (!claimDeficiencies.isEmpty() || !duplicateArtifacts.isEmpty() || !missingArtifacts.isEmpty()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("claimDeficiencies", claimDeficiencies);
                details.put("claimReviewers", claimReviewers);
                details.put("duplicateArtifacts", duplicateArtifacts);
                details.put("missingArtifacts", missingArtifacts);
                throw ApiException.unprocessable("裁决条件不满足", details);
            }

            // 全部满足：同一事务落定案件并冻结快照，杜绝部分裁决与失效批准冻结。
            long newVersion = caseRow.version() + 1;
            LocalDateTime decidedAt = now();
            caseRepository.markDecided(caseRow.id(), decidedAt);
            decisionRepository.insertDecision(caseRow.id(), newVersion, requestId, decidedAt);

            List<FrozenClaimView> claimViews = new ArrayList<>();
            int claimOrdinal = 0;
            for (String key : selectedKeys) {
                ClaimRow claim = claimByKey.get(key);
                decisionRepository.insertFrozenClaim(caseRow.id(), claim.id(), claim.claimKey(),
                        claim.applicant(), claim.statement(), claimOrdinal);
                claimViews.add(new FrozenClaimView(claim.claimKey(), claim.applicant(), claim.statement()));
                claimOrdinal++;
            }

            // 藏品按案件清单顺序冻结到唯一选定主张的申请人。
            List<FrozenArtifactView> artifactViews = new ArrayList<>();
            int artifactOrdinal = 0;
            for (String no : caseArtifacts) {
                String ownerKey = coveredBy.get(no).get(0);
                ClaimRow owner = claimByKey.get(ownerKey);
                decisionRepository.insertFrozenArtifact(caseRow.id(), no, owner.id(),
                        owner.applicant(), artifactOrdinal);
                artifactViews.add(new FrozenArtifactView(no, owner.claimKey(), owner.applicant()));
                artifactOrdinal++;
            }

            List<EvidenceRow> activeEvidence =
                    evidenceRepository.findActiveForClaims(new ArrayList<>(claimByKey.values().stream()
                            .map(ClaimRow::id).toList()));
            List<FrozenEvidenceView> evidenceViews = new ArrayList<>();
            int evidenceOrdinal = 0;
            for (String key : selectedKeys) {
                ClaimRow claim = claimByKey.get(key);
                for (EvidenceRow evidence : activeEvidence) {
                    if (evidence.claimId() != claim.id()) {
                        continue;
                    }
                    decisionRepository.insertFrozenEvidence(caseRow.id(), claim.id(), evidence.id(),
                            evidence.evidenceKey(), evidence.summary(), claim.evidenceVersion(), evidenceOrdinal);
                    evidenceViews.add(new FrozenEvidenceView(claim.claimKey(), evidence.evidenceKey(),
                            evidence.summary(), claim.evidenceVersion()));
                    evidenceOrdinal++;
                }
            }

            List<ApprovalRow> currentApprovals =
                    approvalRepository.findCurrentForClaims(new ArrayList<>(claimByKey.values()));
            List<FrozenApprovalView> approvalViews = new ArrayList<>();
            int approvalOrdinal = 0;
            for (String key : selectedKeys) {
                ClaimRow claim = claimByKey.get(key);
                for (ApprovalRow approval : currentApprovals) {
                    if (approval.claimId() != claim.id()) {
                        continue;
                    }
                    decisionRepository.insertFrozenApproval(caseRow.id(), claim.id(), approval.reviewer(),
                            approval.evidenceVersion(), approvalOrdinal);
                    approvalViews.add(new FrozenApprovalView(claim.claimKey(), approval.reviewer(),
                            approval.evidenceVersion()));
                    approvalOrdinal++;
                }
            }

            return new DecisionResponse(caseRow.caseKey(), CaseStatus.DECIDED.name(), newVersion,
                    claimViews, artifactViews, evidenceViews, approvalViews);
        });
    }

    @Transactional(readOnly = true)
    public DecisionResponse getDecision(String caseKey) {
        CaseRow caseRow = requireCase(caseKey);
        DecisionRepository.FrozenDecisionHeader header = decisionRepository.findHeader(caseRow.id());
        if (header == null) {
            throw ApiException.notFound("案件尚无裁决: " + caseKey);
        }
        List<DecisionRepository.FrozenClaimSnapshot> frozenClaims =
                decisionRepository.findClaims(caseRow.id());
        Map<Long, String> claimKeyById = new LinkedHashMap<>();
        List<FrozenClaimView> claimViews = new ArrayList<>();
        for (DecisionRepository.FrozenClaimSnapshot c : frozenClaims) {
            claimKeyById.put(c.claimId(), c.claimKey());
            claimViews.add(new FrozenClaimView(c.claimKey(), c.applicant(), c.statement()));
        }
        List<FrozenArtifactView> artifactViews = decisionRepository.findArtifacts(caseRow.id()).stream()
                .map(a -> new FrozenArtifactView(a.artifactNo(),
                        claimKeyById.getOrDefault(a.claimId(), null), a.applicant()))
                .toList();
        List<FrozenEvidenceView> evidenceViews = decisionRepository.findEvidence(caseRow.id()).stream()
                .map(e -> new FrozenEvidenceView(claimKeyById.get(e.claimId()), e.evidenceKey(),
                        e.summary(), e.evidenceVersion()))
                .toList();
        List<FrozenApprovalView> approvalViews = decisionRepository.findApprovals(caseRow.id()).stream()
                .map(a -> new FrozenApprovalView(claimKeyById.get(a.claimId()), a.reviewer(),
                        a.evidenceVersion()))
                .toList();
        return new DecisionResponse(caseRow.caseKey(), caseRow.status(), header.version(),
                claimViews, artifactViews, evidenceViews, approvalViews);
    }

    // ---------------------------------------------------------------------
    // 幂等与通用校验
    // ---------------------------------------------------------------------

    /**
     * 写操作幂等模板：同键同操作者同参重放首次结果，异操作者或异参 409；
     * 业务失败随事务回滚、不占键。
     *
     * @param successStatus 首次成功的 HTTP 状态码（201 或 204）
     */
    private <T> T withIdempotency(String requestId, String actorId, String opKey, String fingerprint,
                                  int successStatus, java.util.function.Supplier<T> action) {
        requireText(actorId, "X-Actor-Id");
        requireText(requestId, "X-Request-Id");
        if (actorId.trim().length() > MAX_ACTOR_LENGTH) {
            throw ApiException.badRequest("X-Actor-Id 长度不能超过 " + MAX_ACTOR_LENGTH);
        }
        if (requestId.trim().length() > MAX_REQUEST_ID_LENGTH) {
            throw ApiException.badRequest("X-Request-Id 长度不能超过 " + MAX_REQUEST_ID_LENGTH);
        }
        // 先尝试无锁插入占位；冲突时再加行锁等待占键事务结束，循环处理“对方失败未占键”。
        boolean acquired = false;
        try {
            acquired = requestRecordRepository.tryInsert(requestId, actorId, opKey, fingerprint) == 1;
        } catch (DuplicateKeyException ignored) {
            acquired = false;
        }
        while (!acquired) {
            RequestRecordRow existing = requestRecordRepository.lock(requestId);
            if (existing == null) {
                // 占键事务失败回滚，键已释放：短暂退让后由当前请求抢占，避免忙等。
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ApiException.conflict("request_interrupted", "写操作等待被中断");
                }
                try {
                    acquired = requestRecordRepository.tryInsert(requestId, actorId, opKey, fingerprint) == 1;
                } catch (DuplicateKeyException stillRacing) {
                    acquired = false;
                }
                continue;
            }
            return replayOrConflict(existing, actorId, opKey, fingerprint);
        }
        T result;
        try {
            result = action.get();
        } catch (RuntimeException ex) {
            // 失败不占键：占位随事务回滚清除（release 为同事务内的显式保险），并抛出原错误。
            requestRecordRepository.release(requestId);
            throw ex;
        }
        String body = result == null ? null : writeJson(result);
        requestRecordRepository.complete(requestId, successStatus, body);
        return result;
    }

    /**
     * 已存在幂等记录时：同操作者同操作同参重放，否则 409。
     * 占位行（http_status=0）只会在持锁事务内出现，此处能读到说明对方已完成。
     */
    private <T> T replayOrConflict(RequestRecordRow row, String actorId, String opKey, String fingerprint) {
        if (!row.actorId().equals(actorId) || !row.opKey().equals(opKey)
                || !row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("idempotency_conflict",
                    "requestId 已被不同操作者或不同参数占用");
        }
        if (row.httpStatus() == 0) {
            // 极端情况下读到未完成占位：视为并发冲突，调用方按版本冲突语义重试。
            throw ApiException.conflict("request_in_flight", "相同 requestId 的请求仍在处理中");
        }
        throw new IdempotentReplayException(row.httpStatus(), row.responseBody());
    }

    private CaseRow requireCase(String caseKey) {
        requireText(caseKey, "caseKey");
        CaseRow caseRow = caseRepository.findByKey(caseKey);
        if (caseRow == null) {
            throw ApiException.notFound("案件不存在: " + caseKey);
        }
        return caseRow;
    }

    private CaseRow lockOpenCase(String caseKey) {
        requireText(caseKey, "caseKey");
        CaseRow caseRow = caseRepository.lockByKey(caseKey);
        if (caseRow == null) {
            throw ApiException.notFound("案件不存在: " + caseKey);
        }
        if (CaseStatus.DECIDED.name().equals(caseRow.status())) {
            throw ApiException.conflict("case_decided", "案件已裁决终态，拒绝新写入");
        }
        return caseRow;
    }

    private List<String> normalizeArtifactList(List<String> input, boolean rejectDuplicates) {
        if (input == null || input.isEmpty()) {
            throw ApiException.badRequest("藏品编号列表不能为空");
        }
        if (input.size() > MAX_ARTIFACTS) {
            throw ApiException.badRequest("藏品编号至多 " + MAX_ARTIFACTS + " 个");
        }
        List<String> result = new ArrayList<>();
        for (String no : input) {
            if (no == null || no.isBlank()) {
                throw ApiException.badRequest("藏品编号不能为空");
            }
            String trimmed = no.trim();
            if (rejectDuplicates && result.contains(trimmed)) {
                throw ApiException.badRequest("藏品编号重复: " + trimmed);
            }
            result.add(trimmed);
        }
        return result;
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    /**
     * 规范化参数指纹：Map 键排序、集合排序，集合换序视为同参。
     */
    private String fingerprint(Map<String, ?> params) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(params));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("参数指纹生成失败", e);
        }
    }

    private CaseResponse buildCaseResponse(CaseRow caseRow) {
        return new CaseResponse(
                caseRow.caseKey(),
                caseRow.status(),
                caseRow.version(),
                caseRepository.findArtifactNos(caseRow.id()));
    }

    private ClaimResponse buildClaimResponse(ClaimRow claim) {
        return new ClaimResponse(
                claim.claimKey(),
                claim.applicant(),
                claim.statement(),
                claim.status(),
                claim.evidenceVersion(),
                claimRepository.findClaimArtifactNos(claim.id()),
                approvalRepository.findReviewersAtVersion(claim.id(), claim.evidenceVersion()));
    }

    /**
     * 裁决选定主张键规范化：1至10个、非空、彼此不同（非法为 400）；保留提交顺序。
     */
    private List<String> normalizeSelectedClaimKeys(List<String> input) {
        if (input == null || input.isEmpty()) {
            throw ApiException.badRequest("至少选定一个主张");
        }
        if (input.size() > MAX_ARTIFACTS) {
            throw ApiException.badRequest("至多选定 " + MAX_ARTIFACTS + " 个主张");
        }
        List<String> result = new ArrayList<>();
        for (String key : input) {
            if (key == null || key.isBlank()) {
                throw ApiException.badRequest("claimKey 不能为空");
            }
            String trimmed = key.trim();
            if (result.contains(trimmed)) {
                throw ApiException.badRequest("选定主张不能重复: " + trimmed);
            }
            result.add(trimmed);
        }
        return result;
    }

    private List<String> sortedCopy(List<String> input) {
        List<String> copy = new ArrayList<>(input);
        java.util.Collections.sort(copy);
        return copy;
    }

    /**
     * 取有效主张：不存在 404，已撤回 409。
     */
    private ClaimRow requireActiveClaim(CaseRow caseRow, String claimKey) {
        ClaimRow claim = claimRepository.findByKey(caseRow.id(), claimKey);
        if (claim == null) {
            throw ApiException.notFound("主张不存在: " + claimKey);
        }
        ensureClaimNotWithdrawn(claim);
        return claim;
    }

    private void ensureClaimNotWithdrawn(ClaimRow claim) {
        if (com.example.starter.restitution.domain.ClaimStatus.WITHDRAWN.name().equals(claim.status())) {
            throw ApiException.conflict("claim_withdrawn", "主张已撤回，不可写入证据或批准");
        }
    }

    private EvidenceResponse buildEvidenceResponse(EvidenceRow evidence, long evidenceVersion) {
        return new EvidenceResponse(
                evidence.evidenceKey(),
                evidence.summary(),
                evidence.status(),
                evidenceVersion);
    }
}
