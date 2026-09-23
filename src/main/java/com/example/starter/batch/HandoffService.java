package com.example.starter.batch;

import com.example.starter.batch.dto.CancelHandoffRequest;
import com.example.starter.batch.dto.CreateHandoffRequest;
import com.example.starter.batch.dto.HandoffResponse;
import com.example.starter.batch.dto.ReceiveHandoffRequest;
import com.example.starter.batch.dto.SealSpec;
import com.example.starter.batch.dto.ShipHandoffRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 跨厂隔离移交与召回屏障服务。
 *
 * <p>状态机：CREATED → SHIPPED → RECEIVED；SHIPPED 且清单全部在途时可由源厂 CANCELLED。
 * 创建时按 batchKey 字典序冻结清单与祖先血缘快照；发运/接收/取消均整单原子生效。
 *
 * <p>并发策略：移交单操作先锁 handoff 行，再按 batchKey 字典序逐行锁清单批次，
 * 与召回（锁定召回批次及其后代）按事务提交顺序互斥裁决；任何已提交召回都会使接收失败。
 * requestId 幂等复用 command_log：同参（清单换序视为同参）重放首次快照，异参 409，失败不占键。
 */
@Service
public class HandoffService {

    private static final String CMD_CREATE = "HANDOFF_CREATE";
    private static final String CMD_SHIP = "HANDOFF_SHIP";
    private static final String CMD_RECEIVE = "HANDOFF_RECEIVE";
    private static final String CMD_CANCEL = "HANDOFF_CANCEL";

    private static final TypeReference<List<HandoffResponse.AncestorSnapshot>> LINEAGE_TYPE =
            new TypeReference<>() {
            };

    private final BatchRepository repo;
    private final BatchService batchService;
    private final ObjectMapper objectMapper;

