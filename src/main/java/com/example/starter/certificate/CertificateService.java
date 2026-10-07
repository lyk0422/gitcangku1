package com.example.starter.certificate;

import com.example.starter.batch.ApiException;
import com.example.starter.batch.BatchRepository;
import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.StoredResponse;
import com.example.starter.certificate.dto.BindCertificatesRequest;
import com.example.starter.certificate.dto.BindCertificatesResponse;
import com.example.starter.certificate.dto.BindingResponse;
import com.example.starter.certificate.dto.CertificateDiagnosticsResponse;
import com.example.starter.certificate.dto.CertificateStatusResponse;
import com.example.starter.certificate.dto.DeclarationDetailResponse;
import com.example.starter.certificate.dto.DeclarationResponse;
import com.example.starter.certificate.dto.PublishDeclarationRequest;
import com.example.starter.certificate.dto.ReleaseSnapshotResponse;
import com.example.starter.certificate.dto.WithdrawDeclarationRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 合格证声明与批次绑定核心服务。
 *
 * <p>声明版本化且不可改写；撤回只影响后续绑定与放行，不改写历史绑定与放行快照。
 * 有效区间为 UTC 左闭右开 [validFrom, validTo)。并发策略：发布按物料代码串行（应用内锁），
 * 撤回/绑定/放行对声明行 SELECT ... FOR UPDATE，按事务提交顺序裁决；
 * commandKey 幂等通过 command_log 主键 + 参数指纹实现，仅成功命令占键。
 */
@Service
public class CertificateService {

    static final String CMD_PUBLISH = "PUBLISH_DECLARATION";
    static final String CMD_WITHDRAW = "WITHDRAW_DECLARATION";
    static final String CMD_BIND = "BIND_CERTIFICATE";

    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_WITHDRAWN = "WITHDRAWN";

    /**
     * 指纹拼接分隔符（NUL）：参数先转义再拼接，避免拼接碰撞。
     */
    private static final String SEP = "\u0000";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final CertificateRepository certRepo;
    private final BatchRepository batchRepo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    /**
     * 发布串行化锁：按物料代码分段，保证"前缀重叠且优先级相同"校验与插入原子完成。
     */
    private final ConcurrentHashMap<String, Object> publishLocks = new ConcurrentHashMap<>();

