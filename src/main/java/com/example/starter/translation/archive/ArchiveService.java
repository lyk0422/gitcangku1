package com.example.starter.translation.archive;

import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveDetailView;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveDiagnosticResponse;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveDiagnosticRow;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveHistoryItem;
import com.example.starter.translation.archive.ArchiveDtos.ArchiveHistoryResponse;
import com.example.starter.translation.archive.ArchiveDtos.BatchArchiveItemView;
import com.example.starter.translation.archive.ArchiveDtos.BatchArchiveRequest;
import com.example.starter.translation.archive.ArchiveDtos.BatchArchiveResponse;
import com.example.starter.translation.archive.ArchiveDtos.CreateArchiveRequest;
import com.example.starter.translation.archive.ArchiveDtos.ReleaseSummaryView;
import com.example.starter.translation.archive.ArchiveDtos.RevokeArchiveRequest;
import com.example.starter.translation.archive.ArchiveDtos.SegmentEvidenceView;
import com.example.starter.translation.archive.ArchiveDtos.TranslationEvidenceView;
import com.example.starter.translation.archive.ArchiveDtos.WithdrawReleaseRequest;
import com.example.starter.translation.domain.Rows.ArchiveEventRow;
import com.example.starter.translation.domain.Rows.ArchiveHistoryRow;
import com.example.starter.translation.domain.Rows.ArchivePackageRow;
import com.example.starter.translation.domain.Rows.ArchiveSegmentRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.ReleaseSnapshotMeta;
import com.example.starter.translation.repo.ArchiveRepository;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 译文归档包核心服务。
 *
 * <p>裁决规则：发布、撤回、归档、译文修订、术语退役按提交顺序裁决——所有写操作先对文档行加
 * FOR UPDATE 行锁，归档/撤回再对发布快照行加锁；归档在锁内读取一致快照，多对象写入先完成全部
 * 最终状态校验，再在同一事务提交包主记录、逐段证据与事件，失败回滚且查不到半成品。</p>
 *
 * <p>证据不可变：归档内容取自发布时固化的 snapshot_json；发布撤回、译文修订或术语退役均不改写
 * 归档证据。撤销归档仅置访问失效状态并保留原因。所有时间统一 UTC、毫秒精度。</p>
 */
@Service
public class ArchiveService {

    private final TranslationRepository translationRepository;
    private final ArchiveRepository archiveRepository;
    private final ObjectMapper objectMapper;
    private final UtcClock clock;

