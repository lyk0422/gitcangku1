package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ContentView;
import com.example.starter.evidence.dto.DigestView;
import com.example.starter.evidence.dto.ExportItemView;
import com.example.starter.evidence.dto.ExportPackageRequest;
import com.example.starter.evidence.dto.ExportPackageView;
import com.example.starter.evidence.dto.RegisterContentRequest;
import com.example.starter.evidence.dto.SealContentRequest;
import com.example.starter.evidence.dto.SealedContentView;
import com.example.starter.evidence.dto.WitnessDigestRequest;
import com.example.starter.evidence.dto.WitnessView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 数字摘要见证核心服务。
 *
 * <p>内容登记：当前保管人写入/替换证物当前文件内容字节，不产生封存版本；
 * 封存：对当前内容计算 SHA-256 并为该证物生成递增版本，历史版本只追加；
 * 见证：两名互不相同的历任/现任保管人分别确认同一封存版本，旧版本见证不会转移到新版本；
 * 导出：按证物键排序后逐件锁定证物行，以提交时的完整最新版本重算摘要并校验两名见证，
 * 任一证物不符即整体 422 且不写入任何包数据，全部通过才在同一事务生成一个包及其清单。</p>
 */
@Service
public class DigestService {

    static final String OP_REGISTER_CONTENT = "REGISTER_CONTENT";
    static final String OP_SEAL_CONTENT = "SEAL_CONTENT";
    static final String OP_WITNESS_DIGEST = "WITNESS_DIGEST";

    /**
     * 同一封存版本可导出所需的互不相同见证人数量。
     */
    static final int REQUIRED_WITNESSES = 2;

