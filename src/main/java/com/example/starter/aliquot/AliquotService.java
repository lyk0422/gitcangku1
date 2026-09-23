package com.example.starter.aliquot;

import com.example.starter.aliquot.dto.AliquotApplyRequest;
import com.example.starter.aliquot.dto.AliquotConsumptionView;
import com.example.starter.aliquot.dto.AliquotFirstConfirmRequest;
import com.example.starter.aliquot.dto.AliquotItemInput;
import com.example.starter.aliquot.dto.AliquotRejectRequest;
import com.example.starter.aliquot.dto.AliquotReviewView;
import com.example.starter.aliquot.dto.AliquotSecondConfirmRequest;
import com.example.starter.aliquot.dto.SampleBalanceView;
import com.example.starter.aliquot.dto.SampleRegisterRequest;
import com.example.starter.error.ApiException;
import com.example.starter.evidence.CommandLogRepository;
import com.example.starter.evidence.Evidence;
import com.example.starter.evidence.EvidenceRepository;
import com.example.starter.evidence.EvidenceStatus;
import com.example.starter.evidence.StoredResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 多母样联合取样核心服务。
 *
 * <p>并发模型：申请、二次确认、拒绝均按 sampleKey 排序后锁定全部母样证物行
 * （SELECT ... FOR UPDATE），与原交接/借出共用同一把证物行锁，保证重叠母样操作
 * 按事务提交顺序串行化；取样单行锁保证两次确认/拒绝/取消互斥。
 *
 * <p>预留不使用独立计数：预留与耗用均由取样单状态（RESERVED/CONSUMED）+ 明细实时聚合，
 * 拒绝/取消不删明细但立即不计入预留，天然“一次释放全部预留”，无双重写入不一致。
 */
@Service
public class AliquotService {

    static final String OP_SAMPLE_REGISTER = "SAMPLE_REGISTER";
    static final String OP_ALIQUOT_APPLY = "ALIQUOT_APPLY";
    static final String OP_ALIQUOT_FIRST_CONFIRM = "ALIQUOT_FIRST_CONFIRM";
    static final String OP_ALIQUOT_SECOND_CONFIRM = "ALIQUOT_SECOND_CONFIRM";
    static final String OP_ALIQUOT_REJECT = "ALIQUOT_REJECT";
    static final String OP_ALIQUOT_CANCEL = "ALIQUOT_CANCEL";

