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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 关联观测簇业务服务：建簇（冻结版本）、簇内冲突登记、字段级联合裁决与只读证据查询。
 *
 * <p>所有写操作在单事务内完成：先占位写 request_log，再锁定簇行与簇内全部观测当前行
 * （SELECT ... FOR UPDATE，按观测标识升序加锁避免死锁），随后重读完整簇、冲突集合与版本做判定；
 * 任一项失败整体回滚，观测版本、墓碑与冲突状态均不变，且 requestId/bundleKey 不被占用。
 *
 * <p>联合裁决成功后在同一事务内：一次性生成各观测新版本（含墓碑恢复）、关闭全部对应冲突、
 * 释放未结簇占用并关闭簇、落库不可变裁决记录（逐字段来源、恢复依据、簇级前后快照）。
 */
@Service
public class BundleService {

    private static final String SEPARATOR = "";

    /**
     * 三个可编辑字段的固定顺序：冲突重算、一致性校验、快照与指纹均以此顺序处理。
     */
    private static final List<String> FIELDS = List.of("location", "reading", "note");
    private static final Set<String> FIELD_SET = Set.copyOf(FIELDS);
    private static final java.util.regex.Pattern READING_PATTERN =
            java.util.regex.Pattern.compile("-?\\d+(\\.\\d{1,3})?");

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

    // ---------- 建簇 ----------

    /**
     * 建立关联观测簇：校验同 surveyId 与未结簇唯一性，冻结各成员当前版本；墓碑成员以待恢复身份入簇。
     */
    @Transactional
    public BundleOutcome createBundle(CreateBundleRequest request) {
        List<String> orderedObservationIds = normalizeAndValidateMembers(request.observationIds());
        List<String> orderedConsistentFields = normalizeAndValidateConsistentFields(request.consistentFields());

        String fingerprint = fingerprint("BUNDLE_CREATE", request.bundleKey(), request.surveyId(),
                String.join(",", orderedObservationIds), String.join(",", orderedConsistentFields),
                request.operator());
        BundleOutcome replayed = checkReplay(request.requestId(), fingerprint, BundleResponse.class);
        if (replayed != null) {
            return replayed;
        }
        BundleOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "BUNDLE_CREATE",
                BundleResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        // 严格按观测标识升序逐行加锁，保证并发建簇/裁决/删除的加锁顺序全局一致，避免死锁。
        List<ObservationSnapshot> currents = lockCurrentsInOrder(orderedObservationIds);
        Map<String, ObservationSnapshot> currentById = toCurrentMap(currents, orderedObservationIds);

        for (String observationId : orderedObservationIds) {
            ObservationSnapshot current = currentById.get(observationId);
            if (current.surveyId() != null && !current.surveyId().equals(request.surveyId())) {
                throw ApiException.conflict(
                        "observation belongs to a different survey: " + observationId, current.version());
            }
            if (bundleRepository.countOpenOccupation(observationId) > 0) {
                throw ApiException.conflict(
                        "observation already belongs to another open bundle: " + observationId,
                        current.version());
            }
        }

        Instant now = Instant.now(clock);
        BundleRecord bundle = new BundleRecord(request.bundleKey(), request.surveyId(), BundleStatus.OPEN,
                orderedConsistentFields, request.operator(), null, null, now);
        try {
            bundleRepository.insertBundle(bundle);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("bundleKey already exists: " + request.bundleKey(), null);
        }
        for (String observationId : orderedObservationIds) {
            ObservationSnapshot current = currentById.get(observationId);
            BundleMemberRole role = current.deleted()
                    ? BundleMemberRole.PENDING_RESTORE : BundleMemberRole.ACTIVE;
            try {
                bundleRepository.insertMember(new BundleMemberRecord(
                        0, request.bundleKey(), observationId, request.surveyId(),
                        current.version(), role));
            } catch (DuplicateKeyException e) {
                // 并发下观测已进入其他未结簇或重复入簇：库级唯一索引兜底
                throw ApiException.conflict(
                        "observation already belongs to another open bundle: " + observationId,
                        current.version());
            }
            if (current.surveyId() == null) {
                observationRepository.assignSurvey(observationId, request.surveyId());
            }
        }
        BundleResponse response = buildBundleResponse(bundle, currents, List.of());
        return complete(request.requestId(), HttpStatus.CREATED, response);
    }

    // ---------- 冲突登记 ----------

