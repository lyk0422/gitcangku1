package com.example.starter.api;

import com.example.starter.api.dto.LicensePolicyRequest;
import com.example.starter.api.dto.LicensePolicyResponse;
import com.example.starter.api.dto.MissingNoticeView;
import com.example.starter.api.dto.NarrowRegionsRequest;
import com.example.starter.api.dto.NoticeCoverageView;
import com.example.starter.api.dto.NoticeTextRequest;
import com.example.starter.api.dto.NoticeTextResponse;
import com.example.starter.api.dto.PolicyHitView;
import com.example.starter.api.dto.PublishEntryView;
import com.example.starter.api.dto.PublishLockView;
import com.example.starter.api.dto.PublishRequest;
import com.example.starter.api.dto.PublishResponse;
import com.example.starter.domain.ArtifactVersion;
import com.example.starter.domain.DependencyRange;
import com.example.starter.domain.NoticeHitPaths;
import com.example.starter.repo.LicenseDao;
import com.example.starter.repo.LicenseDao.NoticeTextRow;
import com.example.starter.repo.LicenseDao.PolicyRow;
import com.example.starter.repo.LicenseDao.SnapshotEntryRow;
import com.example.starter.repo.LicenseDao.SnapshotLockRow;
import com.example.starter.repo.LicenseDao.SnapshotRow;
import com.example.starter.repo.RepositoryDao;
import com.example.starter.repo.RepositoryDao.LockEntryRow;
import com.example.starter.repo.RepositoryDao.LockFileRow;
import com.example.starter.support.ApiException;
import com.example.starter.support.Digests;
import com.example.starter.support.IdempotentExecutor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 许可证告知业务实现。
 *
 * <p>全部写操作经 {@link IdempotentExecutor} 与制品/锁定写操作共用同一行锁串行，
 * 按提交顺序裁决；发布指纹在持锁事务内基于一致性视图计算，含锁定图版本、
 * 目标地区、规范化制品集合与绑定文本版本。发布校验任一失败整体回滚，
 * 不留下半成品快照，也不占用 noticeKey。
 */
@Service
public class LicenseServiceImpl implements LicenseService {

    private static final String SCOPE_LOCK_FILE = "LOCK_FILE";
    private static final String SCOPE_ARTIFACT = "ARTIFACT";
    private static final String NOTICE_REQUIRED = "NOTICE_REQUIRED";

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_WITHDRAWN = "WITHDRAWN";

    /** 发布门禁失败原因：策略要求告知但未绑定文本（或绑定文本版本不存在）。 */
    public static final String REASON_MISSING_NOTICE = "MISSING_NOTICE";
    /** 发布门禁失败原因：绑定的告知文本版本未处于已批准状态。 */
    public static final String REASON_TEXT_NOT_APPROVED = "NOTICE_TEXT_NOT_APPROVED";
    /** 发布门禁失败原因：文本地区覆盖不包含全部目标地区。 */
    public static final String REASON_REGION_NOT_COVERED = "NOTICE_REGION_NOT_COVERED";

    private static final String OP_REGISTER_TEXT = "REGISTER_NOTICE_TEXT";
    private static final String OP_APPROVE_TEXT = "APPROVE_NOTICE_TEXT";
    private static final String OP_WITHDRAW_TEXT = "WITHDRAW_NOTICE_TEXT";
    private static final String OP_NARROW_REGIONS = "NARROW_NOTICE_REGIONS";
    private static final String OP_REGISTER_POLICY = "REGISTER_LICENSE_POLICY";
    private static final String OP_PUBLISH = "PUBLISH_LOCKS";

    private final LicenseDao licenseDao;
    private final RepositoryDao repositoryDao;
    private final IdempotentExecutor idempotentExecutor;
    private final Clock clock;