    private final EvidenceRepository evidenceRepository;
    private final EvidenceContentRepository contentRepository;
    private final SealedContentRepository sealedContentRepository;
    private final DigestWitnessRepository witnessRepository;
    private final ExportPackageRepository exportPackageRepository;
    private final TransferRecordRepository transferRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public DigestService(EvidenceRepository evidenceRepository,
                         EvidenceContentRepository contentRepository,
                         SealedContentRepository sealedContentRepository,
                         DigestWitnessRepository witnessRepository,
                         ExportPackageRepository exportPackageRepository,
                         TransferRecordRepository transferRepository,
                         CommandLogRepository commandLogRepository,
                         ObjectMapper objectMapper) {
        this.evidenceRepository = evidenceRepository;
        this.contentRepository = contentRepository;
        this.sealedContentRepository = sealedContentRepository;
        this.witnessRepository = witnessRepository;
        this.exportPackageRepository = exportPackageRepository;
        this.transferRepository = transferRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记/替换证物当前文件内容：仅当前保管人；不产生封存版本。
     * 保管期间文件被改动或恢复均可通过本接口反映，导出与查询时重算摘要与封存版本比对。
     */
    @Transactional
    public StoredResponse registerContent(String actorId, String evidenceKey,
                                          RegisterContentRequest request, String requestHash) {
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
        requireOperable(evidence, evidenceKey);
        byte[] content = decodeBase64(request.contentBase64());
        LocalDateTime now = LocalDateTime.now();
        if (contentRepository.findByEvidenceKey(evidenceKey).isPresent()) {
            contentRepository.updateContent(evidenceKey, content, now);
        } else {
            contentRepository.insert(evidenceKey, content, now);
        }
        ContentView view = new ContentView(evidenceKey, sha256Hex(content), now);
        return record(request.commandKey(), OP_REGISTER_CONTENT, actorId, requestHash, 200, view);
    }

    /**
     * 封存登记：对证物当前已登记内容计算 SHA-256 并生成递增封存版本。
     * 重新封存产生新版本，旧版本摘要与见证永久保留但不会转移到新版本。
     */
    @Transactional
    public StoredResponse sealContent(String actorId, String evidenceKey,
                                      SealContentRequest request, String requestHash) {
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
        requireOperable(evidence, evidenceKey);
        EvidenceContent content = contentRepository.findByEvidenceKey(evidenceKey)
                .orElseThrow(() -> ApiException.unprocessable(
                        "证物尚未登记内容，无法封存: " + evidenceKey));
        String sha256 = sha256Hex(content.content());
        int nextVersion = sealedContentRepository.findLatestByEvidenceKey(evidenceKey)
                .map(latest -> latest.versionNo() + 1).orElse(1);
        LocalDateTime now = LocalDateTime.now();
        sealedContentRepository.insert(evidenceKey, nextVersion, sha256, now);
        SealedContentView view = new SealedContentView(evidenceKey, nextVersion, sha256, now);
        return record(request.commandKey(), OP_SEAL_CONTENT, actorId, requestHash, 200, view);
    }

    /**
     * 见证当前封存版本：见证人须为该证物历任或现任保管人；同一保管人对同一版本只能见证一次。
     * 见证已失效（非最新）版本或从未封存分别返回 422/404。
     */
    @Transactional
    public StoredResponse witnessDigest(String actorId, String evidenceKey,
                                        WitnessDigestRequest request, String requestHash) {
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
        int versionNo = request.versionNo();
        SealedContent latest = sealedContentRepository.findLatestByEvidenceKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物尚未封存登记内容: " + evidenceKey));
        if (versionNo != latest.versionNo()) {
            throw ApiException.unprocessable("见证版本已失效，当前最新版本为 "
                    + latest.versionNo() + ": " + evidenceKey);
        }
        if (!eligibleCustodians(evidence).contains(actorId)) {
            throw ApiException.conflict("见证人不是该证物历任或现任保管人: " + actorId);
        }
        boolean duplicated = witnessRepository.findByVersion(evidenceKey, versionNo).stream()
                .anyMatch(w -> w.witnessId().equals(actorId));
        if (duplicated) {
            throw ApiException.conflict("见证人已确认该封存版本，不得重复见证: " + actorId);
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            witnessRepository.insert(evidenceKey, versionNo, actorId, now);
        } catch (DuplicateKeyException e) {
            // 并发下同一保管人同时提交，唯一约束兜底。
            throw ApiException.conflict("见证人已确认该封存版本，不得重复见证: " + actorId);
        }
        WitnessView view = new WitnessView(actorId, versionNo, true, now);
        return record(request.commandKey(), OP_WITNESS_DIGEST, actorId, requestHash, 200, view);
    }

    /**
     * 生成导出包。按证物键排序加锁后裁决：
     * 每件证物都须已封存、当前内容重算摘要与最新封存版本一致、且该版本有两名不同保管人见证；
     * 任一不满足整体 422 且不生成部分包。同一 requestId 重放返回首次包标识与摘要清单，
     * 更换证物集合返回 409。
     */
    @Transactional
    public ExportPackageView exportPackage(String actorId, ExportPackageRequest request) {
        String requestId = request.requestId();
        List<String> keys = normalizedKeys(request);
        // 按证物键排序后逐件加锁，与并发封存/导出使用相同加锁顺序，避免死锁并保证按提交顺序裁决。
        for (String key : keys) {
            lockEvidence(key);
        }
        Optional<ExportPackage> existing = exportPackageRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            List<ExportPackageItem> items = exportPackageRepository.findItemsByRequestId(requestId);
            if (!firstKeySet(items).equals(new TreeSet<>(keys))) {
                throw ApiException.conflict("导出 requestId 已被不同证物集合使用: " + requestId);
            }
            return toView(existing.get(), items);
        }
        // 收集裁决计划；任何一件不满足都在写入前抛出 422，整个事务回滚，不产生部分包。
        record PlannedItem(String evidenceKey, int versionNo, String contentSha256) {
        }
        List<PlannedItem> planned = new ArrayList<>();
        for (String key : keys) {
            EvidenceContent content = contentRepository.findByEvidenceKey(key)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "证物尚未登记内容，无法导出: " + key));
            SealedContent latest = sealedContentRepository.findLatestByEvidenceKey(key)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "证物缺少封存版本，无法导出: " + key));
            String currentSha256 = sha256Hex(content.content());
            if (!currentSha256.equals(latest.contentSha256())) {
                throw ApiException.unprocessable(
                        "证物内容摘要与封存版本 " + latest.versionNo() + " 不符，禁止导出: " + key);
            }
            Set<String> witnesses = new LinkedHashSet<>(witnessRepository
                    .findByVersion(key, latest.versionNo()).stream()
                    .map(DigestWitness::witnessId).toList());
            if (witnesses.size() < REQUIRED_WITNESSES) {
                throw ApiException.unprocessable(
                        "封存版本 " + latest.versionNo() + " 见证保管人不足两名，禁止导出: " + key);
            }
            planned.add(new PlannedItem(key, latest.versionNo(), latest.contentSha256()));
        }
        String packageKey = "pkg-" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        ExportPackage pkg;
        try {
            pkg = exportPackageRepository.insert(packageKey, requestId, now);
        } catch (DuplicateKeyException e) {
            // 并发下同一 requestId 被另一事务先行建包：重放首次包或按集合差异返回 409。
            ExportPackage first = exportPackageRepository.findByRequestId(requestId)
                    .orElseThrow(() -> ApiException.conflict("导出 requestId 冲突: " + requestId));
            List<ExportPackageItem> items = exportPackageRepository.findItemsByRequestId(requestId);
            if (!firstKeySet(items).equals(new TreeSet<>(keys))) {
                throw ApiException.conflict("导出 requestId 已被不同证物集合使用: " + requestId);
            }
            return toView(first, items);
        }
        for (PlannedItem item : planned) {
            exportPackageRepository.insertItem(pkg.id(), item.evidenceKey(),
                    item.versionNo(), item.contentSha256());
        }
        // 从库中重读包，保证首次响应与后续重放的时间戳精度完全一致。
        ExportPackage stored = exportPackageRepository.findByPackageKey(pkg.packageKey()).orElseThrow();
        List<ExportPackageItem> items = exportPackageRepository.findItemsByPackageId(pkg.id());
        return toView(stored, items);
    }

    /**
     * 查询证物摘要见证状态：未封存 NOT_SEALED；当前内容与最新版本摘要不符 DIGEST_MISMATCH；
     * 摘要一致但当前版本见证不足两名 PENDING_WITNESS；全部满足 EXPORTABLE。
     * 同时返回全部历史见证（标注是否属于当前版本）与涉及该证物的历史导出包标识。
     */
    @Transactional(readOnly = true)
    public DigestView digestView(String evidenceKey) {
        evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        Optional<SealedContent> latest = sealedContentRepository.findLatestByEvidenceKey(evidenceKey);
        Optional<EvidenceContent> content = contentRepository.findByEvidenceKey(evidenceKey);
        List<DigestWitness> allWitnesses = witnessRepository.findByEvidenceKey(evidenceKey);
        List<String> packageKeys = exportPackageRepository.findPackageKeysByEvidenceKey(evidenceKey);

        Integer versionNo = null;
        String sealedSha256 = null;
        String currentSha256 = null;
        DigestState state = DigestState.NOT_SEALED;
        if (latest.isPresent()) {
            SealedContent sealed = latest.get();
            versionNo = sealed.versionNo();
            sealedSha256 = sealed.contentSha256();
            currentSha256 = content.map(c -> sha256Hex(c.content())).orElse(null);
            Set<String> currentWitnesses = new LinkedHashSet<>(allWitnesses.stream()
                    .filter(w -> w.versionNo() == sealed.versionNo())
                    .map(DigestWitness::witnessId).toList());
            if (!sealed.contentSha256().equals(currentSha256)) {
                state = DigestState.DIGEST_MISMATCH;
            } else if (currentWitnesses.size() >= REQUIRED_WITNESSES) {
                state = DigestState.EXPORTABLE;
            } else {
                state = DigestState.PENDING_WITNESS;
            }
        }
        int currentVersion = versionNo == null ? -1 : versionNo;
        List<WitnessView> witnessViews = allWitnesses.stream()
                .map(w -> new WitnessView(w.witnessId(), w.versionNo(),
                        w.versionNo() == currentVersion, w.createdAt()))
                .toList();
        return new DigestView(evidenceKey, latest.isPresent(), versionNo, sealedSha256,
                currentSha256, state.name(), witnessViews, packageKeys);
    }

    private List<String> normalizedKeys(ExportPackageRequest request) {
        List<String> keys = request.evidenceKeys().stream().distinct().sorted().toList();
        if (keys.isEmpty()) {
            throw ApiException.badRequest("导出证物集合不能为空");
        }
        return keys;
    }

    private Set<String> firstKeySet(List<ExportPackageItem> items) {
        return new TreeSet<>(items.stream().map(ExportPackageItem::evidenceKey).toList());
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

    /**
     * 内容登记与封存仅允许在证物可操作状态进行：待接收、借出中禁止，封条异常禁止。
     */
    private void requireOperable(Evidence evidence, String evidenceKey) {
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止内容封存相关操作: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("待接收交接期间禁止内容封存相关操作: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.conflict("借出期间禁止内容封存相关操作: " + evidenceKey);
        }
    }

    /**
     * 历任/现任保管人集合：入库后的每任保管人——交接发起人，以及已接受交接的接收人。
     * 已取消交接的指定接收人从未实际保管，不计入。
     */
    private Set<String> eligibleCustodians(Evidence evidence) {
        Set<String> custodians = new LinkedHashSet<>();
        custodians.add(evidence.custodianId());
        for (TransferRecord transfer : transferRepository.findByEvidenceKey(evidence.evidenceKey())) {
            custodians.add(transfer.fromCustodian());
            if (transfer.status() == TransferStatus.ACCEPTED) {
                custodians.add(transfer.toCustodian());
            }
        }
        return custodians;
    }

    private byte[] decodeBase64(String contentBase64) {
        try {
            return Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("contentBase64 不是合法的 Base64 编码");
        }
    }

    private String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
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

    private ExportPackageView toView(ExportPackage pkg, List<ExportPackageItem> items) {
        List<ExportItemView> itemViews = items.stream()
                .map(i -> new ExportItemView(i.evidenceKey(), i.versionNo(), i.contentSha256()))
                .toList();
        return new ExportPackageView(pkg.packageKey(), pkg.requestId(), itemViews, pkg.createdAt());
    }
}
