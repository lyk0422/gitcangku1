package com.example.starter.batch;

import com.example.starter.batch.dto.BindCertificatesRequest;
import com.example.starter.batch.dto.BindCertificatesResponse;
import com.example.starter.batch.dto.CertificateDetailResponse;
import com.example.starter.batch.dto.CertificateDiagnosisResponse;
import com.example.starter.batch.dto.DeclarationHistoryResponse;
import com.example.starter.batch.dto.DeclarationResponse;
import com.example.starter.batch.dto.PublishDeclarationRequest;
import com.example.starter.batch.dto.WithdrawDeclarationRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 合格证声明与批次绑定核心服务。
 *
 * <p>声明版本化且不可改写，只能撤回；撤回只影响后续绑定与放行，不改写历史绑定与放行快照。
 * 有效 UTC 区间按左闭右开解释。发布/撤回/绑定与批次放行按事务提交顺序裁决：
 * 绑定与放行校验均对声明行 SELECT ... FOR UPDATE，与撤回互斥。
 */
@Service
public class CertificateService {

    private static final String CMD_PUBLISH = "PUBLISH_DECLARATION";
    private static final String CMD_WITHDRAW = "WITHDRAW_DECLARATION";
    private static final String CMD_BIND = "BIND_CERTIFICATE";

    /**
     * 拆分继承绑定时记录的操作人标识。
     */
    public static final String INHERIT_ACTOR = "SYSTEM";

    private final CertificateRepository certRepo;
    private final BatchRepository batchRepo;
    private final CommandExecutor commands;
    private final Clock clock;

    public CertificateService(CertificateRepository certRepo,
                              BatchRepository batchRepo,
                              CommandExecutor commands,
                              Clock clock) {
        this.certRepo = certRepo;
        this.batchRepo = batchRepo;
        this.commands = commands;
        this.clock = clock;
    }

    /**
     * 发布声明版本：区间必须合法（validFrom 早于 validTo，左闭右开）；
     * 同物料下与已发布声明前缀重叠且优先级相同返回 422；同 key 同版本不可改写返回 409。
     */
    public StoredResponse publish(String actorId, PublishDeclarationRequest req) {
        String actor = requireActor(actorId);
        List<String> prefixes = req.batchNoPrefixes().stream().map(String::trim).toList();
        if (new HashSet<>(prefixes).size() != prefixes.size()) {
            throw ApiException.badRequest("batchNoPrefixes 存在重复前缀");
        }
        if (!req.validFrom().isBefore(req.validTo())) {
            throw ApiException.unprocessable("有效区间非法：validFrom=" + req.validFrom()
                    + " 必须早于 validTo=" + req.validTo());
        }
        List<String> sortedPrefixes = prefixes.stream().sorted().toList();
        String fingerprint = commands.fingerprint("publish", actor, req.declarationKey(),
                String.valueOf(req.version()), req.materialCode(), String.valueOf(req.priority()),
                req.validFrom().toString(), req.validTo().toString(),
                String.join(CommandExecutor.SEP, sortedPrefixes));
        return commands.execute(CMD_PUBLISH, req.commandKey(), fingerprint, () -> {
            certRepo.findDeclaration(req.declarationKey(), req.version()).ifPresent(d -> {
                throw ApiException.conflict("声明版本已存在，不可改写: "
                        + req.declarationKey() + " v" + req.version());
            });
            assertNoPrefixConflict(req.materialCode(), req.priority(), prefixes);
            String now = commands.now();
            certRepo.insertDeclaration(new CertificateRepository.DeclarationRow(0L,
                    req.declarationKey(), req.version(), req.materialCode(), req.priority(),
                    req.validFrom().toString(), req.validTo().toString(),
                    DeclarationStatus.PUBLISHED.name(), actor, now, null));
            for (int i = 0; i < prefixes.size(); i++) {
                certRepo.insertPrefix(req.declarationKey(), req.version(), prefixes.get(i), i + 1);
            }
            DeclarationResponse body = new DeclarationResponse(req.declarationKey(), req.version(),
                    req.materialCode(), prefixes, req.priority(), req.validFrom(), req.validTo(),
                    DeclarationStatus.PUBLISHED, actor, Instant.parse(now), null);
            return new StoredResponse(201, commands.toJson(body));
        });
    }