    public CertificateService(CertificateRepository certRepo,
                              BatchRepository batchRepo,
                              PlatformTransactionManager transactionManager,
                              ObjectMapper objectMapper) {
        this.certRepo = certRepo;
        this.batchRepo = batchRepo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 发布声明版本：有效区间非法（起点不早于终点）返回 422；
     * 同物料下其他声明键的 ACTIVE 声明前缀重叠且优先级相同返回 422；
     * 同声明键同版本已存在返回 409。
     */
    public StoredResponse publish(String actorId, PublishDeclarationRequest req) {
        String actor = requireActor(actorId);
        if (!req.validFrom().isBefore(req.validTo())) {
            throw ApiException.unprocessable("有效区间非法：validFrom=" + req.validFrom()
                    + " 必须早于 validTo=" + req.validTo() + "（左闭右开）");
        }
        String fingerprint = fingerprint("publish", actor, req.declarationKey(),
                req.version().toString(), req.materialCode(), req.batchNoPrefix(),
                req.priority().toString(), req.validFrom().toString(), req.validTo().toString());
        synchronized (publishLocks.computeIfAbsent(req.materialCode(), k -> new Object())) {
            return executeIdempotent(CMD_PUBLISH, req.commandKey(), fingerprint, () -> {
                certRepo.findDeclaration(req.declarationKey(), req.version()).ifPresent(d -> {
                    throw ApiException.conflict("声明版本已存在: " + req.declarationKey()
                            + " v" + req.version());
                });
                for (CertificateRepository.DeclarationRow other
                        : certRepo.findDeclarationsByMaterial(req.materialCode())) {
                    if (other.declarationKey().equals(req.declarationKey())
                            || !STATUS_ACTIVE.equals(other.status())) {
                        continue;
                    }
                    if (other.priority() == req.priority()
                            && prefixesOverlap(other.batchNoPrefix(), req.batchNoPrefix())) {
                        throw ApiException.unprocessable("声明前缀重叠且优先级相同："
                                + other.declarationKey() + " v" + other.version()
                                + " 前缀 " + other.batchNoPrefix() + " 与 " + req.batchNoPrefix()
                                + " 重叠，优先级均为 " + req.priority());
                    }
                }
                String now = now();
                CertificateRepository.DeclarationRow row = new CertificateRepository.DeclarationRow(
                        0L, req.declarationKey(), req.version(), req.materialCode(),
                        req.batchNoPrefix(), req.priority(), req.validFrom().toString(),
                        req.validTo().toString(), STATUS_ACTIVE, actor, now, null, null, null);
                certRepo.insertDeclaration(row);
                return new StoredResponse(201, toJson(toDeclarationResponse(row)));
            });
        }
    }

    /**
     * 撤回声明版本：不存在返回 404；已撤回返回 409；撤回只影响后续绑定与放行，不改写历史。
     */
    public StoredResponse withdraw(String declarationKey, int version, String actorId,
                                   WithdrawDeclarationRequest req) {
        String actor = requireActor(actorId);
        String fingerprint = fingerprint("withdraw", actor, declarationKey,
                String.valueOf(version), req.reason());
        return executeIdempotent(CMD_WITHDRAW, req.commandKey(), fingerprint, () -> {
            CertificateRepository.DeclarationRow row = certRepo
                    .findDeclarationForUpdate(declarationKey, version)
                    .orElseThrow(() -> ApiException.notFound(
                            "声明版本不存在: " + declarationKey + " v" + version));
            if (STATUS_WITHDRAWN.equals(row.status())) {
                throw ApiException.conflict("声明已撤回: " + declarationKey + " v" + version);
            }
            String now = now();
            certRepo.markWithdrawn(declarationKey, version, now, actor, req.reason());
            CertificateRepository.DeclarationRow updated = new CertificateRepository.DeclarationRow(
                    row.id(), row.declarationKey(), row.version(), row.materialCode(),
                    row.batchNoPrefix(), row.priority(), row.validFrom(), row.validTo(),
                    STATUS_WITHDRAWN, row.publishedBy(), row.createdAt(), now, actor, req.reason());
            return new StoredResponse(201, toJson(toDeclarationResponse(updated)));
        });
    }

    /**
     * 批量绑定：先校验全部绑定的完整最终状态，再在同一事务提交；任一失效声明或不匹配整次回滚。
     * 批次状态须为 QUARANTINED/PENDING_RELEASE/RELEASE_REVIEW；每批至多一条绑定。
     */
    public StoredResponse bind(String actorId, BindCertificatesRequest req) {
        String actor = requireActor(actorId);
        List<BindCertificatesRequest.BindingSpec> specs = req.bindings();
        List<String> batchKeys = specs.stream().map(BindCertificatesRequest.BindingSpec::batchKey)
                .toList();
        if (new HashSet<>(batchKeys).size() != batchKeys.size()) {
            throw ApiException.conflict("bindings 内 batchKey 重复");
        }
        List<String> parts = new ArrayList<>();
        parts.add("bind");
        parts.add(actor);
        for (BindCertificatesRequest.BindingSpec spec : specs) {
            parts.add(spec.batchKey());
            parts.add(spec.declarationKey());
            parts.add(String.valueOf(spec.declarationVersion()));
        }
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return executeIdempotent(CMD_BIND, req.commandKey(), fingerprint, () -> {
            // 第一阶段：完整校验全部绑定（行锁批次与声明，按提交顺序裁决并发）
            List<CertificateRepository.BindingRow> planned = new ArrayList<>(specs.size());
            String now = now();
            for (BindCertificatesRequest.BindingSpec spec : specs) {
                BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(spec.batchKey())
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + spec.batchKey()));
                CertificateRepository.DeclarationRow decl = certRepo
                        .findDeclarationForUpdate(spec.declarationKey(), spec.declarationVersion())
                        .orElseThrow(() -> ApiException.notFound("声明版本不存在: "
                                + spec.declarationKey() + " v" + spec.declarationVersion()));
                String status = batch.status();
                if (!BatchStatus.QUARANTINED.name().equals(status)
                        && !BatchStatus.PENDING_RELEASE.name().equals(status)
                        && !BatchStatus.RELEASE_REVIEW.name().equals(status)) {
                    throw ApiException.conflict("批次 " + spec.batchKey() + " 状态 " + status
                            + " 不允许绑定合格证");
                }
                if (certRepo.findBinding(spec.batchKey()).isPresent()) {
                    throw ApiException.conflict("批次已绑定合格证: " + spec.batchKey());
                }
                if (!STATUS_ACTIVE.equals(decl.status())) {
                    throw ApiException.unprocessable("声明已撤回（失效）: " + spec.declarationKey()
                            + " v" + spec.declarationVersion() + "，整次绑定回滚");
                }
                if (!decl.materialCode().equals(batch.productCode())) {
                    throw ApiException.unprocessable("批次物料不匹配：批次 " + spec.batchKey()
                            + " 物料 " + batch.productCode() + "，声明要求 " + decl.materialCode());
                }
                if (!batch.batchNo().startsWith(decl.batchNoPrefix())) {
                    throw ApiException.unprocessable("批号不匹配：批次 " + spec.batchKey() + " 批号 "
                            + batch.batchNo() + " 不以声明允许前缀 " + decl.batchNoPrefix() + " 开头");
                }
                planned.add(new CertificateRepository.BindingRow(0L, spec.batchKey(),
                        spec.declarationKey(), spec.declarationVersion(), decl.batchNoPrefix(),
                        actor, now));
            }
            // 第二阶段：全部校验通过后同一事务提交
            List<BindingResponse> bodies = new ArrayList<>(planned.size());
            for (CertificateRepository.BindingRow row : planned) {
                certRepo.insertBinding(row);
                bodies.add(toBindingResponse(row));
            }
            return new StoredResponse(201,
                    toJson(new BindCertificatesResponse(bodies.size(), bodies)));
        });
    }

    /**
     * 声明列表：可按物料过滤；按 声明键、版本号 稳定排序。只读，不改变状态。
     */
    public List<DeclarationResponse> listDeclarations(String materialCode) {
        List<CertificateRepository.DeclarationRow> rows = materialCode == null
                || materialCode.isBlank()
                ? certRepo.findAllDeclarations()
                : certRepo.findDeclarationsByMaterial(materialCode.trim());
        return rows.stream().map(this::toDeclarationResponse).toList();
    }

    /**
     * 声明明细：同一声明键的全部版本，按版本号升序；不存在返回 404。
     */
    public DeclarationDetailResponse declarationDetail(String declarationKey) {
        List<CertificateRepository.DeclarationRow> rows = certRepo.findDeclarationsByKey(declarationKey);
        if (rows.isEmpty()) {
            throw ApiException.notFound("声明不存在: " + declarationKey);
        }
        return new DeclarationDetailResponse(declarationKey,
                rows.stream().map(this::toDeclarationResponse).toList());
    }

    /**
     * 批次合格证状态：绑定（未绑定为 null）+ 放行快照（未放行为 null）。只读，不改变状态。
     */
    public CertificateStatusResponse certificateStatus(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        BindingResponse binding = certRepo.findBinding(batchKey)
                .map(this::toBindingResponse).orElse(null);
        ReleaseSnapshotResponse snapshot = certRepo.findReleaseSnapshot(batchKey)
                .map(this::toSnapshotResponse).orElse(null);
        return new CertificateStatusResponse(batchKey, binding, snapshot);
    }

    /**
     * 匹配诊断：对全部声明版本逐一评估 物料/前缀/状态/评估时点有效性，
     * 按 声明键、版本号 稳定排序；评估时点为 UTC now，区间左闭右开。只读，不改变状态。
     */
    public CertificateDiagnosticsResponse diagnostics(String batchKey) {
        BatchRepository.BatchRow batch = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Instant evaluatedAt = Instant.now();
        List<CertificateDiagnosticsResponse.Candidate> candidates = new ArrayList<>();
        for (CertificateRepository.DeclarationRow decl : certRepo.findAllDeclarations()) {
            boolean materialMatched = decl.materialCode().equals(batch.productCode());
            boolean prefixMatched = batch.batchNo().startsWith(decl.batchNoPrefix());
            boolean active = STATUS_ACTIVE.equals(decl.status());
            boolean validNow = !evaluatedAt.isBefore(Instant.parse(decl.validFrom()))
                    && evaluatedAt.isBefore(Instant.parse(decl.validTo()));
            boolean eligible = materialMatched && prefixMatched && active && validNow;
            String reason;
            if (!materialMatched) {
                reason = "物料不匹配：批次 " + batch.productCode() + "，声明要求 " + decl.materialCode();
            } else if (!prefixMatched) {
                reason = "批号前缀不匹配：批号 " + batch.batchNo() + "，声明前缀 " + decl.batchNoPrefix();
            } else if (!active) {
                reason = "声明已撤回";
            } else if (!validNow) {
                reason = "评估时点 " + evaluatedAt + " 不在有效区间 [" + decl.validFrom()
                        + ", " + decl.validTo() + ") 内";
            } else {
                reason = "OK";
            }
            candidates.add(new CertificateDiagnosticsResponse.Candidate(decl.declarationKey(),
                    decl.version(), decl.status(), materialMatched, prefixMatched, active,
                    validNow, eligible, reason));
        }
        return new CertificateDiagnosticsResponse(batchKey, batch.productCode(), batch.batchNo(),
                evaluatedAt, candidates);
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时重试，读取已提交结果。失败不占键。
     */
    StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                     Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = loggedResponse(type, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    batchRepo.insertCommand(new BatchRepository.CommandRow(type, commandKey,
                            fingerprint, response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同事务键冲突：回滚后重试，读取对方已提交的命令快照或业务结果
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    /**
     * 查询命令快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     * 包级可见：合批在父批行锁后需重查快照。
     */
    Optional<StoredResponse> loggedResponse(String type, String commandKey,
                                            String fingerprint) {
        var existing = batchRepo.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    /**
     * 参数指纹：每个参数先转义反斜杠与分隔符，再用 NUL 分隔拼接后取 SHA-256，
     * 集合不会通过未转义分隔符拼接而产生碰撞。
     */
    String fingerprint(String... parts) {
        String[] escaped = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            escaped[i] = parts[i].replace("\\", "\\\\").replace(SEP, "\\0");
        }
        String canonical = String.join(SEP, escaped);
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

    String now() {
        return Instant.now().toString();
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
    }

    private boolean prefixesOverlap(String p1, String p2) {
        return p1.startsWith(p2) || p2.startsWith(p1);
    }

    private DeclarationResponse toDeclarationResponse(CertificateRepository.DeclarationRow row) {
        return new DeclarationResponse(row.declarationKey(), row.version(), row.materialCode(),
                row.batchNoPrefix(), row.priority(), Instant.parse(row.validFrom()),
                Instant.parse(row.validTo()), row.status(), row.publishedBy(),
                Instant.parse(row.createdAt()),
                row.withdrawnAt() == null ? null : Instant.parse(row.withdrawnAt()),
                row.withdrawnBy(), row.withdrawReason());
    }

    private BindingResponse toBindingResponse(CertificateRepository.BindingRow row) {
        return new BindingResponse(row.batchKey(), row.declarationKey(), row.declarationVersion(),
                row.matchedPrefix(), row.boundBy(), Instant.parse(row.createdAt()));
    }

    private ReleaseSnapshotResponse toSnapshotResponse(CertificateRepository.ReleaseSnapshotRow row) {
        return new ReleaseSnapshotResponse(row.batchKey(), row.declarationKey(),
                row.declarationVersion(), row.matchedPrefix(), Instant.parse(row.releasedAt()));
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}
