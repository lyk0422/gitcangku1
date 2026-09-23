package com.example.starter.handoff;

import com.example.starter.batch.ApiException;
import com.example.starter.batch.BatchRepository;
import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.StoredResponse;
import com.example.starter.handoff.dto.BatchHolderResponse;
import com.example.starter.handoff.dto.CancelHandoffRequest;
import com.example.starter.handoff.dto.CreateHandoffRequest;
import com.example.starter.handoff.dto.HandoffEvidenceResponse;
import com.example.starter.handoff.dto.ReceiveHandoffRequest;
import com.example.starter.handoff.dto.ShipHandoffRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 跨厂移交核心服务。
 *
 * <p>并发策略：同一移交单的发运/接收/取消在事务内对 handoff 行 SELECT ... FOR UPDATE 串行化，
 * 按事务提交顺序裁决；接收在判定前按全局顺序锁定清单全部批次及其完整祖先闭包，
 * 与召回事务对祖先/后代批次的行锁互斥——任何先于接收提交的召回都会被接收重新读到并拦截。
 * requestId 幂等通过 command_log 主键 + 规范化参数指纹实现：清单/封签换序视为同参，
 * 同参重放返回首次快照，异参返回 409，失败不写 command_log；manifestKey 全局唯一。
 */
@Service
public class HandoffService {

    private static final String CMD_CREATE = "HANDOFF_CREATE";
    private static final String CMD_SHIP = "HANDOFF_SHIP";
    private static final String CMD_RECEIVE = "HANDOFF_RECEIVE";
    private static final String CMD_CANCEL = "HANDOFF_CANCEL";

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "";