    /**
     * 登记簇内成员的一次离线三方合并冲突：服务端按基线/当前/候选重算冲突字段并整组保存候选快照；
     * 同一观测以新候选再次登记时替换旧候选。墓碑成员不能登记候选。
     */
    @Transactional
    public BundleOutcome registerConflict(String bundleKey, RegisterBundleConflictRequest request) {
        String fingerprint = fingerprint("BUNDLE_REGISTER", bundleKey, request.observationId(),
                String.valueOf(request.baseVersion()), request.location(), request.reading(), request.note());
        BundleOutcome replayed = checkReplay(request.requestId(), fingerprint, BundleResponse.class);
        if (replayed != null) {
            return replayed;
        }
        BundleOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "BUNDLE_REGISTER",
                BundleResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        BundleRecord bundle = lockOpenBundle(bundleKey);
        List<BundleMemberRecord> members = bundleRepository.findMembers(bundleKey);
        BundleMemberRecord member = members.stream()
                .filter(m -> m.observationId().equals(request.observationId()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound(
                        "observation is not a member of bundle: " + request.observationId()));

        // 与建簇/裁决一致，按观测标识升序一次性锁全部成员，避免锁序倒挂。
        List<ObservationSnapshot> currents = lockCurrentsInOrder(members.stream()
                .map(BundleMemberRecord::observationId).sorted().toList());
        Map<String, ObservationSnapshot> currentById = toCurrentMap(
                currents, members.stream().map(BundleMemberRecord::observationId).sorted().toList());
        ObservationSnapshot current = currentById.get(request.observationId());
        if (current.deleted()) {
            throw ApiException.conflict(
                    "tombstone observation cannot provide candidates, it must be restored first: "
                            + request.observationId(), current.version());
        }
        ObservationSnapshot base = observationRepository
                .findVersion(request.observationId(), request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + request.observationId() + "@" + request.baseVersion()));

        List<String> conflictFields = recalcConflictFields(base, current,
                request.location(), request.reading(), request.note());
        if (conflictFields.isEmpty()) {
            throw ApiException.conflict(
                    "candidate has no remaining conflicts against current version, nothing to register",
                    current.version());
        }

        String candidateToken = candidateToken(request.baseVersion(),
                request.location(), request.reading(), request.note());
        Instant now = Instant.now(clock);
        // 差异更新：持续冲突字段原地更新候选快照（行 id 保持稳定，旧 token 随即失效）；
        // 新冲突字段插入；已不再冲突的旧行删除。
        List<BundleConflictRecord> existing =
                bundleRepository.findOpenConflictsForObservation(bundleKey, request.observationId());
        Map<String, BundleConflictRecord> existingByField = existing.stream()
                .collect(java.util.stream.Collectors.toMap(
                        BundleConflictRecord::field, conflict -> conflict, (a, b) -> a, LinkedHashMap::new));
        Set<String> newFieldSet = Set.copyOf(conflictFields);
        for (BundleConflictRecord old : existing) {
            if (!newFieldSet.contains(old.field())) {
                bundleRepository.deleteConflict(old.id());
            }
        }
        for (String field : conflictFields) {
            BundleConflictRecord old = existingByField.get(field);
            if (old == null) {
                bundleRepository.insertConflict(new BundleConflictRecord(
                        0, bundleKey, request.observationId(), field, request.baseVersion(),
                        request.location(), request.reading(), request.note(), candidateToken,
                        BundleConflictStatus.OPEN, null, null, null, null, now));
            } else if (!old.candidateToken().equals(candidateToken)) {
                bundleRepository.updateConflictCandidate(old.id(), request.baseVersion(),
                        request.location(), request.reading(), request.note(), candidateToken);
            }
        }

        List<BundleConflictRecord> openConflicts = bundleRepository.findOpenConflictsForUpdate(bundleKey);
        BundleResponse response = buildBundleResponse(bundle, currents, openConflicts);
        return complete(request.requestId(), HttpStatus.OK, response);
    }

    // ---------- 联合裁决 ----------

