package com.example.starter.batch;

import com.example.starter.batch.dto.BatchBindRequest;
import com.example.starter.batch.dto.BatchBindResponse;
import com.example.starter.batch.dto.BatchCertificateResponse;
import com.example.starter.batch.dto.BindCertificateRequest;
import com.example.starter.batch.dto.BindingResponse;
import com.example.starter.batch.dto.DeclarationDiagnosticsResponse;
import com.example.starter.batch.dto.DeclarationResponse;
import com.example.starter.batch.dto.PublishDeclarationRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 合格证声明与批次绑定核心服务。
 *
 * <p>声明发布、撤回、批次绑定按提交顺序裁决：声明行 SELECT ... FOR UPDATE 串行化，
 * 绑定与放行在同一批次行锁内互斥。commandKey 幂等经 {@link CommandExecutor} 实现，
 * 指纹包含操作者、操作类型、规范化对象集合及全部影响结果的字段（长度前缀编码）。
 */
@Service
public class CertificateService {

    private static final String CMD_PUBLISH = "PUBLISH_DECLARATION";
    private static final String CMD_WITHDRAW = "WITHDRAW_DECLARATION";
    private static final String CMD_BIND = "BIND_CERTIFICATE";
    private static final String CMD_BATCH_BIND = "BATCH_BIND_CERTIFICATE";

    private final BatchRepository batchRepo;
    private final CertificateRepository certRepo;
    private final CommandExecutor commands;
    private final BatchService batchService;
    private final ObjectMapper objectMapper;

