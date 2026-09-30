package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.FreezeAffectedSegmentRow;
import com.example.starter.translation.domain.Rows.FreezeRow;
import com.example.starter.translation.domain.Rows.FreezeUpdateRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档术语冻结窗口服务：窗口绑定术语版本与 UTC 左闭右开冻结区间。
 * 窗口内（由 TranslationService 拦截）草稿不得换术语、修改术语引用或发布；
 * 窗口结束后的首次术语更新由本服务对全部受影响段落整单校验，通过后原子生成新术语版本、
 * 解冻记录、受影响段落清单与新草稿版本，任一段落失败整体 422 回滚，不留下部分术语替换。
 * 文档行锁与冻结窗口行锁串行化解冻与发布，发布只能引用已完整校验的草稿术语版本。
 */
@Service
public class FreezeService {

    private final TranslationRepository repository;
    private final Clock clock;

    public FreezeService(TranslationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 创建冻结窗口：调用方持文档行锁，绑定当前术语版本，区间 UTC epoch 毫秒、左闭右开。
     * endAt 必须严格晚于 startAt；不允许为同一文档创建时间区间重叠的窗口。
     */
    @Transactional
    public ApiDtos.FreezeResponse createFreeze(long documentId, ApiDtos.CreateFreezeRequest request) {
        DocumentRow document = lockDocument(documentId);
        long now = clock.instant().toEpochMilli();
        if (request.endAt() <= request.startAt()) {
            throw ApiException.unprocessable("冻结窗口结束时间必须严格晚于开始时间（UTC 毫秒）");
        }
        if (request.endAt() <= now) {
            throw ApiException.unprocessable("冻结窗口结束时间必须晚于当前时间（UTC 毫秒）");
        }
        List<Integer> unresolved = repository.listUnresolvedFreezeIds(documentId);
        if (!unresolved.isEmpty()) {
            throw ApiException.conflict("文档存在未完成解冻更新的冻结窗口 #" + unresolved.get(0)
                    + "，待其解冻更新完成后才能创建新窗口");
        }
        int freezeId = repository.listFreezes(documentId).size() + 1;
        FreezeRow freeze = new FreezeRow(freezeId, document.termVersion(), request.startAt(), request.endAt());
        repository.insertFreeze(documentId, freeze);
        String status = statusOf(freeze, now, null);
        return new ApiDtos.FreezeResponse(documentId, freezeId, document.termVersion(),
                request.startAt(), request.endAt(), status, document.draftVersion());
    }

    /**
     * 窗口结束后的首次术语更新：
     * 1. 锁文档行与冻结窗口行，确认窗口存在、已解冻（endAt 右开，endAt 时刻即可更新）且窗口内未更新过；
     * 2. expectedTermVersion 必须等于窗口绑定术语版本（换术语或窗口返回 409）；
     * 3. 计算新规则命中的全部受影响段落（segment × language），逐个整单校验：
     *    缺译、译文待更新、绑定术语版本与窗口不一致、批准缺失/失效或违反新术语规则，任一失败 422；
     * 4. 全部通过后原子写入新术语版本与规则、解冻记录、受影响段落，并递增草稿版本。
     */
    @Transactional
    public ApiDtos.FreezeUpdateResponse updateTermsAfterFreeze(
            long documentId, ApiDtos.UpdateTermsAfterFreezeRequest request) {
        DocumentRow document = lockDocument(documentId);
        FreezeRow freeze = repository.findFreezeForUpdate(documentId, request.freezeId())
                .orElseThrow(() -> ApiException.notFound(
                        "冻结窗口不存在: " + documentId + "/" + request.freezeId()));
        long now = clock.instant().toEpochMilli();
        if (!freeze.endedAt(now)) {
            throw ApiException.conflict("冻结窗口尚未结束，窗口内不得更换术语版本");
        }
        FreezeUpdateRow existingUpdate = repository.findFreezeUpdate(documentId, freeze.freezeId()).orElse(null);
        if (existingUpdate != null) {
            if (existingUpdate.requestId().equals(request.requestId())) {
                // 同 requestId 并发或重放：首个事务已提交，回滚当前事务后由 WriteExecutor 重放首次成功结果。
                throw new WriteResult.ReplaySignal(request.requestId(), null);
            }
            throw ApiException.conflict("冻结窗口 #" + freeze.freezeId() + " 已完成解冻更新，术语版本 "
                    + existingUpdate.termVersion() + " 不可重复更新");
        }
        if (document.termVersion() != freeze.termVersion()
                || request.expectedTermVersion() != freeze.termVersion()) {
            throw ApiException.conflict("术语版本冲突：窗口绑定术语版本 " + freeze.termVersion()
                    + "，当前草稿术语版本 " + document.termVersion() + "，期望 " + request.expectedTermVersion());
        }

        List<TermRuleRow> rules = TermRules.normalize(request.rules(), document.targetLanguages());
        int newTermVersion = document.termVersion() + 1;

        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));