    /**
     * 字段级联合裁决：提交时重读完整簇、冲突集合与版本，校验选择完整性、候选指纹、版本前提、
     * 墓碑恢复必填依据与声明字段一致性；全部通过后在同一事务内生成新版本、关闭冲突并关闭簇。
     */
    @Transactional
    public ArbitrateOutcome arbitrate(String bundleKey, ArbitrateBundleRequest request) {
        NormalizedArbitration normalized = normalizeArbitrationRequest(request);
        String fingerprint = fingerprint("BUNDLE_ARBITRATE", bundleKey, request.operator(), normalized.payload);
        ArbitrateOutcome replayed = checkArbitrationReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ArbitrateOutcome concurrent = insertArbitrationPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        BundleRecord bundle = lockOpenBundle(bundleKey);
        List<BundleMemberRecord> members = bundleRepository.findMembers(bundleKey);
        List<String> observationIds = members.stream().map(BundleMemberRecord::observationId).sorted().toList();

        List<ObservationSnapshot> currentsLocked = lockCurrentsInOrder(observationIds);
        Map<String, ObservationSnapshot> currentById = toCurrentMap(currentsLocked, observationIds);
        List<BundleConflictRecord> openConflicts = bundleRepository.findOpenConflictsForUpdate(bundleKey);

        // 1) expectedVersion 必须恰好覆盖簇内全部观测且与提交时版本一致
        validateExpectedVersions(members, normalized.expectedVersions, currentById);
        // 2) choices 必须恰好覆盖全部 OPEN 冲突，候选指纹一致，来源与显式值合法
        Map<Long, BundleConflictRecord> openConflictById = new LinkedHashMap<>();
        for (BundleConflictRecord conflict : openConflicts) {
            openConflictById.put(conflict.id(), conflict);
        }
        Map<Long, ParsedChoice> choices = validateChoices(normalized.choices, openConflictById);
        // 3) restores 必须恰好覆盖当前为墓碑的成员，字段来源（BASE/VALUE）完整合法
        Map<String, List<ParsedRestoreField>> restores =
                validateRestores(members, normalized.restores, currentById);
        // 4) 计算每个观测的最终字段值（冲突选择、非冲突自动合并、墓碑恢复）
        Map<String, FinalObservation> finals = computeFinals(
                members, currentById, openConflicts, choices, restores);
        // 5) 声明一致字段在全部非墓碑（最终存活）观测间必须相同
        validateConsistency(bundle, finals);

        Instant now = Instant.now(clock);
        String arbitrationId = "arb-" + UUID.randomUUID();

        // 6) 一次性写入新版本（含墓碑恢复）；未选择恢复的墓碑与内容无变化的存活观测不产生新版本
        for (String observationId : observationIds) {
            ObservationSnapshot current = currentById.get(observationId);
            FinalObservation finalValue = finals.get(observationId);
            if (finalValue.deleted()) {
                continue;
            }
            boolean changed = current.deleted() || !sameLiveContent(current, finalValue);
            if (changed) {
                int newVersion = current.version() + 1;
                ObservationSnapshot next = new ObservationSnapshot(observationId, current.surveyId(),
                        newVersion, finalValue.location(), finalValue.reading(), finalValue.note(), false);
                observationRepository.updateCurrent(next);
                observationRepository.insertVersion(next);
            }
        }

        // 7) 关闭全部冲突并保存逐字段来源与最终值
        for (BundleConflictRecord conflict : openConflicts) {
            ParsedChoice choice = choices.get(conflict.id());
            bundleRepository.resolveConflict(conflict.id(), choice.source(), choice.value(),
                    arbitrationId, now);
        }

        // 8) 组装证据 JSON 并落库不可变裁决记录，然后关闭簇、释放成员占用
        List<ArbitrationResponse.MemberSnapshotView> beforeSnapshots =
                buildSnapshotViews(members, currentsLocked);
        List<ObservationSnapshot> afterCurrents = observationIds.stream()
                .map(id -> observationRepository.findCurrent(id).orElseThrow())
                .sorted(java.util.Comparator.comparing(ObservationSnapshot::observationId))
                .toList();
        List<ArbitrationResponse.MemberSnapshotView> afterSnapshots =
                buildSnapshotViews(members, afterCurrents);
        List<ArbitrationResponse.ConflictResolutionView> resolutionViews =
                buildConflictResolutionViews(openConflicts, choices, finals);
        List<ArbitrationResponse.RestoreView> restoreViews = buildRestoreViews(restores, finals);

        BundleArbitrationRecord record = new BundleArbitrationRecord(
                arbitrationId, bundleKey, request.requestId(), bundle.surveyId(), request.operator(),
                writeJson(resolutionViews), writeJson(restoreViews),
                writeJson(beforeSnapshots), writeJson(afterSnapshots), now);
        bundleRepository.insertArbitration(record);
        bundleRepository.closeBundle(bundleKey, arbitrationId, now);
        bundleRepository.releaseOpenKeys(bundleKey);

        ArbitrationResponse response = new ArbitrationResponse(
                arbitrationId, bundleKey, request.requestId(), bundle.surveyId(), request.operator(), now,
                resolutionViews, restoreViews, beforeSnapshots, afterSnapshots);
        return completeArbitration(request.requestId(), HttpStatus.OK, response);
    }

    // ---------- 证据查询（只读） ----------

    /**
     * 查询簇证据：簇信息、成员（含冻结版本/角色/当前版本/墓碑状态）与全部未解决冲突，按观测、字段排序。
     */
    @Transactional(readOnly = true)
    public BundleResponse getBundle(String bundleKey) {
        BundleRecord bundle = bundleRepository.findBundle(bundleKey)
                .orElseThrow(() -> ApiException.notFound("bundle not found: " + bundleKey));
        List<BundleMemberRecord> members = bundleRepository.findMembers(bundleKey);
        List<ObservationSnapshot> currents = new ArrayList<>();
        for (BundleMemberRecord member : members) {
            observationRepository.findCurrent(member.observationId()).ifPresent(currents::add);
        }
        currents.sort((a, b) -> a.observationId().compareTo(b.observationId()));
        List<BundleConflictRecord> openConflicts = bundleRepository.findConflicts(bundleKey).stream()
                .filter(conflict -> conflict.status() == BundleConflictStatus.OPEN)
                .toList();
        return buildBundleResponse(bundle, currents, openConflicts);
    }

    /**
     * 按全局裁决标识查询不可变联合裁决记录（结构化证据）。
     */
    @Transactional(readOnly = true)
    public ArbitrationResponse getArbitration(String arbitrationId) {
        BundleArbitrationRecord record = bundleRepository.findArbitration(arbitrationId)
                .orElseThrow(() -> ApiException.notFound("arbitration not found: " + arbitrationId));
        return toArbitrationResponse(record);
    }

    /**
     * 按簇查询全部联合裁决记录，按裁决时刻先后排序。
     */
    @Transactional(readOnly = true)
    public List<ArbitrationResponse> listArbitrations(String bundleKey) {
        bundleRepository.findBundle(bundleKey)
                .orElseThrow(() -> ApiException.notFound("bundle not found: " + bundleKey));
        return bundleRepository.findArbitrationsByBundle(bundleKey).stream()
                .map(this::toArbitrationResponse)
                .toList();
    }

    // ---------- 请求规范化 ----------