    public LicenseServiceImpl(LicenseDao licenseDao,
                              RepositoryDao repositoryDao,
                              IdempotentExecutor idempotentExecutor,
                              Clock clock) {
        this.licenseDao = licenseDao;
        this.repositoryDao = repositoryDao;
        this.idempotentExecutor = idempotentExecutor;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 告知文本
    // ------------------------------------------------------------------

    @Override
    public NoticeTextResponse registerNoticeText(String requestId, NoticeTextRequest request) {
        requireRequestId(requestId);
        if (request.textKey() == null || request.textKey().isBlank()) {
            throw ApiException.badRequest("textKey 不能为空");
        }
        if (request.content() == null || request.content().isBlank()) {
            throw ApiException.badRequest("content 不能为空");
        }
        String textKey = request.textKey().trim();
        List<String> regions = normalizeRegions(request.regions());
        String hash = Digests.sha256(OP_REGISTER_TEXT + "|" + textKey + "|" + request.version()
                + "|" + request.content() + "|" + toCsv(regions));
        return idempotentExecutor.execute(requestId, OP_REGISTER_TEXT, hash, 201,
                () -> doRegisterNoticeText(textKey, request.version(), request.content(), regions),
                NoticeTextResponse.class);
    }

    @Override
    public NoticeTextResponse approveNoticeText(String requestId, String textKey, int version) {
        requireRequestId(requestId);
        String key = requireTextKey(textKey);
        String hash = Digests.sha256(OP_APPROVE_TEXT + "|" + key + "|" + version);
        return idempotentExecutor.execute(requestId, OP_APPROVE_TEXT, hash, 200,
                () -> doApproveNoticeText(key, version), NoticeTextResponse.class);
    }

    @Override
    public NoticeTextResponse withdrawNoticeText(String requestId, String textKey, int version) {
        requireRequestId(requestId);
        String key = requireTextKey(textKey);
        String hash = Digests.sha256(OP_WITHDRAW_TEXT + "|" + key + "|" + version);
        return idempotentExecutor.execute(requestId, OP_WITHDRAW_TEXT, hash, 200,
                () -> doWithdrawNoticeText(key, version), NoticeTextResponse.class);
    }

    @Override
    public NoticeTextResponse narrowNoticeTextRegions(String requestId, String textKey, int version,
                                                      NarrowRegionsRequest request) {
        requireRequestId(requestId);
        String key = requireTextKey(textKey);
        List<String> narrowed = normalizeRegions(request.regions());
        String hash = Digests.sha256(OP_NARROW_REGIONS + "|" + key + "|" + version + "|" + toCsv(narrowed));
        return idempotentExecutor.execute(requestId, OP_NARROW_REGIONS, hash, 200,
                () -> doNarrowNoticeTextRegions(key, version, narrowed), NoticeTextResponse.class);
    }

    @Override
    public NoticeCoverageView getNoticeCoverage(String textKey, int version) {
        NoticeTextRow row = requireNoticeText(textKey, version);
        return new NoticeCoverageView(row.textKey(), row.version(), row.status(), parseCsv(row.regions()));
    }

    private NoticeTextResponse doRegisterNoticeText(String textKey, int version, String content,
                                                    List<String> regions) {
        if (licenseDao.findNoticeText(textKey, version) != null) {
            throw ApiException.conflict("告知文本版本已存在: " + textKey + ":" + version);
        }
        Instant now = Instant.now(clock);
        licenseDao.insertNoticeText(textKey, version, content, toCsv(regions), STATUS_DRAFT, now);
        return new NoticeTextResponse(textKey, version, content, regions, STATUS_DRAFT, now, now);
    }

    private NoticeTextResponse doApproveNoticeText(String textKey, int version) {
        NoticeTextRow row = requireNoticeText(textKey, version);
        if (STATUS_APPROVED.equals(row.status())) {
            throw ApiException.conflict("告知文本版本已批准: " + textKey + ":" + version);
        }
        if (STATUS_WITHDRAWN.equals(row.status())) {
            throw ApiException.conflict("告知文本版本已撤销，不能批准: " + textKey + ":" + version);
        }
        int affected = licenseDao.transitionNoticeTextStatus(
                row.id(), STATUS_DRAFT, STATUS_APPROVED, Instant.now(clock));
        if (affected == 0) {
            throw ApiException.conflict("告知文本版本状态已变化: " + textKey + ":" + version);
        }
        return toTextResponse(requireNoticeText(textKey, version));
    }

    private NoticeTextResponse doWithdrawNoticeText(String textKey, int version) {
        NoticeTextRow row = requireNoticeText(textKey, version);
        if (STATUS_WITHDRAWN.equals(row.status())) {
            throw ApiException.conflict("告知文本版本已撤销: " + textKey + ":" + version);
        }
        int affected = licenseDao.withdrawNoticeText(row.id(), Instant.now(clock));
        if (affected == 0) {
            throw ApiException.conflict("告知文本版本已撤销: " + textKey + ":" + version);
        }
        return toTextResponse(requireNoticeText(textKey, version));
    }

    private NoticeTextResponse doNarrowNoticeTextRegions(String textKey, int version,
                                                         List<String> narrowed) {
        NoticeTextRow row = requireNoticeText(textKey, version);
        if (STATUS_WITHDRAWN.equals(row.status())) {
            throw ApiException.conflict("告知文本版本已撤销，不能缩窄地区: " + textKey + ":" + version);
        }
        List<String> current = parseCsv(row.regions());
        if (!current.containsAll(narrowed) || narrowed.size() >= current.size()) {
            throw ApiException.badRequest(
                    "新地区集合必须是当前覆盖的非空真子集: 当前=" + toCsv(current));
        }
        int affected = licenseDao.narrowNoticeTextRegions(row.id(), toCsv(narrowed), Instant.now(clock));
        if (affected == 0) {
            throw ApiException.conflict("告知文本版本已撤销，不能缩窄地区: " + textKey + ":" + version);
        }
        return toTextResponse(requireNoticeText(textKey, version));
    }

    // ------------------------------------------------------------------
    // 许可证策略
    // ------------------------------------------------------------------

    @Override
    public LicensePolicyResponse registerPolicy(String requestId, LicensePolicyRequest request) {
        requireRequestId(requestId);
        validatePolicyRequest(request);
        String hash = Digests.sha256(OP_REGISTER_POLICY + "|" + request.scopeType().trim()
                + "|" + request.lockFileId() + "|" + normalizedArtifactName(request)
                + "|" + request.artifactVersion() + "|" + request.noticeType().trim()
                + "|" + normalizedTextKey(request) + "|" + request.textVersion());
        return idempotentExecutor.execute(requestId, OP_REGISTER_POLICY, hash, 201,
                () -> doRegisterPolicy(request), LicensePolicyResponse.class);
    }

    @Override
    public List<LicensePolicyResponse> listPolicies() {
        return licenseDao.listPolicies().stream().map(this::toPolicyResponse).toList();
    }

    private LicensePolicyResponse doRegisterPolicy(LicensePolicyRequest request) {
        String scopeType = request.scopeType().trim();
        if (SCOPE_LOCK_FILE.equals(scopeType)) {
            if (repositoryDao.getLockFile(request.lockFileId()) == null) {
                throw ApiException.notFound("锁文件不存在: " + request.lockFileId());
            }
        } else {
            String name = normalizedArtifactName(request);
            if (repositoryDao.countVersions(name) == 0) {
                throw ApiException.notFound("制品不存在: " + name);
            }
            if (request.artifactVersion() != null
                    && repositoryDao.loadArtifact(name, request.artifactVersion()) == null) {
                throw ApiException.notFound("制品版本不存在: " + name + ":" + request.artifactVersion());
            }
        }
        String textKey = normalizedTextKey(request);
        if (textKey != null && licenseDao.findNoticeText(textKey, request.textVersion()) == null) {
            throw ApiException.notFound("告知文本版本不存在: " + textKey + ":" + request.textVersion());
        }
        Instant now = Instant.now(clock);
        long id = licenseDao.insertPolicy(scopeType, request.lockFileId(),
                normalizedArtifactName(request), request.artifactVersion(),
                request.noticeType().trim(), textKey, request.textVersion(), now);
        return new LicensePolicyResponse(id, scopeType, request.lockFileId(),
                normalizedArtifactName(request), request.artifactVersion(),
                request.noticeType().trim(), textKey, request.textVersion(), now);
    }

    private void validatePolicyRequest(LicensePolicyRequest request) {
        String scopeType = request.scopeType() == null ? null : request.scopeType().trim();
        if (!SCOPE_LOCK_FILE.equals(scopeType) && !SCOPE_ARTIFACT.equals(scopeType)) {
            throw ApiException.badRequest("scopeType 仅支持 LOCK_FILE 或 ARTIFACT");
        }
        if (!NOTICE_REQUIRED.equals(request.noticeType() == null ? null : request.noticeType().trim())) {
            throw ApiException.badRequest("noticeType 仅支持 NOTICE_REQUIRED");
        }
        if (SCOPE_LOCK_FILE.equals(scopeType)) {
            if (request.lockFileId() == null) {
                throw ApiException.badRequest("LOCK_FILE 作用域必须提供 lockFileId");
            }
            if (request.artifactName() != null || request.artifactVersion() != null) {
                throw ApiException.badRequest("LOCK_FILE 作用域不能携带制品坐标");
            }
        } else {
            if (request.artifactName() == null || request.artifactName().isBlank()) {
                throw ApiException.badRequest("ARTIFACT 作用域必须提供 artifactName");
            }
            if (request.lockFileId() != null) {
                throw ApiException.badRequest("ARTIFACT 作用域不能携带 lockFileId");
            }
        }
        boolean hasKey = request.textKey() != null && !request.textKey().isBlank();
        boolean hasVersion = request.textVersion() != null;
        if (hasKey != hasVersion) {
            throw ApiException.badRequest("textKey 与 textVersion 必须同时提供或同时缺省");
        }
    }

    // ------------------------------------------------------------------
    // 命中路径与缺失告知查询
    // ------------------------------------------------------------------

    @Override
    public List<PolicyHitView> listPolicyHits(long lockFileId) {
        LockContext context = loadLockContext(lockFileId);
        List<PolicyHitView> hits = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : context.entries().entrySet()) {
            for (PolicyRow policy : matchedPolicies(
                    context.policies(), lockFileId, entry.getKey(), entry.getValue())) {
                hits.add(new PolicyHitView(lockFileId, entry.getKey(), entry.getValue(),
                        policy.id(), policy.noticeType(), policy.textKey(), policy.textVersion(),
                        context.paths().get(entry.getKey())));
            }
        }
        return hits;
    }