    public HandoffService(BatchRepository repo, BatchService batchService,
                          ObjectMapper objectMapper) {
        this.repo = repo;
        this.batchService = batchService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建移交单：校验源厂持有、非 RELEASED、版本一致、无有效召回、未在其他移交中，
     * 冻结清单（按 batchKey 字典序）与每批祖先血缘快照；任一不满足整单失败。
     */
    public StoredResponse create(CreateHandoffRequest req) {
        String source = req.sourcePlant().trim();
        String target = req.targetPlant().trim();
        if (source.equals(target)) {
            throw ApiException.unprocessable("目标厂不能与源厂相同: " + source);
        }
        List<CreateHandoffRequest.Item> sorted = req.items().stream()
                .sorted(Comparator.comparing(CreateHandoffRequest.Item::batchKey))
                .toList();
        List<String> keys = sorted.stream().map(CreateHandoffRequest.Item::batchKey).toList();
        if (new HashSet<>(keys).size() != keys.size()) {
            throw ApiException.conflict("清单内 batchKey 重复");
        }
        List<String> parts = new ArrayList<>(List.of("create", req.manifestKey(), source, target,
                req.expectedArrivalAt().toString()));
        for (CreateHandoffRequest.Item item : sorted) {
            parts.add(item.batchKey());
            parts.add(String.valueOf(item.expectedVersion()));
        }
        String fingerprint = batchService.fingerprint(parts.toArray(new String[0]));
        return batchService.executeIdempotent(CMD_CREATE, req.requestId(), fingerprint, () -> {
            repo.findHandoff(req.manifestKey()).ifPresent(h -> {
                throw ApiException.conflict("manifestKey 已存在: " + req.manifestKey());
            });
            // 按 batchKey 字典序逐批加行锁，锁顺序确定，与并发移交/召回互斥
            Map<String, BatchRepository.BatchRow> batches = new HashMap<>();
            for (String key : keys) {
                BatchRepository.BatchRow row = repo.findBatchForUpdate(key)
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
                batches.put(key, row);
            }
            List<String> inHandoff = repo.findBatchesInActiveHandoff(keys);
            if (!inHandoff.isEmpty()) {
                throw ApiException.conflict("批次已在其他进行中的移交单: " + inHandoff.get(0));
            }
            Map<String, String> parentOf = batchService.childToParent();
            Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
            for (CreateHandoffRequest.Item item : sorted) {
                BatchRepository.BatchRow batch = batches.get(item.batchKey());
                if (!batch.holdingPlant().equals(source)) {
                    throw ApiException.conflict("批次 " + item.batchKey() + " 当前持有厂为 "
                            + batch.holdingPlant() + "，不由源厂 " + source + " 持有");
                }
                if (BatchStatus.RELEASED.name().equals(batch.status())) {
                    throw ApiException.conflict("批次 " + item.batchKey() + " 已 RELEASED，不可移交");
                }
                if (batch.batchVersion() != item.expectedVersion()) {
                    throw ApiException.conflict("批次 " + item.batchKey() + " 当前版本 "
                            + batch.batchVersion() + " 与 expectedVersion "
                            + item.expectedVersion() + " 不一致");
                }
                effectiveRecall(item.batchKey(), parentOf, recalled).ifPresent(recalledKey -> {
                    throw ApiException.unprocessable(
                            "批次 " + item.batchKey() + " 存在有效召回（" + recalledKey + "），整单失败");
                });
            }
            String now = batchService.now();
            repo.insertHandoff(new BatchRepository.HandoffRow(0L, req.manifestKey(), source,
                    target, req.expectedArrivalAt().toString(), HandoffStatus.CREATED.name(),
                    null, now, null, null, null));
            int seq = 1;
            for (CreateHandoffRequest.Item item : sorted) {
                List<HandoffResponse.AncestorSnapshot> lineage =
                        ancestorSnapshot(item.batchKey(), parentOf, recalled);
                repo.insertHandoffItem(new BatchRepository.HandoffItemRow(0L, req.manifestKey(),
                        item.batchKey(), seq, item.expectedVersion(),
                        batchService.toJson(lineage), null, null, null));
                seq++;
            }
            return new StoredResponse(201, batchService.toJson(toResponse(req.manifestKey())));
        });
    }

    /**
     * 发运：仅源厂可执行；seals 必须恰好覆盖冻结清单。同一事务内全部批次转 IN_TRANSIT
     * 并写入发运前状态与封签快照；任一批次不满足（版本漂移、新增召回）整单失败。
     */
    public StoredResponse ship(String manifestKey, ShipHandoffRequest req) {
        String plant = req.plant().trim();
        List<SealSpec> sorted = req.seals().stream()
                .sorted(Comparator.comparing(SealSpec::batchKey))
                .toList();
        String fingerprint = sealFingerprint("ship", manifestKey, plant, sorted);
        return batchService.executeIdempotent(CMD_SHIP, req.requestId(), fingerprint, () -> {
            BatchRepository.HandoffRow handoff = repo.findHandoffForUpdate(manifestKey)
                    .orElseThrow(() -> ApiException.notFound("移交单不存在: " + manifestKey));
            // 行锁后重查命令快照：并发同 requestId 请求在锁等待期间可能已由对方提交
            var logged = batchService.loggedResponse(CMD_SHIP, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!handoff.sourcePlant().equals(plant)) {
                throw ApiException.conflict("只能由源厂 " + handoff.sourcePlant() + " 发运");
            }
            if (!HandoffStatus.CREATED.name().equals(handoff.status())) {
                throw ApiException.conflict("移交单状态 " + handoff.status() + " 不允许发运");
            }
            List<BatchRepository.HandoffItemRow> items = repo.findHandoffItems(manifestKey);
            Map<String, String> sealByBatch = validateSealCoverage(sorted, items, "发运");
            Map<String, String> parentOf = batchService.childToParent();
            Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
            List<BatchRepository.BatchRow> locked = new ArrayList<>(items.size());
            for (BatchRepository.HandoffItemRow item : items) {
                BatchRepository.BatchRow batch = lockManifestBatch(item);
                if (batch.batchVersion() != item.expectedVersion()) {
                    throw ApiException.conflict("批次 " + item.batchKey() + " 版本已变化，整单发运失败");
                }
                effectiveRecall(item.batchKey(), parentOf, recalled).ifPresent(recalledKey -> {
                    throw ApiException.unprocessable(
                            "批次 " + item.batchKey() + " 存在有效召回（" + recalledKey + "），整单发运失败");
                });
                locked.add(batch);
            }
            String now = batchService.now();
            for (BatchRepository.BatchRow batch : locked) {
                repo.updateItemShipped(manifestKey, batch.batchKey(), batch.status(),
                        sealByBatch.get(batch.batchKey()));
                repo.updateStatus(batch.batchKey(), BatchStatus.IN_TRANSIT.name());
            }
            repo.updateHandoffShipped(manifestKey, now);
            return new StoredResponse(200, batchService.toJson(toResponse(manifestKey)));
        });
    }

    /**
     * 接收：仅目标厂可执行；必须提交完整 manifest（无遗漏/多余/重复）且逐批封签相符。
     * 服务端重新读取清单、版本与完整血缘闭包：运输中新增召回或任何非 IN_TRANSIT 状态整单拒绝，
     * 全部批次保持在途。成功则所有批次一次性切换持有厂、统一 QUARANTINED、版本各加一，
     * 并保存源厂、目标厂、血缘与封签的不可变接收快照。
     */
    public StoredResponse receive(String manifestKey, ReceiveHandoffRequest req) {
        String plant = req.plant().trim();
        String receiver = req.receiver().trim();
        List<SealSpec> sorted = req.items().stream()
                .sorted(Comparator.comparing(SealSpec::batchKey))
                .toList();
        String fingerprint = sealFingerprint("receive", manifestKey, plant + "\u0000" + receiver,
                sorted);
        return batchService.executeIdempotent(CMD_RECEIVE, req.requestId(), fingerprint, () -> {
            BatchRepository.HandoffRow handoff = repo.findHandoffForUpdate(manifestKey)
                    .orElseThrow(() -> ApiException.notFound("移交单不存在: " + manifestKey));
            var logged = batchService.loggedResponse(CMD_RECEIVE, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!handoff.targetPlant().equals(plant)) {
                throw ApiException.conflict("只能由目标厂 " + handoff.targetPlant() + " 接收");
            }
            if (!HandoffStatus.SHIPPED.name().equals(handoff.status())) {
                throw ApiException.conflict("移交单状态 " + handoff.status() + " 不允许接收");
            }
            List<BatchRepository.HandoffItemRow> items = repo.findHandoffItems(manifestKey);
            Map<String, String> sealByBatch = validateSealCoverage(sorted, items, "接收");
            Map<String, String> parentOf = batchService.childToParent();
            Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
            for (BatchRepository.HandoffItemRow item : items) {
                BatchRepository.BatchRow batch = lockManifestBatch(item);
                if (!BatchStatus.IN_TRANSIT.name().equals(batch.status())) {
                    throw ApiException.conflict("批次 " + item.batchKey() + " 状态 "
                            + batch.status() + " 非 IN_TRANSIT，整单拒绝接收");
                }
                if (batch.batchVersion() != item.expectedVersion()) {
                    throw ApiException.conflict("批次 " + item.batchKey() + " 版本在运输中变化，整单拒绝接收");
                }
                if (!sealByBatch.get(item.batchKey()).equals(item.sealNo())) {
                    throw ApiException.unprocessable(
                            "批次 " + item.batchKey() + " 封签不符，整单拒绝接收");
                }
                effectiveRecall(item.batchKey(), parentOf, recalled).ifPresent(recalledKey -> {
                    throw ApiException.unprocessable("批次 " + item.batchKey()
                            + " 运输中新增召回（" + recalledKey + "），整单拒绝接收");
                });
            }
            String now = batchService.now();
            for (BatchRepository.HandoffItemRow item : items) {
                int newVersion = item.expectedVersion() + 1;
                repo.updateBatchReceived(item.batchKey(), BatchStatus.QUARANTINED.name(),
                        handoff.targetPlant(), newVersion);
                repo.updateItemReceived(manifestKey, item.batchKey(), newVersion);
            }
            repo.updateHandoffReceived(manifestKey, receiver, now);
            return new StoredResponse(200, batchService.toJson(toResponse(manifestKey)));
        });
    }

    /**
     * 取消：仅源厂可执行，且移交单尚未接收、清单全部仍在途；
     * 同一事务内按发运前快照原子恢复全部批次状态。
     */
    public StoredResponse cancel(String manifestKey, CancelHandoffRequest req) {
        String plant = req.plant().trim();
        String fingerprint = batchService.fingerprint("cancel", manifestKey, plant);
        return batchService.executeIdempotent(CMD_CANCEL, req.requestId(), fingerprint, () -> {
            BatchRepository.HandoffRow handoff = repo.findHandoffForUpdate(manifestKey)
                    .orElseThrow(() -> ApiException.notFound("移交单不存在: " + manifestKey));
            var logged = batchService.loggedResponse(CMD_CANCEL, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!handoff.sourcePlant().equals(plant)) {
                throw ApiException.conflict("只能由源厂 " + handoff.sourcePlant() + " 取消");
            }
            if (!HandoffStatus.SHIPPED.name().equals(handoff.status())) {
                throw ApiException.conflict("移交单状态 " + handoff.status()
                        + " 不允许取消，仅已发运且未接收的移交单可取消");
            }
            List<BatchRepository.HandoffItemRow> items = repo.findHandoffItems(manifestKey);
            for (BatchRepository.HandoffItemRow item : items) {
                BatchRepository.BatchRow batch = lockManifestBatch(item);
                if (!BatchStatus.IN_TRANSIT.name().equals(batch.status())) {
                    throw ApiException.conflict("批次 " + item.batchKey() + " 状态 "
                            + batch.status() + " 已不在途，整单不能取消");
                }
            }
            String now = batchService.now();
            for (BatchRepository.HandoffItemRow item : items) {
                repo.updateStatus(item.batchKey(), item.preStatus());
            }
            repo.updateHandoffCancelled(manifestKey, now);
            return new StoredResponse(200, batchService.toJson(toResponse(manifestKey)));
        });
    }

    /**
     * 移交证据查询：只读，清单按冻结顺序稳定排序。
     */
    public HandoffResponse evidence(String manifestKey) {
        repo.findHandoff(manifestKey)
                .orElseThrow(() -> ApiException.notFound("移交单不存在: " + manifestKey));
        return toResponse(manifestKey);
    }

    /**
     * 由当前库内状态组装移交单完整视图（含冻结清单与各阶段快照）。
     */
    private HandoffResponse toResponse(String manifestKey) {
        BatchRepository.HandoffRow handoff = repo.findHandoff(manifestKey)
                .orElseThrow(() -> ApiException.notFound("移交单不存在: " + manifestKey));
        List<HandoffResponse.HandoffItemResponse> items = repo.findHandoffItems(manifestKey)
                .stream()
                .map(item -> new HandoffResponse.HandoffItemResponse(item.seq(), item.batchKey(),
                        item.expectedVersion(), item.preStatus(), item.sealNo(),
                        item.receivedVersion(), parseLineage(item.lineageSnapshot())))
                .toList();
        return new HandoffResponse(handoff.manifestKey(), handoff.sourcePlant(),
                handoff.targetPlant(), HandoffStatus.valueOf(handoff.status()),
                Instant.parse(handoff.expectedArrivalAt()), handoff.receiver(),
                Instant.parse(handoff.createdAt()), parseInstant(handoff.shippedAt()),
                parseInstant(handoff.receivedAt()), parseInstant(handoff.cancelledAt()), items);
    }

    /**
     * 锁定清单批次行：按清单冻结顺序（batchKey 字典序）逐批 SELECT ... FOR UPDATE。
     */
    private BatchRepository.BatchRow lockManifestBatch(BatchRepository.HandoffItemRow item) {
        return repo.findBatchForUpdate(item.batchKey())
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + item.batchKey()));
    }

    /**
     * 校验发运/接收提交的封签清单与冻结清单一一对应：拒绝重复、遗漏与多余批次。
     */
    private Map<String, String> validateSealCoverage(List<SealSpec> specs,
                                                     List<BatchRepository.HandoffItemRow> items,
                                                     String phase) {
        Map<String, String> sealByBatch = new HashMap<>();
        for (SealSpec spec : specs) {
            if (sealByBatch.put(spec.batchKey(), spec.sealNo()) != null) {
                throw ApiException.unprocessable(phase + "清单重复批次: " + spec.batchKey());
            }
        }
        Set<String> manifest = new HashSet<>();
        for (BatchRepository.HandoffItemRow item : items) {
            manifest.add(item.batchKey());
        }
        for (String key : manifest) {
            if (!sealByBatch.containsKey(key)) {
                throw ApiException.unprocessable(phase + "清单遗漏批次: " + key);
            }
        }
        for (String key : sealByBatch.keySet()) {
            if (!manifest.contains(key)) {
                throw ApiException.unprocessable(phase + "清单包含多余批次: " + key);
            }
        }
        return sealByBatch;
    }

    /**
     * 有效召回：批次自身已被直接召回，或任一级祖先已被直接召回。
     */
    private Optional<String> effectiveRecall(String batchKey, Map<String, String> parentOf,
                                             Set<String> recalled) {
        if (recalled.contains(batchKey)) {
            return Optional.of(batchKey);
        }
        return batchService.recalledAncestor(batchKey, parentOf, recalled);
    }

    /**
     * 展开批次祖先链（自直接父批到根）并记录各祖先当前状态与是否已被直接召回。
     */
    private List<HandoffResponse.AncestorSnapshot> ancestorSnapshot(String batchKey,
                                                                    Map<String, String> parentOf,
                                                                    Set<String> recalled) {
        List<HandoffResponse.AncestorSnapshot> result = new ArrayList<>();
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            String ancestorKey = current;
            BatchRepository.BatchRow row = repo.findBatch(ancestorKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + ancestorKey));
            result.add(new HandoffResponse.AncestorSnapshot(current, row.status(),
                    recalled.contains(current)));
        }
        return result;
    }

    private String sealFingerprint(String action, String manifestKey, String actor,
                                   List<SealSpec> sorted) {
        List<String> parts = new ArrayList<>(List.of(action, manifestKey, actor));
        for (SealSpec spec : sorted) {
            parts.add(spec.batchKey());
            parts.add(spec.sealNo());
        }
        return batchService.fingerprint(parts.toArray(new String[0]));
    }

    private List<HandoffResponse.AncestorSnapshot> parseLineage(String json) {
        try {
            return objectMapper.readValue(json, LINEAGE_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("血缘快照反序列化失败", e);
        }
    }

    private Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
