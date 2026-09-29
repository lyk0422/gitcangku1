package com.example.starter.translation.archive;

import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveCreatedResponse;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveDetailResponse;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveDiagnosticsResponse;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveEvidence;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveHistoryItem;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveHistoryResponse;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveSummary;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveTermView;
import com.example.starter.translation.archive.ArchiveDtos.ArchivedApproval;
import com.example.starter.translation.archive.ArchiveDtos.ArchivedSegment;
import com.example.starter.translation.archive.ArchiveDtos.ArchivedTranslation;
import com.example.starter.translation.archive.ArchiveDtos.BatchArchiveResponse;
import com.example.starter.translation.archive.ArchiveDtos.LanguageCompleteness;
import com.example.starter.translation.archive.ArchiveDtos.ReleaseSummary;
import com.example.starter.translation.archive.ArchiveDtos.RevokeArchiveResponse;
import com.example.starter.translation.archive.ArchiveDtos.TermRetirementResponse;
import com.example.starter.translation.archive.ArchiveDtos.WithdrawalResponse;
import com.example.starter.translation.archive.ArchiveRows.ArchivePackageRow;
import com.example.starter.translation.archive.ArchiveRows.TermRetirementRow;
import com.example.starter.translation.archive.ArchiveRows.WithdrawalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 发布撤回、术语退役与译文归档包核心业务服务。
 * 写操作先对文档行加 FOR UPDATE 行锁，发布/撤回/归档/译文修订/术语退役按提交顺序串行裁决；
 * 多对象写入（批量归档）先完成全部最终状态校验，再在同一事务内插入，失败整体回滚、查不到半成品。
 * 归档证据取自发布时固化的快照 JSON，发布撤回、译文修订与术语退役均不改写证据。
 */