    @Override
    public List<MissingNoticeView> listMissingNotices(long lockFileId, List<String> targetRegions) {
        List<String> regions = normalizeRegions(targetRegions);
        LockContext context = loadLockContext(lockFileId);
        return validateLock(context, regions).stream()
                .map(v -> new MissingNoticeView(v.lockFileId(), v.artifactName(),
                        v.artifactVersion(), v.policyId(), v.reason(), v.hitPath()))
                .toList();
    }

    // ------------------------------------------------------------------
    // 发布
    // ------------------------------------------------------------------

    @Override
    public PublishResponse publishLocks(PublishRequest request) {
        if (request.noticeKey() == null || request.noticeKey().isBlank()) {
            throw ApiException.badRequest("noticeKey 不能为空");
        }
        if (request.lockFileIds() == null || request.lockFileIds().isEmpty()) {
            throw ApiException.badRequest("lockFileIds 不能为空");
        }
        List<Long> lockFileIds = request.lockFileIds().stream().distinct().sorted().toList();
        if (lockFileIds.size() != request.lockFileIds().size()) {
            throw ApiException.badRequest("lockFileIds 存在重复");
        }
        List<String> targetRegions = normalizeRegions(request.targetRegions());
        String noticeKey = request.noticeKey().trim();

        // 指纹含锁定图版本、地区、规范化制品集合及文本版本，须在持锁事务内基于一致性视图计算。
        final PublishPlan[] plan = new PublishPlan[1];
        return idempotentExecutor.executeWithLateHash(noticeKey, OP_PUBLISH,
                () -> {
                    plan[0] = buildPublishPlan(lockFileIds, targetRegions);
                    return plan[0].fingerprint();
                },
                201,
                () -> executePublishPlan(noticeKey, targetRegions, plan[0]),
                PublishResponse.class);
    }