    private final SampleLedgerRepository ledgerRepository;
    private final AliquotRequestRepository requestRepository;
    private final AliquotRequestItemRepository itemRepository;
    private final AliquotReviewRepository reviewRepository;
    private final AliquotConsumptionRepository consumptionRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public AliquotService(SampleLedgerRepository ledgerRepository,
                          AliquotRequestRepository requestRepository,
                          AliquotRequestItemRepository itemRepository,
                          AliquotReviewRepository reviewRepository,
                          AliquotConsumptionRepository consumptionRepository,
                          EvidenceRepository evidenceRepository,
                          CommandLogRepository commandLogRepository,
                          ObjectMapper objectMapper) {
        this.ledgerRepository = ledgerRepository;
        this.requestRepository = requestRepository;
        this.itemRepository = itemRepository;
        this.reviewRepository = reviewRepository;
        this.consumptionRepository = consumptionRepository;
        this.evidenceRepository = evidenceRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 母样总量登记：母样证物须存在且操作人为当前保管人；总量与单位登记一次后不可修改。
     */
    @Transactional
    public StoredResponse registerSample(String actorId, String sampleKey,
                                         SampleRegisterRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(sampleKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("仅当前保管人可登记母样总量: " + actorId);
        }
        if (evidence.aliquotChild()) {
            throw ApiException.unprocessable("联合取样生成的子样不可再作为母样: " + sampleKey);
        }
        if (ledgerRepository.findByKey(sampleKey).isPresent()) {
            throw ApiException.conflict("母样总量已登记且不可修改: " + sampleKey);
        }
        LocalDateTime now = LocalDateTime.now();
        ledgerRepository.insert(sampleKey, request.totalQuantity(), request.unit(), now);
        SampleLedger ledger = ledgerRepository.findByKey(sampleKey).orElseThrow();
        return record(request.commandKey(), OP_SAMPLE_REGISTER, actorId, requestHash,
                201, toBalanceView(ledger, new SampleReservation(sampleKey, 0, 0)));
    }

    /**
     * 联合取样申请：锁定全部母样行后原子预留；任一前置条件不满足则整单回滚、无任何预留。
     */
    @Transactional
    public StoredResponse apply(String actorId, AliquotApplyRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 参数集合规范化：去重判定与锁定顺序均按 sampleKey 排序，换序请求视为同一组参数。
        Map<String, Long> wanted = normalizeItems(request.items());
        if (requestRepository.findByKey(request.aliquotKey()).isPresent()) {
            throw ApiException.conflict("联合取样单键已存在: " + request.aliquotKey());
        }
        LocalDateTime now = LocalDateTime.now();
        // 记录申请时各母样版本快照；锁定顺序全局一致，避免与其它跨母样事务死锁。
        Map<String, Evidence> locked = new LinkedHashMap<>();
        for (String sampleKey : wanted.keySet()) {
            locked.put(sampleKey, lockEvidence(sampleKey));
        }
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Map<String, SampleLedger> ledgers = new LinkedHashMap<>();
        for (String sampleKey : wanted.keySet()) {
            Evidence evidence = locked.get(sampleKey);
            requireSamplable(evidence);
            if (!evidence.custodianId().equals(actorId)) {
                throw ApiException.conflict("申请人不是母样当前保管人: " + sampleKey);
            }
            SampleLedger ledger = ledgerRepository.findByKeyForUpdate(sampleKey)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "母样未登记总量: " + sampleKey));
            SampleReservation usage = consumptionRepository.aggregateBySample(sampleKey);
            long available = ledger.totalQuantity() - usage.reserved() - usage.consumed();
            if (wanted.get(sampleKey) > available) {
                throw ApiException.unprocessable(
                        "母样可用余额不足: " + sampleKey + "，可用 " + available
                                + "，申请 " + wanted.get(sampleKey));
            }
            ledgers.put(sampleKey, ledger);
        }