    public ArchiveService(TranslationRepository translationRepository, ArchiveRepository archiveRepository,
                          ObjectMapper objectMapper, UtcClock clock) {
        this.translationRepository = translationRepository;
        this.archiveRepository = archiveRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建单个归档包：只能引用一个已发布版本；指定语种缺段、审批缺失或版本不一致整次 422；
     * 引用发布已撤回 422。先校验完整最终状态，包主记录、逐段证据与事件同一事务提交。
     */
    @Transactional
    public ArchiveDetailView createArchive(long documentId, String actor, CreateArchiveRequest request) {
        DocumentRow document = lockDocument(documentId);
        int archiveVersion = archiveRepository.countArchives(documentId) + 1;
        PreparedArchive prepared = prepareArchive(document, request.publishedVersion(),
                normalizeRequestedLanguages(request.targetLanguages()), archiveVersion);
        long archiveId = commitArchive(document.documentId(), prepared, actor);
        return toDetail(prepared.withId(archiveId));
    }

    /**
     * 批量归档：多个发布版本分别成包；先对全部项做完整最终状态校验（任一语种不完整整批 422），
     * 再在同一事务按归档版本顺序提交所有包，任何失败回滚，不产生部分包。
     */
    @Transactional
    public BatchArchiveResponse batchCreateArchives(long documentId, String actor, BatchArchiveRequest request) {
        DocumentRow document = lockDocument(documentId);
        List<BatchItemInput> inputs = new ArrayList<>();
        for (var item : request.items()) {
            inputs.add(new BatchItemInput(item.publishedVersion(),
                    normalizeRequestedLanguages(item.targetLanguages())));
        }
        // 与幂等指纹一致，按发布版本、规范化语种集合排序后分配归档版本，保证结果不依赖请求顺序
        inputs.sort((a, b) -> {
            int byVersion = Integer.compare(a.publishedVersion(), b.publishedVersion());
            return byVersion != 0 ? byVersion
                    : String.join(",", a.languages()).compareTo(String.join(",", b.languages()));
        });
        int baseVersion = archiveRepository.countArchives(documentId);
        // 先完成全部项的完整校验（空语种在此解析为发布版本的全部语种），任一失败整批回滚
        List<PreparedArchive> preparedAll = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            BatchItemInput input = inputs.get(i);
            preparedAll.add(prepareArchive(document, input.publishedVersion(), input.languages(),
                    baseVersion + i + 1));
        }
        // 以解析后的实际语种集合去重：空集合（全部语种）与显式列出全部语种视为同一项
        Set<String> actualKeys = new LinkedHashSet<>();
        for (PreparedArchive prepared : preparedAll) {
            ArchivePackageRow row = prepared.row();
            if (!actualKeys.add(row.publishedVersion() + "|" + String.join(",", row.targetLanguages()))) {
                throw ApiException.unprocessable("批量归档存在重复项: 发布版本 " + row.publishedVersion()
                        + " 语种集合 " + row.targetLanguages());
            }
        }
        List<BatchArchiveItemView> views = new ArrayList<>();
        for (PreparedArchive prepared : preparedAll) {
            long archiveId = commitArchive(document.documentId(), prepared, actor);
            ArchivePackageRow row = prepared.withId(archiveId);
            views.add(new BatchArchiveItemView(row.publishedVersion(), row.targetLanguages(),
                    row.archiveId(), row.archiveVersion()));
        }
        return new BatchArchiveResponse(documentId, preparedAll.size(), views);
    }

    /** 撤回发布：仅置发布快照撤回状态并记录事件，证据 JSON 不改写；已撤回再撤回 409。 */
    @Transactional
    public ReleaseSummaryView withdrawRelease(long documentId, int publishedVersion, String actor,
                                              WithdrawReleaseRequest request) {
        lockDocument(documentId);
        ReleaseSnapshotMeta release = lockRelease(documentId, publishedVersion);
        if ("WITHDRAWN".equals(release.status())) {
            throw ApiException.conflict("发布版本已撤回: " + documentId + "/" + publishedVersion);
        }
        Instant now = clock.now();
        archiveRepository.markReleaseWithdrawn(documentId, publishedVersion, actor, request.reason(), now);
        archiveRepository.insertEvent(new ArchiveEventRow(0L, documentId, null, "RELEASE_WITHDRAWN",
                publishedVersion, actor, request.reason(), now));
        return summarizeRelease(release, "WITHDRAWN");
    }