    @Override
    public List<PublishResponse> listPublishes() {
        List<PublishResponse> result = new ArrayList<>();
        for (SnapshotRow row : licenseDao.listSnapshots()) {
            result.add(toPublishResponse(row));
        }
        return result;
    }

    @Override
    public PublishResponse getPublish(long id) {
        SnapshotRow row = licenseDao.findSnapshot(id);
        if (row == null) {
            throw ApiException.notFound("发布快照不存在: " + id);
        }
        return toPublishResponse(row);
    }

    // ------------------------------------------------------------------
    // 发布内部：计划构建、校验与落库（均在持锁事务内）
    // ------------------------------------------------------------------

    /** 单条门禁校验失败记录。 */
    private record Violation(long lockFileId, String artifactName, int artifactVersion,
                             long policyId, String reason, String hitPath) {
    }

    /** 一张锁定图的发布上下文：锁文件、闭包、命中路径与全部策略。 */
    private record LockContext(LockFileRow lock, Map<String, Integer> entries,
                               Map<String, String> paths, List<PolicyRow> policies) {
    }

    /** 发布计划：各锁定图上下文与幂等指纹。 */
    private record PublishPlan(List<LockContext> contexts, String fingerprint) {
    }

    private LockContext loadLockContext(long lockFileId) {
        LockFileRow lock = repositoryDao.getLockFile(lockFileId);
        if (lock == null) {
            throw ApiException.notFound("锁文件不存在: " + lockFileId);
        }
        Map<String, Integer> entries = new TreeMap<>();
        for (LockEntryRow entry : repositoryDao.listLockEntries(lockFileId)) {
            entries.put(entry.name(), entry.version());
        }
        Map<String, List<DependencyRange>> dependenciesByName = new LinkedHashMap<>();
        entries.forEach((name, version) -> {
            ArtifactVersion artifact = repositoryDao.loadArtifact(name, version);
            dependenciesByName.put(name, artifact == null ? List.of() : artifact.dependencies());
        });
        Map<String, String> paths = NoticeHitPaths.compute(lock.rootName(), entries, dependenciesByName);
        return new LockContext(lock, entries, paths, licenseDao.listPolicies());
    }

