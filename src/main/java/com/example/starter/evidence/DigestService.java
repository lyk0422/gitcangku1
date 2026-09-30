package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ContentReplacedView;
import com.example.starter.evidence.dto.DigestContentReplaceRequest;
import com.example.starter.evidence.dto.DigestSealRequest;
import com.example.starter.evidence.dto.DigestState;
import com.example.starter.evidence.dto.DigestStatusView;
import com.example.starter.evidence.dto.DigestVersionView;
import com.example.starter.evidence.dto.DigestWitnessRequest;
import com.example.starter.evidence.dto.ExportItemView;
import com.example.starter.evidence.dto.ExportPackageRequest;
import com.example.starter.evidence.dto.ExportPackageView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 数字摘要见证核心服务：封存登记 SHA-256、两名不同保管人见证、导出前重算校验。
 * 所有写操作在单事务内先锁定证物行（SELECT ... FOR UPDATE）再裁决，
 * 保证并发重封存与导出以提交时的完整版本为准，不混入不同版本内容；
 * 校验失败时事务回滚，不写入任何部分导出包。
 */
@Service
public class DigestService {

    static final String OP_DIGEST_SEAL = "DIGEST_SEAL";
    static final String OP_DIGEST_WITNESS = "DIGEST_WITNESS";
    static final String OP_DIGEST_EXPORT = "DIGEST_EXPORT";
    static final String OP_DIGEST_CONTENT_REPLACE = "DIGEST_CONTENT_REPLACE";

    /**
     * 每个封存版本要求的不同见证人数量。
     */
    static final int REQUIRED_WITNESSES = 2;