    private List<String> normalizeAndValidateMembers(List<String> rawObservationIds) {
        if (rawObservationIds.size() < 2 || rawObservationIds.size() > 50) {
            throw ApiException.badRequest("bundle must contain between 2 and 50 observations");
        }
        List<String> ordered = rawObservationIds.stream().sorted().distinct().toList();
        if (ordered.size() != rawObservationIds.size()) {
            throw ApiException.badRequest("observationIds must not contain duplicates");
        }
        return ordered;
    }

    private List<String> normalizeAndValidateConsistentFields(List<String> rawFields) {
        List<String> ordered = new ArrayList<>();
        for (String field : FIELDS) {
            if (rawFields.contains(field)) {
                ordered.add(field);
            }
        }
        for (String raw : rawFields) {
            if (!FIELD_SET.contains(raw)) {
                throw ApiException.badRequest(
                        "consistent field must be one of location/reading/note: " + raw);
            }
        }
        if (ordered.size() != rawFields.size()) {
            throw ApiException.badRequest("consistentFields must not contain duplicates");
        }
        return ordered;
    }

    /**
     * 规范化裁决请求：观测/字段项换序不影响指纹；同时检出重复的观测前提、冲突 id 与恢复指令。
     */
    private NormalizedArbitration normalizeArbitrationRequest(ArbitrateBundleRequest request) {
        Map<String, Integer> expectedVersions = new TreeMap<>();
        for (BundleExpectedVersion entry : request.expectedVersions()) {
            if (expectedVersions.put(entry.observationId(), entry.expectedVersion()) != null) {
                throw ApiException.badRequest(
                        "duplicate expectedVersion for observation: " + entry.observationId());
            }
        }

        Map<Long, BundleConflictChoice> choicesById = new TreeMap<>();
        for (BundleConflictChoice choice : request.choices()) {
            if (choicesById.put(choice.conflictId(), choice) != null) {
                throw ApiException.badRequest("duplicate choice for conflict id: " + choice.conflictId());
            }
        }

        Map<String, List<BundleRestoreField>> restoresByObservation = new TreeMap<>();
        for (BundleRestoreInstruction instruction : request.restores()) {
            if (restoresByObservation.put(instruction.observationId(), instruction.fields()) != null) {
                throw ApiException.badRequest(
                        "duplicate restore instruction for observation: " + instruction.observationId());
            }
        }

        String payload;
        try {
            Map<String, Object> canonical = new TreeMap<>();
            canonical.put("expectedVersions", new TreeMap<>(expectedVersions));
            List<Map<String, Object>> choiceList = new ArrayList<>();
            for (BundleConflictChoice choice : choicesById.values()) {
                Map<String, Object> entry = new TreeMap<>();
                entry.put("conflictId", choice.conflictId());
                entry.put("candidateToken", choice.candidateToken());
                entry.put("source", choice.source().trim().toUpperCase(java.util.Locale.ROOT));
                entry.put("value", choice.value());
                choiceList.add(entry);
            }
            canonical.put("choices", choiceList);
            List<Map<String, Object>> restoreList = new ArrayList<>();
            for (Map.Entry<String, List<BundleRestoreField>> restoreEntry : restoresByObservation.entrySet()) {
                Map<String, Object> entry = new TreeMap<>();
                entry.put("observationId", restoreEntry.getKey());
                Map<String, Object> fieldMap = new TreeMap<>();
                for (BundleRestoreField field : restoreEntry.getValue()) {
                    Map<String, String> sourceValue = new TreeMap<>();
                    sourceValue.put("source", field.source().trim().toUpperCase(java.util.Locale.ROOT));
                    sourceValue.put("value", field.value());
                    if (fieldMap.put(field.field(), sourceValue) != null) {
                        throw ApiException.badRequest(
                                "duplicate restore field '" + field.field() + "' for observation: "
                                        + restoreEntry.getKey());
                    }
                }
                entry.put("fields", fieldMap);
                restoreList.add(entry);
            }
            canonical.put("restores", restoreList);
            payload = objectMapper.writeValueAsString(canonical);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to canonicalize arbitration request", e);
        }
        return new NormalizedArbitration(expectedVersions, choicesById, restoresByObservation, payload);
    }

    // ---------- 裁决校验 ----------

    private void validateExpectedVersions(List<BundleMemberRecord> members,
                                          Map<String, Integer> expectedVersions,
                                          Map<String, ObservationSnapshot> currentById) {
        Set<String> memberIds = members.stream().map(BundleMemberRecord::observationId)
                .collect(java.util.stream.Collectors.toSet());
        if (!expectedVersions.keySet().equals(memberIds)) {
            List<String> missing = members.stream().map(BundleMemberRecord::observationId)
                    .filter(id -> !expectedVersions.containsKey(id)).sorted().toList();
            List<String> extra = expectedVersions.keySet().stream()
                    .filter(id -> !memberIds.contains(id)).sorted().toList();
            List<String> problems = new ArrayList<>();
            if (!missing.isEmpty()) {
                problems.add("missing expectedVersion for observations: " + String.join(", ", missing));
            }
            if (!extra.isEmpty()) {
                problems.add("expectedVersion for non-member observations: " + String.join(", ", extra));
            }
            throw ApiException.badRequest("expectedVersions must cover exactly the bundle members; "
                    + String.join("; ", problems));
        }
        for (Map.Entry<String, Integer> entry : expectedVersions.entrySet()) {
            ObservationSnapshot current = currentById.get(entry.getKey());
            if (entry.getValue() != current.version()) {
                throw ApiException.conflict("expectedVersion mismatch for observation: "
                        + entry.getKey(), current.version());
            }
        }
    }