    /**
     * 撤回声明版本：仅 PUBLISHED 可撤回；撤回只影响后续绑定与放行，不改写历史。
     */
    public StoredResponse withdraw(String declarationKey, int version, String actorId,
                                   WithdrawDeclarationRequest req) {
        String actor = requireActor(actorId);
        String fingerprint = commands.fingerprint("withdraw", actor, declarationKey,
                String.valueOf(version));
        return commands.execute(CMD_WITHDRAW, req.commandKey(), fingerprint, () -> {
            CertificateRepository.DeclarationRow row = certRepo
                    .findDeclarationForUpdate(declarationKey, version)
                    .orElseThrow(() -> ApiException.notFound(
                            "声明不存在: " + declarationKey + " v" + version));
            if (DeclarationStatus.WITHDRAWN.name().equals(row.status())) {
                throw ApiException.conflict("声明已撤回: " + declarationKey + " v" + version);
            }
            String now = commands.now();
            certRepo.markWithdrawn(declarationKey, version, now);
            DeclarationResponse body = toDeclarationResponse(
                    new CertificateRepository.DeclarationRow(row.id(), row.declarationKey(),
                            row.version(), row.materialCode(), row.priority(), row.validFrom(),
                            row.validTo(), DeclarationStatus.WITHDRAWN.name(), row.actorId(),
                            row.createdAt(), now));
            return new StoredResponse(200, commands.toJson(body));
        });
    }

    /**
     * 批量绑定：先校验全部绑定的最终状态，再在同一事务提交；任一声明失效、
     * 物料不匹配、前缀不匹配或区间失效则整次回滚，查询不到半成品。
     */
    public StoredResponse bindBulk(String actorId, BindCertificatesRequest req) {
        String actor = requireActor(actorId);
        List<BindCertificatesRequest.BindingSpec> specs = req.bindings().stream()
                .sorted(Comparator.comparing(BindCertificatesRequest.BindingSpec::batchKey)
                        .thenComparing(BindCertificatesRequest.BindingSpec::declarationKey)
                        .thenComparing(BindCertificatesRequest.BindingSpec::version))
                .toList();
        Set<String> batchKeys = new HashSet<>();
        for (BindCertificatesRequest.BindingSpec spec : specs) {
            if (!batchKeys.add(spec.batchKey())) {
                throw ApiException.conflict("批次在请求内重复绑定: " + spec.batchKey());
            }
        }
        List<String> parts = new ArrayList<>(List.of("bind", actor));
        for (BindCertificatesRequest.BindingSpec spec : specs) {
            parts.add(spec.batchKey());
            parts.add(spec.declarationKey());
            parts.add(String.valueOf(spec.version()));
        }
        String fingerprint = commands.fingerprint(parts.toArray(new String[0]));
        return commands.execute(CMD_BIND, req.commandKey(), fingerprint, () -> {
            List<BindCertificatesResponse.BindingResult> results = new ArrayList<>(specs.size());
            for (BindCertificatesRequest.BindingSpec spec : specs) {
                results.add(bindOne(spec, actor));
            }
            return new StoredResponse(201,
                    commands.toJson(new BindCertificatesResponse(results.size(), results)));
        });
    }