@Service
public class ArchiveService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";

    private final TranslationRepository repository;
    private final ArchiveRepository archiveRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ArchiveService(TranslationRepository repository, ArchiveRepository archiveRepository,
                          ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.archiveRepository = archiveRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 撤回发布：撤回为终态（重复撤回 409）；撤回后不可对该版本新建归档包，快照与已有归档证据保留。 */
    @Transactional
    public WithdrawalResponse withdrawRelease(long documentId, int publishedVersion, String actorId,
                                              ArchiveDtos.WithdrawReleaseRequest request) {
        lockDocument(documentId);
        requireReleaseExists(documentId, publishedVersion);
        if (archiveRepository.findWithdrawal(documentId, publishedVersion).isPresent()) {
            throw ApiException.conflict("发布版本已撤回: " + documentId + "/" + publishedVersion);
        }
        Instant now = nowUtc();
        try {
            archiveRepository.insertWithdrawal(
                    new WithdrawalRow(documentId, publishedVersion, actorId, request.reason(), now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("发布版本已撤回: " + documentId + "/" + publishedVersion);
        }
        return new WithdrawalResponse(documentId, publishedVersion, actorId, request.reason(), format(now));
    }

    /** 术语版本退役：仅标记退役，不删除/改写术语版本与规则，不影响已固化归档证据；重复退役 409。 */
    @Transactional
    public TermRetirementResponse retireTerms(long documentId, String actorId,
                                              ArchiveDtos.RetireTermsRequest request) {
        lockDocument(documentId);
        if (request.termVersion() < 1 || !repository.termVersionExists(documentId, request.termVersion())) {
            throw ApiException.notFound("术语版本不存在: " + documentId + "/" + request.termVersion());
        }
        if (archiveRepository.findTermRetirement(documentId, request.termVersion()).isPresent()) {
            throw ApiException.conflict("术语版本已退役: " + documentId + "/" + request.termVersion());
        }
        Instant now = nowUtc();
        try {
            archiveRepository.insertTermRetirement(new TermRetirementRow(
                    documentId, request.termVersion(), actorId, request.reason(), now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("术语版本已退役: " + documentId + "/" + request.termVersion());
        }
        return new TermRetirementResponse(documentId, request.termVersion(), actorId,
                request.reason(), format(now));
    }

    /** 创建归档包：引用版本必须存在且未撤回、未归档；任一语种缺段或版本不一致整次 422，不产生半成品。 */
    @Transactional
    public ArchiveCreatedResponse createArchive(long documentId, String actorId,
                                                ArchiveDtos.CreateArchiveRequest request) {
        lockDocument(documentId);
        PreparedArchive prepared = prepareArchive(documentId, request.publishedVersion(), true);
        return insertArchive(documentId, request.publishedVersion(), actorId, prepared);
    }

    /** 批量归档：版本集合去重排序后逐个完成全部校验，任一版本不可归档则整批不产生包。 */
    @Transactional
    public BatchArchiveResponse batchArchive(long documentId, String actorId,
                                             ArchiveDtos.BatchArchiveRequest request) {
        lockDocument(documentId);
        List<Integer> versions = request.publishedVersions().stream().distinct().sorted().toList();
        // 第一阶段：校验全部版本的完整最终状态
        List<PreparedArchive> prepared = new ArrayList<>();
        for (Integer version : versions) {
            prepared.add(prepareArchive(documentId, version, false));
        }
        // 第二阶段：同一事务内提交，失败整体回滚
        List<ArchiveSummary> packages = new ArrayList<>();
        int createdCount = 0;
        int existingCount = 0;
        for (int i = 0; i < versions.size(); i++) {
            int version = versions.get(i);
            PreparedArchive candidate = prepared.get(i);
            if (candidate.existing() != null) {
                ArchivePackageRow row = candidate.existing();
                existingCount++;
                packages.add(new ArchiveSummary(row.archiveId(), version, row.status(),
                        format(row.createdAt().truncatedTo(ChronoUnit.MILLIS))));
            } else {
                ArchiveCreatedResponse created = insertArchive(documentId, version, actorId, candidate);
                createdCount++;
                packages.add(new ArchiveSummary(created.archiveId(), version, created.status(), created.createdAt()));
            }
        }
        packages.sort(Comparator.comparingInt(ArchiveSummary::publishedVersion));
        return new BatchArchiveResponse(documentId, versions.size(), createdCount, existingCount, packages);
    }

    /** 撤销归档：仅置 REVOKED 并记录操作者/时刻/原因，证据保留；重复撤销 409，不存在 404。 */
    @Transactional
    public RevokeArchiveResponse revokeArchive(long documentId, long archiveId, String actorId,
                                               ArchiveDtos.RevokeArchiveRequest request) {
        lockDocument(documentId);
        ArchivePackageRow archive = archiveRepository.findArchiveById(documentId, archiveId)
                .orElseThrow(() -> ApiException.notFound("归档包不存在: " + documentId + "/" + archiveId));
        if (STATUS_REVOKED.equals(archive.status())) {
            throw ApiException.conflict("归档包已撤销: " + archiveId);
        }
        Instant now = nowUtc();
        int updated = archiveRepository.markArchiveRevoked(archiveId, actorId, now, request.reason());
        if (updated == 0) {
            throw ApiException.conflict("归档包已撤销: " + archiveId);
        }
        return new RevokeArchiveResponse(archiveId, STATUS_REVOKED, actorId, format(now), request.reason());
    }

    /** 归档包明细：ACTIVE 返回完整证据；REVOKED 返回 410（证据保留但访问失效）；不存在 404。 */
    @Transactional(readOnly = true)
    public ArchiveDetailResponse getArchive(long documentId, long archiveId) {
        ArchivePackageRow archive = archiveRepository.findArchiveById(documentId, archiveId)
                .orElseThrow(() -> ApiException.notFound("归档包不存在: " + documentId + "/" + archiveId));
        if (STATUS_REVOKED.equals(archive.status())) {
            throw ApiException.gone("归档包已撤销，访问失效；撤销原因: " + archive.revokedReason());
        }
        return new ArchiveDetailResponse(archive.archiveId(), documentId, archive.publishedVersion(),
                archive.status(), archive.createdBy(), format(archive.createdAt()), null, null, null,
                parseEvidence(archive.packageJson()));
    }

    /** 归档历史：from/to 为 UTC ISO-8601，区间左闭右开；按生成时刻、归档 ID 稳定升序。 */
    @Transactional(readOnly = true)
    public ArchiveHistoryResponse listArchives(long documentId, String from, String to) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Instant fromInstant = parseBoundary(from, "from");
        Instant toInstant = parseBoundary(to, "to");
        if (fromInstant != null && toInstant != null && !fromInstant.isBefore(toInstant)) {
            throw ApiException.badRequest("from 必须早于 to（区间左闭右开）");
        }
        List<ArchivePackageRow> rows = archiveRepository.listArchivesBetween(documentId, fromInstant, toInstant);
        List<ArchiveHistoryItem> items = new ArrayList<>();
        int activeCount = 0;
        int revokedCount = 0;
        for (ArchivePackageRow row : rows) {
            boolean revoked = STATUS_REVOKED.equals(row.status());
            if (revoked) {
                revokedCount++;
            } else {
                activeCount++;
            }
            items.add(new ArchiveHistoryItem(row.archiveId(), row.publishedVersion(), row.status(),
                    row.createdBy(), format(row.createdAt()), row.revokedBy(),
                    row.revokedAt() == null ? null : format(row.revokedAt()), row.revokedReason()));
        }
        return new ArchiveHistoryResponse(documentId, from == null ? null : format(fromInstant),
                to == null ? null : format(toInstant), items.size(), activeCount, revokedCount, items);
    }

    /** 归档诊断：发布是否存在/撤回/已归档，语种与单元的要求值、实际值、差额及全部问题原因；只读不改状态。 */
    @Transactional(readOnly = true)
    public ArchiveDiagnosticsResponse getDiagnostics(long documentId, int publishedVersion) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Optional<JsonNode> snapshotJson = repository.findSnapshot(documentId, publishedVersion)
                .map(this::readSnapshot);
        String withdrawalReason = archiveRepository.findWithdrawal(documentId, publishedVersion)
                .map(WithdrawalRow::reason).orElse(null);
        boolean withdrawn = withdrawalReason != null;
        ArchivePackageRow archive = archiveRepository.findArchiveByVersion(documentId, publishedVersion).orElse(null);
        List<String> issues = new ArrayList<>();
        if (snapshotJson.isEmpty()) {
            issues.add("发布版本不存在: " + publishedVersion);
        }
        if (withdrawn) {
            issues.add("发布版本已撤回，不能新建归档包");
        }
        if (archive != null) {
            issues.add("该发布版本已存在归档包: archiveId=" + archive.archiveId()
                    + "，状态 " + archive.status());
        }

        int requiredLanguageCount = 0;
        int requiredSegmentCount = 0;
        int completeLanguageCount = 0;
        int incompleteLanguageCount = 0;
        int requiredCellCount = 0;
        int completeCellCount = 0;
        List<LanguageCompleteness> languages = List.of();

        if (snapshotJson.isPresent()) {
            Snapshot snapshot = parseSnapshot(snapshotJson.get());
            requiredLanguageCount = snapshot.languages().size();
            requiredSegmentCount = snapshot.segments().size();
            requiredCellCount = requiredLanguageCount * requiredSegmentCount;
            languages = new ArrayList<>();
            for (String language : snapshot.languages()) {
                List<String> missingSegmentIds = new ArrayList<>();
                int actual = 0;
                boolean languageComplete = true;
                for (SnapshotSegment segment : snapshot.segments()) {
                    JsonNode translation = segment.translations().get(language);
                    if (translation == null) {
                        languageComplete = false;
                        missingSegmentIds.add(segment.segmentId());
                        issues.add("语种 " + language + " 缺少段落 " + segment.segmentId() + " 的译文");
                        continue;
                    }
                    actual++;
                    List<String> cellIssues = validateCell(publishedVersion, snapshot, segment, language, translation);
                    if (cellIssues.isEmpty()) {
                        completeCellCount++;
                    } else {
                        languageComplete = false;
                        issues.addAll(cellIssues);
                    }
                }
                if (languageComplete) {
                    completeLanguageCount++;
                } else {
                    incompleteLanguageCount++;
                }
                languages.add(new LanguageCompleteness(language, requiredSegmentCount, actual,
                        missingSegmentIds.size(), missingSegmentIds, languageComplete));
            }
        }
        int missingCellCount = requiredCellCount - completeCellCount;
        return new ArchiveDiagnosticsResponse(documentId, publishedVersion, snapshotJson.isPresent(),
                withdrawn, withdrawalReason, archive != null,
                archive == null ? null : archive.archiveId(),
                archive == null ? null : archive.status(),
                requiredLanguageCount, requiredSegmentCount, completeLanguageCount,
                incompleteLanguageCount, requiredCellCount, completeCellCount, missingCellCount,
                languages, issues);
    }

    /** 校验单个发布版本可归档并构建证据；requireNewArchive=true 时已存在归档包判 409，否则作为既有包参与批量。 */
    private PreparedArchive prepareArchive(long documentId, int publishedVersion, boolean requireNewArchive) {
        String snapshotJson = repository.findSnapshot(documentId, publishedVersion)
                .orElseThrow(() -> ApiException.notFound("发布版本不存在: " + documentId + "/" + publishedVersion));
        if (archiveRepository.findWithdrawal(documentId, publishedVersion).isPresent()) {
            throw ApiException.unprocessable("发布版本已撤回，不能新建归档包: " + publishedVersion);
        }
        ArchivePackageRow existing = archiveRepository.findArchiveByVersion(documentId, publishedVersion)
                .orElse(null);
        if (existing != null && requireNewArchive) {
            throw ApiException.conflict("该发布版本已存在归档包: archiveId=" + existing.archiveId());
        }
        Snapshot snapshot = parseSnapshot(readSnapshot(snapshotJson));
        List<String> issues = validateSnapshot(publishedVersion, snapshot);
        if (!issues.isEmpty()) {
            throw ApiException.unprocessable("归档完整性校验失败，共 " + issues.size() + " 项: "
                    + String.join("; ", issues));
        }
        ArchiveEvidence evidence = buildEvidence(documentId, publishedVersion, snapshot, snapshotJson);
        return new PreparedArchive(existing, evidence);
    }

    private ArchiveCreatedResponse insertArchive(long documentId, int publishedVersion, String actorId,
                                                 PreparedArchive prepared) {
        Instant now = nowUtc();
        String packageJson;
        try {
            packageJson = objectMapper.writeValueAsString(prepared.evidence());
        } catch (Exception e) {
            throw new IllegalStateException("归档证据序列化失败", e);
        }
        long archiveId;
        try {
            archiveId = archiveRepository.insertArchivePackage(
                    documentId, publishedVersion, actorId, now, packageJson);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("该发布版本已存在归档包: " + documentId + "/" + publishedVersion);
        }
        return new ArchiveCreatedResponse(archiveId, documentId, publishedVersion, STATUS_ACTIVE,
                actorId, format(now));
    }

    /** 完整性/一致性校验：每个目标语种在每个段落上必须有译文、批准且引用版本一致；返回全部问题。 */
    private List<String> validateSnapshot(int publishedVersion, Snapshot snapshot) {
        List<String> issues = new ArrayList<>();
        for (SnapshotSegment segment : snapshot.segments()) {
            for (String language : snapshot.languages()) {
                JsonNode translation = segment.translations().get(language);
                if (translation == null) {
                    issues.add("语种 " + language + " 缺少段落 " + segment.segmentId() + " 的译文");
                    continue;
                }
                issues.addAll(validateCell(publishedVersion, snapshot, segment, language, translation));
            }
        }
        return issues;
    }

    /** 校验单个段落×语种单元的译文/审批版本一致性。 */
    private List<String> validateCell(int publishedVersion, Snapshot snapshot, SnapshotSegment segment,
                                      String language, JsonNode translation) {
        List<String> issues = new ArrayList<>();
        String prefix = "语种 " + language + " 段落 " + segment.segmentId();
        int translationSourceVersion = translation.path("sourceVersion").asInt(-1);
        if (translationSourceVersion != segment.sourceVersion()) {
            issues.add(prefix + " 译文引用源文版本 " + translationSourceVersion + " 与段落版本 "
                    + segment.sourceVersion() + " 不一致");
        }
        int translationTermVersion = translation.path("termVersion").asInt(-1);
        if (translationTermVersion != snapshot.termVersion()) {
            issues.add(prefix + " 译文引用术语版本 " + translationTermVersion + " 与发布固化术语版本 "
                    + snapshot.termVersion() + " 不一致");
        }
        JsonNode reviewerNode = translation.get("reviewer");
        String reviewer = reviewerNode == null || reviewerNode.isNull() ? null : reviewerNode.asText();
        if (reviewer == null || reviewer.isBlank()) {
            issues.add(prefix + " 缺少有效审批记录");
        } else if (translationSourceVersion != segment.sourceVersion()) {
            // 审批记录固化于同一译文单元，其源文版本须与段落版本一致（译文源文版本已在上面校验，此处为防御性重复断言）
            issues.add(prefix + " 审批记录引用源文版本 " + translationSourceVersion + " 与段落版本 "
                    + segment.sourceVersion() + " 不一致");
        }
        return issues;
    }

    /** 构建归档证据：规范排序（语种、段落、术语、审批）后固化，发布快照时间作为发布时刻摘要。 */
    private ArchiveEvidence buildEvidence(long documentId, int publishedVersion, Snapshot snapshot,
                                          String rawSnapshotJson) {
        List<ArchivedSegment> segmentViews = new ArrayList<>();
        List<ArchivedApproval> approvals = new ArrayList<>();
        int translationCount = 0;
        int approvalCount = 0;
        for (SnapshotSegment segment : snapshot.segments()) {
            List<ArchivedTranslation> translationViews = new ArrayList<>();
            for (String language : snapshot.languages()) {
                JsonNode translation = segment.translations().get(language);
                translationViews.add(new ArchivedTranslation(language, translation.path("content").asText(),
                        translation.path("author").asText(), translation.path("translationVersion").asInt(),
                        translation.path("sourceVersion").asInt(), translation.path("termVersion").asInt(),
                        translation.path("reviewer").asText()));
                translationCount++;
                approvals.add(new ArchivedApproval(segment.segmentId(), language,
                        translation.path("reviewer").asText(), translation.path("sourceVersion").asInt(),
                        translation.path("translationVersion").asInt()));
                approvalCount++;
            }
            segmentViews.add(new ArchivedSegment(segment.segmentId(), segment.sourceVersion(),
                    segment.sourceText(), translationViews));
        }
        approvals.sort(Comparator.comparing(ArchivedApproval::segmentId)
                .thenComparing(ArchivedApproval::language));
        List<ArchiveTermView> terms = snapshot.terms().stream()
                .sorted(Comparator.comparing(ArchiveTermView::sourceTerm)
                        .thenComparing(ArchiveTermView::language))
                .toList();
        Instant releasedAt = repository.findSnapshotCreatedAt(documentId, publishedVersion)
                .map(instant -> instant.truncatedTo(ChronoUnit.MILLIS))
                .orElse(nowUtc());
        ReleaseSummary summary = new ReleaseSummary(publishedVersion, format(releasedAt),
                snapshot.segments().size(), snapshot.languages().size(), translationCount, approvalCount,
                snapshot.termVersion(), terms.size());
        return new ArchiveEvidence(publishedVersion, snapshot.termVersion(), snapshot.languages(),
                terms, segmentViews, approvals, summary);
    }

    private ArchiveEvidence parseEvidence(String packageJson) {
        try {
            return objectMapper.readValue(packageJson, ArchiveEvidence.class);
        } catch (Exception e) {
            throw new IllegalStateException("归档证据解析失败", e);
        }
    }

    private JsonNode readSnapshot(String snapshotJson) {
        try {
            return objectMapper.readTree(snapshotJson);
        } catch (Exception e) {
            throw new IllegalStateException("发布快照解析失败", e);
        }
    }

    /** 解析发布快照并规范化排序：语种按语言码、段落按 segmentId、译文按语言码。 */
    private Snapshot parseSnapshot(JsonNode root) {
        List<String> languages = new ArrayList<>();
        root.path("targetLanguages").forEach(node -> languages.add(node.asText()));
        languages.sort(String::compareTo);
        List<ArchiveTermView> terms = new ArrayList<>();
        root.path("terms").forEach(node -> terms.add(new ArchiveTermView(
                node.path("sourceTerm").asText(), node.path("language").asText(),
                node.path("requiredTranslation").asText())));
        List<SnapshotSegment> segments = new ArrayList<>();
        root.path("segments").forEach(node -> {
            String segmentId = node.path("segmentId").asText();
            int sourceVersion = node.path("sourceVersion").asInt();
            String sourceText = node.path("sourceText").asText();
            Map<String, JsonNode> translations = new LinkedHashMap<>();
            node.path("translations").forEach(translation -> translations
                    .put(translation.path("language").asText(), translation));
            segments.add(new SnapshotSegment(segmentId, sourceVersion, sourceText, translations));
        });
        segments.sort(Comparator.comparing(SnapshotSegment::segmentId));
        return new Snapshot(root.path("termVersion").asInt(), languages, terms, segments);
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private void requireReleaseExists(long documentId, int publishedVersion) {
        if (repository.findSnapshot(documentId, publishedVersion).isEmpty()) {
            throw ApiException.notFound("发布版本不存在: " + documentId + "/" + publishedVersion);
        }
    }

    private Instant parseBoundary(String value, String param) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value).truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(param + " 必须是 UTC ISO-8601 时间，例如 2026-09-29T00:00:00Z");
        }
    }

    private Instant nowUtc() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String format(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    /** 归档准备结果：既有包（批量时可复用）与待固化证据二选一语义。 */
    private record PreparedArchive(ArchivePackageRow existing, ArchiveEvidence evidence) {
    }

    /** 解析后的发布快照结构。 */
    private record Snapshot(int termVersion, List<String> languages, List<ArchiveTermView> terms,
                            List<SnapshotSegment> segments) {
    }

    /** 解析后的快照段落：translations 以语言码为键。 */
    private record SnapshotSegment(String segmentId, int sourceVersion, String sourceText,
                                   Map<String, JsonNode> translations) {
    }
}