    private Map<Long, ParsedChoice> validateChoices(Map<Long, BundleConflictChoice> rawChoices,
                                                    Map<Long, BundleConflictRecord> openConflictById) {
        List<Long> missing = openConflictById.keySet().stream()
                .filter(id -> !rawChoices.containsKey(id)).sorted().toList();
        List<Long> extra = rawChoices.keySet().stream()
                .filter(id -> !openConflictById.containsKey(id)).sorted().toList();
        if (!missing.isEmpty() || !extra.isEmpty()) {
            List<String> problems = new ArrayList<>();
            if (!missing.isEmpty()) {
                problems.add("missing choices for open conflict ids: " + idList(missing));
            }
            if (!extra.isEmpty()) {
                problems.add("choices for unknown or already resolved conflict ids: " + idList(extra));
            }
            throw ApiException.badRequest(
                    "choices must cover exactly the open conflicts; " + String.join("; ", problems));
        }

        Map<Long, ParsedChoice> parsed = new LinkedHashMap<>();
        for (Map.Entry<Long, BundleConflictChoice> entry : rawChoices.entrySet()) {
            BundleConflictChoice raw = entry.getValue();
            BundleSource source = BundleSource.fromValue(raw.source());
            if (source == null || source == BundleSource.AUTO) {
                throw ApiException.badRequest(
                        "choice source must be one of LOCAL/REMOTE/BASE/VALUE for conflict id: "
                                + raw.conflictId());
            }
            String value;
            if (source == BundleSource.VALUE) {
                value = raw.value();
                if (value == null || value.isBlank()) {
                    throw ApiException.badRequest(
                            "explicit value is required when source is VALUE for conflict id: "
                                    + raw.conflictId());
                }
            } else if (raw.value() != null && !raw.value().isBlank()) {
                throw ApiException.badRequest(
                        "value must be absent when source is " + source + " for conflict id: "
                                + raw.conflictId());
            } else {
                value = null;
            }
            BundleConflictRecord conflict = openConflictById.get(entry.getKey());
            if (!conflict.candidateToken().equals(raw.candidateToken())) {
                throw ApiException.conflict(
                        "candidate has changed since conflict registration for conflict id: "
                                + raw.conflictId(), null);
            }
            if (source == BundleSource.VALUE && "reading".equals(conflict.field())
                    && !READING_PATTERN.matcher(value).matches()) {
                throw ApiException.badRequest(
                        "explicit reading value must be a decimal string with at most 3 fraction digits: "
                                + value);
            }
            parsed.put(entry.getKey(), new ParsedChoice(source, value));
        }
        return parsed;
    }

    private Map<String, List<ParsedRestoreField>> validateRestores(
            List<BundleMemberRecord> members,
            Map<String, List<BundleRestoreField>> rawRestores,
            Map<String, ObservationSnapshot> currentById) {
        Set<String> tombstoneIds = members.stream()
                .map(BundleMemberRecord::observationId)
                .filter(id -> currentById.get(id).deleted())
                .collect(java.util.stream.Collectors.toSet());
        // 恢复为可选项：只能对当前墓碑成员下达恢复指令；未选择恢复的墓碑在裁决后保持墓碑。
        List<String> extra = rawRestores.keySet().stream()
                .filter(id -> !tombstoneIds.contains(id)).sorted().toList();
        if (!extra.isEmpty()) {
            throw ApiException.badRequest(
                    "restore instructions are only allowed for tombstone observations: "
                            + String.join(", ", extra));
        }

        Map<String, List<ParsedRestoreField>> parsed = new LinkedHashMap<>();
        for (String observationId : rawRestores.keySet().stream().sorted().toList()) {
            ObservationSnapshot tombstone = currentById.get(observationId);
            Map<String, BundleRestoreField> fieldsByName = new LinkedHashMap<>();
            for (BundleRestoreField field : rawRestores.get(observationId)) {
                String fieldName = field.field() == null ? null : field.field().trim();
                if (!FIELD_SET.contains(fieldName)) {
                    throw ApiException.badRequest(
                            "restore field must be one of location/reading/note: " + field.field());
                }
                if (fieldsByName.put(fieldName, field) != null) {
                    throw ApiException.badRequest(
                            "duplicate restore field '" + fieldName + "' for observation: " + observationId);
                }
            }
            if (!fieldsByName.keySet().equals(FIELD_SET)) {
                throw ApiException.badRequest(
                        "restore must provide sources for all required fields location/reading/note: "
                                + observationId);
            }
            List<ParsedRestoreField> parsedFields = new ArrayList<>();
            for (String field : FIELDS) {
                BundleRestoreField raw = fieldsByName.get(field);
                BundleSource source = BundleSource.fromValue(raw.source());
                if (source != BundleSource.BASE && source != BundleSource.VALUE) {
                    throw ApiException.badRequest(
                            "restore source for field '" + field + "' must be BASE or VALUE: " + observationId);
                }
                String value;
                if (source == BundleSource.VALUE) {
                    value = raw.value();
                    if (value == null || value.isBlank()) {
                        throw ApiException.badRequest(
                                "explicit value is required when restore source is VALUE for field '"
                                        + field + "': " + observationId);
                    }
                    if ("reading".equals(field) && !READING_PATTERN.matcher(value).matches()) {
                        throw ApiException.badRequest(
                                "restored reading must be a decimal string with at most 3 fraction digits: "
                                        + value);
                    }
                } else {
                    if (raw.value() != null && !raw.value().isBlank()) {
                        throw ApiException.badRequest(
                                "value must be absent when restore source is BASE for field '" + field
                                        + "': " + observationId);
                    }
                    ObservationSnapshot lastLive = observationRepository
                            .findLastLiveVersionBefore(observationId, tombstone.version())
                            .orElseThrow(() -> ApiException.badRequest(
                                    "no live baseline version available for restore field '" + field
                                            + "': " + observationId));
                    value = fieldValue(lastLive, field);
                }
                parsedFields.add(new ParsedRestoreField(field, source, value));
            }
            parsed.put(observationId, List.copyOf(parsedFields));
        }
        return parsed;
    }

