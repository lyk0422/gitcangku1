package com.example.starter.evidence.aliquot;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.CommandLogRepository;
import com.example.starter.evidence.Evidence;
import com.example.starter.evidence.EvidenceRepository;
import com.example.starter.evidence.EvidenceStatus;
import com.example.starter.evidence.SampleKind;
import com.example.starter.evidence.StoredResponse;
import com.example.starter.evidence.aliquot.dto.AliquotMappingView;
import com.example.starter.evidence.aliquot.dto.MotherRegisterRequest;
import com.example.starter.evidence.aliquot.dto.MotherSampleView;
import com.example.starter.evidence.aliquot.dto.ReviewView;
import com.example.starter.evidence.aliquot.dto.SamplingApplyRequest;
import com.example.starter.evidence.aliquot.dto.SamplingCancelRequest;
import com.example.starter.evidence.aliquot.dto.SamplingItemInput;
import com.example.starter.evidence.aliquot.dto.SamplingItemView;
import com.example.starter.evidence.aliquot.dto.SamplingOrderView;
import com.example.starter.evidence.aliquot.dto.SamplingReviewRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 多母样联合取样核心服务。所有写操作在单事务内完成：
 * 按母样键排序依次锁定证物行与母样行，避免与交接/借出发生死锁；
 * 预留使用带可用余额条件的更新，整单任一母样余额不足即抛错回滚，不产生任何预留、不占用键。
 * 二次确认通过申请版本与全部母样版本快照检测期间任何转移、借出、封条异常、数量或保管人变化。
 */
@Service
public class AliquotService {

    static final String OP_MOTHER_REGISTER = "MOTHER_REGISTER";
    static final String OP_SAMPLING_APPLY = "SAMPLING_APPLY";
    static final String OP_SAMPLING_CONFIRM = "SAMPLING_CONFIRM";
    static final String OP_SAMPLING_REJECT = "SAMPLING_REJECT";
    static final String OP_SAMPLING_CANCEL = "SAMPLING_CANCEL";

    private final MotherSampleRepository motherSampleRepository;
    private final SamplingOrderRepository samplingOrderRepository;
    private final SamplingItemRepository samplingItemRepository;
    private final SamplingReviewRepository samplingReviewRepository;
    private final AliquotMappingRepository aliquotMappingRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final SamplingApplyExecutor applyExecutor;
    private final ObjectMapper objectMapper;