    private final EvidenceRepository evidenceRepository;
    private final DigestContentRepository contentRepository;
    private final DigestVersionRepository versionRepository;
    private final DigestWitnessRepository witnessRepository;
    private final ExportPackageRepository exportPackageRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public DigestService(EvidenceRepository evidenceRepository,
                         DigestContentRepository contentRepository,
                         DigestVersionRepository versionRepository,
                         DigestWitnessRepository witnessRepository,
                         ExportPackageRepository exportPackageRepository,
                         CommandLogRepository commandLogRepository,
                         ObjectMapper objectMapper) {
        this.evidenceRepository = evidenceRepository;
        this.contentRepository = contentRepository;
        this.versionRepository = versionRepository;
        this.witnessRepository = witnessRepository;
        this.exportPackageRepository = exportPackageRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 封存：仅当前保管人；按当前内容计算 SHA-256 登记新版本，旧 ACTIVE 版本置为 SUPERSEDED。
     * 旧版本的见证绑定旧版本，不会随新版本转移。
     */
    @Transactional
    public StoredResponse seal(String actorId, String evidenceKey,
                               DigestSealRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireCustodian(evidence, actorId);
        byte[] content = decodeBase64(request.contentBase64());
        String sha256 = sha256Hex(content);
        LocalDateTime now = LocalDateTime.now();
        contentRepository.upsert(evidenceKey, request.contentRef(), request.contentBase64(), now);
        versionRepository.findActiveByEvidenceKey(evidenceKey)
                .ifPresent(active -> versionRepository.markSuperseded(active.id()));
        int versionNo = versionRepository.maxVersionNo(evidenceKey) + 1;
        versionRepository.insert(evidenceKey, versionNo, actorId, request.contentRef(), sha256,
                DigestVersionStatus.ACTIVE, now);
        DigestVersion created = versionRepository.findByVersion(evidenceKey, versionNo).orElseThrow();
        return record(request.commandKey(), OP_DIGEST_SEAL, actorId, requestHash, 200,
                toVersionView(created, List.of()));
    }

    /**
     * 替换证物当前文件内容（不重新封存、不产生新版本、不改写已登记摘要）。
     * 仅当前保管人可操作；替换后重算摘要若与封存登记不一致，状态变为摘要异常、导出返回 422；
     * 恢复为与被见证版本完全一致的引用与内容后，导出仍按原版本裁决。
     */
    @Transactional
    public StoredResponse replaceContent(String actorId, String evidenceKey,
                                         DigestContentReplaceRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireCustodian(evidence, actorId);
        byte[] content = decodeBase64(request.contentBase64());
        LocalDateTime now = LocalDateTime.now();
        contentRepository.upsert(evidenceKey, request.contentRef(), request.contentBase64(), now);
        String sha256 = sha256Hex(content);
        return record(request.commandKey(), OP_DIGEST_CONTENT_REPLACE, actorId, requestHash, 200,
                new ContentReplacedView(evidenceKey, request.contentRef(), sha256, now));
    }

    /**
     * 见证：保管人对指定（或当前 ACTIVE）封存版本确认。
     * 同一版本须两名不同保管人分别确认；同一人重复见证或见证已失效版本返回 422。
     */
    @Transactional
    public StoredResponse witness(String actorId, String evidenceKey,
                                  DigestWitnessRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DigestVersion version = resolveWitnessVersion(evidenceKey, request.versionNo());        List<DigestWitness> witnesses = witnessRepository.findByVersion(evidenceKey, version.versionNo());
        List<String> witnessIds = witnesses.stream().map(DigestWitness::witnessId).toList();
        if (witnessIds.contains(actorId)) {
            throw ApiException.unprocessable("见证人重复，同一保管人不能重复见证版本 "
                    + version.versionNo() + ": " + actorId);
        }
        if (witnessIds.size() >= REQUIRED_WITNESSES) {
            throw ApiException.unprocessable("版本 " + version.versionNo() + " 已有两名见证人，见证已齐备");
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            witnessRepository.insert(evidenceKey, version.versionNo(), actorId, request.commandKey(), now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 行锁内预检之外的并发兜底：同人重复见证统一返回 422。
            throw ApiException.unprocessable("见证人重复，同一保管人不能重复见证版本 "
                    + version.versionNo() + ": " + actorId);
        }
        List<DigestWitness> updated = witnessRepository.findByVersion(evidenceKey, version.versionNo());
        return record(request.commandKey(), OP_DIGEST_WITNESS, actorId, requestHash, 200,
                toVersionView(version, updated.stream().map(DigestWitness::witnessId).toList()));
    }

    /**
     * 导出：按证物键排序后逐件锁定，重算当前内容摘要并与提交时锁定的完整版本核对；
     * 任一证物缺少版本、摘要/引用不符、见证人不足两名或重复、版本已失效，整体返回 422 且不生成部分包。
     * 同 requestId 同证物集合（含乱序重放）返回首次包标识与摘要清单；同 requestId 换证物集合返回 409。
     */
    @Transactional
    public StoredResponse export(String actorId, ExportPackageRequest request, String requestHash) {
        // requestHash 由控制器按排序去重后的证物集合计算，故同集合乱序重放指纹相同，集合变化返回 409。
        List<String> evidenceKeys = canonicalKeys(request.evidenceKeys());
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<Evidence> locked = new ArrayList<>();
        for (String evidenceKey : evidenceKeys) {
            locked.add(lockEvidence(evidenceKey));
        }
        // 并发下本事务可能在证物行锁上等待；全部持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<ExportItem> items = new ArrayList<>();
        for (String evidenceKey : evidenceKeys) {
            items.add(adjudicate(evidenceKey));
        }
        String packageId = "PKG-" + java.util.UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        String commonWitness1 = null;
        String commonWitness2 = null;
        if (sameWitnessesAcross(items)) {
            List<String> witnesses = splitWitnesses(items.get(0).witnessIdsCsv());
            commonWitness1 = witnesses.get(0);
            commonWitness2 = witnesses.get(1);
        }
        ExportItem first = items.get(0);
        exportPackageRepository.insertPackage(packageId, request.requestId(), first.evidenceKey(),
                first.versionNo(), first.contentSha256(), commonWitness1, commonWitness2, now);
        for (ExportItem item : items) {
            exportPackageRepository.insertItem(packageId, item.evidenceKey(), item.versionNo(),
                    item.contentRef(), item.contentSha256(), item.witnessIdsCsv());
        }
        ExportPackage created = exportPackageRepository.findByPackageId(packageId).orElseThrow();
        ExportPackageView view = toPackageView(created, items);
        // 命令日志与导出包在同一事务：唯一约束冲突时整事务回滚，包与明细一并消失，不残留部分包。
        return record(request.requestId(), OP_DIGEST_EXPORT, actorId, requestHash, 200, view);
    }

    /**
     * 查询单件证物的摘要状态、全部版本/见证人及历史导出清单。
     */
    @Transactional(readOnly = true)
    public DigestStatusView status(String evidenceKey) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        List<DigestVersion> versions = versionRepository.findByEvidenceKey(evidenceKey);
        Optional<DigestVersion> active = versions.stream()
                .filter(v -> v.status() == DigestVersionStatus.ACTIVE)
                .findFirst();
        Optional<DigestContent> content = contentRepository.findByEvidenceKey(evidenceKey);
        String currentContentSha = content.map(c -> sha256Hex(decodeBase64(c.contentBase64()))).orElse(null);

        DigestState state;
        Integer currentVersionNo = null;
        String currentSha256 = null;
        if (active.isEmpty()) {
            state = DigestState.NO_DIGEST;
        } else {
            DigestVersion version = active.get();
            currentVersionNo = version.versionNo();
            currentSha256 = version.contentSha256();
            List<String> witnesses = witnessRepository
                    .findByVersion(evidenceKey, version.versionNo()).stream()
                    .map(DigestWitness::witnessId).toList();
            if (witnesses.size() < REQUIRED_WITNESSES) {
                state = DigestState.PENDING_WITNESS;
            } else if (contentMatched(version, content.orElse(null))) {
                state = DigestState.EXPORTABLE;
            } else {
                state = DigestState.DIGEST_MISMATCH;
            }
        }

        List<DigestVersionView> versionViews = versions.stream()
                .map(v -> toVersionView(v, witnessRepository
                        .findByVersion(evidenceKey, v.versionNo()).stream()
                        .map(DigestWitness::witnessId).toList()))
                .toList();
        List<ExportPackageView> exportViews = exportPackageRepository
                .findPackagesContainingEvidence(evidenceKey).stream()
                .map(pkg -> toPackageView(pkg, exportPackageRepository.findItems(pkg.packageId())))
                .toList();
        return new DigestStatusView(evidenceKey, state, currentVersionNo, currentSha256,
                currentContentSha, versionViews, exportViews);
    }

    /**
     * 查询单个导出包（含导出时固化的版本、摘要与两名见证人明细）。
     */
    @Transactional(readOnly = true)
    public ExportPackageView getPackage(String packageId) {
        ExportPackage pkg = exportPackageRepository.findByPackageId(packageId)
                .orElseThrow(() -> ApiException.notFound("导出包不存在: " + packageId));
        return toPackageView(pkg, exportPackageRepository.findItems(packageId));
    }

    /**
     * 单件证物导出版本裁决：必须存在 ACTIVE 版本、两名不同见证人齐备、当前内容引用与摘要均与登记一致。
     */
    private ExportItem adjudicate(String evidenceKey) {
        DigestVersion version = versionRepository.findActiveByEvidenceKey(evidenceKey)
                .orElseThrow(() -> ApiException.unprocessable(
                        "证物尚无有效封存版本，不能导出: " + evidenceKey));
        if (version.status() != DigestVersionStatus.ACTIVE) {
            throw ApiException.unprocessable("见证版本已失效: " + evidenceKey + " 版本 " + version.versionNo());
        }
        List<String> witnesses = witnessRepository.findByVersion(evidenceKey, version.versionNo()).stream()
                .map(DigestWitness::witnessId).toList();
        long distinctWitnesses = witnesses.stream().distinct().count();
        if (distinctWitnesses < witnesses.size()) {
            throw ApiException.unprocessable("见证人重复: " + evidenceKey + " 版本 " + version.versionNo());
        }
        if (witnesses.size() < REQUIRED_WITNESSES) {
            throw ApiException.unprocessable("见证人不足两名，版本 " + version.versionNo()
                    + " 当前仅 " + witnesses.size() + " 名: " + evidenceKey);
        }
        DigestContent content = contentRepository.findByEvidenceKey(evidenceKey)
                .orElseThrow(() -> ApiException.unprocessable(
                        "证物当前内容缺失，无法重新计算摘要: " + evidenceKey));
        if (!contentMatched(version, content)) {
            throw ApiException.unprocessable("摘要不符，当前内容与封存版本 " + version.versionNo()
                    + " 登记摘要不一致: " + evidenceKey);
        }
        List<String> firstTwo = witnesses.subList(0, REQUIRED_WITNESSES);
        return new ExportItem(null, null, evidenceKey, version.versionNo(), version.contentRef(),
                version.contentSha256(), String.join(",", firstTwo));
    }

    private DigestVersion resolveWitnessVersion(String evidenceKey, Integer requestedVersionNo) {
        if (requestedVersionNo == null) {
            return versionRepository.findActiveByEvidenceKey(evidenceKey)
                    .orElseThrow(() -> ApiException.unprocessable("证物尚无封存版本，无法见证: " + evidenceKey));
        }
        DigestVersion version = versionRepository.findByVersion(evidenceKey, requestedVersionNo)
                .orElseThrow(() -> ApiException.notFound(
                        "封存版本不存在: " + evidenceKey + " 版本 " + requestedVersionNo));
        if (version.status() == DigestVersionStatus.SUPERSEDED) {
            throw ApiException.unprocessable("见证版本已失效，请见证最新封存版本: "
                    + evidenceKey + " 版本 " + requestedVersionNo);
        }
        return version;
    }

    private boolean contentMatched(DigestVersion version, DigestContent content) {
        if (content == null) {
            return false;
        }
        if (!version.contentRef().equals(content.contentRef())) {
            return false;
        }
        return version.contentSha256().equals(sha256Hex(decodeBase64(content.contentBase64())));
    }

    private boolean sameWitnessesAcross(List<ExportItem> items) {
        List<String> first = splitWitnesses(items.get(0).witnessIdsCsv());
        return items.stream()
                .map(item -> splitWitnesses(item.witnessIdsCsv()))
                .allMatch(w -> w.equals(first));
    }

    private static List<String> splitWitnesses(String csv) {
        return List.of(csv.split(",", -1));
    }

    private static List<String> canonicalKeys(List<String> rawKeys) {
        List<String> keys = new ArrayList<>(new TreeSet<>(rawKeys));
        if (keys.isEmpty()) {
            throw ApiException.badRequest("导出品证物集合不能为空");
        }
        return keys;
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private void requireCustodian(Evidence evidence, String actorId) {
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是当前保管人: " + actorId);
        }
    }

    private Optional<StoredResponse> checkReplay(String commandKey, String requestHash) {
        return commandLogRepository.findByKey(commandKey).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + commandKey);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse record(String commandKey, String operation, String actorId,
                                  String requestHash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(commandKey, actorId, operation, requestHash, status, json,
                LocalDateTime.now());
        return new StoredResponse(status, json);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private static byte[] decodeBase64(String contentBase64) {
        try {
            return Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("内容 Base-64 非法");
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private DigestVersionView toVersionView(DigestVersion version, List<String> witnesses) {
        return new DigestVersionView(version.versionNo(), version.sealedBy(), version.contentRef(),
                version.contentSha256(), version.status().name(), List.copyOf(witnesses),
                version.createdAt());
    }

    private ExportPackageView toPackageView(ExportPackage pkg, List<ExportItem> items) {
        List<ExportItemView> itemViews = items.stream()
                .map(item -> new ExportItemView(item.evidenceKey(), item.versionNo(), item.contentRef(),
                        item.contentSha256(), splitWitnesses(item.witnessIdsCsv())))
                .toList();
        return new ExportPackageView(pkg.packageId(), pkg.requestId(), itemViews, pkg.createdAt());
    }

    /**
     * 便于测试/本地示例以 UTF-8 文本生成内容 Base-64。
     */
    public static String encodeContentUtf8(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