        // 受影响段落：新规则 sourceTerm 命中该段落源文的（段落, 语言）组合；无命中规则的语言不受影响。
        List<FreezeAffectedSegmentRow> affected = new ArrayList<>();
        List<ApiDtos.AffectedSegmentView> affectedViews = new ArrayList<>();
        List<ApiDtos.TermRuleView> violations = new ArrayList<>();
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                List<ApiDtos.TermRuleView> segmentHits = rulesForLanguage(rules, language).stream()
                        .filter(rule -> segment.sourceText().contains(rule.sourceTerm()))
                        .map(TermRules::toView)
                        .toList();
                if (segmentHits.isEmpty()) {
                    continue;
                }
                affected.add(new FreezeAffectedSegmentRow(freeze.freezeId(), segment.segmentId(), language));
                affectedViews.add(new ApiDtos.AffectedSegmentView(
                        segment.segmentId(), language, segment.sourceVersion()));

                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                if (translation == null) {
                    throw ApiException.unprocessable(
                            "受影响段落缺少译文: " + segment.segmentId() + "/" + language);
                }
                if (translation.sourceVersion() != segment.sourceVersion()) {
                    throw ApiException.unprocessable("受影响段落译文待更新: " + segment.segmentId() + "/" + language
                            + " 基于源文版本 " + translation.sourceVersion()
                            + "，当前源文版本 " + segment.sourceVersion());
                }
                if (translation.termVersion() != freeze.termVersion()) {
                    throw ApiException.unprocessable("受影响段落术语版本与窗口不一致: "
                            + segment.segmentId() + "/" + language + " 绑定术语版本 " + translation.termVersion()
                            + "，窗口绑定 " + freeze.termVersion());
                }
                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                if (approval == null) {
                    throw ApiException.unprocessable(
                            "受影响段落缺少批准: " + segment.segmentId() + "/" + language);
                }
                if (approval.translationVersion() != translation.translationVersion()
                        || approval.sourceVersion() != segment.sourceVersion()) {
                    throw ApiException.unprocessable("受影响段落批准已失效: " + segment.segmentId() + "/" + language
                            + "，源文或译文版本已改变");
                }
                violations.addAll(TermRules.findViolations(
                        segment.sourceText(), language, translation.content(), rules));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.termViolation(
                    "解冻更新整单校验失败，" + violations.size() + " 条术语规则不满足", violations);
        }

        repository.insertTermVersion(documentId, newTermVersion);
        for (TermRuleRow rule : rules) {
            repository.insertTermRule(documentId, newTermVersion, rule);
        }
        repository.updateTermVersion(documentId, newTermVersion);
        // 受影响段落已按新规则逐段校验通过；非受影响段落不命中任何新规则，全部译文原子绑定新版本。
        repository.rebindAllTranslationTermVersions(documentId, newTermVersion);
        repository.insertFreezeUpdate(documentId,
                new FreezeUpdateRow(freeze.freezeId(), newTermVersion, request.requestId()));
        repository.insertFreezeAffectedSegments(documentId, affected);
        int draftVersion = document.draftVersion() + 1;
        repository.updateDraftVersion(documentId, draftVersion);
        return new ApiDtos.FreezeUpdateResponse(documentId, freeze.freezeId(), newTermVersion,
                rules.size(), draftVersion, affectedViews);
    }

    /** 查询指定冻结窗口视图：冻结起止、状态、草稿术语版本与受影响段落；不存在 404。 */
    @Transactional(readOnly = true)
    public ApiDtos.FreezeView getFreeze(long documentId, int freezeId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        FreezeRow freeze = repository.findFreeze(documentId, freezeId)
                .orElseThrow(() -> ApiException.notFound(
                        "冻结窗口不存在: " + documentId + "/" + freezeId));
        return toView(documentId, freeze);
    }

    /** 查询文档全部冻结窗口视图，按窗口序号升序；文档不存在 404。 */
    @Transactional(readOnly = true)
    public List<ApiDtos.FreezeView> listFreezes(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        return repository.listFreezes(documentId).stream()
                .map(freeze -> toView(document.documentId(), freeze))
                .toList();
    }

    private ApiDtos.FreezeView toView(long documentId, FreezeRow freeze) {
        long now = clock.instant().toEpochMilli();
        FreezeUpdateRow update = repository.findFreezeUpdate(documentId, freeze.freezeId()).orElse(null);
        DocumentRow document = repository.findDocument(documentId).orElseThrow();
        List<ApiDtos.AffectedSegmentView> affectedViews = new ArrayList<>();
        if (update != null) {
            Map<String, SegmentRow> segments = repository.listSegments(documentId).stream()
                    .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
            for (FreezeAffectedSegmentRow row :
                    repository.listFreezeAffectedSegments(documentId, freeze.freezeId())) {
                SegmentRow segment = segments.get(row.segmentId());
                affectedViews.add(new ApiDtos.AffectedSegmentView(
                        row.segmentId(), row.language(), segment == null ? 0 : segment.sourceVersion()));
            }
        }
        return new ApiDtos.FreezeView(documentId, freeze.freezeId(), freeze.termVersion(),
                freeze.startAt(), freeze.endAt(), statusOf(freeze, now, update), document.termVersion(),
                update == null ? null : update.termVersion(), affectedViews);
    }

    /**
     * 窗口状态：当前时刻在 [startAt,endAt) 内为 FROZEN；早于 startAt 为 SCHEDULED；
     * endAt（右开）之后未更新为 ENDED，已完成解冻更新为 UPDATED。
     */
    private static String statusOf(FreezeRow freeze, long nowEpochMilli, FreezeUpdateRow update) {
        if (nowEpochMilli < freeze.startAt()) {
            return "SCHEDULED";
        }
        if (freeze.contains(nowEpochMilli)) {
            return "FROZEN";
        }
        return update == null ? "ENDED" : "UPDATED";
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private static String key(String segmentId, String language) {
        return segmentId + " " + language;
    }

    private static List<TermRuleRow> rulesForLanguage(List<TermRuleRow> rules, String language) {
        return rules.stream().filter(rule -> rule.language().equals(language)).toList();
    }
}