        requestRepository.insert(request.aliquotKey(), actorId, now);
        AliquotRequest created = requestRepository.findByKey(request.aliquotKey()).orElseThrow();
        for (Map.Entry<String, Long> entry : wanted.entrySet()) {
            itemRepository.insert(created.id(), entry.getKey(), entry.getValue(),
                    locked.get(entry.getKey()).version(), now);
        }
        AliquotConsumptionView.Detail view = loadDetail(created);
        return record(request.commandKey(), OP_ALIQUOT_APPLY, actorId, requestHash, 201, view);
    }

    /**
     * 第一次审核确认：审核人不能是任一母样当前保管人；确认后申请版本加 1。
     */
    @Transactional
    public StoredResponse firstConfirm(String actorId, String aliquotKey,
                                      AliquotFirstConfirmRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        AliquotRequest aliquot = lockRequest(aliquotKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireReserved(aliquot, aliquotKey);
        if (aliquot.firstReviewer() != null) {
            throw ApiException.conflict("取样单已完成第一次确认: " + aliquotKey);
        }
        List<AliquotRequestItem> items = itemRepository.findByRequestId(aliquot.id());
        requireReviewerNotCustodian(actorId, items, aliquotKey);

        LocalDateTime now = LocalDateTime.now();
        if (!requestRepository.markFirstReview(aliquot.id(), actorId, now)) {
            throw ApiException.conflict("第一次确认已被并发提交: " + aliquotKey);
        }
        reviewRepository.insert(aliquot.id(), 1, actorId, now);
        AliquotRequest updated = requestRepository.findByKey(aliquotKey).orElseThrow();
        return record(request.commandKey(), OP_ALIQUOT_FIRST_CONFIRM, actorId, requestHash,
                200, loadDetail(updated));
    }

    /**
     * 第二次审核确认：两名审核人须不同，且审核人不是任一母样当前保管人；
     * 必须携带申请版本（第一次确认后为 1）与全部母样当前版本；
     * 期间任一母样转移、借出、封条异常、数量或保管人变化（版本不一致）均 409。
     * 成功后一次把预留转为耗用，生成全部 SEALED 子样与不可变映射。
     */
    @Transactional
    public StoredResponse secondConfirm(String actorId, String aliquotKey,
                                       AliquotSecondConfirmRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        AliquotRequest aliquot = lockRequest(aliquotKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireReserved(aliquot, aliquotKey);
        if (aliquot.firstReviewer() == null) {
            throw ApiException.conflict("取样单尚未完成第一次确认: " + aliquotKey);
        }
        if (aliquot.firstReviewer().equals(actorId)) {
            throw ApiException.conflict("两次确认必须由不同审核人完成: " + actorId);
        }
        if (aliquot.secondReviewer() != null) {
            throw ApiException.conflict("取样单已完成耗用: " + aliquotKey);
        }
        if (request.requestVersion() == null || request.requestVersion() != aliquot.version()) {
            throw ApiException.conflict(
                    "申请版本不匹配: 携带 " + request.requestVersion() + "，当前 " + aliquot.version());
        }

        List<AliquotRequestItem> items = itemRepository.findByRequestId(aliquot.id());
        Map<String, Long> carried = normalizeVersionMap(request.sampleVersions());
        Set<String> itemKeys = items.stream().map(AliquotRequestItem::sampleKey)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        if (!carried.keySet().equals(itemKeys)) {
            throw ApiException.badRequest("携带的母样版本键集合与申请明细不一致");
        }

        // 持锁核对各母样：申请期间发生任何保管人/状态/数量变化，版本都会偏离申请快照。
        LocalDateTime now = LocalDateTime.now();
        for (AliquotRequestItem item : items) {
            Evidence evidence = lockEvidence(item.sampleKey());
            if (evidence.version() != item.sampleVersion()) {
                throw ApiException.conflict(
                        "母样自申请后已发生变化，确认失效: " + item.sampleKey());
            }
            if (carried.get(item.sampleKey()) != evidence.version()) {
                throw ApiException.conflict(
                        "携带的母样版本与当前版本不一致: " + item.sampleKey());
            }
            if (evidence.status() != EvidenceStatus.SEALED) {
                throw ApiException.conflict(
                        "母样当前封条/借出/交接状态不允许耗用: " + item.sampleKey());
            }
            if (actorId.equals(evidence.custodianId())) {
                throw ApiException.conflict(
                        "审核人不能是任一母样当前保管人: " + actorId);
            }
        }

        if (!requestRepository.markConsumed(aliquot.id(), actorId, now)) {
            throw ApiException.conflict("取样单已被并发终结: " + aliquotKey);
        }
        for (AliquotRequestItem item : items) {
            Evidence mother = evidenceRepository.findByKey(item.sampleKey()).orElseThrow();
            String digest = digest(aliquotKey + "|" + item.sampleKey());
            String childKey = "ALQC-" + digest.substring(0, 59);
            String childSealNo = "ALQS-" + digest.substring(0, 16);
            evidenceRepository.insertChild(childKey, mother.caseKey(), mother.category(),
                    childSealNo, mother.custodianId(), now);
            consumptionRepository.insert(aliquot.id(), item.sampleKey(), item.quantity(),
                    childKey, now);
            // 耗用改变母样数量，版本加 1，使任何携带旧版本的迟到确认失败。
            evidenceRepository.bumpVersion(item.sampleKey(), now);
        }
        reviewRepository.insert(aliquot.id(), 2, actorId, now);
        AliquotRequest updated = requestRepository.findByKey(aliquotKey).orElseThrow();
        return record(request.commandKey(), OP_ALIQUOT_SECOND_CONFIRM, actorId, requestHash,
                200, loadDetail(updated));
    }

    /**
     * 审核拒绝：审核人不能是任一母样当前保管人；拒绝后单据终结，预留一次全部释放。
     */
    @Transactional
    public StoredResponse reject(String actorId, String aliquotKey,
                                 AliquotRejectRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        AliquotRequest aliquot = lockRequest(aliquotKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireReserved(aliquot, aliquotKey);
        List<AliquotRequestItem> items = itemRepository.findByRequestId(aliquot.id());
        requireReviewerNotCustodian(actorId, items, aliquotKey);

        LocalDateTime now = LocalDateTime.now();
        if (!requestRepository.markRejected(aliquot.id(), actorId, now)) {
            throw ApiException.conflict("取样单已被并发终结: " + aliquotKey);
        }
        AliquotRequest updated = requestRepository.findByKey(aliquotKey).orElseThrow();
        return record(request.commandKey(), OP_ALIQUOT_REJECT, actorId, requestHash,
                200, loadDetail(updated));
    }

    /**
     * 审核前取消：仅申请人可取消，且必须尚未有任何审核；取消后预留一次全部释放。
     */
    @Transactional
    public StoredResponse cancel(String actorId, String aliquotKey, String commandKey,
                                 String requestHash) {
        Optional<StoredResponse> replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        AliquotRequest aliquot = lockRequest(aliquotKey);
        replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireReserved(aliquot, aliquotKey);
        if (aliquot.firstReviewer() != null) {
            throw ApiException.conflict("已开始审核的取样单不能取消，只能拒绝: " + aliquotKey);
        }
        if (!aliquot.applicantId().equals(actorId)) {
            throw ApiException.conflict("仅申请人可取消取样单: " + actorId);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!requestRepository.markCancelled(aliquot.id(), now)) {
            throw ApiException.conflict("取样单已被并发终结: " + aliquotKey);
        }
        AliquotRequest updated = requestRepository.findByKey(aliquotKey).orElseThrow();
        return record(commandKey, OP_ALIQUOT_CANCEL, actorId, requestHash,
                200, loadDetail(updated));
    }

    /**
     * 查询取样单详情：明细、耗用映射与审核历史，只读。
     */
    @Transactional(readOnly = true)
    public AliquotConsumptionView.Detail getDetail(String aliquotKey) {
        AliquotRequest aliquot = requestRepository.findByKey(aliquotKey)
                .orElseThrow(() -> ApiException.notFound("联合取样单不存在: " + aliquotKey));
        return loadDetail(aliquot);
    }

    /**
     * 查询母样余额视图：总量、当前预留、累计耗用与可用余额，只读。
     */
    @Transactional(readOnly = true)
    public SampleBalanceView getBalance(String sampleKey) {
        SampleLedger ledger = ledgerRepository.findByKey(sampleKey)
                .orElseThrow(() -> ApiException.notFound("母样台账不存在: " + sampleKey));
        SampleReservation usage = consumptionRepository.aggregateBySample(sampleKey);
        return toBalanceView(ledger, usage);
    }

    private Map<String, Long> normalizeItems(List<AliquotItemInput> items) {
        if (items == null || items.size() < 2 || items.size() > 20) {
            throw ApiException.badRequest("联合取样须覆盖 2～20 件母样");
        }
        Map<String, Long> sorted = new TreeMap<>();
        for (AliquotItemInput item : items) {
            if (item == null || item.sampleKey() == null || item.sampleKey().isBlank()
                    || item.quantity() == null || item.quantity() <= 0) {
                throw ApiException.badRequest("母样键不可为空且取用量须为正整数");
            }
            if (sorted.put(item.sampleKey(), item.quantity()) != null) {
                throw ApiException.badRequest("同一取样单内母样不可重复: " + item.sampleKey());
            }
        }
        return sorted;
    }

    private Map<String, Long> normalizeVersionMap(Map<String, Long> versions) {
        if (versions == null || versions.size() < 2 || versions.size() > 20) {
            throw ApiException.badRequest("必须携带全部 2～20 件母样的版本");
        }
        Map<String, Long> sorted = new TreeMap<>();
        for (Map.Entry<String, Long> entry : versions.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw ApiException.badRequest("母样键与版本均不可为空");
            }
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }

    private Evidence lockEvidence(String sampleKey) {
        return evidenceRepository.findByKeyForUpdate(sampleKey)
                .orElseThrow(() -> ApiException.notFound("母样证物不存在: " + sampleKey));
    }

    private AliquotRequest lockRequest(String aliquotKey) {
        return requestRepository.findByKeyForUpdate(aliquotKey)
                .orElseThrow(() -> ApiException.notFound("联合取样单不存在: " + aliquotKey));
    }

    private void requireSamplable(Evidence evidence) {
        if (evidence.aliquotChild()) {
            throw ApiException.unprocessable("子样不可再作为母样取样: " + evidence.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("母样封条异常，禁止取样: " + evidence.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.unprocessable("母样已借出，禁止取样: " + evidence.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.unprocessable("母样存在待接收交接，禁止取样: " + evidence.evidenceKey());
        }
    }

    private void requireReserved(AliquotRequest aliquot, String aliquotKey) {
        if (aliquot.status() != AliquotStatus.RESERVED) {
            throw ApiException.conflict("取样单已终结，当前状态: " + aliquot.status());
        }
    }

    private void requireReviewerNotCustodian(String actorId, List<AliquotRequestItem> items,
                                             String aliquotKey) {
        for (AliquotRequestItem item : items) {
            Evidence evidence = evidenceRepository.findByKeyForUpdate(item.sampleKey())
                    .orElseThrow(() -> ApiException.notFound("母样证物不存在: " + item.sampleKey()));
            if (actorId.equals(evidence.custodianId())) {
                throw ApiException.conflict(
                        "审核人不能是任一母样当前保管人: " + actorId);
            }
        }
    }

    private AliquotConsumptionView.Detail loadDetail(AliquotRequest aliquot) {
        List<AliquotConsumptionView.ItemView> itemViews = itemRepository
                .findByRequestId(aliquot.id()).stream()
                .map(item -> new AliquotConsumptionView.ItemView(
                        item.sampleKey(), item.quantity(), item.sampleVersion()))
                .toList();
        List<AliquotConsumptionView> consumptions = consumptionRepository
                .findByRequestId(aliquot.id()).stream()
                .map(c -> new AliquotConsumptionView(
                        c.sampleKey(), c.quantity(), c.childEvidenceKey()))
                .toList();
        List<AliquotReviewView> reviews = reviewRepository.findByRequestId(aliquot.id()).stream()
                .map(r -> new AliquotReviewView(r.seq(), r.reviewerId(), r.createdAt()))
                .toList();
        return new AliquotConsumptionView.Detail(
                aliquot.aliquotKey(), aliquot.applicantId(), aliquot.status(), aliquot.version(),
                itemViews, consumptions, reviews, aliquot.createdAt(), aliquot.updatedAt());
    }

    private SampleBalanceView toBalanceView(SampleLedger ledger, SampleReservation usage) {
        long available = ledger.totalQuantity() - usage.reserved() - usage.consumed();
        return new SampleBalanceView(ledger.sampleKey(), ledger.totalQuantity(), ledger.unit(),
                usage.reserved(), usage.consumed(), available);
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

    private String digest(String input) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    messageDigest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