    /**
     * 计算每个观测裁决后的最终字段值：冲突字段按选择取值，其余字段按三方规则自动合并；墓碑按恢复依据复活。
     */
    private Map<String, FinalObservation> computeFinals(
            List<BundleMemberRecord> members,
            Map<String, ObservationSnapshot> currentById,
            List<BundleConflictRecord> openConflicts,
            Map<Long, ParsedChoice> choices,
            Map<String, List<ParsedRestoreField>> restores) {
        Map<String, List<BundleConflictRecord>> conflictsByObservation = openConflicts.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        BundleConflictRecord::observationId,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));

        Map<String, FinalObservation> finals = new LinkedHashMap<>();
        for (BundleMemberRecord member : members.stream()
                .sorted(java.util.Comparator.comparing(BundleMemberRecord::observationId)).toList()) {
            String observationId = member.observationId();
            ObservationSnapshot current = currentById.get(observationId);

            if (current.deleted()) {
                List<ParsedRestoreField> restore = restores.get(observationId);
                if (restore == null) {
                    // 未选择恢复的墓碑成员裁决后保持墓碑，不产生新版本，也不参与一致性比较。
                    finals.put(observationId, FinalObservation.tombstone());
                    continue;
                }
                Map<String, String> restored = new LinkedHashMap<>();
                for (ParsedRestoreField field : restore) {
                    restored.put(field.field(), field.value());
                }
                finals.put(observationId, new FinalObservation(
                        false, restored.get("location"), restored.get("reading"), restored.get("note")));
                continue;
            }

            List<BundleConflictRecord> conflicts =
                    conflictsByObservation.getOrDefault(observationId, List.of());
            Map<String, BundleConflictRecord> conflictByField = conflicts.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            BundleConflictRecord::field, conflict -> conflict,
                            (a, b) -> a, LinkedHashMap::new));

            String location;
            String reading;
            String note;
            if (conflicts.isEmpty()) {
                // 该观测没有未解决冲突：无候选动作，保持当前内容
                location = current.location();
                reading = current.reading();
                note = current.note();
            } else {
                BundleConflictRecord template = conflicts.get(0);
                ObservationSnapshot base = observationRepository
                        .findVersion(observationId, template.baseVersion())
                        .orElseThrow(() -> ApiException.notFound(
                                "base version not found: " + observationId + "@" + template.baseVersion()));
                location = resolveOneField("location", base.location(), current.location(),
                        template.candidateLocation(), conflictByField.get("location"), choices, false);
                reading = resolveOneField("reading", base.reading(), current.reading(),
                        template.candidateReading(), conflictByField.get("reading"), choices, true);
                note = resolveOneField("note", base.note(), current.note(),
                        template.candidateNote(), conflictByField.get("note"), choices, false);
            }
            finals.put(observationId, new FinalObservation(false, location, reading, note));
        }
        return finals;
    }

    private String resolveOneField(String field, String baseValue, String currentValue, String candidateValue,
                                   BundleConflictRecord conflict, Map<Long, ParsedChoice> choices,
                                   boolean numeric) {
        if (conflict != null) {
            ParsedChoice choice = choices.get(conflict.id());
            return switch (choice.source()) {
                case LOCAL -> currentValue;
                case REMOTE -> candidateValue;
                case BASE -> baseValue;
                case VALUE -> choice.value();
                case AUTO -> throw new IllegalStateException("AUTO is not a client-selectable source");
            };
        }
        // 非冲突字段沿用原三方自动合并规则
        if (valueEquals(candidateValue, baseValue, numeric)) {
            return currentValue;
        }
        return candidateValue;
    }

    /**
     * 一致性校验：声明一致字段的最终值（读数按数值、其余按原文）必须在全部最终存活（非墓碑）观测间相同；
     * 裁决后仍为墓碑的成员不参与比较。
     */
    private void validateConsistency(BundleRecord bundle, Map<String, FinalObservation> finals) {
        for (String field : bundle.consistentFields()) {
            boolean numeric = "reading".equals(field);
            String expected = null;
            String expectedObservation = null;
            for (Map.Entry<String, FinalObservation> entry : finals.entrySet()) {
                if (entry.getValue().deleted()) {
                    continue;
                }
                String value = fieldValue(entry.getValue(), field);
                if (expected == null) {
                    expected = value;
                    expectedObservation = entry.getKey();
                    continue;
                }
                if (!valueEquals(value, expected, numeric)) {
                    throw ApiException.conflict(
                            "consistent field '" + field + "' differs across observations: "
                                    + expectedObservation + "=" + expected + ", "
                                    + entry.getKey() + "=" + value, null);
                }
            }
        }
    }

    // ---------- 证据视图组装 ----------

    private BundleResponse buildBundleResponse(BundleRecord bundle,
                                               List<ObservationSnapshot> currents,
                                               List<BundleConflictRecord> openConflicts) {
        Map<String, ObservationSnapshot> currentById = currents.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ObservationSnapshot::observationId, snapshot -> snapshot,
                        (a, b) -> a, LinkedHashMap::new));
        List<BundleResponse.MemberView> memberViews = bundleRepository.findMembers(bundle.bundleKey()).stream()
                .map(member -> {
                    ObservationSnapshot current = currentById.get(member.observationId());
                    return new BundleResponse.MemberView(
                            member.observationId(), member.surveyId(), member.frozenVersion(),
                            member.role().name(),
                            current == null ? member.frozenVersion() : current.version(),
                            current != null && current.deleted());
                })
                .sorted(java.util.Comparator.comparing(BundleResponse.MemberView::observationId))
                .toList();
        List<BundleResponse.ConflictView> conflictViews = openConflicts.stream()
                .sorted(java.util.Comparator.comparing(BundleConflictRecord::observationId)
                        .thenComparing(BundleConflictRecord::field))
                .map(conflict -> new BundleResponse.ConflictView(
                        conflict.id(), conflict.observationId(), conflict.field(), conflict.baseVersion(),
                        conflict.candidateToken(), conflict.candidateLocation(),
                        conflict.candidateReading(), conflict.candidateNote()))
                .toList();
        return new BundleResponse(
                bundle.bundleKey(), bundle.surveyId(), bundle.status().name(),
                List.copyOf(bundle.consistentFields()), bundle.operator(), bundle.arbitrationId(),
                bundle.closedAtUtc(), bundle.createdAtUtc(), memberViews, conflictViews);
    }

    private List<ArbitrationResponse.MemberSnapshotView> buildSnapshotViews(
            List<BundleMemberRecord> members, List<ObservationSnapshot> currents) {
        Map<String, BundleMemberRecord> memberById = members.stream()
                .collect(java.util.stream.Collectors.toMap(
                        BundleMemberRecord::observationId, member -> member, (a, b) -> a));
        return currents.stream()
                .sorted(java.util.Comparator.comparing(ObservationSnapshot::observationId))
                .map(current -> new ArbitrationResponse.MemberSnapshotView(
                        current.observationId(),
                        memberById.get(current.observationId()).role().name(),
                        current.version(), current.deleted(),
                        current.location(), current.reading(), current.note()))
                .toList();
    }

    private List<ArbitrationResponse.ConflictResolutionView> buildConflictResolutionViews(
            List<BundleConflictRecord> openConflicts,
            Map<Long, ParsedChoice> choices,
            Map<String, FinalObservation> finals) {
        return openConflicts.stream()
                .sorted(java.util.Comparator.comparing(BundleConflictRecord::observationId)
                        .thenComparing(BundleConflictRecord::field))
                .map(conflict -> {
                    ParsedChoice choice = choices.get(conflict.id());
                    String value = fieldValue(finals.get(conflict.observationId()), conflict.field());
                    return new ArbitrationResponse.ConflictResolutionView(
                            conflict.id(), conflict.observationId(), conflict.field(),
                            choice.source().name(), value);
                })
                .toList();
    }

    private List<ArbitrationResponse.RestoreView> buildRestoreViews(
            Map<String, List<ParsedRestoreField>> restores,
            Map<String, FinalObservation> finals) {
        return restores.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    FinalObservation finalValue = finals.get(entry.getKey());
                    List<ArbitrationResponse.RestoreFieldView> fields = entry.getValue().stream()
                            .map(parsed -> new ArbitrationResponse.RestoreFieldView(
                                    parsed.field(), parsed.source().name(),
                                    fieldValue(finalValue, parsed.field())))
                            .toList();
                    return new ArbitrationResponse.RestoreView(entry.getKey(), fields);
                })
                .toList();
    }

    private ArbitrationResponse toArbitrationResponse(BundleArbitrationRecord record) {
        return new ArbitrationResponse(
                record.arbitrationId(), record.bundleKey(), record.requestId(), record.surveyId(),
                record.operator(), record.arbitratedAtUtc(),
                readJson(record.fieldSources(), new TypeReference<>() {
                }),
                readJson(record.restoreBasis(), new TypeReference<>() {
                }),
                readJson(record.snapshotBefore(), new TypeReference<>() {
                }),
                readJson(record.snapshotAfter(), new TypeReference<>() {
                }));
    }

    // ---------- 共享判定与工具 ----------

    private BundleRecord lockOpenBundle(String bundleKey) {
        BundleRecord bundle = bundleRepository.findBundleForUpdate(bundleKey)
                .orElseThrow(() -> ApiException.notFound("bundle not found: " + bundleKey));
        if (bundle.status() != BundleStatus.OPEN) {
            throw ApiException.conflict("bundle is already closed: " + bundleKey, null);
        }
        return bundle;
    }

    /**
     * 严格按观测标识升序逐行锁定观测当前记录，统一全系统加锁顺序。
     */
    private List<ObservationSnapshot> lockCurrentsInOrder(List<String> sortedObservationIds) {
        List<ObservationSnapshot> locked = new ArrayList<>();
        for (String observationId : sortedObservationIds) {
            locked.add(observationRepository.findCurrentForUpdate(observationId)
                    .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId)));
        }
        return locked;
    }

    private Map<String, ObservationSnapshot> toCurrentMap(List<ObservationSnapshot> currents,
                                                          List<String> expectedIds) {        Map<String, ObservationSnapshot> map = new LinkedHashMap<>();
        for (ObservationSnapshot current : currents) {
            map.put(current.observationId(), current);
        }
        List<String> missing = expectedIds.stream().filter(id -> !map.containsKey(id)).sorted().toList();
        if (!missing.isEmpty()) {
            throw ApiException.notFound("observation not found: " + String.join(", ", missing));
        }
        return map;
    }

    private List<String> recalcConflictFields(ObservationSnapshot base, ObservationSnapshot current,
                                              String candidateLocation, String candidateReading,
                                              String candidateNote) {
        List<String> conflictFields = new ArrayList<>();
        if (fieldConflicts(base.location(), current.location(), candidateLocation, false)) {
            conflictFields.add("location");
        }
        if (fieldConflicts(base.reading(), current.reading(), candidateReading, true)) {
            conflictFields.add("reading");
        }
        if (fieldConflicts(base.note(), current.note(), candidateNote, false)) {
            conflictFields.add("note");
        }
        return conflictFields;
    }

    private boolean fieldConflicts(String base, String current, String candidate, boolean numeric) {
        if (valueEquals(candidate, base, numeric)) {
            return false;
        }
        return !valueEquals(current, base, numeric) && !valueEquals(candidate, current, numeric);
    }

    private boolean valueEquals(String left, String right, boolean numeric) {
        if (!numeric) {
            return Objects.equals(left, right);
        }
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
    }

    private boolean sameLiveContent(ObservationSnapshot current, FinalObservation finalValue) {
        return Objects.equals(current.location(), finalValue.location())
                && Objects.equals(current.note(), finalValue.note())
                && valueEquals(current.reading(), finalValue.reading(), true);
    }

    private String fieldValue(Object holder, String field) {
        return switch (field) {
            case "location" -> holder instanceof FinalObservation f ? f.location()
                    : holder instanceof ObservationSnapshot s ? s.location() : null;
            case "reading" -> holder instanceof FinalObservation f ? f.reading()
                    : holder instanceof ObservationSnapshot s ? s.reading() : null;
            case "note" -> holder instanceof FinalObservation f ? f.note()
                    : holder instanceof ObservationSnapshot s ? s.note() : null;
            default -> throw new IllegalArgumentException("unknown field: " + field);
        };
    }

    private String candidateToken(int baseVersion, String location, String reading, String note) {
        return fingerprint("CANDIDATE", String.valueOf(baseVersion), location, reading, note);
    }

    private String idList(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize arbitration evidence", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize arbitration evidence", e);
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

    // ---------- request_log 幂等 ----------

    private <T> BundleOutcome checkReplay(String requestId, String fingerprint, Class<T> bodyType) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new BundleOutcome(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
    }

    private <T> BundleOutcome insertPlaceholder(String requestId, String fingerprint, String operation,
                                                Class<T> bodyType) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict(
                        "requestId reused with different parameters: " + requestId, null);
            }
            return new BundleOutcome(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
        }
    }

    private BundleOutcome complete(String requestId, HttpStatus status, BundleResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new BundleOutcome(status.value(), body);
    }

    private ArbitrateOutcome checkArbitrationReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new ArbitrateOutcome(entry.responseStatus(),
                readBody(entry.responseBody(), ArbitrationResponse.class));
    }

    private ArbitrateOutcome insertArbitrationPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "BUNDLE_ARBITRATE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict(
                        "requestId reused with different parameters: " + requestId, null);
            }
            return new ArbitrateOutcome(entry.responseStatus(),
                    readBody(entry.responseBody(), ArbitrationResponse.class));
        }
    }

    private ArbitrateOutcome completeArbitration(String requestId, HttpStatus status,
                                                 ArbitrationResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new ArbitrateOutcome(status.value(), body);
    }

    private <T> T readBody(String json, Class<T> bodyType) {
        try {
            return objectMapper.readValue(json, bodyType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 建簇/登记结果：HTTP 状态码与簇视图响应体。
     */
    public record BundleOutcome(int status, Object body) {
    }

    /**
     * 联合裁决结果：HTTP 状态码与裁决响应体。
     */
    public record ArbitrateOutcome(int status, ArbitrationResponse body) {
    }

    private record ParsedChoice(BundleSource source, String value) {
    }

    private record ParsedRestoreField(String field, BundleSource source, String value) {
    }

    private record FinalObservation(boolean deleted, String location, String reading, String note) {

        private static FinalObservation tombstone() {
            return new FinalObservation(true, null, null, null);
        }
    }

    private record NormalizedArbitration(
            Map<String, Integer> expectedVersions,
            Map<Long, BundleConflictChoice> choices,
            Map<String, List<BundleRestoreField>> restores,
            String payload) {
    }
}