    public AliquotService(MotherSampleRepository motherSampleRepository,
                          SamplingOrderRepository samplingOrderRepository,
                          SamplingItemRepository samplingItemRepository,
                          SamplingReviewRepository samplingReviewRepository,
                          AliquotMappingRepository aliquotMappingRepository,
                          EvidenceRepository evidenceRepository,
                          CommandLogRepository commandLogRepository,
                          SamplingApplyExecutor applyExecutor,
                          ObjectMapper objectMapper) {
        this.motherSampleRepository = motherSampleRepository;
        this.samplingOrderRepository = samplingOrderRepository;
        this.samplingItemRepository = samplingItemRepository;
        this.samplingReviewRepository = samplingReviewRepository;
        this.aliquotMappingRepository = aliquotMappingRepository;
        this.evidenceRepository = evidenceRepository;
        this.commandLogRepository = commandLogRepository;
        this.applyExecutor = applyExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 母样登记：仅当前保管人，证物须 SEALED 且为普通证物；总量正整数、单位一经登记不可修改。
     */
    @Transactional
    public StoredResponse registerMother(String actorId, String sampleKey,
                                         MotherRegisterRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = evidenceRepository.findByKeyForUpdate(sampleKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + sampleKey));
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是当前保管人: " + actorId);
        }
        if (evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.conflict("母样登记时证物须为 SEALED: " + sampleKey);
        }
        if (evidence.sampleKind() == SampleKind.ALIQUOT) {
            throw ApiException.unprocessable("联合取样生成的子样不可再取样: " + sampleKey);
        }
        if (motherSampleRepository.findByKey(sampleKey).isPresent()) {
            throw ApiException.conflict("母样已登记，总量与单位不可修改: " + sampleKey);
        }
        LocalDateTime now = LocalDateTime.now();
        motherSampleRepository.insert(sampleKey, request.totalQty(), request.unit(), now);
        MotherSample mother = motherSampleRepository.findByKey(sampleKey).orElseThrow();
        return record(request.commandKey(), OP_MOTHER_REGISTER, actorId, requestHash,
                201, toMotherView(mother));
    }

    /**
     * 联合取样申请：原子预留各母样数量，任一余额不足或键冲突则整单失败，无预留、不占键。
     * 本方法不在事务内：先做无锁重放预判，再由 {@link SamplingApplyExecutor} 在单事务内
     * 先插入申请单（request_id 唯一约束拦截并发重复）、再逐母样预留；
     * 捕获唯一冲突后重放先提交事务的首次结果（允许同参集合换序、换 commandKey）。
     */
    public StoredResponse apply(String actorId, SamplingApplyRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<SamplingItemInput> items = sortedItems(request);
        Optional<SamplingOrder> preExisting = samplingOrderRepository
                .findByRequestId(request.requestId());
        if (preExisting.isPresent()) {
            return handleExistingRequest(preExisting.get(), request, actorId, items);
        }
        try {
            String fingerprint = fingerprint(actorId, request.aliquotKey(), items);
            return applyExecutor.execute(actorId, request, items, fingerprint, requestHash,
                    (a, h) -> {
                        SamplingOrderView view = buildOrderView(
                                samplingOrderRepository.findByRequestId(request.requestId())
                                        .orElseThrow());
                        return record(request.commandKey(), OP_SAMPLING_APPLY, a, h, 201, view);
                    });
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发下唯一约束触发：先查命令日志（同 commandKey），再查申请单（同 requestId 换序重放）。
            StoredResponse byCommand = commandLogRepository.findByKey(request.commandKey())
                    .map(log -> {
                        if (!log.requestHash().equals(requestHash)) {
                            throw ApiException.conflict(
                                    "幂等键已被不同参数使用: " + request.commandKey());
                        }
                        return new StoredResponse(log.responseStatus(), log.responseBody());
                    })
                    .orElse(null);
            if (byCommand != null) {
                return byCommand;
            }
            SamplingOrder existing = samplingOrderRepository.findByRequestId(request.requestId())
                    .orElseThrow(() -> ApiException.conflict(
                            "资源键冲突: requestId 或 aliquotKey 已被占用"));
            return handleExistingRequest(existing, request, actorId, items);
        }
    }

    /**
     * 审核确认：version=0 为第一次确认（不得携带版本）；version=1 为第二次确认，
     * 必须携带申请版本 1 与全部母样版本。两名审核人不同且都不得是任一母样当前保管人。
     */
    @Transactional
    public StoredResponse confirm(String actorId, String requestId,
                                  SamplingReviewRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        SamplingOrder order = lockOrder(requestId);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (order.status() != SamplingStatus.PENDING) {
            throw ApiException.conflict("联合取样单已终态，不可再确认: " + requestId);
        }
        List<SamplingReview> reviews = samplingReviewRepository.findByRequestId(requestId);
        List<SamplingItem> items = samplingItemRepository.findByRequestId(requestId);
        LocalDateTime now = LocalDateTime.now();
        if (order.version() == 0) {
            if (request.requestVersion() != null || request.sampleVersions() != null) {
                throw ApiException.badRequest("第一次确认不得携带申请版本或母样版本");
            }
            List<LockedMother> locked = lockMothers(items);
            requireReviewerNotCustodian(actorId, locked);
            locked.forEach(this::requireMotherUnchanged);
            if (!samplingOrderRepository.advanceFirstConfirmation(requestId, now)) {
                throw ApiException.conflict("申请单状态已变化，第一次确认失败: " + requestId);
            }
            samplingReviewRepository.insert(requestId, 1, ReviewAction.CONFIRM, actorId,
                    request.note(), now);
            SamplingOrderView view = buildOrderView(
                    samplingOrderRepository.findByRequestId(requestId).orElseThrow());
            return record(request.commandKey(), OP_SAMPLING_CONFIRM, actorId, requestHash,
                    200, view);
        }
        if (order.version() != 1) {
            throw ApiException.conflict("申请单审核版本异常: " + requestId);
        }
        SamplingReview first = reviews.stream()
                .filter(r -> r.action() == ReviewAction.CONFIRM && r.seq() == 1)
                .findFirst()
                .orElseThrow(() -> ApiException.conflict("缺少第一次确认记录: " + requestId));
        if (first.reviewerId().equals(actorId)) {
            throw ApiException.conflict("第二次确认审核人必须与第一次不同: " + actorId);
        }
        if (request.requestVersion() == null || request.requestVersion() != order.version()) {
            throw ApiException.conflict("第二次确认携带的申请版本不匹配: " + requestId);
        }
        Map<String, Long> versions = request.sampleVersions();
        if (versions == null || versions.size() != items.size()) {
            throw ApiException.conflict("第二次确认必须携带全部母样版本: " + requestId);
        }
        // 按母样键排序逐件成对锁定证物行与母样行：检测封条、保管人、数量版本任何变化，
        // 并验证客户端携带版本。
        List<LockedMother> locked = lockMothers(items);
        requireReviewerNotCustodian(actorId, locked);
        for (LockedMother lm : locked) {
            SamplingItem item = lm.item();
            Long carried = versions.get(item.sampleKey());
            if (carried == null || carried != item.sampleVersion()) {
                throw ApiException.conflict(
                        "母样版本与申请时不一致或缺失: " + item.sampleKey());
            }
            requireMotherUnchanged(lm);
        }
        if (evidenceRepository.findByKey(order.aliquotKey()).isPresent()) {
            throw ApiException.conflict(
                    "aliquotKey 已被其他证物占用，子样无法生成: " + order.aliquotKey());
        }
        String caseKey = locked.get(0).evidence().caseKey();
        // 一次把全部预留转为耗用，同事务生成子样与全部不可变映射，杜绝半生成。
        if (!samplingOrderRepository.confirm(requestId, order.version(), now)) {
            throw ApiException.conflict("申请单已被并发处理: " + requestId);
        }
        for (LockedMother lm : locked) {
            SamplingItem item = lm.item();
            if (!motherSampleRepository.consume(item.sampleKey(), item.qty(), now)) {
                throw ApiException.conflict(
                        "母样预留数量异常，耗用失败: " + item.sampleKey());
            }
            aliquotMappingRepository.insert(order.aliquotKey(), requestId, item.sampleKey(),
                    item.qty(), item.unit(), now);
        }
        evidenceRepository.insertAliquot(order.aliquotKey(), caseKey, order.custodianId(), now);
        samplingReviewRepository.insert(requestId, 2, ReviewAction.CONFIRM, actorId,
                request.note(), now);
        SamplingOrderView view = buildOrderView(
                samplingOrderRepository.findByRequestId(requestId).orElseThrow());
        return record(request.commandKey(), OP_SAMPLING_CONFIRM, actorId, requestHash, 200, view);
    }

    /**
     * 审核拒绝：审核人不得是任一母样当前保管人；PENDING 期间（含第一次确认后）可拒绝，
     * 一次释放全部预留。
     */
    @Transactional
    public StoredResponse reject(String actorId, String requestId,
                                 SamplingReviewRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        SamplingOrder order = lockOrder(requestId);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (order.status() != SamplingStatus.PENDING) {
            throw ApiException.conflict("联合取样单已终态，不可拒绝: " + requestId);
        }
        List<SamplingItem> items = samplingItemRepository.findByRequestId(requestId);
        List<LockedMother> locked = lockMothers(items);
        requireReviewerNotCustodian(actorId, locked);
        int seq = (int) samplingReviewRepository.findByRequestId(requestId).size() + 1;
        LocalDateTime now = LocalDateTime.now();
        releaseAll(locked, now);
        if (!samplingOrderRepository.reject(requestId, now)) {
            throw ApiException.conflict("申请单已被并发处理: " + requestId);
        }
        samplingReviewRepository.insert(requestId, seq, ReviewAction.REJECT, actorId,
                request.note(), now);
        SamplingOrderView view = buildOrderView(
                samplingOrderRepository.findByRequestId(requestId).orElseThrow());
        return record(request.commandKey(), OP_SAMPLING_REJECT, actorId, requestHash, 200, view);
    }

    /**
     * 审核前取消：仅申请保管人，且尚无任何审核动作；一次释放全部预留。
     */
    @Transactional
    public StoredResponse cancel(String actorId, String requestId,
                                 SamplingCancelRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        SamplingOrder order = lockOrder(requestId);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!order.custodianId().equals(actorId)) {
            throw ApiException.conflict("仅申请时的母样保管人可取消申请: " + actorId);
        }
        if (order.status() != SamplingStatus.PENDING) {
            throw ApiException.conflict("联合取样单已终态，不可取消: " + requestId);
        }
        if (order.version() != 0
                || !samplingReviewRepository.findByRequestId(requestId).isEmpty()) {
            throw ApiException.conflict("已有审核确认，不可再取消，只能拒绝: " + requestId);
        }
        List<SamplingItem> items = samplingItemRepository.findByRequestId(requestId);
        LocalDateTime now = LocalDateTime.now();
        releaseAll(lockMothers(items), now);
        if (!samplingOrderRepository.cancel(requestId, now)) {
            throw ApiException.conflict("申请单已被并发处理: " + requestId);
        }
        samplingReviewRepository.insert(requestId, 1, ReviewAction.CANCEL, actorId,
                request.note(), now);
        SamplingOrderView view = buildOrderView(
                samplingOrderRepository.findByRequestId(requestId).orElseThrow());
        return record(request.commandKey(), OP_SAMPLING_CANCEL, actorId, requestHash, 200, view);
    }

    /**
     * 查询母样余额、预留、耗用与版本（只读）。
     */
    @Transactional(readOnly = true)
    public MotherSampleView motherView(String sampleKey) {
        MotherSample mother = motherSampleRepository.findByKey(sampleKey)
                .orElseThrow(() -> ApiException.notFound("母样未登记: " + sampleKey));
        return toMotherView(mother);
    }

    /**
     * 查询联合取样单明细、审核历史与不可变映射（只读）。
     */
    @Transactional(readOnly = true)
    public SamplingOrderView orderView(String requestId) {
        samplingOrderRepository.findByRequestId(requestId)
                .orElseThrow(() -> ApiException.notFound("联合取样单不存在: " + requestId));
        return buildOrderView(samplingOrderRepository.findByRequestId(requestId).orElseThrow());
    }

    private List<SamplingItemInput> sortedItems(SamplingApplyRequest request) {
        Set<String> distinct = request.items().stream()
                .map(SamplingItemInput::sampleKey)
                .collect(Collectors.toSet());
        if (distinct.size() != request.items().size()) {
            throw ApiException.badRequest("联合取样的母样不得重复");
        }
        return request.items().stream()
                .sorted((a, b) -> a.sampleKey().compareTo(b.sampleKey()))
                .toList();
    }

    /**
     * 已成对锁定的母样：证物行锁与母样行锁均已持有。
     */
    private record LockedMother(SamplingItem item, Evidence evidence, MotherSample mother) {
    }

    /**
     * 按母样键排序后逐件成对锁定（先证物行后母样行），与申请、交接/借出走同一全局顺序，避免死锁。
     */
    private List<LockedMother> lockMothers(List<SamplingItem> items) {
        return items.stream()
                .sorted((a, b) -> a.sampleKey().compareTo(b.sampleKey()))
                .map(item -> {
                    Evidence evidence = evidenceRepository.findByKeyForUpdate(item.sampleKey())
                            .orElseThrow(() -> ApiException.notFound(
                                    "母样证物不存在: " + item.sampleKey()));
                    MotherSample mother = motherSampleRepository.findByKeyForUpdate(item.sampleKey())
                            .orElseThrow(() -> ApiException.notFound(
                                    "母样登记不存在: " + item.sampleKey()));
                    return new LockedMother(item, evidence, mother);
                })
                .toList();
    }

    private void requireReviewerNotCustodian(String reviewerId, List<LockedMother> locked) {
        for (LockedMother lm : locked) {
            if (lm.evidence().custodianId().equals(reviewerId)) {
                throw ApiException.conflict("审核人不能是任一母样当前保管人: " + reviewerId);
            }
        }
    }

    /**
     * 二次确认（含第一次确认前）快照比对：母样数量版本、证物版本、封条状态、保管人任一变化即 409。
     * 证物版本覆盖转移、借出（即使已归还）、封条核验等全部证物变更。
     */
    private void requireMotherUnchanged(LockedMother lm) {
        SamplingItem item = lm.item();
        if (lm.mother().version() != item.sampleVersion()) {
            throw ApiException.conflict(
                    "期间母样数量已变化，联合取样确认失败: " + item.sampleKey());
        }
        if (lm.evidence().version() != item.evidenceVersion()) {
            throw ApiException.conflict(
                    "期间母样发生转移、借出或封条变更，联合取样确认失败: " + item.sampleKey());
        }
        if (lm.evidence().status() != EvidenceStatus.SEALED
                || !lm.evidence().custodianId().equals(item.custodianSnapshot())) {
            throw ApiException.conflict(
                    "期间母样发生转移、借出或封条异常，联合取样确认失败: " + item.sampleKey());
        }
    }

    private void releaseAll(List<LockedMother> locked, LocalDateTime now) {
        for (LockedMother lm : locked) {
            if (!motherSampleRepository.release(lm.item().sampleKey(), lm.item().qty(), now)) {
                throw ApiException.conflict(
                        "母样预留数量与申请明细不一致，释放失败: " + lm.item().sampleKey());
            }
        }
    }

    private StoredResponse handleExistingRequest(SamplingOrder existing, SamplingApplyRequest request,
                                                 String actorId, List<SamplingItemInput> items) {
        String fingerprint = fingerprint(actorId, request.aliquotKey(), items);
        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已被不同参数集合使用: " + request.requestId());
        }
        // 同参集合（允许换序）重放：原样返回首次结果，失败不产生新申请。
        return replayStoredCommand(existing.commandKey());
    }

    private StoredResponse replayStoredCommand(String commandKey) {
        CommandLogRepository.CommandLog log = commandLogRepository.findByKey(commandKey)
                .orElseThrow(() -> ApiException.conflict(
                        "requestId 对应首次结果不可读取: " + commandKey));
        return new StoredResponse(log.responseStatus(), log.responseBody());
    }

    private SamplingOrder lockOrder(String requestId) {
        return samplingOrderRepository.findByRequestIdForUpdate(requestId)
                .orElseThrow(() -> ApiException.notFound("联合取样单不存在: " + requestId));
    }

    /**
     * 申请指纹：操作人 + aliquotKey + 按母样键排序的“键:数量”行，集合换序指纹不变，
     * 改数量、改母样集合、改 aliquotKey 或换人指纹均改变。
     */
    private String fingerprint(String actorId, String aliquotKey, List<SamplingItemInput> items) {
        String canonical = actorId + "\n" + aliquotKey + "\n"
                + items.stream()
                .map(i -> i.sampleKey() + ":" + i.qty())
                .collect(Collectors.joining("\n"));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
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

    private MotherSampleView toMotherView(MotherSample mother) {
        return new MotherSampleView(mother.sampleKey(), mother.totalQty(), mother.unit(),
                mother.reservedQty(), mother.consumedQty(), mother.availableQty(),
                mother.version());
    }

    private SamplingOrderView buildOrderView(SamplingOrder order) {
        List<SamplingItemView> items = samplingItemRepository.findByRequestId(order.requestId())
                .stream()
                .map(i -> new SamplingItemView(i.sampleKey(), i.qty(), i.unit(), i.sampleVersion()))
                .toList();
        List<ReviewView> reviews = samplingReviewRepository.findByRequestId(order.requestId())
                .stream()
                .map(r -> new ReviewView(r.seq(), r.action().name(), r.reviewerId(), r.note(),
                        r.createdAt()))
                .toList();
        List<AliquotMappingView> mappings =
                aliquotMappingRepository.findByRequestId(order.requestId()).stream()
                        .map(m -> new AliquotMappingView(m.sampleKey(), m.qty(), m.unit(),
                                m.aliquotKey()))
                        .toList();
        return new SamplingOrderView(order.requestId(), order.aliquotKey(), order.custodianId(),
                order.status().name(), order.version(), items, reviews, mappings,
                order.createdAt(), order.confirmedAt(), order.decidedAt());
    }
}