    public CertificateService(BatchRepository batchRepo, CertificateRepository certRepo,
                              CommandExecutor commands, BatchService batchService,
                              ObjectMapper objectMapper) {
        this.batchRepo = batchRepo;
        this.certRepo = certRepo;
        this.commands = commands;
        this.batchService = batchService;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布声明版本：有效区间必须合法（validFrom 早于 validTo，左闭右开）；
     * 同物料下与任一有效声明前缀重叠且优先级相同返回 422；
     * 同 declarationKey+version 重复发布返回 409。
     */
    public StoredResponse publish(String actorId, PublishDeclarationRequest req) {
        String actor = requireActor(actorId);
        if (req.version() < 1) {
            throw ApiException.badRequest("version 必须 >= 1，实际 " + req.version());
        }
        if (!req.validFrom().isBefore(req.validTo())) {
            throw ApiException.unprocessable("INVALID_VALIDITY_WINDOW",
                    "有效区间非法: 要求 validFrom 早于 validTo，实际 [" + req.validFrom() + ", "
                            + req.validTo() + ")");
        }
        String fingerprint = Fingerprints.of(List.of(actor, "publish", req.declarationKey(),
                String.valueOf(req.version()), req.materialCode(), req.batchNoPrefix(),
                String.valueOf(req.priority()), req.validFrom().toString(),
                req.validTo().toString()));
        return commands.execute(CMD_PUBLISH, req.commandKey(), fingerprint, () -> {
            if (certRepo.findDeclaration(req.declarationKey(), req.version()).isPresent()) {
                throw ApiException.conflict("声明版本已存在: " + req.declarationKey() + " v"
                        + req.version());
            }
            for (CertificateRepository.DeclarationRow existing
                    : certRepo.findDeclarationsByMaterial(req.materialCode())) {
                boolean conflict = DeclarationStatus.ACTIVE.name().equals(existing.status())
                        && existing.priority() == req.priority()
                        && DeclarationRules.prefixOverlap(existing.batchNoPrefix(),
                                req.batchNoPrefix());
                if (conflict) {
                    throw ApiException.unprocessable("PREFIX_PRIORITY_CONFLICT",
                            "与有效声明 " + existing.declarationKey() + " v" + existing.version()
                                    + " 前缀重叠且优先级相同: 前缀 " + existing.batchNoPrefix() + " 与 "
                                    + req.batchNoPrefix() + "，优先级 " + req.priority());
                }
            }
            String now = Instant.now().toString();
            CertificateRepository.DeclarationRow row = new CertificateRepository.DeclarationRow(
                    0L, req.declarationKey(), req.version(), req.materialCode(),
                    req.batchNoPrefix(), req.priority(), req.validFrom().toString(),
                    req.validTo().toString(), DeclarationStatus.ACTIVE.name(), actor, now,
                    null, null);
            certRepo.insertDeclaration(row);
            return new StoredResponse(201, toJson(toDeclarationResponse(row)));
        });
    }

    /**
     * 撤回声明版本：只影响后续绑定与放行，不改写已固化的放行快照与历史。
     */
    public StoredResponse withdraw(String actorId, String declarationKey, int version,
                                   String commandKey) {
        String actor = requireActor(actorId);
        String fingerprint = Fingerprints.of(List.of(actor, "withdraw", declarationKey,
                String.valueOf(version)));
        return commands.execute(CMD_WITHDRAW, commandKey, fingerprint, () -> {
            CertificateRepository.DeclarationRow row = certRepo
                    .findDeclarationForUpdate(declarationKey, version)
                    .orElseThrow(() -> ApiException.notFound(
                            "声明版本不存在: " + declarationKey + " v" + version));
            if (DeclarationStatus.WITHDRAWN.name().equals(row.status())) {
                throw ApiException.conflict(
                        "声明版本已撤回: " + declarationKey + " v" + version);
            }
            String now = Instant.now().toString();
            certRepo.markWithdrawn(declarationKey, version, actor, now);
            CertificateRepository.DeclarationRow updated = new CertificateRepository.DeclarationRow(
                    row.id(), row.declarationKey(), row.version(), row.materialCode(),
                    row.batchNoPrefix(), row.priority(), row.validFrom(), row.validTo(),
                    DeclarationStatus.WITHDRAWN.name(), row.publishedBy(), row.publishedAt(),
                    actor, now);
            return new StoredResponse(200, toJson(toDeclarationResponse(updated)));
        });
    }
    /**
     * 单个批次绑定声明版本：批次须处于放行前状态（QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW），
     * 声明须有效且与物料、批号匹配；已绑定时整行换绑。绑定与放行在同一批次行锁内串行。
     */
    public StoredResponse bind(String actorId, BindCertificateRequest req) {
        String actor = requireActor(actorId);
        String fingerprint = Fingerprints.of(List.of(actor, "bind", req.batchKey(),
                req.declarationKey(), String.valueOf(req.declarationVersion())));
        return commands.execute(CMD_BIND, req.commandKey(), fingerprint, () -> {
            BindingResult result = doBind(actor, req.batchKey(), req.declarationKey(),
                    req.declarationVersion());
            return new StoredResponse(result.replaced() ? 200 : 201, toJson(result.body()));
        });
    }

    /**
     * 批量绑定：先校验全部绑定的最终状态，再在同一事务提交；任一失效或 mismatch
     * 整次回滚，查询不到半成品，失败不占 commandKey。
     */
    public StoredResponse bindBatch(String actorId, BatchBindRequest req) {
        String actor = requireActor(actorId);
        List<BatchBindRequest.BindItem> items = req.bindings();
        Set<String> keys = new HashSet<>();
        for (BatchBindRequest.BindItem item : items) {
            if (!keys.add(item.batchKey())) {
                throw ApiException.conflict("batchKey 在请求内重复: " + item.batchKey());
            }
        }
        List<String> fpParts = new ArrayList<>();
        fpParts.add(actor);
        fpParts.add("batchBind");
        items.stream()
                .map(i -> i.batchKey() + "=" + i.declarationKey() + "@" + i.declarationVersion())
                .sorted()
                .forEach(fpParts::add);
        String fingerprint = Fingerprints.of(fpParts);
        return commands.execute(CMD_BATCH_BIND, req.commandKey(), fingerprint, () -> {
            List<BindingResponse> bodies = new ArrayList<>(items.size());
            for (BatchBindRequest.BindItem item : items) {
                bodies.add(doBind(actor, item.batchKey(), item.declarationKey(),
                        item.declarationVersion()).body());
            }
            BatchBindResponse body = new BatchBindResponse(items.size(), bodies.size(), bodies);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 在调用方事务内执行单条绑定：锁定批次行与声明行后校验并写入。
     */
    private BindingResult doBind(String actor, String batchKey, String declarationKey,
                                 int declarationVersion) {
        BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        BatchStatus status = BatchStatus.valueOf(batch.status());
        if (status != BatchStatus.QUARANTINED && status != BatchStatus.PENDING_RELEASE
                && status != BatchStatus.RELEASE_REVIEW) {
            throw ApiException.conflict("批次状态 " + status + " 不允许绑定合格证声明: " + batchKey);
        }
        batchService.assertNoRecalledAncestor(batchKey);
        CertificateRepository.DeclarationRow declaration = certRepo
                .findDeclarationForUpdate(declarationKey, declarationVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "声明版本不存在: " + declarationKey + " v" + declarationVersion));
        String now = Instant.now().toString();
        DeclarationRules.assertUsable(declaration, batch.productCode(), batch.batchNo(), now);
        CertificateRepository.BindingRow row = new CertificateRepository.BindingRow(0L, batchKey,
                declarationKey, declarationVersion, declaration.batchNoPrefix(), actor, now);
        boolean replaced = certRepo.findBindingForUpdate(batchKey).isPresent();
        if (replaced) {
            certRepo.replaceBinding(row);
        } else {
            certRepo.insertBinding(row);
        }
        BindingResponse body = new BindingResponse(batchKey, declarationKey, declarationVersion,
                declaration.batchNoPrefix(), actor, Instant.parse(now));
        return new BindingResult(body, replaced);
    }

    private record BindingResult(BindingResponse body, boolean replaced) {
    }
    /**
     * 声明版本明细列表：可按物料过滤，按物料、声明键、版本稳定排序；只读，不改变状态。
     */
    public List<DeclarationResponse> listDeclarations(String materialCode) {
        List<CertificateRepository.DeclarationRow> rows = materialCode == null
                ? certRepo.findAllDeclarations()
                : certRepo.findDeclarationsByMaterial(materialCode);
        return rows.stream().map(this::toDeclarationResponse).toList();
    }

    /**
     * 某声明键的版本历史：按版本号稳定排序，含撤回信息；只读，不改变状态。
     */
    public List<DeclarationResponse> declarationHistory(String declarationKey) {
        List<CertificateRepository.DeclarationRow> rows =
                certRepo.findDeclarationHistory(declarationKey);
        if (rows.isEmpty()) {
            throw ApiException.notFound("声明不存在: " + declarationKey);
        }
        return rows.stream().map(this::toDeclarationResponse).toList();
    }

    /**
     * 批次合格证明细：当前绑定与放行快照；未绑定/未放行对应字段为 null；只读。
     */
    public BatchCertificateResponse batchCertificate(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        BindingResponse binding = certRepo.findBinding(batchKey)
                .map(b -> new BindingResponse(b.batchKey(), b.declarationKey(),
                        b.declarationVersion(), b.matchedPrefix(), b.boundBy(),
                        Instant.parse(b.boundAt())))
                .orElse(null);
        BatchCertificateResponse.ReleaseSnapshotResponse snapshot = certRepo
                .findReleaseSnapshot(batchKey)
                .map(s -> new BatchCertificateResponse.ReleaseSnapshotResponse(s.declarationKey(),
                        s.declarationVersion(), s.matchedPrefix(), Instant.parse(s.releasedAt())))
                .orElse(null);
        return new BatchCertificateResponse(batchKey, binding, snapshot);
    }

    /**
     * 匹配诊断：对给定物料、批号与时点（UTC，左闭右开）逐个声明版本给出判定与原因，
     * 按优先级降序、声明键、版本稳定排序；只读，不改变状态。
     */
    public DeclarationDiagnosticsResponse diagnostics(String materialCode, String batchNo,
                                                      Instant at) {
        Instant effectiveAt = at == null ? Instant.now() : at;
        List<CertificateRepository.DeclarationRow> rows = new ArrayList<>(
                certRepo.findDeclarationsByMaterial(materialCode));
        rows.sort((a, b) -> {
            int byPriority = Integer.compare(b.priority(), a.priority());
            if (byPriority != 0) {
                return byPriority;
            }
            int byKey = a.declarationKey().compareTo(b.declarationKey());
            return byKey != 0 ? byKey : Integer.compare(a.version(), b.version());
        });
        List<DeclarationDiagnosticsResponse.Candidate> candidates = new ArrayList<>(rows.size());
        for (CertificateRepository.DeclarationRow row : rows) {
            boolean prefixMatched = batchNo.startsWith(row.batchNoPrefix());
            boolean within = DeclarationRules.withinValidity(row, effectiveAt);
            boolean active = DeclarationStatus.ACTIVE.name().equals(row.status());
            boolean eligible = prefixMatched && within && active;
            String reason = null;
            if (!active) {
                reason = "声明已撤回";
            } else if (!prefixMatched) {
                reason = "批号不匹配前缀 " + row.batchNoPrefix();
            } else if (!within) {
                reason = "时点不在有效区间 [" + row.validFrom() + ", " + row.validTo() + ")";
            }
            candidates.add(new DeclarationDiagnosticsResponse.Candidate(row.declarationKey(),
                    row.version(), row.priority(), row.status(), prefixMatched, within, active,
                    eligible, reason));
        }
        List<DeclarationDiagnosticsResponse.Candidate> eligible = candidates.stream()
                .filter(DeclarationDiagnosticsResponse.Candidate::eligible).toList();
        String matchedKey = null;
        Integer matchedVersion = null;
        boolean ambiguous = false;
        if (!eligible.isEmpty()) {
            int top = eligible.get(0).priority();
            List<DeclarationDiagnosticsResponse.Candidate> best = eligible.stream()
                    .filter(c -> c.priority() == top).toList();
            if (best.size() > 1) {
                ambiguous = true;
            } else {
                matchedKey = best.get(0).declarationKey();
                matchedVersion = best.get(0).version();
            }
        }
        return new DeclarationDiagnosticsResponse(materialCode, batchNo, effectiveAt,
                matchedKey, matchedVersion, ambiguous, candidates);
    }

    private DeclarationResponse toDeclarationResponse(CertificateRepository.DeclarationRow row) {
        return new DeclarationResponse(row.declarationKey(), row.version(), row.materialCode(),
                row.batchNoPrefix(), row.priority(), Instant.parse(row.validFrom()),
                Instant.parse(row.validTo()), DeclarationStatus.valueOf(row.status()),
                row.publishedBy(), Instant.parse(row.publishedAt()), row.withdrawnBy(),
                row.withdrawnAt() == null ? null : Instant.parse(row.withdrawnAt()));
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}