    /** 撤销归档：仅置访问失效状态、记录撤销时刻/操作者/原因，证据列完全保留；重复撤销 409。 */
    @Transactional
    public ArchiveDetailView revokeArchive(long documentId, int archiveVersion, String actor,
                                           RevokeArchiveRequest request) {
        lockDocument(documentId);
        ArchivePackageRow pkg = archiveRepository.findArchivePackageForUpdate(documentId, archiveVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "归档包不存在: " + documentId + "/" + archiveVersion));
        if ("REVOKED".equals(pkg.status())) {
            throw ApiException.conflict("归档包已撤销: " + documentId + "/" + archiveVersion);
        }
        Instant now = clock.now();
        archiveRepository.markArchiveRevoked(pkg.archiveId(), actor, request.reason(), now);
        archiveRepository.insertEvent(new ArchiveEventRow(0L, documentId, pkg.archiveId(),
                "ARCHIVE_REVOKED", pkg.publishedVersion(), actor, request.reason(), now));
        ArchivePackageRow refreshed = archiveRepository.findArchivePackage(documentId, archiveVersion).orElseThrow();
        return toDetail(refreshed);
    }

    /**
     * 查询归档明细：包不存在 404；已撤销 410（证据仍保留，可经历史/诊断查询）；
     * 引用发布事后被撤回不影响既有 ACTIVE 归档包的受限查询。
     */
    @Transactional(readOnly = true)
    public ArchiveDetailView getArchive(long documentId, int archiveVersion) {
        ArchivePackageRow pkg = archiveRepository.findArchivePackage(documentId, archiveVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "归档包不存在: " + documentId + "/" + archiveVersion));
        if ("REVOKED".equals(pkg.status())) {
            throw ApiException.gone("归档包已撤销，访问失效: " + documentId + "/" + archiveVersion
                    + "，原因: " + pkg.revokeReason());
        }
        return toDetail(pkg);
    }

    /** 查询归档领域事件历史，按提交顺序（eventId 升序）稳定排序，字段与 null 语义不变。 */
    @Transactional(readOnly = true)
    public ArchiveHistoryResponse getHistory(long documentId) {
        translationRepository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<ArchiveHistoryItem> events = archiveRepository.listHistory(documentId).stream()
                .map(this::toHistoryItem)
                .toList();
        return new ArchiveHistoryResponse(documentId, events);
    }

    /**
     * 诊断查询：可按发布版本过滤、按 UTC 左闭右开时间区间 [from,to) 过滤归档生成时刻；
     * 每行返回实际逐段行数、要求行数与差额，并标注引用发布是否已撤回，按归档版本稳定排序。
     */
    @Transactional(readOnly = true)
    public ArchiveDiagnosticResponse diagnose(long documentId, Integer publishedVersion, Instant from, Instant to) {
        translationRepository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.unprocessable("时间区间非法：from 必须早于 to（左闭右开）");
        }
        List<ArchiveDiagnosticRow> rows = new ArrayList<>();
        for (ArchivePackageRow pkg : archiveRepository.listArchivePackages(documentId, publishedVersion)) {
            if (from != null && pkg.createdAt().isBefore(from)) {
                continue;
            }
            if (to != null && !pkg.createdAt().isBefore(to)) {
                continue;
            }
            ReleaseSnapshotMeta release = archiveRepository.findReleaseMeta(documentId, pkg.publishedVersion())
                    .orElse(null);
            int archivedRows = archiveRepository.countArchiveSegments(pkg.archiveId());
            int requiredRows = pkg.approvalCount();
            int missing = Math.max(0, requiredRows - archivedRows);
            Integer releaseTermVersion = release == null ? null
                    : parseSnapshot(release.snapshotJson()).termVersion();
            boolean withdrawn = release != null && "WITHDRAWN".equals(release.status());
            String detail = "要求审批/译文证据 " + requiredRows + " 条，实际固化 " + archivedRows + " 条，差额 "
                    + (archivedRows - requiredRows) + " 条";
            rows.add(new ArchiveDiagnosticRow(pkg.archiveId(), pkg.archiveVersion(), pkg.publishedVersion(),
                    pkg.status(), pkg.termVersion(), releaseTermVersion, pkg.segmentCount(), pkg.languageCount(),
                    pkg.approvalCount(), archivedRows, missing, withdrawn, detail));
        }
        return new ArchiveDiagnosticResponse(documentId, publishedVersion, rows);
    }

    private DocumentRow lockDocument(long documentId) {
        return translationRepository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private ReleaseSnapshotMeta lockRelease(long documentId, int publishedVersion) {
        return archiveRepository.findReleaseMetaForUpdate(documentId, publishedVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "发布版本不存在: " + documentId + "/" + publishedVersion));
    }

    private List<String> normalizeRequestedLanguages(List<String> languages) {
        List<String> normalized = languages.stream()
                .map(String::trim)
                .peek(language -> {
                    if (language.isBlank()) {
                        throw ApiException.unprocessable("目标语言码不能为空");
                    }
                })
                .map(language -> language.toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
        return normalized;
    }

    /**
     * 准备一个归档包（不写库）：对发布快照行加锁，从快照 JSON 读取一致证据，校验每个请求语种
     * 段段齐全、审批存在、译文源文版本与段落一致、术语版本与快照一致；任一不符整次 422。
     */
    private PreparedArchive prepareArchive(DocumentRow document, int publishedVersion, List<String> requestedLanguages,
                                           int archiveVersion) {
        ReleaseSnapshotMeta release = lockRelease(document.documentId(), publishedVersion);
        if ("WITHDRAWN".equals(release.status())) {
            throw ApiException.unprocessable(
                    "发布版本已撤回，不能新建归档包: " + document.documentId() + "/" + publishedVersion);
        }
        Snapshot snapshot = parseSnapshot(release.snapshotJson());
        if (snapshot.publishedVersion() != publishedVersion) {
            throw ApiException.unprocessable("归档引用版本不一致：请求 " + publishedVersion + "，快照为 "
                    + snapshot.publishedVersion());
        }        List<String> languages = requestedLanguages.isEmpty()
                ? snapshot.targetLanguages().stream().sorted().toList() : requestedLanguages;
        for (String language : languages) {
            if (!snapshot.targetLanguages().contains(language)) {
                throw ApiException.unprocessable("目标语种不在发布版本 " + publishedVersion + " 中: " + language
                        + "（该语种不完整，不能归档）");
            }
        }

        List<String> problems = new ArrayList<>();
        List<ArchiveSegmentRow> segmentRows = new ArrayList<>();
        List<Map<String, Object>> evidenceSegments = new ArrayList<>();
        for (JsonNode segmentNode : snapshot.segments()) {
            String segmentId = segmentNode.get("segmentId").asText();
            String sourceText = segmentNode.get("sourceText").asText();
            int sourceVersion = segmentNode.get("sourceVersion").asInt();
            Map<String, JsonNode> translationsByLanguage = new LinkedHashMap<>();
            for (JsonNode translation : segmentNode.get("translations")) {
                translationsByLanguage.put(translation.get("language").asText(), translation);
            }
            List<Map<String, Object>> evidenceTranslations = new ArrayList<>();
            for (String language : languages) {
                JsonNode translation = translationsByLanguage.get(language);
                if (translation == null) {
                    problems.add("缺少译文段落: " + segmentId + "/" + language);
                    continue;
                }
                int translationVersion = translation.get("translationVersion").asInt();
                int translationSourceVersion = translation.get("sourceVersion").asInt();
                int translationTermVersion = translation.get("termVersion").asInt();
                JsonNode reviewerNode = translation.get("reviewer");
                if (reviewerNode == null || reviewerNode.isNull() || reviewerNode.asText().isBlank()) {
                    problems.add("缺少审批记录: " + segmentId + "/" + language);
                }
                if (translationSourceVersion != sourceVersion) {
                    problems.add("版本不一致: " + segmentId + "/" + language + " 译文依据源文版本 "
                            + translationSourceVersion + "，段落源文版本 " + sourceVersion);
                }
                if (translationTermVersion != snapshot.termVersion()) {
                    problems.add("术语版本不一致: " + segmentId + "/" + language + " 绑定 "
                            + translationTermVersion + "，发布快照术语版本 " + snapshot.termVersion());
                }
                segmentRows.add(new ArchiveSegmentRow(0L, segmentId, language, translationVersion,
                        translationSourceVersion, translationTermVersion));
                Map<String, Object> translationEvidence = new LinkedHashMap<>();
                translationEvidence.put("language", language);
                translationEvidence.put("content", translation.get("content").asText());
                translationEvidence.put("author", translation.get("author").asText());
                translationEvidence.put("reviewer", reviewerNode == null || reviewerNode.isNull()
                        ? null : reviewerNode.asText());
                translationEvidence.put("translationVersion", translationVersion);
                translationEvidence.put("sourceVersion", translationSourceVersion);
                translationEvidence.put("termVersion", translationTermVersion);
                evidenceTranslations.add(translationEvidence);
            }
            Map<String, Object> segmentEvidence = new LinkedHashMap<>();
            segmentEvidence.put("segmentId", segmentId);
            segmentEvidence.put("sourceText", sourceText);
            segmentEvidence.put("sourceVersion", sourceVersion);
            segmentEvidence.put("translations", evidenceTranslations);
            evidenceSegments.add(segmentEvidence);
        }
        if (!problems.isEmpty()) {
            throw ApiException.unprocessable("归档校验失败 " + problems.size() + " 项: " + String.join("; ", problems));
        }

        Instant now = clock.now();
        int segmentCount = snapshot.segmentCount();
        int languageCount = languages.size();
        int approvalCount = segmentCount * languageCount;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("documentId", document.documentId());
        evidence.put("archiveVersion", archiveVersion);
        evidence.put("publishedVersion", publishedVersion);
        evidence.put("targetLanguages", languages);
        evidence.put("termVersion", snapshot.termVersion());
        evidence.put("segmentCount", segmentCount);
        evidence.put("languageCount", languageCount);
        evidence.put("approvalCount", approvalCount);
        evidence.put("release", buildReleaseSummaryMap(release, snapshot));
        evidence.put("terms", snapshot.terms());
        evidence.put("segments", evidenceSegments);
        evidence.put("createdAt", now.toString());
        String evidenceJson;
        try {
            evidenceJson = objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            throw new IllegalStateException("归档证据序列化失败", e);
        }

        ArchivePackageRow row = new ArchivePackageRow(0L, document.documentId(), archiveVersion, publishedVersion,
                languages, snapshot.termVersion(), segmentCount, languageCount, approvalCount, evidenceJson,
                "ACTIVE", null, null, null, now);
        return new PreparedArchive(row, segmentRows, now);
    }

    /** 提交已准备好的归档包：主记录、逐段证据与生成事件在同一事务写入，返回自增 archiveId。 */
    private long commitArchive(long documentId, PreparedArchive prepared, String actor) {
        long archiveId = archiveRepository.insertArchivePackage(prepared.row());
        archiveRepository.insertArchiveSegments(documentId, prepared.segmentRows().stream()
                .map(r -> new ArchiveSegmentRow(archiveId, r.segmentId(), r.language(), r.translationVersion(),
                        r.sourceVersion(), r.termVersion()))
                .toList());
        archiveRepository.insertEvent(new ArchiveEventRow(0L, documentId, archiveId, "ARCHIVE_CREATED",
                prepared.row().publishedVersion(), actor, null, prepared.createdAt()));
        return archiveId;
    }

    private Snapshot parseSnapshot(String snapshotJson) {
        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            int publishedVersion = root.get("publishedVersion").asInt();
            int termVersion = root.get("termVersion").asInt();
            JsonNode createdAtNode = root.get("createdAt");
            Instant releaseCreatedAt = createdAtNode == null || createdAtNode.isNull()
                    ? null : Instant.parse(createdAtNode.asText());
            List<String> targetLanguages = new ArrayList<>();
            root.get("targetLanguages").forEach(node -> targetLanguages.add(node.asText()));
            JsonNode segmentsNode = root.get("segments");
            JsonNode termsNode = root.get("terms");
            List<JsonNode> segments = new ArrayList<>();
            if (segmentsNode != null) {
                segmentsNode.forEach(segments::add);
            }
            List<JsonNode> terms = new ArrayList<>();
            if (termsNode != null) {
                termsNode.forEach(terms::add);
            }
            return new Snapshot(publishedVersion, termVersion, releaseCreatedAt, targetLanguages, segments, terms);
        } catch (Exception e) {
            throw new IllegalStateException("发布快照解析失败", e);
        }
    }

    private Map<String, Object> buildReleaseSummaryMap(ReleaseSnapshotMeta release, Snapshot snapshot) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("publishedVersion", snapshot.publishedVersion());
        summary.put("createdAt", snapshot.releaseCreatedAt() == null ? null
                : snapshot.releaseCreatedAt().toString());
        summary.put("termVersion", snapshot.termVersion());
        summary.put("segmentCount", snapshot.segmentCount());
        summary.put("languageCount", snapshot.targetLanguages().size());
        summary.put("approvalCount", snapshot.segmentCount() * snapshot.targetLanguages().size());
        summary.put("status", "PUBLISHED");
        return summary;
    }

    private ReleaseSummaryView summarizeRelease(ReleaseSnapshotMeta release, String status) {
        Snapshot snapshot = parseSnapshot(release.snapshotJson());
        return new ReleaseSummaryView(snapshot.publishedVersion(), snapshot.releaseCreatedAt(),
                snapshot.termVersion(), snapshot.segmentCount(), snapshot.targetLanguages().size(),
                snapshot.segmentCount() * snapshot.targetLanguages().size(), status);
    }

    /** 由包行与其固化证据 JSON 组装明细；完全不读取可变的译文/审批/术语业务表。 */
    private ArchiveDetailView toDetail(ArchivePackageRow pkg) {
        try {
            JsonNode evidence = objectMapper.readTree(pkg.evidenceJson());
            JsonNode releaseNode = evidence.get("release");
            ReleaseSummaryView release = new ReleaseSummaryView(
                    releaseNode.get("publishedVersion").asInt(),
                    parseInstant(releaseNode.get("createdAt")),
                    releaseNode.get("termVersion").asInt(),
                    releaseNode.get("segmentCount").asInt(),
                    releaseNode.get("languageCount").asInt(),
                    releaseNode.get("approvalCount").asInt(),
                    releaseNode.get("status").asText());
            List<SegmentEvidenceView> segments = new ArrayList<>();
            for (JsonNode segment : evidence.get("segments")) {
                List<TranslationEvidenceView> translations = new ArrayList<>();
                for (JsonNode t : segment.get("translations")) {
                    translations.add(new TranslationEvidenceView(t.get("language").asText(),
                            t.get("content").asText(), t.get("author").asText(),
                            t.get("reviewer") == null || t.get("reviewer").isNull() ? null
                                    : t.get("reviewer").asText(),
                            t.get("translationVersion").asInt(), t.get("sourceVersion").asInt(),
                            t.get("termVersion").asInt()));
                }
                segments.add(new SegmentEvidenceView(segment.get("segmentId").asText(),
                        segment.get("sourceText").asText(), segment.get("sourceVersion").asInt(), translations));
            }
            return new ArchiveDetailView(pkg.archiveId(), pkg.documentId(), pkg.archiveVersion(),
                    pkg.publishedVersion(), pkg.targetLanguages(), pkg.termVersion(), pkg.segmentCount(),
                    pkg.languageCount(), pkg.approvalCount(), release, segments, pkg.createdAt(), pkg.status(),
                    pkg.revokedAt(), pkg.revokedBy(), pkg.revokeReason());
        } catch (Exception e) {
            throw new IllegalStateException("归档证据解析失败", e);
        }
    }

    private static Instant parseInstant(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return Instant.parse(node.asText());
    }

    private ArchiveHistoryItem toHistoryItem(ArchiveHistoryRow row) {
        return new ArchiveHistoryItem(row.eventId(), row.eventType(), row.archiveId(), row.archiveVersion(),
                row.publishedVersion(), row.actor(), row.reason(), row.eventTime());
    }

    /** 发布快照解析结果：UTC 生成时刻、版本、语种、段落与术语节点。 */
    private record Snapshot(int publishedVersion, int termVersion, Instant releaseCreatedAt,
                           List<String> targetLanguages, List<JsonNode> segments, List<JsonNode> terms) {
        int segmentCount() {
            return segments.size();
        }
    }

    private record BatchItemInput(int publishedVersion, List<String> languages) {
    }

    /** 已通过完整校验、待提交的归档包：主记录（archiveId 暂为 0）、逐段证据与生成时刻。 */
    private record PreparedArchive(ArchivePackageRow row, List<ArchiveSegmentRow> segmentRows, Instant createdAt) {
        ArchivePackageRow withId(long archiveId) {
            return new ArchivePackageRow(archiveId, row.documentId(), row.archiveVersion(), row.publishedVersion(),
                    row.targetLanguages(), row.termVersion(), row.segmentCount(), row.languageCount(),
                    row.approvalCount(), row.evidenceJson(), row.status(), row.revokedAt(), row.revokedBy(),
                    row.revokeReason(), row.createdAt());
        }
    }
}