    private static final String PHASE_CREATE = "CREATE";
    private static final String PHASE_RECEIVE = "RECEIVE";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final HandoffRepository handoffRepo;
    private final BatchRepository batchRepo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public HandoffService(HandoffRepository handoffRepo,
                          BatchRepository batchRepo,
                          PlatformTransactionManager transactionManager,
                          ObjectMapper objectMapper) {
        this.handoffRepo = handoffRepo;
        this.batchRepo = batchRepo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 创建移交单：冻结清单与各批次祖先召回状态。
     * 任一批次非本厂持有、RELEASED、版本不符、已在其他活动移交、自身/祖先有效召回，
     * 或目标厂与源厂相同，整单失败且不占用 manifestKey。
     */
    public StoredResponse createHandoff(String sourcePlantHeader, CreateHandoffRequest req) {
        String sourcePlant = requirePlant(sourcePlantHeader, "X-Source-Plant");
        String targetPlant = req.targetPlant().trim();
        if (targetPlant.equals(sourcePlant)) {
            throw ApiException.unprocessable("目标厂与源厂相同: " + sourcePlant);
        }
        // 保留请求顺序作为 seq；指纹按 batchKey 排序规范化，清单换序视为同参。
        List<CreateHandoffRequest.ItemSpec> items = req.items();
        List<String> requestKeys = items.stream().map(CreateHandoffRequest.ItemSpec::batchKey).toList();
        if (new HashSet<>(requestKeys).size() != requestKeys.size()) {
            throw ApiException.conflict("清单批次在请求内重复");
        }
        List<CreateHandoffRequest.ItemSpec> canonical = items.stream()
                .sorted(java.util.Comparator.comparing(CreateHandoffRequest.ItemSpec::batchKey))
                .toList();
        List<String> parts = new ArrayList<>();
        parts.add("create");
        parts.add(sourcePlant);
        parts.add(req.manifestKey());
        parts.add(targetPlant);
        parts.add(req.expectedArrivalAt().toString());
        for (CreateHandoffRequest.ItemSpec item : canonical) {
            parts.add(item.batchKey());
            parts.add(String.valueOf(item.expectedVersion()));
        }
        String fingerprint = fingerprint(parts.toArray(new String[0]));

        return executeIdempotent(CMD_CREATE, req.requestId(), fingerprint, () -> {
            String now = now();
            HandoffRepository.HandoffRow handoff = new HandoffRepository.HandoffRow(0L,
                    req.manifestKey(), req.manifestKey(), sourcePlant, targetPlant,
                    HandoffStatus.CREATED.name(), req.expectedArrivalAt().toString(),
                    null, null, null, now);
            try {
                handoffRepo.insertHandoff(handoff);
            } catch (DuplicateKeyException e) {
                if (handoffRepo.findHandoffByManifest(req.manifestKey()).isPresent()) {
                    // 并发同 requestId 同参创建的对方已提交：抛唯一键冲突交由外层重试重放首次快照；
                    // 否则属于不同 requestId 复用 manifestKey，整单 409。
                    if (loggedResponse(CMD_CREATE, req.requestId(), fingerprint).isPresent()) {
                        throw e;
                    }
                    throw ApiException.conflict("manifestKey 已存在: " + req.manifestKey());
                }
                // 对方事务尚未提交：交由外层重试，提交后重放首次快照。
                throw e;
            }

            // 行锁顺序按 batchKey 全局确定，避免并发移交/召回间死锁。
            List<String> sortedKeys = new ArrayList<>(requestKeys);
            Collections.sort(sortedKeys);
            Map<String, BatchRepository.BatchRow> locked = new HashMap<>();
            for (String batchKey : sortedKeys) {
                BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
                assertHeldBySource(batch, sourcePlant);
                if (BatchStatus.RELEASED.name().equals(batch.status())) {
                    throw ApiException.unprocessable("RELEASED 批次不能跨厂移交: " + batchKey);
                }
                if (BatchStatus.IN_TRANSIT.name().equals(batch.status())
                        || handoffRepo.countActiveHandoffForBatch(batchKey) > 0) {
                    throw ApiException.conflict("批次已在其他活动移交单中: " + batchKey);
                }
                if (BatchStatus.RECALLED.name().equals(batch.status())) {
                    throw ApiException.unprocessable("批次存在有效召回，禁止移交: " + batchKey);
                }
                locked.put(batchKey, batch);
            }
            // 版本乐观校验与祖先召回展开在全部行锁获取后进行。
            Map<String, String> parentOf = childToParent();
            for (CreateHandoffRequest.ItemSpec item : items) {
                BatchRepository.BatchRow batch = locked.get(item.batchKey());
                if (batch.version() != item.expectedVersion()) {
                    throw ApiException.conflict("批次 " + item.batchKey() + " expectedVersion 不符: 期望 "
                            + item.expectedVersion() + "，实际 " + batch.version());
                }
                // 展开祖先闭包并冻结召回状态；任一祖先 RECALLED 整单失败。
                assertNoRecalledAncestor(item.batchKey(), parentOf);
            }

            for (int i = 0; i < items.size(); i++) {
                CreateHandoffRequest.ItemSpec item = items.get(i);
                handoffRepo.insertItem(new HandoffRepository.HandoffItemRow(0L, req.manifestKey(),
                        item.batchKey(), item.expectedVersion(), i + 1, null, null, null));
            }
            freezeLineage(req.manifestKey(),
                    items.stream().map(CreateHandoffRequest.ItemSpec::batchKey).toList(),
                    parentOf, PHASE_CREATE);

            HandoffEvidenceResponse body = buildEvidence(req.manifestKey());
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 发运：一个事务内把冻结清单全部批次转为 IN_TRANSIT，补写封签号与发运前快照，
     * 并写源厂交接事件；封签遗漏/多余/重复整单失败，不能逐批成功。
     */
    public StoredResponse shipHandoff(String manifestKey, String sourcePlantHeader, ShipHandoffRequest req) {
        String sourcePlant = requirePlant(sourcePlantHeader, "X-Source-Plant");
        Map<String, String> sealsByBatch = normalizeSeals(req.seals().stream()
                .map(s -> new SealInput(s.batchKey(), s.sealNo())).toList());
        List<String> parts = new ArrayList<>();
        parts.add("ship");
        parts.add(manifestKey);
        parts.add(sourcePlant);
        parts.add(canonicalSeals(sealsByBatch));
        String fingerprint = fingerprint(parts.toArray(new String[0]));

        return executeIdempotent(CMD_SHIP, req.requestId(), fingerprint, () -> {
            HandoffRepository.HandoffRow handoff = lockHandoff(manifestKey);
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，须重放首次结果。
            var logged = loggedResponse(CMD_SHIP, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!handoff.sourcePlant().equals(sourcePlant)) {
                throw ApiException.unprocessable("仅源厂可发运: " + handoff.sourcePlant());
            }
            if (!HandoffStatus.CREATED.name().equals(handoff.status())) {
                throw ApiException.conflict("移交单状态 " + handoff.status() + " 不允许发运，仅 CREATED 可发运");
            }
            List<HandoffRepository.HandoffItemRow> frozen = handoffRepo.findItems(manifestKey);
            assertSetEquals(sealsByBatch.keySet(), frozenKeys(frozen), "封签");

            // 发运前再次确认批次仍由源厂持有、非 RELEASED、无新增有效召回，随后整体转在途。
            Map<String, BatchRepository.BatchRow> locked = lockBatches(sortedBatchLocks(frozenKeys(frozen)));
            Map<String, String> parentOf = childToParent();
            String now = now();
            for (HandoffRepository.HandoffItemRow item : frozen) {
                BatchRepository.BatchRow batch = locked.get(item.batchKey());
                assertHeldBySource(batch, sourcePlant);
                if (BatchStatus.RELEASED.name().equals(batch.status())) {
                    throw ApiException.unprocessable("RELEASED 批次不能发运: " + item.batchKey());
                }
                if (BatchStatus.RECALLED.name().equals(batch.status())) {
                    throw ApiException.unprocessable("批次存在有效召回，禁止发运: " + item.batchKey());
                }
                assertNoRecalledAncestor(item.batchKey(), parentOf);
                handoffRepo.updateItemShipInfo(manifestKey, item.batchKey(),
                        sealsByBatch.get(item.batchKey()), batch.status(), batch.version());
                batchRepo.updateStatusInTransit(item.batchKey());
            }
            handoffRepo.markShipped(manifestKey, now);
            HandoffEvidenceResponse evidence = buildEvidence(manifestKey);
            handoffRepo.insertEvent(new HandoffRepository.HandoffEventRow(0L, manifestKey,
                    "HANDOFF_SHIPPED", toJson(evidence), now));
            return new StoredResponse(200, toJson(buildEvidence(manifestKey)));
        });
    }

    /**
     * 目标厂接收：服务端重新读取清单、版本及完整血缘闭包，拒绝遗漏、多余、重复、封签不符、
     * 运输中新增召回或任何非 IN_TRANSIT 状态。失败保持全部批次在途，不生成部分接收链。
     * 成功后持有厂一次性切换为目标厂、状态统一 QUARANTINED、版本各加一，并写不可变接收快照。
     */
    public StoredResponse receiveHandoff(String manifestKey, String targetPlantHeader,
                                         ReceiveHandoffRequest req) {
        String targetPlant = requirePlant(targetPlantHeader, "X-Target-Plant");
        String receiver = req.receiver().trim();

        List<String> manifest = req.manifest();
        if (new HashSet<>(manifest).size() != manifest.size()) {
            throw ApiException.unprocessable("manifest 存在重复批次");
        }
        Map<String, String> sealsByBatch = normalizeSeals(req.seals().stream()
                .map(s -> new SealInput(s.batchKey(), s.sealNo())).toList());
        List<String> sortedManifest = new ArrayList<>(new HashSet<>(manifest));
        Collections.sort(sortedManifest);

        List<String> parts = new ArrayList<>();
        parts.add("receive");
        parts.add(manifestKey);
        parts.add(targetPlant);
        parts.add(receiver);
        parts.add(String.join(SEP, sortedManifest));
        parts.add(canonicalSeals(sealsByBatch));
        String fingerprint = fingerprint(parts.toArray(new String[0]));

        return executeIdempotent(CMD_RECEIVE, req.requestId(), fingerprint, () -> {
            HandoffRepository.HandoffRow handoff = lockHandoff(manifestKey);
            var logged = loggedResponse(CMD_RECEIVE, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!handoff.targetPlant().equals(targetPlant)) {
                throw ApiException.unprocessable("仅目标厂可接收: " + handoff.targetPlant());
            }
            if (!HandoffStatus.IN_TRANSIT.name().equals(handoff.status())) {
                throw ApiException.conflict("移交单状态 " + handoff.status() + " 不允许接收，仅 IN_TRANSIT 可接收");
            }
            List<HandoffRepository.HandoffItemRow> frozen = handoffRepo.findItems(manifestKey);
            Set<String> frozenKeys = frozenKeys(frozen);
            assertSetEquals(new HashSet<>(manifest), frozenKeys, "manifest");
            assertSetEquals(sealsByBatch.keySet(), frozenKeys, "封签");

            // 先计算完整祖先闭包，再按全局顺序锁定清单批次与全部祖先，与召回事务互斥。
            Map<String, List<String>> ancestorsOf = new LinkedHashMap<>();
            Map<String, String> parentOf = childToParent();
            Set<String> closure = new HashSet<>(frozenKeys);
            for (String batchKey : frozenKeys) {
                List<String> ancestors = ancestorChain(batchKey, parentOf);
                ancestorsOf.put(batchKey, ancestors);
                closure.addAll(ancestors);
            }
            lockBatches(sortedBatchLocks(closure));

            // 行锁取得后重新读取：任何先提交的召回在此可见并阻止接收。
            String now = now();
            for (HandoffRepository.HandoffItemRow item : frozen) {
                BatchRepository.BatchRow batch = batchRepo.findBatch(item.batchKey())
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + item.batchKey()));
                if (!BatchStatus.IN_TRANSIT.name().equals(batch.status())) {
                    throw ApiException.unprocessable("批次 " + item.batchKey() + " 当前状态 "
                            + batch.status() + "，非 IN_TRANSIT，拒绝接收");
                }
                if (!handoff.sourcePlant().equals(batch.holderPlant())) {
                    throw ApiException.unprocessable("批次 " + item.batchKey() + " 持有厂不是源厂，拒绝接收");
                }
                if (batch.version() != item.expectedVersion()) {
                    throw ApiException.conflict("批次 " + item.batchKey() + " 版本与冻结清单不符: 冻结 "
                            + item.expectedVersion() + "，实际 " + batch.version());
                }
                String submittedSeal = sealsByBatch.get(item.batchKey());
                if (submittedSeal == null || !submittedSeal.equals(item.sealNo())) {
                    throw ApiException.unprocessable("批次 " + item.batchKey() + " 封签不符，拒绝接收");
                }
            }
            // 运输中新增召回屏障：完整祖先闭包中任一已提交召回都阻止接收。
            for (Map.Entry<String, List<String>> entry : ancestorsOf.entrySet()) {
                for (String ancestorKey : entry.getValue()) {
                    BatchRepository.BatchRow ancestor = batchRepo.findBatch(ancestorKey)
                            .orElseThrow(() -> ApiException.notFound("祖先批次不存在: " + ancestorKey));
                    if (BatchStatus.RECALLED.name().equals(ancestor.status())) {
                        throw ApiException.unprocessable(
                                "批次 " + entry.getKey() + " 的祖先 " + ancestorKey + " 已召回，拒绝接收");
                    }
                }
            }

            // 全部校验通过后才落库：冻结接收阶段血缘闭包、整体切换持有厂/状态/版本。
            List<String> frozenOrder = frozen.stream()
                    .map(HandoffRepository.HandoffItemRow::batchKey).toList();
            freezeLineage(manifestKey, frozenOrder, parentOf, PHASE_RECEIVE);
            for (HandoffRepository.HandoffItemRow item : frozen) {
                BatchRepository.BatchRow batch = batchRepo.findBatch(item.batchKey()).orElseThrow();
                batchRepo.applyReceipt(item.batchKey(), targetPlant, batch.version() + 1);
            }
            handoffRepo.markReceived(manifestKey, now, receiver);
            HandoffEvidenceResponse evidence = buildEvidence(manifestKey);
            handoffRepo.insertEvent(new HandoffRepository.HandoffEventRow(0L, manifestKey,
                    "HANDOFF_RECEIVED", toJson(evidence), now));
            return new StoredResponse(200, toJson(buildEvidence(manifestKey)));
        });
    }

    /**
     * 源厂取消：仅尚未接收且清单全部仍在途时允许，在同一事务内原子恢复发运前状态与版本，
     * 并写取消快照；接收后或非全部在途一律拒绝。
     */
    public StoredResponse cancelHandoff(String manifestKey, String sourcePlantHeader, CancelHandoffRequest req) {
        String sourcePlant = requirePlant(sourcePlantHeader, "X-Source-Plant");
        String fingerprint = fingerprint("cancel", manifestKey, sourcePlant);

        return executeIdempotent(CMD_CANCEL, req.requestId(), fingerprint, () -> {
            HandoffRepository.HandoffRow handoff = lockHandoff(manifestKey);
            var logged = loggedResponse(CMD_CANCEL, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!handoff.sourcePlant().equals(sourcePlant)) {
                throw ApiException.unprocessable("仅源厂可取消: " + handoff.sourcePlant());
            }
            if (!HandoffStatus.IN_TRANSIT.name().equals(handoff.status())) {
                throw ApiException.conflict("移交单状态 " + handoff.status()
                        + " 不允许取消，仅尚未接收且清单全部仍在途（IN_TRANSIT）时可取消");
            }
            List<HandoffRepository.HandoffItemRow> frozen = handoffRepo.findItems(manifestKey);
            Map<String, BatchRepository.BatchRow> locked = lockBatches(sortedBatchLocks(frozenKeys(frozen)));
            for (HandoffRepository.HandoffItemRow item : frozen) {
                BatchRepository.BatchRow batch = locked.get(item.batchKey());
                if (!BatchStatus.IN_TRANSIT.name().equals(batch.status())) {
                    throw ApiException.unprocessable("批次 " + item.batchKey() + " 当前状态 "
                            + batch.status() + "，清单未全部在途，拒绝取消");
                }
                if (item.statusBeforeShip() == null || item.versionBeforeShip() == null) {
                    throw ApiException.unprocessable("批次 " + item.batchKey() + " 缺少发运前快照，拒绝取消");
                }
            }
            for (HandoffRepository.HandoffItemRow item : frozen) {
                batchRepo.restoreStatusAndVersion(item.batchKey(), item.statusBeforeShip(),
                        item.versionBeforeShip());
            }
            handoffRepo.markCancelled(manifestKey);
            String now = now();
            HandoffEvidenceResponse evidence = buildEvidence(manifestKey);
            handoffRepo.insertEvent(new HandoffRepository.HandoffEventRow(0L, manifestKey,
                    "HANDOFF_CANCELLED", toJson(evidence), now));
            return new StoredResponse(200, toJson(buildEvidence(manifestKey)));
        });
    }

    /**
     * 移交证据只读查询：概要、冻结清单、创建/接收血缘快照与不可变事件，全部稳定排序。
     */
    public HandoffEvidenceResponse evidence(String manifestKey) {
        handoffRepo.findHandoffByManifest(manifestKey)
                .orElseThrow(() -> ApiException.notFound("移交单不存在: " + manifestKey));
        return buildEvidence(manifestKey);
    }

    /**
     * 批次当前持有厂只读查询。
     */
    public BatchHolderResponse holder(String batchKey) {
        BatchRepository.BatchRow batch = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return new BatchHolderResponse(batch.batchKey(), batch.holderPlant(), batch.version(),
                batch.status());
    }

    // ---------- 内部辅助 ----------

    private record SealInput(String batchKey, String sealNo) {
    }

    private String requirePlant(String plantHeader, String headerName) {
        if (plantHeader == null || plantHeader.isBlank()) {
            throw ApiException.badRequest(headerName + " 不能为空");
        }
        return plantHeader.trim();
    }

    private void assertHeldBySource(BatchRepository.BatchRow batch, String sourcePlant) {
        if (!batch.holderPlant().equals(sourcePlant)) {
            throw ApiException.unprocessable("批次 " + batch.batchKey() + " 当前持有厂为 "
                    + batch.holderPlant() + "，非源厂 " + sourcePlant);
        }
    }

    private HandoffRepository.HandoffRow lockHandoff(String manifestKey) {
        return handoffRepo.findHandoffByManifestForUpdate(manifestKey)
                .orElseThrow(() -> ApiException.notFound("移交单不存在: " + manifestKey));
    }

    private Set<String> frozenKeys(List<HandoffRepository.HandoffItemRow> frozen) {
        Set<String> keys = new HashSet<>();
        for (HandoffRepository.HandoffItemRow item : frozen) {
            keys.add(item.batchKey());
        }
        return keys;
    }

    /**
     * 合并批次键集合并全局排序，作为确定性行锁顺序避免死锁。
     */
    private List<String> sortedBatchLocks(Set<String> keys) {
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        return sorted;
    }

    private Map<String, BatchRepository.BatchRow> lockBatches(List<String> sortedKeys) {
        Map<String, BatchRepository.BatchRow> locked = new HashMap<>();
        for (String batchKey : sortedKeys) {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            locked.put(batchKey, batch);
        }
        return locked;
    }

    /**
     * 校验提交的批次键集合与冻结清单完全一致：遗漏或多余均拒绝（重复由调用方先行检测）。
     */
    private void assertSetEquals(Set<String> submitted, Set<String> frozen, String label) {
        if (!submitted.equals(frozen)) {
            Set<String> missing = new HashSet<>(frozen);
            missing.removeAll(submitted);
            Set<String> extra = new HashSet<>(submitted);
            extra.removeAll(frozen);
            throw ApiException.unprocessable(label + " 与冻结清单不一致，遗漏: " + missing + "，多余: " + extra);
        }
    }

    private Map<String, String> normalizeSeals(List<SealInput> seals) {
        Map<String, String> byBatch = new LinkedHashMap<>();
        for (SealInput seal : seals) {
            String batchKey = seal.batchKey();
            String sealNo = seal.sealNo() == null ? null : seal.sealNo().trim();
            if (sealNo == null || sealNo.isEmpty()) {
                throw ApiException.badRequest("批次 " + batchKey + " 的 sealNo 不能为空");
            }
            if (byBatch.put(batchKey, sealNo) != null) {
                throw ApiException.unprocessable("封签批次重复: " + batchKey);
            }
        }
        return byBatch;
    }

    private String canonicalSeals(Map<String, String> sealsByBatch) {
        List<String> keys = new ArrayList<>(sealsByBatch.keySet());
        Collections.sort(keys);
        List<String> ordered = new ArrayList<>();
        for (String key : keys) {
            ordered.add(key);
            ordered.add(sealsByBatch.get(key));
        }
        return String.join(SEP, ordered);
    }

    /**
     * 全部血缘边的 子批→父批 映射；关系不可改写，只增不改。
     */
    private Map<String, String> childToParent() {
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepo.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        return parentOf;
    }

    /**
     * 沿父链向上返回祖先键：depth 1 为直接父批，逐级到根。
     */
    private List<String> ancestorChain(String batchKey, Map<String, String> parentOf) {
        List<String> chain = new ArrayList<>();
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            chain.add(current);
        }
        return chain;
    }

    private void assertNoRecalledAncestor(String batchKey, Map<String, String> parentOf) {
        for (String ancestorKey : ancestorChain(batchKey, parentOf)) {
            BatchRepository.BatchRow ancestor = batchRepo.findBatch(ancestorKey)
                    .orElseThrow(() -> ApiException.notFound("祖先批次不存在: " + ancestorKey));
            if (BatchStatus.RECALLED.name().equals(ancestor.status())) {
                throw ApiException.unprocessable("批次 " + batchKey + " 的祖先 " + ancestorKey + " 已召回，禁止移交");
            }
        }
    }

    /**
     * 冻结一批清单项的祖先闭包快照；按清单顺序、祖先代数递增赋稳定 seq。
     */
    private void freezeLineage(String handoffKey, List<String> orderedBatchKeys,
                               Map<String, String> parentOf, String phase) {
        int seq = 0;
        for (String batchKey : orderedBatchKeys) {
            int depth = 0;
            for (String ancestorKey : ancestorChain(batchKey, parentOf)) {
                depth++;
                BatchRepository.BatchRow ancestor = batchRepo.findBatch(ancestorKey)
                        .orElseThrow(() -> ApiException.notFound("祖先批次不存在: " + ancestorKey));
                handoffRepo.insertLineageSnapshot(new HandoffRepository.LineageSnapshotRow(0L,
                        handoffKey, batchKey, ancestorKey, depth, ancestor.status(),
                        ancestor.holderPlant(), phase, ++seq));
            }
        }
    }

    private HandoffEvidenceResponse buildEvidence(String manifestKey) {
        HandoffRepository.HandoffRow h = handoffRepo.findHandoffByManifest(manifestKey).orElseThrow();
        List<HandoffEvidenceResponse.ItemEvidence> items = handoffRepo.findItems(manifestKey).stream()
                .map(i -> new HandoffEvidenceResponse.ItemEvidence(i.batchKey(), i.expectedVersion(),
                        i.seq(), i.sealNo(), i.statusBeforeShip(), i.versionBeforeShip()))
                .toList();
        List<HandoffEvidenceResponse.LineageEvidence> lineage =
                handoffRepo.findLineageSnapshots(manifestKey).stream()
                        .map(l -> new HandoffEvidenceResponse.LineageEvidence(l.batchKey(), l.ancestorKey(),
                                l.depth(), l.ancestorStatus(), l.ancestorHolderPlant(), l.phase()))
                        .toList();
        List<HandoffEvidenceResponse.EventEvidence> events = new ArrayList<>();
        for (HandoffRepository.HandoffEventRow event : handoffRepo.findEvents(manifestKey)) {
            events.add(new HandoffEvidenceResponse.EventEvidence(event.eventType(),
                    parseJson(event.payload()), Instant.parse(event.createdAt())));
        }
        return new HandoffEvidenceResponse(h.manifestKey(), h.sourcePlant(), h.targetPlant(),
                h.status(), Instant.parse(h.expectedArrivalAt()), Instant.parse(h.createdAt()),
                h.shippedAt() == null ? null : Instant.parse(h.shippedAt()),
                h.receivedAt() == null ? null : Instant.parse(h.receivedAt()),
                h.receiver(), items, lineage, events);
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时重试，读取已提交结果。
     */
    private StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                             Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = loggedResponse(type, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    batchRepo.insertCommand(new BatchRepository.CommandRow(type, commandKey, fingerprint,
                            response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同 requestId 键冲突：回滚后重试，读取对方已提交的命令快照。
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    private Optional<StoredResponse> loggedResponse(String type, String commandKey, String fingerprint) {
        var existing = batchRepo.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已以不同参数使用: " + commandKey);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件快照解析失败", e);
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private String now() {
        return Instant.now().toString();
    }

    private String fingerprint(String... parts) {
        String canonical = String.join(SEP, parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