    /**
     * 单条绑定校验并落库（在批量事务内调用）：批次行锁 + 声明行锁，按提交顺序与撤回互斥。
     */
    private BindCertificatesResponse.BindingResult bindOne(
            BindCertificatesRequest.BindingSpec spec, String actor) {
        BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(spec.batchKey())
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + spec.batchKey()));
        BatchStatus status = BatchStatus.valueOf(batch.status());
        if (status != BatchStatus.QUARANTINED && status != BatchStatus.PENDING_RELEASE
                && status != BatchStatus.RELEASE_REVIEW) {
            throw ApiException.conflict("批次状态 " + status + " 不允许绑定合格证声明");
        }
        certRepo.findBinding(spec.batchKey()).ifPresent(b -> {
            throw ApiException.conflict("批次已绑定合格证声明: " + spec.batchKey());
        });
        CertificateRepository.DeclarationRow decl = certRepo
                .findDeclarationForUpdate(spec.declarationKey(), spec.version())
                .orElseThrow(() -> ApiException.unprocessable("声明不存在或已失效: "
                        + spec.declarationKey() + " v" + spec.version()));
        assertDeclarationUsable(decl, batch.productCode());
        String matchedPrefix = longestMatchingPrefix(
                certRepo.findPrefixes(decl.declarationKey(), decl.version()), batch.batchNo())
                .orElseThrow(() -> ApiException.unprocessable("批次批号 " + batch.batchNo()
                        + " 不匹配声明允许前缀"));
        String now = commands.now();
        certRepo.insertBinding(new CertificateRepository.BindingRow(0L, spec.batchKey(),
                decl.declarationKey(), decl.version(), decl.materialCode(), matchedPrefix,
                actor, now));
        return new BindCertificatesResponse.BindingResult(spec.batchKey(), decl.declarationKey(),
                decl.version(), decl.materialCode(), matchedPrefix, Instant.parse(now));
    }

    /**
     * 放行车门禁（在批准事务内调用，批次行已锁）：批次必须已绑定声明，
     * 声明在放行时点未撤回且处于有效区间；通过后写入放行快照（版本、匹配前缀、时点）。
     */
    public void assertAndSnapshotRelease(String batchKey, String releasedAt) {
        CertificateRepository.BindingRow binding = certRepo.findBinding(batchKey)
                .orElseThrow(() -> ApiException.unprocessable(
                        "批次未绑定合格证声明，不能放行: " + batchKey));
        CertificateRepository.DeclarationRow decl = certRepo
                .findDeclarationForUpdate(binding.declarationKey(), binding.version())
                .orElseThrow(() -> ApiException.unprocessable("绑定的声明不存在: "
                        + binding.declarationKey() + " v" + binding.version()));
        if (!DeclarationStatus.PUBLISHED.name().equals(decl.status())) {
            throw ApiException.unprocessable("声明 " + decl.declarationKey() + " v" + decl.version()
                    + " 已撤回，不能放行");
        }
        assertWithinValidity(decl, releasedAt);
        certRepo.insertSnapshot(new CertificateRepository.SnapshotRow(0L, batchKey,
                decl.declarationKey(), decl.version(), binding.matchedPrefix(), releasedAt));
    }

    /**
     * 拆分继承（在拆分事务内调用）：父批若有绑定，子批继承同一声明版本引用。
     */
    public void inheritBinding(String parentKey, String childKey, String now) {
        certRepo.findBinding(parentKey).ifPresent(binding ->
                certRepo.insertBinding(new CertificateRepository.BindingRow(0L, childKey,
                        binding.declarationKey(), binding.version(), binding.materialCode(),
                        binding.matchedPrefix(), INHERIT_ACTOR, now)));
    }

    /**
     * 合批绑定（在合批事务内调用）：为合批产物写入已重新校验声明的绑定。
     */
    public void bindMergedBatch(String batchKey, String declarationKey, int version,
                                String materialCode, String matchedPrefix, String actor,
                                String now) {
        certRepo.insertBinding(new CertificateRepository.BindingRow(0L, batchKey, declarationKey,
                version, materialCode, matchedPrefix, actor, now));
    }

    /**
     * 合批校验（在合批事务内调用）：声明必须存在、未撤回、合批时点有效，
     * 物料与全部父批一致，新批批号命中允许前缀；返回匹配前缀。
     */
    public String validateForMerge(String declarationKey, int version,
                                   List<BatchRepository.BatchRow> parents, String batchNo,
                                   String now) {
        CertificateRepository.DeclarationRow decl = certRepo
                .findDeclarationForUpdate(declarationKey, version)
                .orElseThrow(() -> ApiException.unprocessable(
                        "声明不存在或已失效: " + declarationKey + " v" + version));
        if (!DeclarationStatus.PUBLISHED.name().equals(decl.status())) {
            throw ApiException.unprocessable("声明 " + declarationKey + " v" + version
                    + " 已撤回，不能用于合批");
        }
        assertWithinValidity(decl, now);
        for (BatchRepository.BatchRow parent : parents) {
            if (!decl.materialCode().equals(parent.productCode())) {
                throw ApiException.unprocessable("父批 " + parent.batchKey() + " 物料 "
                        + parent.productCode() + " 与声明物料 " + decl.materialCode() + " 不一致");
            }
        }
        return longestMatchingPrefix(
                certRepo.findPrefixes(decl.declarationKey(), decl.version()), batchNo)
                .orElseThrow(() -> ApiException.unprocessable("新批批号 " + batchNo
                        + " 不匹配声明允许前缀"));
    }

    /**
     * 声明历史：某声明键的全部版本，按版本号升序；不存在返回 404。
     */
    public DeclarationHistoryResponse history(String declarationKey) {
        List<CertificateRepository.DeclarationRow> rows = certRepo.findDeclarationsByKey(
                declarationKey);
        if (rows.isEmpty()) {
            throw ApiException.notFound("声明不存在: " + declarationKey);
        }
        return new DeclarationHistoryResponse(declarationKey,
                rows.stream().map(this::toDeclarationResponse).toList());
    }

    /**
     * 声明版本明细；不存在返回 404。
     */
    public DeclarationResponse getVersion(String declarationKey, int version) {
        return certRepo.findDeclaration(declarationKey, version)
                .map(this::toDeclarationResponse)
                .orElseThrow(() -> ApiException.notFound(
                        "声明不存在: " + declarationKey + " v" + version));
    }

    /**
     * 批次合格证明细：当前绑定与放行快照；未绑定/未放行对应字段为 null。
     */
    public CertificateDetailResponse batchCertificate(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        CertificateDetailResponse.BindingInfo binding = certRepo.findBinding(batchKey)
                .map(b -> new CertificateDetailResponse.BindingInfo(b.declarationKey(), b.version(),
                        b.materialCode(), b.matchedPrefix(), b.actorId(),
                        Instant.parse(b.createdAt())))
                .orElse(null);
        CertificateDetailResponse.ReleaseInfo release = certRepo.findSnapshot(batchKey)
                .map(s -> new CertificateDetailResponse.ReleaseInfo(s.declarationKey(), s.version(),
                        s.matchedPrefix(), Instant.parse(s.releasedAt())))
                .orElse(null);
        return new CertificateDetailResponse(batchKey, binding, release);
    }

    /**
     * 放行诊断（只读）：按固定顺序检查绑定、撤回、区间、物料、前缀，返回全部不满足原因。
     */
    public CertificateDiagnosisResponse diagnose(String batchKey) {
        BatchRepository.BatchRow batch = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        String checkedAt = commands.now();
        List<String> reasons = new ArrayList<>();
        Optional<CertificateRepository.BindingRow> binding = certRepo.findBinding(batchKey);
        if (binding.isEmpty()) {
            reasons.add("批次未绑定合格证声明");
        } else {
            CertificateRepository.BindingRow b = binding.get();
            Optional<CertificateRepository.DeclarationRow> decl = certRepo.findDeclaration(
                    b.declarationKey(), b.version());
            if (decl.isEmpty()) {
                reasons.add("绑定的声明不存在: " + b.declarationKey() + " v" + b.version());
            } else {
                CertificateRepository.DeclarationRow d = decl.get();
                if (!DeclarationStatus.PUBLISHED.name().equals(d.status())) {
                    reasons.add("声明已撤回: " + d.declarationKey() + " v" + d.version());
                }
                if (!withinValidity(d, checkedAt)) {
                    reasons.add("声明不在有效区间内: [" + d.validFrom() + ", " + d.validTo() + ")");
                }
                if (!d.materialCode().equals(batch.productCode())) {
                    reasons.add("声明物料 " + d.materialCode() + " 与批次物料 "
                            + batch.productCode() + " 不一致");
                }
                if (longestMatchingPrefix(certRepo.findPrefixes(d.declarationKey(), d.version()),
                        batch.batchNo()).isEmpty()) {
                    reasons.add("批次批号 " + batch.batchNo() + " 不匹配声明允许前缀");
                }
            }
        }
        CertificateDetailResponse.BindingInfo bindingInfo = binding
                .map(b -> new CertificateDetailResponse.BindingInfo(b.declarationKey(), b.version(),
                        b.materialCode(), b.matchedPrefix(), b.actorId(),
                        Instant.parse(b.createdAt())))
                .orElse(null);
        return new CertificateDiagnosisResponse(batchKey, reasons.isEmpty(), reasons, bindingInfo,
                Instant.parse(checkedAt));
    }

    /**
     * 声明可用于绑定/放行的公共校验：未撤回、物料一致、当前时点处于有效区间。
     */
    private void assertDeclarationUsable(CertificateRepository.DeclarationRow decl,
                                         String materialCode) {
        if (!DeclarationStatus.PUBLISHED.name().equals(decl.status())) {
            throw ApiException.unprocessable("声明 " + decl.declarationKey() + " v"
                    + decl.version() + " 已撤回");
        }
        if (!decl.materialCode().equals(materialCode)) {
            throw ApiException.unprocessable("批次物料 " + materialCode + " 与声明物料 "
                    + decl.materialCode() + " 不匹配");
        }
        assertWithinValidity(decl, commands.now());
    }

    /**
     * 有效区间左闭右开校验：at 必须满足 validFrom <= at < validTo。
     */
    private void assertWithinValidity(CertificateRepository.DeclarationRow decl, String at) {
        if (!withinValidity(decl, at)) {
            throw ApiException.unprocessable("声明 " + decl.declarationKey() + " v" + decl.version()
                    + " 在 " + at + " 不在有效区间 [" + decl.validFrom() + ", " + decl.validTo()
                    + ") 内");
        }
    }

    private boolean withinValidity(CertificateRepository.DeclarationRow decl, String at) {
        Instant instant = Instant.parse(at);
        return !instant.isBefore(Instant.parse(decl.validFrom()))
                && instant.isBefore(Instant.parse(decl.validTo()));
    }

    /**
     * 批号命中的最长允许前缀；无命中返回空。
     */
    private Optional<String> longestMatchingPrefix(List<String> prefixes, String batchNo) {
        return prefixes.stream()
                .filter(batchNo::startsWith)
                .max(Comparator.comparingInt(String::length));
    }

    /**
     * 发布校验：同物料下任一已发布声明的前缀与新前缀重叠（互为前缀）且优先级相同 → 422。
     */
    private void assertNoPrefixConflict(String materialCode, int priority, List<String> prefixes) {
        for (CertificateRepository.DeclarationRow existing
                : certRepo.findPublishedByMaterial(materialCode)) {
            if (existing.priority() != priority) {
                continue;
            }
            List<String> existingPrefixes = certRepo.findPrefixes(existing.declarationKey(),
                    existing.version());
            for (String incoming : prefixes) {
                for (String current : existingPrefixes) {
                    if (incoming.startsWith(current) || current.startsWith(incoming)) {
                        throw ApiException.unprocessable("前缀 " + incoming + " 与已发布声明 "
                                + existing.declarationKey() + " v" + existing.version()
                                + " 的前缀 " + current + " 重叠且优先级相同: " + priority);
                    }
                }
            }
        }
    }

    private DeclarationResponse toDeclarationResponse(CertificateRepository.DeclarationRow row) {
        return new DeclarationResponse(row.declarationKey(), row.version(), row.materialCode(),
                certRepo.findPrefixes(row.declarationKey(), row.version()), row.priority(),
                Instant.parse(row.validFrom()), Instant.parse(row.validTo()),
                DeclarationStatus.valueOf(row.status()), row.actorId(),
                Instant.parse(row.createdAt()),
                row.withdrawnAt() == null ? null : Instant.parse(row.withdrawnAt()));
    }

    private String requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        return actorId.trim();
    }
}
