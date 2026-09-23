package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 关联观测簇与字段级联合裁决业务服务。
 *
 * <p>建簇（BUNDLE_CREATE）与联合裁决（ARBITRATE）复用 request_log 占位幂等机制：
 * 业务失败回滚不占键；requestId 同参重放首次成功快照，观测与字段项换序视为同参，异参 409。
 *
 * <p>联合裁决在单事务内：先锁定簇行再按观测标识升序锁定簇内全部当前行，
 * 与离线同步、单条冲突解决、删除及另一联合裁决按提交顺序串行；任一项校验失败则
 * 所有观测版本、墓碑状态与冲突状态保持不变；成功后一次性生成新版本、关闭全部冲突、
 * 写入逐字段来源、墓碑恢复依据与簇级前后快照。
 */
@Service
public class BundleService {

    private static final String SEPARATOR = "";
    private static final Pattern READING_PATTERN = Pattern.compile("-?\\d+(\\.\\d{1,3})?");
    private static final int READING_MAX_LENGTH = 64;
    private static final int LOCATION_MAX_LENGTH = 512;
    private static final int NOTE_MAX_LENGTH = 1024;

    private static final List<String> FIELDS = List.of("location", "reading", "note");

    private final ObservationRepository observationRepository;
    private final BundleRepository bundleRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BundleService(ObservationRepository observationRepository,
                         BundleRepository bundleRepository,
                         RequestLogRepository requestLogRepository,
                         ObjectMapper objectMapper,
                         Clock clock) {
        this.observationRepository = observationRepository;
        this.bundleRepository = bundleRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 建立关联观测簇：校验成员数量、surveyId 一致、无重复、未进入其他未结簇，冻结各观测 currentVersion，
     * 存活成员按三方规则登记字段冲突，墓碑成员登记三个 RESTORE 待恢复字段。
     */
    @Transactional
    public BundleOutcome createBundle(CreateBundleRequest request) {
        List<String> consistentFields = normalizeConsistentFields(request.consistentFields());
        List<BundleMemberItem> normalizedMembers = normalizeMembers(request.members());
        String fingerprint = fingerprint("BUNDLE_CREATE", request.bundleKey(), request.surveyId(),
                String.join(",", consistentFields), request.operator(), canonicalMembers(normalizedMembers));
        BundleOutcome replayed = checkBundleReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        BundleOutcome concurrent = insertBundlePlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        if (bundleRepository.findBundleByKey(request.bundleKey()).isPresent()) {
            throw ApiException.conflict("bundle already exists: " + request.bundleKey(), null);
        }

        // 成员项重复（换序后）在规范化阶段拒绝
        Set<String> memberIds = normalizedMembers.stream().map(BundleMemberItem::observationId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (memberIds.size() != normalizedMembers.size()) {
            throw ApiException.badRequest("duplicate observation in bundle members");
        }

        // 按观测标识升序锁定全部当前行，保证并发建簇的重复加入判定可串行化
        List<String> orderedIds = new ArrayList<>(memberIds);
        orderedIds.sort(String::compareTo);
        List<ObservationSnapshot> currents = observationRepository.findCurrentsForUpdateOrdered(orderedIds);
        Map<String, ObservationSnapshot> currentById = currents.stream()
                .collect(Collectors.toMap(ObservationSnapshot::observationId, snapshot -> snapshot,
                        (a, b) -> a, LinkedHashMap::new));
        for (String observationId : orderedIds) {
            ObservationSnapshot current = currentById.get(observationId);
            if (current == null) {
                throw ApiException.notFound("observation not found: " + observationId);
            }
            if (!Objects.equals(current.surveyId(), request.surveyId())) {
                throw ApiException.badRequest(
                        "observation surveyId mismatch: " + observationId + " expected " + request.surveyId());
            }
        }

        BundleRecord bundle = new BundleRecord(request.bundleKey(), request.surveyId(),
                List.copyOf(consistentFields), BundleRecord.OPEN, request.operator(), null);
        try {
            bundleRepository.insertBundle(bundle);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("bundle already exists: " + request.bundleKey(), null);
        }

        Map<String, BundleMemberItem> itemById = normalizedMembers.stream()
                .collect(Collectors.toMap(BundleMemberItem::observationId, item -> item,
                        (a, b) -> a, LinkedHashMap::new));
        List<BundleMemberRecord> frozenMembers = new ArrayList<>();
        List<FieldConflictRecord> conflicts = new ArrayList<>();
        for (String observationId : orderedIds) {
            ObservationSnapshot current = currentById.get(observationId);
            BundleMemberItem item = itemById.get(observationId);
            if (item.candidatePartiallyProvided()) {
                throw ApiException.badRequest(
                        "candidate values must be provided together or omitted: " + observationId);
            }

            if (current.deleted()) {
                // 墓碑观测只能作为待恢复项：请求中不得携带候选值，且不能已在其他未结簇中
                if (item.hasCandidate()) {
                    throw ApiException.badRequest(
                            "tombstone observation can only be a pending restore item: " + observationId);
                }
                attachOrReject(observationId, request.bundleKey());
                BundleMemberRecord member = new BundleMemberRecord(request.bundleKey(), observationId,
                        current.surveyId(), current.version(), true, null, null, null, null);
                frozenMembers.add(member);
                for (String field : FIELDS) {
                    conflicts.add(new FieldConflictRecord(null, request.bundleKey(), observationId, field,
                            FieldConflictRecord.RESTORE, null, null, null, null,
                            FieldConflictRecord.OPEN, null, null, null, null, null));
                }
            } else {
                if (!item.hasCandidate()) {
                    throw ApiException.badRequest(
                            "live observation requires complete candidate values: " + observationId);
                }
                attachOrReject(observationId, request.bundleKey());
                ObservationSnapshot base = observationRepository
                        .findVersion(observationId, item.baseVersion())
                        .orElseThrow(() -> ApiException.notFound(
                                "base version not found: " + observationId + "@" + item.baseVersion()));
                if (base.deleted()) {
                    throw ApiException.badRequest(
                            "base version must not be a tombstone: " + observationId + "@" + item.baseVersion());
                }
                BundleMemberRecord member = new BundleMemberRecord(request.bundleKey(), observationId,
                        current.surveyId(), current.version(), false, item.baseVersion(),
                        item.location(), item.reading(), item.note());
                frozenMembers.add(member);
                registerFieldConflicts(current, base, item, observationId, request.bundleKey(), conflicts);
            }
        }

        for (BundleMemberRecord member : frozenMembers) {
            bundleRepository.insertMember(member);
        }
        for (FieldConflictRecord conflict : conflicts) {
            bundleRepository.insertConflict(conflict);
        }

        BundleResponse body = buildBundleResponse(bundle, frozenMembers, conflicts);
        return completeBundle(request.requestId(), HttpStatus.CREATED, body);
    }

    /**
     * 联合裁决：重读完整簇、全部未决冲突与簇内当前版本，校验请求恰好覆盖且选择有效、
     * expectedVersion 匹配、必填字段齐全、声明一致字段规范化后全部相同，随后原子应用全部变更。
     */
    @Transactional
    public ArbitrationOutcome arbitrate(String bundleKey, ArbitrateBundleRequest request) {
        String decisionKey = canonicalDecisions(request.decisions());
        String versionsKey = canonicalVersions(request.expectedVersions());
        String fingerprint = fingerprint("ARBITRATE", bundleKey, request.operator(), versionsKey, decisionKey);
        ArbitrationOutcome replayed = checkArbitrationReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ArbitrationOutcome concurrent = insertArbitrationPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // 先锁簇行，串行化针对同一簇的并发裁决；再按固定顺序锁成员当前行，避免死锁与半个簇裁决
        BundleRecord bundle = bundleRepository.findBundleByKeyForUpdate(bundleKey)
                .orElseThrow(() -> ApiException.notFound("bundle not found: " + bundleKey));
        if (!bundle.open()) {
            throw ApiException.conflict("bundle already closed: " + bundleKey, null);
        }

        List<BundleMemberRecord> members = bundleRepository.findMembersByKey(bundleKey);
        Map<String, BundleMemberRecord> memberById = members.stream()
                .collect(Collectors.toMap(BundleMemberRecord::observationId, member -> member,
                        (a, b) -> a, LinkedHashMap::new));

        List<String> orderedIds = members.stream().map(BundleMemberRecord::observationId).toList();
        List<ObservationSnapshot> currents = observationRepository.findCurrentsForUpdateOrdered(orderedIds);
        Map<String, ObservationSnapshot> currentById = currents.stream()
                .collect(Collectors.toMap(ObservationSnapshot::observationId, snapshot -> snapshot,
                        (a, b) -> a, LinkedHashMap::new));

        List<FieldConflictRecord> openConflicts = bundleRepository.findOpenConflictsByKey(bundleKey);
        Map<ConflictKey, FieldConflictRecord> conflictByKey = openConflicts.stream()
                .collect(Collectors.toMap(
                        conflict -> new ConflictKey(conflict.observationId(), conflict.fieldName()),
                        conflict -> conflict, (a, b) -> a, LinkedHashMap::new));

        validateExpectedVersions(request, members, currentById);
        Map<ConflictKey, FieldDecision> decisions =
                validateDecisions(request, memberById, conflictByKey);

        // 计算每条观测裁决后的完整内容：冲突字段取人工决定，存活成员的非冲突字段按原三方规则自动合并；
        // 墓碑恢复字段必须三个必填项齐全。changed 标记内容或存活状态是否变化（决定是否生成新版本）。
        Map<String, FinalContent> finalContentById = new LinkedHashMap<>();
        Map<String, LinkedHashMap<String, ResolvedField>> resolvedByObservation = new LinkedHashMap<>();
        for (String observationId : orderedIds) {
            resolvedByObservation.put(observationId, new LinkedHashMap<>());
        }
        for (FieldConflictRecord conflict : openConflicts) {
            FieldDecision decision = decisions.get(new ConflictKey(conflict.observationId(), conflict.fieldName()));
            ObservationSnapshot current = currentById.get(conflict.observationId());
            ResolvedField resolved = resolveOne(conflict, decision, current);
            resolvedByObservation.get(conflict.observationId()).put(conflict.fieldName(), resolved);
        }
        for (BundleMemberRecord member : members) {
            ObservationSnapshot current = currentById.get(member.observationId());
            LinkedHashMap<String, ResolvedField> resolved = resolvedByObservation.get(member.observationId());
            FinalContent content = buildFinalContent(member, current, resolved);
            finalContentById.put(member.observationId(), content);
        }

        validateConsistency(bundle, members, finalContentById);

        return applyArbitration(bundle, request, members, currentById,
                openConflicts, resolvedByObservation, finalContentById);
    }

    /**
     * 查询簇证据：只读，返回簇信息、冻结成员与全部冲突（按观测、字段稳定排序）；簇不存在返回 404。
     */
    @Transactional(readOnly = true)
    public BundleResponse getBundle(String bundleKey) {
        BundleRecord bundle = bundleRepository.findBundleByKey(bundleKey)
                .orElseThrow(() -> ApiException.notFound("bundle not found: " + bundleKey));
        List<BundleMemberRecord> members = bundleRepository.findMembersByKey(bundleKey);
        List<FieldConflictRecord> conflicts = bundleRepository.findConflictsByKey(bundleKey);
        return buildBundleResponse(bundle, members, conflicts);
    }

    /**
     * 按联合裁决 requestId 查询不可变裁决记录及其逐字段证据；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ArbitrationResponse getArbitration(String requestId) {
        ArbitrationRecordView record = bundleRepository.findArbitrationByRequestId(requestId)
                .orElseThrow(() -> ApiException.notFound("arbitration not found: " + requestId));
        return responseFromRecord(record);
    }

    // ---------- 建簇辅助 ----------

    /**
     * 挂接未结簇；该观测已在其他未结簇时拒绝（不允许重复加入）。调用方已锁定全部成员当前行。
     */
    private void attachOrReject(String observationId, String bundleKey) {
        String existing = observationRepository.findOpenBundleKeyForUpdate(observationId);
        if (existing == null) {
            observationRepository.attachOpenBundleIfFree(observationId, bundleKey);
            return;
        }
        if (Objects.equals(existing, bundleKey)) {
            throw ApiException.conflict(
                    "observation already attached to this bundle: " + observationId, null);
        }
        throw ApiException.conflict(
                "observation already belongs to another open bundle: " + observationId, null);
    }

    /**
     * 存活成员按建簇登记时的三方规则计算字段冲突并追加到 conflicts。
     */
    private void registerFieldConflicts(ObservationSnapshot current, ObservationSnapshot base,
                                        BundleMemberItem item, String observationId, String bundleKey,
                                        List<FieldConflictRecord> conflicts) {
        if (fieldConflicts(base.location(), current.location(), item.location(), false)) {
            conflicts.add(new FieldConflictRecord(null, bundleKey, observationId, "location",
                    FieldConflictRecord.FIELD, item.baseVersion(), base.location(),
                    current.location(), item.location(), FieldConflictRecord.OPEN, null, null, null, null, null));
        }
        if (fieldConflicts(base.reading(), current.reading(), item.reading(), true)) {
            conflicts.add(new FieldConflictRecord(null, bundleKey, observationId, "reading",
                    FieldConflictRecord.FIELD, item.baseVersion(), base.reading(),
                    current.reading(), item.reading(), FieldConflictRecord.OPEN, null, null, null, null, null));
        }
        if (fieldConflicts(base.note(), current.note(), item.note(), false)) {
            conflicts.add(new FieldConflictRecord(null, bundleKey, observationId, "note",
                    FieldConflictRecord.FIELD, item.baseVersion(), base.note(),
                    current.note(), item.note(), FieldConflictRecord.OPEN, null, null, null, null, null));
        }
    }

    // ---------- 裁决校验 ----------

    /**
     * 校验每条成员观测的 expectedVersion：不得遗漏、多余，且必须等于重读后的当前版本（候选已变化检测）。
     */
    private void validateExpectedVersions(ArbitrateBundleRequest request,
                                          List<BundleMemberRecord> members,
                                          Map<String, ObservationSnapshot> currentById) {
        Set<String> expectedKeys = new LinkedHashSet<>(request.expectedVersions().keySet());
        Set<String> memberKeys = members.stream().map(BundleMemberRecord::observationId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!expectedKeys.equals(memberKeys)) {
            List<String> missing = new ArrayList<>(memberKeys);
            missing.removeAll(expectedKeys);
            List<String> extra = new ArrayList<>(expectedKeys);
            extra.removeAll(memberKeys);
            List<String> problems = new ArrayList<>();
            if (!missing.isEmpty()) {
                problems.add("missing expectedVersion for observations: " + String.join(", ", missing));
            }
            if (!extra.isEmpty()) {
                problems.add("unexpected expectedVersion for observations: " + String.join(", ", extra));
            }
            throw ApiException.badRequest("expectedVersions must cover exactly the bundle members; "
                    + String.join("; ", problems));
        }
        for (BundleMemberRecord member : members) {
            ObservationSnapshot current = currentById.get(member.observationId());
            if (current == null) {
                throw ApiException.notFound("observation not found: " + member.observationId());
            }
            Integer expected = request.expectedVersions().get(member.observationId());
            if (expected == null || expected != current.version()) {
                throw ApiException.conflict(
                        "expectedVersion mismatch: " + member.observationId(), current.version());
            }
            if (member.tombstoneAtFreeze() != current.deleted()) {
                // 候选已变化：建簇后成员存活状态被单条操作改变（如删除），登记的冲突集已失效
                throw ApiException.conflict(
                        "observation state changed after bundle freeze: " + member.observationId(),
                        current.version());
            }
        }
    }

    /**
     * 校验逐字段决定：恰好覆盖全部 OPEN 冲突，无遗漏、多余、重复；成员与字段必须属于该簇；
     * EXPLICIT 必须给值并通过字段格式校验；非墓碑成员不得使用 BASE 之外不存在的来源取值问题；
     * 墓碑恢复必须逐字段给出来源（RESTORE 无 local/remote/base 快照，只能 EXPLICIT 或 BASE 历史版本）。
     */
    private Map<ConflictKey, FieldDecision> validateDecisions(
            ArbitrateBundleRequest request,
            Map<String, BundleMemberRecord> memberById,
            Map<ConflictKey, FieldConflictRecord> conflictByKey) {
        Map<ConflictKey, FieldDecision> decisions = new LinkedHashMap<>();
        for (FieldDecision decision : request.decisions()) {
            String field = normalizeField(decision.field());
            String observationId = decision.observationId();
            if (!memberById.containsKey(observationId)) {
                throw ApiException.badRequest("decision for unknown bundle member: " + observationId);
            }
            ConflictKey key = new ConflictKey(observationId, field);
            if (decisions.put(key, new FieldDecision(observationId, field, decision.source(),
                    decision.value(), decision.baseVersion())) != null) {
                throw ApiException.badRequest("duplicate decision: " + observationId + "#" + field);
            }
            if (decision.source() == ArbitrationSource.EXPLICIT) {
                if (decision.value() == null || decision.value().isBlank()) {
                    throw ApiException.badRequest(
                            "EXPLICIT decision requires a value: " + observationId + "#" + field);
                }
                validateFieldValue(field, decision.value());
            }
        }

        if (!decisions.keySet().equals(conflictByKey.keySet())) {
            List<String> missing = conflictByKey.keySet().stream()
                    .filter(key -> !decisions.containsKey(key))
                    .map(ConflictKey::render)
                    .sorted()
                    .toList();
            List<String> extra = decisions.keySet().stream()
                    .filter(key -> !conflictByKey.containsKey(key))
                    .map(ConflictKey::render)
                    .sorted()
                    .toList();
            List<String> problems = new ArrayList<>();
            if (!missing.isEmpty()) {
                problems.add("missing decisions for open conflicts: " + String.join(", ", missing));
            }
            if (!extra.isEmpty()) {
                problems.add("decisions for non-open conflicts are not allowed: " + String.join(", ", extra));
            }
            throw ApiException.badRequest(
                    "decisions must cover exactly the open field conflicts; " + String.join("; ", problems));
        }

        // 逐冲突校验来源在该冲突上可取
        for (FieldConflictRecord conflict : conflictByKey.values()) {
            FieldDecision decision = decisions.get(new ConflictKey(conflict.observationId(), conflict.fieldName()));
            validateSourceAvailable(conflict, decision);
        }
        return decisions;
    }

    /**
     * 校验单个决定的来源是否对该冲突可用：
     * FIELD 冲突可取 LOCAL/REMOTE/BASE（均为登记时快照）或 EXPLICIT；
     * RESTORE（墓碑）只能 EXPLICIT，或 BASE 指定一个存在的非墓碑历史版本；不得直接取墓碑候选。
     */
    private void validateSourceAvailable(FieldConflictRecord conflict, FieldDecision decision) {
        String where = conflict.observationId() + "#" + conflict.fieldName();
        if (FieldConflictRecord.FIELD.equals(conflict.conflictType())) {
            if (decision.source() == ArbitrationSource.BASE) {
                if (decision.baseVersion() != null
                        && decision.baseVersion() != conflict.baseVersion()) {
                    throw ApiException.badRequest(
                            "BASE decision version must match registered base version: " + where);
                }
            } else if (decision.baseVersion() != null) {
                throw ApiException.badRequest(
                        "baseVersion is only allowed for BASE/restore source: " + where);
            }
            return;
        }
        // RESTORE 墓碑恢复
        if (decision.source() == ArbitrationSource.LOCAL || decision.source() == ArbitrationSource.REMOTE) {
            throw ApiException.badRequest(
                    "tombstone cannot provide LOCAL/REMOTE candidate values: " + where);
        }
        if (decision.source() == ArbitrationSource.BASE) {
            if (decision.baseVersion() == null) {
                throw ApiException.badRequest(
                        "BASE restore requires an explicit historical baseVersion: " + where);
            }
            ObservationSnapshot historical = observationRepository
                    .findVersion(conflict.observationId(), decision.baseVersion())
                    .orElseThrow(() -> ApiException.notFound(
                            "restore base version not found: "
                                    + conflict.observationId() + "@" + decision.baseVersion()));
            if (historical.deleted()) {
                throw ApiException.badRequest(
                        "restore base version must not be a tombstone: "
                                + conflict.observationId() + "@" + decision.baseVersion());
            }
            if (fieldValue(historical, conflict.fieldName()) == null) {
                throw ApiException.badRequest(
                        "restore base field value missing: " + where);
            }
        }
    }

    /**
     * 计算单个冲突的最终字段值、来源与墓碑恢复依据。
     */
    private ResolvedField resolveOne(FieldConflictRecord conflict, FieldDecision decision,
                                     ObservationSnapshot current) {
        String where = conflict.observationId() + "#" + conflict.fieldName();
        String value;
        String basis = null;
        switch (decision.source()) {
            case LOCAL -> {
                value = conflict.localValue();
                basis = "local current v" + current.version();
            }
            case REMOTE -> {
                value = conflict.remoteValue();
                basis = "remote candidate against base v" + conflict.baseVersion();
            }
            case BASE -> {
                if (FieldConflictRecord.FIELD.equals(conflict.conflictType())) {
                    value = conflict.baseValue();
                    basis = "base v" + conflict.baseVersion();
                } else {
                    ObservationSnapshot historical = observationRepository
                            .findVersion(conflict.observationId(), decision.baseVersion()).orElseThrow();
                    value = fieldValue(historical, conflict.fieldName());
                    basis = "restored from historical base v" + decision.baseVersion();
                }
            }
            case EXPLICIT -> {
                value = decision.value();
                basis = FieldConflictRecord.RESTORE.equals(conflict.conflictType())
                        ? "explicit new value provided on restore"
                        : "explicit new value";
            }
            default -> throw ApiException.badRequest("unsupported arbitration source: " + where);
        }
        if (value == null) {
            throw ApiException.badRequest("resolved value missing for required field: " + where);
        }
        validateFieldValue(conflict.fieldName(), value);
        String restoreBasis = FieldConflictRecord.RESTORE.equals(conflict.conflictType())
                ? conflict.observationId() + " " + conflict.fieldName() + " <- " + basis : null;
        return new ResolvedField(value, decision.source(), restoreBasis, decision.baseVersion());
    }

    /**
     * 计算单条观测裁决后的完整内容：
     * 存活成员的冲突字段取人工决定，非冲突字段按原三方规则（候选未改保留当前，否则接受候选）自动合并；
     * 墓碑成员的三个必填字段都必须由 RESTORE 决定提供。
     */
    private FinalContent buildFinalContent(BundleMemberRecord member, ObservationSnapshot current,
                                           LinkedHashMap<String, ResolvedField> resolved) {
        String location;
        String reading;
        String note;
        if (current.deleted()) {
            if (!resolved.containsKey("location") || !resolved.containsKey("reading")
                    || !resolved.containsKey("note")) {
                throw ApiException.badRequest(
                        "tombstone restore requires sources for all required fields: "
                                + member.observationId());
            }
            location = resolved.get("location").value();
            reading = resolved.get("reading").value();
            note = resolved.get("note").value();
        } else {
            location = resolved.containsKey("location")
                    ? resolved.get("location").value()
                    : autoMergeField("location", member, current);
            reading = resolved.containsKey("reading")
                    ? resolved.get("reading").value()
                    : autoMergeField("reading", member, current);
            note = resolved.containsKey("note")
                    ? resolved.get("note").value()
                    : autoMergeField("note", member, current);
        }
        validateFieldValue("location", location);
        validateFieldValue("reading", reading);
        validateFieldValue("note", note);
        boolean changed = current.deleted()
                || !Objects.equals(location, current.location())
                || !readingEquals(reading, current.reading())
                || !Objects.equals(note, current.note());
        return new FinalContent(location, reading, note, changed);
    }

    /**
     * 存活成员非冲突字段的自动合并：候选相对基线未改则保留当前，否则接受候选（建簇时已确认非冲突）。
     */
    private String autoMergeField(String field, BundleMemberRecord member, ObservationSnapshot current) {
        ObservationSnapshot base = observationRepository
                .findVersion(member.observationId(), member.baseVersion()).orElseThrow(() ->
                        ApiException.notFound("base version not found: "
                                + member.observationId() + "@" + member.baseVersion()));
        String baseValue = fieldValue(base, field);
        String remoteValue = switch (field) {
            case "location" -> member.remoteLocation();
            case "reading" -> member.remoteReading();
            case "note" -> member.remoteNote();
            default -> throw ApiException.badRequest("unknown field: " + field);
        };
        boolean numeric = "reading".equals(field);
        if (valueEquals(remoteValue, baseValue, numeric)) {
            return fieldValue(current, field);
        }
        return remoteValue;
    }

    /**
     * 一致性校验：声明必须一致的字段，规范化后的最终值必须在全部非墓碑观测间相同。
     * 裁决后全部成员都为存活（墓碑已恢复），因此所有成员均参与比较；观测顺序固定为标识升序。
     */
    private void validateConsistency(BundleRecord bundle,
                                     List<BundleMemberRecord> members,
                                     Map<String, FinalContent> finalContentById) {
        for (String field : bundle.consistentFields()) {
            String normalized = null;
            String owner = null;
            for (BundleMemberRecord member : members) {
                FinalContent content = finalContentById.get(member.observationId());
                String candidate = normalizeValue(field, fieldValue(content, field));
                if (normalized == null) {
                    normalized = candidate;
                    owner = member.observationId();
                } else if (!Objects.equals(normalized, candidate)) {
                    throw ApiException.conflict(
                            "consistent field mismatch after arbitration: '" + field + "' differs between "
                                    + owner + " and " + member.observationId(), null);
                }
            }
        }
    }

    private String fieldValue(FinalContent content, String field) {
        return switch (field) {
            case "location" -> content.location();
            case "reading" -> content.reading();
            case "note" -> content.note();
            default -> throw ApiException.badRequest("unknown field: " + field);
        };
    }

    // ---------- 应用裁决 ----------

    /**
     * 应用裁决：对发生变化的观测一次性写入新版本（墓碑恢复必产生新版本），无变化的观测不加版本；
     * 随后关闭全部冲突、解除未结簇挂接、关闭簇、写入簇级前后快照与 request_log 响应；全部在同一事务提交。
     */
    private ArbitrationOutcome applyArbitration(
            BundleRecord bundle,
            ArbitrateBundleRequest request,
            List<BundleMemberRecord> members,
            Map<String, ObservationSnapshot> currentById,
            List<FieldConflictRecord> openConflicts,
            Map<String, LinkedHashMap<String, ResolvedField>> resolvedByObservation,
            Map<String, FinalContent> finalContentById) {
        Instant now = Instant.now(clock);
        List<ObservationResponse> before = new ArrayList<>();
        List<ObservationResponse> after = new ArrayList<>();
        List<FieldConflictResponse> conflictResponses = new ArrayList<>();

        for (BundleMemberRecord member : members) {
            ObservationSnapshot current = currentById.get(member.observationId());
            FinalContent content = finalContentById.get(member.observationId());
            before.add(ObservationResponse.of(current));

            ObservationSnapshot resulting;
            if (content.changed()) {
                ObservationSnapshot next = new ObservationSnapshot(current.observationId(), current.surveyId(),
                        current.version() + 1, content.location(), content.reading(), content.note(), false);
                if (current.deleted()) {
                    observationRepository.restoreCurrent(next);
                } else {
                    observationRepository.updateCurrent(next);
                }
                observationRepository.insertVersion(next);
                resulting = next;
            } else {
                // 裁决结果与当前完全相同：不加版本
                resulting = current;
            }
            after.add(ObservationResponse.of(resulting));
        }

        for (FieldConflictRecord conflict : openConflicts) {
            ResolvedField resolved = resolvedByObservation.get(conflict.observationId()).get(conflict.fieldName());
            int updated = bundleRepository.resolveConflict(bundle.bundleKey(), conflict.observationId(),
                    conflict.fieldName(), resolved.source().name(), resolved.value(), resolved.restoreBasis(),
                    request.requestId(), now);
            if (updated != 1) {
                // 候选已变化：冲突在重读后不再是 OPEN（理论上由事务锁串行化排除），整体回滚
                throw ApiException.conflict(
                        "field conflict changed before arbitration: "
                                + conflict.observationId() + "#" + conflict.fieldName(), null);
            }
            conflictResponses.add(FieldConflictResponse.of(new FieldConflictRecord(
                    conflict.id(), conflict.bundleKey(), conflict.observationId(), conflict.fieldName(),
                    conflict.conflictType(), conflict.baseVersion(), conflict.baseValue(), conflict.localValue(),
                    conflict.remoteValue(), FieldConflictRecord.RESOLVED, resolved.source().name(),
                    resolved.value(), resolved.restoreBasis(), request.requestId(), now)));
        }

        observationRepository.clearOpenBundle(bundle.bundleKey());
        bundleRepository.closeBundle(bundle.bundleKey(), now);

        String beforeJson = writeSnapshotJson(before);
        String afterJson = writeSnapshotJson(after);
        try {
            bundleRepository.insertArbitration(request.requestId(), bundle.bundleKey(), request.operator(),
                    beforeJson, afterJson, now);
        } catch (DuplicateKeyException e) {
            // 并发下 requestId 或 bundleKey 已被占用：交由占位/唯一约束语义判为重放或 409
            if (bundleRepository.arbitrationExistsForBundle(bundle.bundleKey())) {
                throw ApiException.conflict("bundle already closed: " + bundle.bundleKey(), null);
            }
            throw ApiException.conflict("arbitration requestId conflict: " + request.requestId(), null);
        }

        ArbitrationResponse body = new ArbitrationResponse(request.requestId(), bundle.bundleKey(),
                request.operator(), now, List.copyOf(before), List.copyOf(after),
                List.copyOf(conflictResponses));
        return completeArbitration(request.requestId(), HttpStatus.OK, body);
    }

    // ---------- 规范化 ----------

    /**
     * 规范化一致字段集合：去空白、去重、按固定字段顺序排序，非法字段名 400。
     */
    private List<String> normalizeConsistentFields(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : raw) {
            String field = normalizeField(value);
            normalized.add(field);
        }
        return FIELDS.stream().filter(normalized::contains).toList();
    }

    private String normalizeField(String raw) {
        if (raw == null) {
            throw ApiException.badRequest("field name must not be null");
        }
        String field = raw.trim();
        if (!FIELDS.contains(field)) {
            throw ApiException.badRequest("field must be one of location/reading/note: " + raw);
        }
        return field;
    }

    /**
     * 规范化成员项：观测标识去空白，按观测标识升序排序（换序同参）。
     */
    private List<BundleMemberItem> normalizeMembers(List<BundleMemberItem> raw) {
        List<BundleMemberItem> items = new ArrayList<>();
        for (BundleMemberItem item : raw) {
            items.add(new BundleMemberItem(item.observationId() == null ? null : item.observationId().trim(),
                    item.baseVersion(), item.location(), item.reading(), item.note()));
        }
        items.sort((a, b) -> {
            String left = a.observationId() == null ? "" : a.observationId();
            String right = b.observationId() == null ? "" : b.observationId();
            return left.compareTo(right);
        });
        return items;
    }

    /**
     * 成员项的规范化指纹文本：按观测标识排序后逐字段拼接。
     */
    private String canonicalMembers(List<BundleMemberItem> members) {
        return members.stream()
                .map(item -> String.join("=",
                        nz(item.observationId()), nz(String.valueOf(item.baseVersion())),
                        nz(item.location()), nz(item.reading()), nz(item.note())))
                .collect(Collectors.joining(";"));
    }

    /**
     * 决定列表的规范化指纹文本：按观测标识+字段排序后拼接（换序同参）。
     */
    private String canonicalDecisions(List<FieldDecision> decisions) {
        List<FieldDecision> ordered = new ArrayList<>(decisions);
        ordered.sort((a, b) -> {
            int byObservation = a.observationId().compareTo(b.observationId());
            if (byObservation != 0) {
                return byObservation;
            }
            return normalizeField(a.field()).compareTo(normalizeField(b.field()));
        });
        return ordered.stream()
                .map(decision -> String.join("=",
                        nz(decision.observationId()), normalizeField(decision.field()),
                        decision.source().name(), nz(decision.value()),
                        nz(String.valueOf(decision.baseVersion()))))
                .collect(Collectors.joining(";"));
    }

    /**
     * expectedVersion 映射的规范化指纹文本：按观测标识排序。
     */
    private String canonicalVersions(Map<String, Integer> versions) {
        return new TreeMap<>(versions).entrySet().stream()
                .map(entry -> nz(entry.getKey()) + "=" + entry.getValue())
                .collect(Collectors.joining(";"));
    }

    /**
     * 字段值规范化：读数按数值（BigDecimal）比较，去除前导/尾随零差异；其余字段按去空白原文比较。
     */
    private String normalizeValue(String field, String value) {
        if (value == null) {
            return null;
        }
        if ("reading".equals(field)) {
            return new BigDecimal(value).stripTrailingZeros().toPlainString();
        }
        return value.trim();
    }

    private void validateFieldValue(String field, String value) {
        if (value == null || value.isEmpty()) {
            throw ApiException.badRequest(field + " value must not be empty");
        }
        switch (field) {
            case "location" -> {
                if (value.length() > LOCATION_MAX_LENGTH) {
                    throw ApiException.badRequest("location too long");
                }
            }
            case "reading" -> {
                if (value.length() > READING_MAX_LENGTH || !READING_PATTERN.matcher(value).matches()) {
                    throw ApiException.badRequest(
                            "reading must be a decimal string with at most 3 fraction digits");
                }
            }
            case "note" -> {
                if (value.length() > NOTE_MAX_LENGTH) {
                    throw ApiException.badRequest("note too long");
                }
            }
            default -> throw ApiException.badRequest("unknown field: " + field);
        }
    }

    private String fieldValue(ObservationSnapshot snapshot, String field) {
        return switch (field) {
            case "location" -> snapshot.location();
            case "reading" -> snapshot.reading();
            case "note" -> snapshot.note();
            default -> throw ApiException.badRequest("unknown field: " + field);
        };
    }

    // ---------- 三方规则（与 ObservationService 保持一致） ----------

    private boolean fieldConflicts(String base, String current, String candidate, boolean numeric) {
        if (valueEquals(candidate, base, numeric)) {
            return false;
        }
        if (valueEquals(current, base, numeric) || valueEquals(candidate, current, numeric)) {
            return false;
        }
        return true;
    }

    private boolean valueEquals(String left, String right, boolean numeric) {
        return numeric ? readingEquals(left, right) : Objects.equals(left, right);
    }

    private boolean readingEquals(String left, String right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
    }

    // ---------- 响应组装 ----------

    private BundleResponse buildBundleResponse(BundleRecord bundle,
                                               List<BundleMemberRecord> members,
                                               List<FieldConflictRecord> conflicts) {
        List<BundleMemberRecord> orderedMembers = new ArrayList<>(members);
        orderedMembers.sort((a, b) -> a.observationId().compareTo(b.observationId()));
        List<FieldConflictRecord> orderedConflicts = new ArrayList<>(conflicts);
        orderedConflicts.sort((a, b) -> {
            int byObservation = a.observationId().compareTo(b.observationId());
            if (byObservation != 0) {
                return byObservation;
            }
            return a.fieldName().compareTo(b.fieldName());
        });
        return new BundleResponse(bundle.bundleKey(), bundle.surveyId(),
                List.copyOf(bundle.consistentFields()), bundle.status(), bundle.operator(),
                bundle.closedAtUtc(),
                orderedMembers.stream().map(BundleResponse.MemberView::of).toList(),
                orderedConflicts.stream().map(FieldConflictResponse::of).toList());
    }

    private String writeSnapshotJson(List<ObservationResponse> snapshots) {
        try {
            return objectMapper.writeValueAsString(snapshots);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize bundle snapshot", e);
        }
    }

    private ArbitrationResponse responseFromRecord(ArbitrationRecordView record) {
        List<ObservationResponse> before = readSnapshotList(record.beforeSnapshotJson());
        List<ObservationResponse> after = readSnapshotList(record.afterSnapshotJson());
        List<FieldConflictResponse> conflicts =
                bundleRepository.findConflictsByArbitration(record.requestId()).stream()
                        .map(FieldConflictResponse::of)
                        .toList();
        return new ArbitrationResponse(record.requestId(), record.bundleKey(), record.operator(),
                record.arbitratedAtUtc(), before, after, conflicts);
    }

    private List<ObservationResponse> readSnapshotList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize bundle snapshot", e);
        }
    }

    // ---------- 幂等 ----------

    private BundleOutcome checkBundleReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new BundleOutcome(entry.responseStatus(), readBundleBody(entry.responseBody()));
    }

    private BundleOutcome insertBundlePlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "BUNDLE_CREATE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new BundleOutcome(entry.responseStatus(), readBundleBody(entry.responseBody()));
        }
    }

    private BundleOutcome completeBundle(String requestId, HttpStatus status, BundleResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize bundle response", e);
        }
        return new BundleOutcome(status.value(), body);
    }

    private BundleResponse readBundleBody(String json) {
        try {
            return objectMapper.readValue(json, BundleResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize bundle response", e);
        }
    }

    private ArbitrationOutcome checkArbitrationReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new ArbitrationOutcome(entry.responseStatus(), readArbitrationBody(entry.responseBody()));
    }

    private ArbitrationOutcome insertArbitrationPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "ARBITRATE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new ArbitrationOutcome(entry.responseStatus(), readArbitrationBody(entry.responseBody()));
        }
    }

    private ArbitrationOutcome completeArbitration(String requestId, HttpStatus status,
                                                   ArbitrationResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize arbitration response", e);
        }
        return new ArbitrationOutcome(status.value(), body);
    }

    private ArbitrationResponse readArbitrationBody(String json) {
        try {
            return objectMapper.readValue(json, ArbitrationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize arbitration response", e);
        }
    }

    private String fingerprint(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String nz(String value) {
        return value == null ? "<null>" : value;
    }

    /**
     * 冲突定位键：观测标识 + 字段名。
     */
    private record ConflictKey(String observationId, String field) {
        String render() {
            return observationId + "#" + field;
        }
    }

    /**
     * 单字段裁决结果。
     *
     * @param value         最终字段值
     * @param source        选择的来源
     * @param restoreBasis  墓碑恢复依据；非恢复字段为 null
     * @param baseVersion   BASE 恢复时引用的历史版本号；其余为 null
     */
    private record ResolvedField(String value, ArbitrationSource source, String restoreBasis,
                                 Integer baseVersion) {
    }

    /**
     * 单条观测裁决后的完整内容。
     *
     * @param location 最终地点
     * @param reading  最终读数
     * @param note     最终备注
     * @param changed  内容或存活状态是否变化：true 需要生成新版本（墓碑恢复必为 true）
     */
    private record FinalContent(String location, String reading, String note, boolean changed) {
    }

    /**
     * 建簇结果：HTTP 状态码与响应体。
     */
    public record BundleOutcome(int status, BundleResponse body) {
    }

    /**
     * 联合裁决结果：HTTP 状态码与响应体。
     */
    public record ArbitrationOutcome(int status, ArbitrationResponse body) {
    }
}