    private PublishPlan buildPublishPlan(List<Long> lockFileIds, List<String> targetRegions) {
        List<LockContext> contexts = new ArrayList<>();
        for (Long lockFileId : lockFileIds) {
            contexts.add(loadLockContext(lockFileId));
        }
        StringBuilder canonical = new StringBuilder("regions=").append(toCsv(targetRegions));
        for (LockContext context : contexts) {
            LockFileRow lock = context.lock();
            canonical.append("|lock#").append(lock.id())
                    .append(':').append(lock.rootName()).append(':').append(lock.rootVersion())
                    .append("@repo").append(lock.repositoryVersion())
                    .append("|entries=");
            context.entries().forEach((name, version) ->
                    canonical.append(name).append('=').append(version).append(','));
            canonical.append("|notices=");
            for (Map.Entry<String, Integer> entry : context.entries().entrySet()) {
                List<String> bindings = new ArrayList<>();
                for (PolicyRow policy : matchedPolicies(context.policies(), lock.id(),
                        entry.getKey(), entry.getValue())) {
                    if (NOTICE_REQUIRED.equals(policy.noticeType())) {
                        bindings.add(policy.textKey() == null
                                ? "UNBOUND" : policy.textKey() + "@" + policy.textVersion());
                    }
                }
                if (!bindings.isEmpty()) {
                    canonical.append(entry.getKey()).append(':').append(entry.getValue())
                            .append('=').append(String.join("+", bindings)).append(';');
                }
            }
        }
        return new PublishPlan(contexts, Digests.sha256(canonical.toString()));
    }

