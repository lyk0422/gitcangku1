package com.example.starter.certificate;

import com.example.starter.batch.ApiException;
import com.example.starter.batch.BatchRepository;
import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.StoredResponse;
import com.example.starter.certificate.dto.MergeBatchesRequest;
import com.example.starter.certificate.dto.MergeBatchesResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合批（合并批次）服务：2～5 个当前可用的 RELEASED 父批合并为一个全新子批。
 *
 * <p>合批重新校验所有父物料：全部父批物料必须一致，且每个父批绑定的声明在合批时点
 * 仍然 ACTIVE 且处于有效区间（左闭右开）；任一不满足整次回滚。父批置为 MERGED 终态，
 * 子批初始 QUARANTINED，不继承绑定（放行前须重新绑定）。多对象写入先校验完整最终状态，
 * 再在同一事务提交；父批行按业务键排序加锁，按事务提交顺序裁决并发。
 */
@Service
public class MergeService {

    static final String CMD_MERGE = "MERGE";

    private final CertificateRepository certRepo;
    private final BatchRepository batchRepo;
    private final CertificateService support;
    private final ObjectMapper objectMapper;

    public MergeService(CertificateRepository certRepo, BatchRepository batchRepo,
                        CertificateService support, ObjectMapper objectMapper) {
        this.certRepo = certRepo;
        this.batchRepo = batchRepo;
        this.support = support;
        this.objectMapper = objectMapper;
    }

    /**
     * 合批：父批数量要求 2～5（失败响应返回实际值与要求值）；全部校验通过后同事务提交。
     */
    public StoredResponse merge(String actorId, MergeBatchesRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        List<String> parentKeys = req.parentKeys();
        if (parentKeys.size() < 2 || parentKeys.size() > 5) {
            throw ApiException.badRequest("父批数量实际为 " + parentKeys.size() + "，要求 2～5");
        }
        if (new HashSet<>(parentKeys).size() != parentKeys.size()) {
            throw ApiException.conflict("parentKeys 内父批键重复");
        }
        if (parentKeys.contains(req.childBatchKey())) {
            throw ApiException.conflict("子批键不得与父批键相同: " + req.childBatchKey());
        }
        List<String> parts = new ArrayList<>();
        parts.add("merge");
        parts.add(actor);
        parts.add(req.childBatchKey());
        parts.add(req.childBatchNo());
        parts.addAll(parentKeys);
        String fingerprint = support.fingerprint(parts.toArray(new String[0]));
        return support.executeIdempotent(CMD_MERGE, req.commandKey(), fingerprint, () -> {
            // 按业务键排序逐行锁定全部父批，保证锁顺序确定
            List<String> sortedKeys = new ArrayList<>(parentKeys);
            Collections.sort(sortedKeys);
            Map<String, BatchRepository.BatchRow> parents = new HashMap<>();
            for (String key : sortedKeys) {
                parents.put(key, batchRepo.findBatchForUpdate(key)
                        .orElseThrow(() -> ApiException.notFound("父批不存在: " + key)));
            }
            // 父批行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = support.loggedResponse(CMD_MERGE, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (batchRepo.findBatch(req.childBatchKey()).isPresent()) {
                throw ApiException.conflict("子批 batchKey 已存在: " + req.childBatchKey());
            }
            String now = support.now();
            // 完整校验所有父批最终状态：可用 RELEASED、无召回祖先、物料一致、声明重新校验
            String productCode = null;
            String producedAt = null;
            Set<String> requiredUnion = new LinkedHashSet<>();
            for (String key : parentKeys) {
                BatchRepository.BatchRow parent = parents.get(key);
                assertNoRecalledAncestor(key);
                if (!BatchStatus.RELEASED.name().equals(parent.status())) {
                    throw ApiException.conflict("父批 " + key + " 状态 " + parent.status()
                            + " 不允许合批，仅当前可用的 RELEASED 批次可合批");
                }
                if (productCode == null) {
                    productCode = parent.productCode();
                } else if (!productCode.equals(parent.productCode())) {
                    throw ApiException.unprocessable("父批物料不一致：父批 " + key + " 物料 "
                            + parent.productCode() + "，要求与其余父批一致为 " + productCode);
                }
                revalidateParentCertificate(key, now);
                if (producedAt == null || parent.producedAt().compareTo(producedAt) < 0) {
                    producedAt = parent.producedAt();
                }
                requiredUnion.addAll(batchRepo.findRequiredTests(key));
            }
            // 全部校验通过：同一事务提交 子批 + 合批血缘 + 父批状态
            batchRepo.insertBatch(new BatchRepository.BatchRow(0L, req.childBatchKey(),
                    productCode, req.childBatchNo(), producedAt, BatchStatus.QUARANTINED.name(),
                    now));
            List<String> required = new ArrayList<>(requiredUnion);
            for (int i = 0; i < required.size(); i++) {
                batchRepo.insertRequiredTest(req.childBatchKey(), required.get(i), i + 1);
            }
            for (int i = 0; i < parentKeys.size(); i++) {
                certRepo.insertMergeLineage(new CertificateRepository.MergeLineageRow(0L,
                        req.childBatchKey(), parentKeys.get(i), i + 1, now));
                batchRepo.updateStatus(parentKeys.get(i), BatchStatus.MERGED.name());
            }
            MergeBatchesResponse body = new MergeBatchesResponse(req.childBatchKey(),
                    req.childBatchNo(), productCode, BatchStatus.QUARANTINED, parentKeys.size(),
                    parentKeys, required, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 合批产物血缘：按请求顺序返回父批关系。只读，不改变状态。
     */
    public List<CertificateRepository.MergeLineageRow> mergeLineage(String childKey) {
        batchRepo.findBatch(childKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + childKey));
        return certRepo.findMergeLineageByChild(childKey);
    }

    /**
     * 重新校验父批合格证：绑定必须存在，声明必须 ACTIVE 且在合批时点处于有效区间（左闭右开）。
     */
    private void revalidateParentCertificate(String parentKey, String now) {
        CertificateRepository.BindingRow binding = certRepo.findBinding(parentKey)
                .orElseThrow(() -> ApiException.unprocessable(
                        "父批 " + parentKey + " 未绑定合格证声明，不允许合批"));
        CertificateRepository.DeclarationRow decl = certRepo
                .findDeclarationForUpdate(binding.declarationKey(), binding.declarationVersion())
                .orElseThrow(() -> ApiException.unprocessable("父批 " + parentKey
                        + " 绑定的声明版本不存在: " + binding.declarationKey()
                        + " v" + binding.declarationVersion()));
        if (!CertificateService.STATUS_ACTIVE.equals(decl.status())) {
            throw ApiException.unprocessable("父批 " + parentKey + " 绑定的声明已撤回（失效）: "
                    + decl.declarationKey() + " v" + decl.version() + "，整次合批回滚");
        }
        Instant at = Instant.parse(now);
        if (at.isBefore(Instant.parse(decl.validFrom()))
                || !at.isBefore(Instant.parse(decl.validTo()))) {
            throw ApiException.unprocessable("父批 " + parentKey + " 绑定的声明在合批时点无效：时点 "
                    + now + " 不在有效区间 [" + decl.validFrom() + ", " + decl.validTo() + ") 内");
        }
    }

    /**
     * 若任一级祖先已被召回则抛 422：该批次禁止参与合批。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepo.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        Set<String> recalled = new HashSet<>(batchRepo.findRecalledKeys());
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                throw ApiException.unprocessable("祖先批次 " + current + " 已召回，禁止参与合批");
            }
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