    private PublishResponse executePublishPlan(String noticeKey, List<String> targetRegions,
                                               PublishPlan plan) {
        List<Violation> violations = new ArrayList<>();
        for (LockContext context : plan.contexts()) {
            violations.addAll(validateLock(context, targetRegions));
        }
        if (!violations.isEmpty()) {
            Violation first = violations.get(0);
            throw ApiException.unprocessable(first.reason(),
                    "发布门禁校验失败[" + first.reason() + "]: lockFile=" + first.lockFileId()
                            + " artifact=" + first.artifactName() + ":" + first.artifactVersion()
                            + " policy=" + first.policyId() + " path=" + first.hitPath()
                            + "（整批共 " + violations.size() + " 处失败，未发布任何部分）");
        }

        // 与 TIMESTAMP(6) 存储精度对齐，保证发布响应与历史快照查询一致。
        Instant now = Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        long snapshotId = licenseDao.insertSnapshot(noticeKey, toCsv(targetRegions), now);
        List<PublishLockView> locks = new ArrayList<>();
        for (LockContext context : plan.contexts()) {
            LockFileRow lock = context.lock();
            long snapshotLockId = licenseDao.insertSnapshotLock(snapshotId, lock.id(),
                    lock.rootName(), lock.rootVersion(), lock.repositoryVersion());
            List<PublishEntryView> entries = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : context.entries().entrySet()) {
                List<PolicyRow> required = matchedPolicies(context.policies(), lock.id(),
                        entry.getKey(), entry.getValue()).stream()
                        .filter(p -> NOTICE_REQUIRED.equals(p.noticeType()))
                        .toList();
                String hitPath = context.paths().get(entry.getKey());
                if (required.isEmpty()) {
                    licenseDao.insertSnapshotEntry(snapshotLockId, entry.getKey(), entry.getValue(),
                            null, null, null, null, hitPath);
                    entries.add(new PublishEntryView(entry.getKey(), entry.getValue(),
                            null, null, null, null, hitPath));
                } else {
                    for (PolicyRow policy : required) {
                        NoticeTextRow text = licenseDao.findNoticeText(
                                policy.textKey(), policy.textVersion());
                        licenseDao.insertSnapshotEntry(snapshotLockId, entry.getKey(),
                                entry.getValue(), policy.id(), policy.textKey(),
                                policy.textVersion(), text.regions(), hitPath);
                        entries.add(new PublishEntryView(entry.getKey(), entry.getValue(),
                                policy.id(), policy.textKey(), policy.textVersion(),
                                parseCsv(text.regions()), hitPath));
                    }
                }
            }
            locks.add(new PublishLockView(lock.id(), lock.rootName(), lock.rootVersion(),
                    lock.repositoryVersion(), entries));
        }
        return new PublishResponse(snapshotId, noticeKey, targetRegions, now, locks);
    }

    /**
     * 校验单张锁定图的最终依赖闭包：全部直接、传递制品的命中策略、
     * 文本版本批准状态与地区覆盖。返回顺序稳定（制品名称升序、策略 ID 升序）。
     */
    private List<Violation> validateLock(LockContext context, List<String> targetRegions) {
        List<Violation> violations = new ArrayList<>();
        long lockFileId = context.lock().id();
        for (Map.Entry<String, Integer> entry : context.entries().entrySet()) {
            for (PolicyRow policy : matchedPolicies(context.policies(), lockFileId,
                    entry.getKey(), entry.getValue())) {
                if (!NOTICE_REQUIRED.equals(policy.noticeType())) {
                    continue;
                }
                String hitPath = context.paths().get(entry.getKey());
                if (policy.textKey() == null) {
                    violations.add(new Violation(lockFileId, entry.getKey(), entry.getValue(),
                            policy.id(), REASON_MISSING_NOTICE, hitPath));
                    continue;
                }
                NoticeTextRow text = licenseDao.findNoticeText(policy.textKey(), policy.textVersion());
                if (text == null) {
                    violations.add(new Violation(lockFileId, entry.getKey(), entry.getValue(),
                            policy.id(), REASON_MISSING_NOTICE, hitPath));
                    continue;
                }
                if (!STATUS_APPROVED.equals(text.status())) {
                    violations.add(new Violation(lockFileId, entry.getKey(), entry.getValue(),
                            policy.id(), REASON_TEXT_NOT_APPROVED, hitPath));
                    continue;
                }
                if (!parseCsv(text.regions()).containsAll(targetRegions)) {
                    violations.add(new Violation(lockFileId, entry.getKey(), entry.getValue(),
                            policy.id(), REASON_REGION_NOT_COVERED, hitPath));
                }
            }
        }
        return violations;
    }

    // ------------------------------------------------------------------
    // 匹配、转换与工具
    // ------------------------------------------------------------------

    /** 策略匹配：LOCK_FILE 精确命中该锁定图；ARTIFACT 按名称（可限定版本）命中。 */
    private List<PolicyRow> matchedPolicies(List<PolicyRow> policies, long lockFileId,
                                            String artifactName, int artifactVersion) {
        List<PolicyRow> matched = new ArrayList<>();
        for (PolicyRow policy : policies) {
            if (SCOPE_LOCK_FILE.equals(policy.scopeType())) {
                if (policy.lockFileId() != null && policy.lockFileId() == lockFileId) {
                    matched.add(policy);
                }
            } else if (SCOPE_ARTIFACT.equals(policy.scopeType())
                    && policy.artifactName() != null
                    && policy.artifactName().equals(artifactName)
                    && (policy.artifactVersion() == null
                            || policy.artifactVersion() == artifactVersion)) {
                matched.add(policy);
            }
        }
        return matched;
    }

    private PublishResponse toPublishResponse(SnapshotRow row) {
        List<PublishLockView> locks = new ArrayList<>();
        for (SnapshotLockRow lockRow : licenseDao.listSnapshotLocks(row.id())) {
            List<PublishEntryView> entries = new ArrayList<>();
            for (SnapshotEntryRow entryRow : licenseDao.listSnapshotEntries(lockRow.id())) {
                entries.add(new PublishEntryView(entryRow.artifactName(), entryRow.artifactVersion(),
                        entryRow.policyId(), entryRow.textKey(), entryRow.textVersion(),
                        entryRow.noticeRegions() == null ? null : parseCsv(entryRow.noticeRegions()),
                        entryRow.hitPath()));
            }
            locks.add(new PublishLockView(lockRow.lockFileId(), lockRow.rootName(),
                    lockRow.rootVersion(), lockRow.repositoryVersion(), entries));
        }
        return new PublishResponse(row.id(), row.noticeKey(), parseCsv(row.targetRegions()),
                row.createdAt(), locks);
    }

    private NoticeTextResponse toTextResponse(NoticeTextRow row) {
        return new NoticeTextResponse(row.textKey(), row.version(), row.content(),
                parseCsv(row.regions()), row.status(), row.createdAt(), row.updatedAt());
    }

    private LicensePolicyResponse toPolicyResponse(PolicyRow row) {
        return new LicensePolicyResponse(row.id(), row.scopeType(), row.lockFileId(),
                row.artifactName(), row.artifactVersion(), row.noticeType(),
                row.textKey(), row.textVersion(), row.createdAt());
    }

    private NoticeTextRow requireNoticeText(String textKey, int version) {
        NoticeTextRow row = licenseDao.findNoticeText(textKey, version);
        if (row == null) {
            throw ApiException.notFound("告知文本版本不存在: " + textKey + ":" + version);
        }
        return row;
    }

    private String requireTextKey(String textKey) {
        if (textKey == null || textKey.isBlank()) {
            throw ApiException.badRequest("textKey 不能为空");
        }
        return textKey.trim();
    }

    private String normalizedArtifactName(LicensePolicyRequest request) {
        return request.artifactName() == null ? null : request.artifactName().trim();
    }

    private String normalizedTextKey(LicensePolicyRequest request) {
        return request.textKey() == null || request.textKey().isBlank()
                ? null : request.textKey().trim();
    }

    /**
     * 规范化地区集合：去空白、大写、去重、升序；空集合或非法地区码返回 400。
     */
    private List<String> normalizeRegions(List<String> regions) {
        if (regions == null || regions.isEmpty()) {
            throw ApiException.badRequest("地区集合不能为空");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String region : regions) {
            if (region == null || region.isBlank()) {
                throw ApiException.badRequest("地区码不能为空");
            }
            String code = region.trim().toUpperCase(Locale.ROOT);
            if (!code.matches("[A-Z0-9-]{1,16}")) {
                throw ApiException.badRequest("地区码不合法: " + region);
            }
            normalized.add(code);
        }
        return List.copyOf(normalized);
    }

    private String toCsv(List<String> values) {
        return String.join(",", values);
    }

    private List<String> parseCsv(String csv) {
        return List.of(csv.split(","));
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("缺少请求头 X-Request-Id");
        }
    }
}
