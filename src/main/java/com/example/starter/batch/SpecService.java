package com.example.starter.batch;

import com.example.starter.batch.dto.CreateSpecRequest;
import com.example.starter.batch.dto.MeasurementResponse;
import com.example.starter.batch.dto.RebindSpecRequest;
import com.example.starter.batch.dto.RebindSpecResponse;
import com.example.starter.batch.dto.SpecVersionResponse;
import com.example.starter.batch.dto.SubmitMeasurementRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 特性规范服务：规范版本管理（只追加）、测量向量检验与批次换版。
 *
 * <p>并发策略与批次域一致：同一批次的测量/换版在事务内对 batch 行 SELECT ... FOR UPDATE
 * 串行化；requestId 幂等通过 IdempotentExecutor（command_log 主键 + 参数指纹）实现，
 * 失败命令不占键，业务与去重记录原子提交。
 */
@Service
public class SpecService {

    private static final String CMD_CREATE_SPEC = "CREATE_SPEC";
    private static final String CMD_MEASURE = "SUBMIT_MEASUREMENT";
    private static final String CMD_REBIND = "REBIND_SPEC";

    private final BatchRepository batchRepo;
    private final SpecRepository specRepo;
    private final LineageSupport lineage;
    private final IdempotentExecutor idempotent;
    private final ObjectMapper objectMapper;

    public SpecService(BatchRepository batchRepo, SpecRepository specRepo,
                       LineageSupport lineage, IdempotentExecutor idempotent,
                       ObjectMapper objectMapper) {
        this.batchRepo = batchRepo;
        this.specRepo = specRepo;
        this.lineage = lineage;
        this.idempotent = idempotent;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建规范新版本：版本只追加（必须大于已有最大版本）；系数 -100～100 且单条不得全零。
     */
    public StoredResponse createSpec(CreateSpecRequest req) {
        List<String> features = req.features().stream().map(String::trim).toList();
        if (new HashSet<>(features).size() != features.size()) {
            throw ApiException.badRequest("features 存在重复特性名");
        }
        for (CreateSpecRequest.ConstraintSpec constraint : req.constraints()) {
            if (constraint.coefficients().size() != features.size()) {
                throw ApiException.badRequest("coefficients 数量必须与 features 特性数一致");
            }
            if (constraint.coefficients().stream().allMatch(c -> c == 0)) {
                throw ApiException.badRequest("单条约束的系数不得全零");
            }
        }
        List<String> parts = new ArrayList<>();
        parts.add("spec");
        parts.add(req.specKey());
        parts.add(req.version().toString());
        parts.add(req.operator());
        parts.addAll(features);
        for (CreateSpecRequest.ConstraintSpec constraint : req.constraints()) {
            constraint.coefficients().forEach(c -> parts.add(c.toString()));
            parts.add(SpecEvaluator.canonicalDecimal(constraint.upperBound()));
        }
        String fingerprint = IdempotentExecutor.fingerprint(parts.toArray(new String[0]));
        return idempotent.execute(CMD_CREATE_SPEC, req.requestId(), fingerprint, () -> {
            int maxVersion = specRepo.findMaxVersion(req.specKey()).orElse(0);
            if (req.version() <= maxVersion) {
                throw ApiException.conflict(
                        "规范只追加新版本：version 必须大于当前最大版本 " + maxVersion);
            }
            String now = Instant.now().toString();
            specRepo.insertSpecVersion(req.specKey(), req.version(), req.operator(), now);
            for (int i = 0; i < features.size(); i++) {
                specRepo.insertFeature(req.specKey(), req.version(), i + 1, features.get(i));
            }
            List<SpecVersionResponse.ConstraintView> views = new ArrayList<>();
            for (int i = 0; i < req.constraints().size(); i++) {
                CreateSpecRequest.ConstraintSpec constraint = req.constraints().get(i);
                long constraintId = specRepo.insertConstraint(req.specKey(), req.version(),
                        i + 1, constraint.upperBound().toPlainString());
                for (int j = 0; j < constraint.coefficients().size(); j++) {
                    specRepo.insertCoefficient(constraintId, j + 1, constraint.coefficients().get(j));
                }
                views.add(new SpecVersionResponse.ConstraintView(constraint.coefficients(),
                        constraint.upperBound()));
            }
            SpecVersionResponse body = new SpecVersionResponse(req.specKey(), req.version(),
                    features, views, req.operator(), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 提交完整测量向量：缺特性或超精度（&gt;6 位小数）400 且不留检验；
     * 合法向量越界记录 FAIL 并使批次 REJECTED；全部约束满足记录 PASS。
     */
    public StoredResponse submitMeasurement(String batchKey, SubmitMeasurementRequest req) {
        List<String> parts = new ArrayList<>();
        parts.add("measure");
        parts.add(batchKey);
        parts.add(req.measurementKey());
        parts.add(req.operator());
        req.values().forEach(v -> parts.add(SpecEvaluator.canonicalDecimal(v)));
        String fingerprint = IdempotentExecutor.fingerprint(parts.toArray(new String[0]));
        return idempotent.execute(CMD_MEASURE, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：锁等待期间并发同键请求可能已提交
            var logged = idempotent.logged(CMD_MEASURE, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            for (BigDecimal value : req.values()) {
                if (!SpecEvaluator.withinScale(value)) {
                    throw ApiException.badRequest("测量值最多 6 位小数: " + value.toPlainString());
                }
            }
            BatchStatus status = BatchStatus.valueOf(batch.status());
            lineage.assertNoRecalledAncestor(batchKey);
            if (status == BatchStatus.REJECTED || status == BatchStatus.RELEASED
                    || status == BatchStatus.RECALLED || status == BatchStatus.SPLIT) {
                throw ApiException.conflict("批次状态 " + status + " 不允许提交测量向量");
            }
            if (batch.specKey() == null) {
                throw ApiException.unprocessable("批次未绑定特性规范: " + batchKey);
            }
            var existing = specRepo.findMeasurement(batchKey, req.measurementKey());
            if (existing.isPresent()) {
                SpecRepository.MeasurementRow row = existing.get();
                boolean sameContent = row.specKey().equals(batch.specKey())
                        && row.specVersion() == batch.specVersion()
                        && row.operator().equals(req.operator())
                        && canonicalVector(row.vectorJson()).equals(canonicalVector(req.values()));
                if (!sameContent) {
                    throw ApiException.conflict(
                            "measurementKey 已以不同内容提交: " + req.measurementKey());
                }
                return new StoredResponse(200, toJson(toMeasurementResponse(row)));
            }
            SpecDefinition spec = specRepo.loadSpec(batch.specKey(), batch.specVersion())
                    .orElseThrow(() -> ApiException.unprocessable(
                            "绑定的规范版本不存在: " + batch.specKey() + " v" + batch.specVersion()));
            if (req.values().size() != spec.features().size()) {
                throw ApiException.badRequest("测量向量缺特性：规范需要 " + spec.features().size()
                        + " 个值，实收 " + req.values().size());
            }
            if (specRepo.findPassMeasurement(batchKey, batch.specKey(),
                    batch.specVersion()).isPresent()) {
                throw ApiException.conflict("当前规范版本已存在 PASS 测量向量");
            }
            boolean pass = SpecEvaluator.passes(spec, req.values());
            String now = Instant.now().toString();
            BatchStatus newStatus = status;
            if (!pass) {
                newStatus = BatchStatus.REJECTED;
            } else if (status == BatchStatus.QUARANTINED && allRequiredPassed(batchKey)) {
                newStatus = BatchStatus.PENDING_RELEASE;
            }
            specRepo.insertMeasurement(new SpecRepository.MeasurementRow(0L, batchKey,
                    req.measurementKey(), batch.specKey(), batch.specVersion(),
                    pass ? "PASS" : "FAIL", newStatus.name(), req.operator(),
                    toJson(req.values()), now));
            if (newStatus != status) {
                batchRepo.updateStatus(batchKey, newStatus.name());
            }
            MeasurementResponse body = new MeasurementResponse(batchKey, req.measurementKey(),
                    batch.specKey(), batch.specVersion(), req.values(),
                    pass ? "PASS" : "FAIL", req.operator(), newStatus, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 显式换版：仅未放行（QUARANTINED/PENDING_RELEASE）且尚无任何批准的批次可换；
     * expectedVersion 须等于当前绑定版本（乐观并发）；旧向量保留但不再作为放行依据，
     * 新版本无 PASS 向量时批次回退 QUARANTINED。
     */
    public StoredResponse rebind(String batchKey, RebindSpecRequest req) {
        String fingerprint = IdempotentExecutor.fingerprint("rebind", batchKey,
                req.expectedVersion().toString(), req.newVersion().toString(), req.operator());
        return idempotent.execute(CMD_REBIND, req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = idempotent.logged(CMD_REBIND, req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            lineage.assertNoRecalledAncestor(batchKey);
            if (batch.specKey() == null) {
                throw ApiException.unprocessable("批次未绑定特性规范，不能换版: " + batchKey);
            }
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status != BatchStatus.QUARANTINED && status != BatchStatus.PENDING_RELEASE) {
                throw ApiException.conflict("批次状态 " + status + " 不允许换版，仅未放行批次可换版");
            }
            if (!batchRepo.findApprovals(batchKey).isEmpty()) {
                throw ApiException.conflict("批次已存在批准记录，不能换版: " + batchKey);
            }
            if (batch.specVersion() != req.expectedVersion()) {
                throw ApiException.conflict("expectedVersion 与当前绑定版本不符，当前为 "
                        + batch.specVersion());
            }
            if (req.newVersion() == batch.specVersion()) {
                throw ApiException.badRequest("newVersion 与当前绑定版本相同: " + req.newVersion());
            }
            if (!specRepo.existsVersion(batch.specKey(), req.newVersion())) {
                throw ApiException.unprocessable(
                        "规范版本不存在: " + batch.specKey() + " v" + req.newVersion());
            }
            String now = Instant.now().toString();
            int previousVersion = batch.specVersion();
            batchRepo.updateSpecVersion(batchKey, req.newVersion());
            BatchStatus newStatus = status;
            if (status == BatchStatus.PENDING_RELEASE
                    && specRepo.findPassMeasurement(batchKey, batch.specKey(),
                    req.newVersion()).isEmpty()) {
                newStatus = BatchStatus.QUARANTINED;
                batchRepo.updateStatus(batchKey, newStatus.name());
            }
            RebindSpecResponse body = new RebindSpecResponse(batchKey, batch.specKey(),
                    previousVersion, req.newVersion(), newStatus, req.operator(),
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 创建批次时校验：引用的规范版本必须存在。
     */
    public void requireSpecVersion(String specKey, int specVersion) {
        if (!specRepo.existsVersion(specKey, specVersion)) {
            throw ApiException.unprocessable("规范版本不存在: " + specKey + " v" + specVersion);
        }
    }

    /**
     * 批次当前是否满足规范放行前提：未绑定规范视为满足；绑定批次须当前版本存在 PASS 向量。
     */
    public boolean specSatisfied(BatchRepository.BatchRow batch) {
        if (batch.specKey() == null) {
            return true;
        }
        return specRepo.findPassMeasurement(batch.batchKey(), batch.specKey(),
                batch.specVersion()).isPresent();
    }

    /**
     * 批准前校验：绑定规范的批次必须当前绑定版本存在 PASS 测量向量。
     */
    public void assertReleaseReady(BatchRepository.BatchRow batch) {
        if (batch.specKey() != null && !specSatisfied(batch)) {
            throw ApiException.unprocessable("绑定规范 " + batch.specKey() + " v"
                    + batch.specVersion() + " 尚无 PASS 测量向量，不能批准");
        }
    }

    /**
     * 最终批准（RELEASED）时冻结完整系数、上界与 PASS 向量快照；未绑定规范的批次不冻结。
     */
    public void freezeRelease(BatchRepository.BatchRow batch, String now) {
        if (batch.specKey() == null) {
            return;
        }
        SpecDefinition spec = specRepo.loadSpec(batch.specKey(), batch.specVersion())
                .orElseThrow(() -> ApiException.unprocessable(
                        "绑定的规范版本不存在: " + batch.specKey() + " v" + batch.specVersion()));
        SpecRepository.MeasurementRow passRow = specRepo.findPassMeasurement(
                        batch.batchKey(), batch.specKey(), batch.specVersion())
                .orElseThrow(() -> ApiException.unprocessable(
                        "绑定规范版本无 PASS 测量向量，不能冻结放行"));
        specRepo.insertFreeze(batch.batchKey(), batch.specKey(), batch.specVersion(),
                toJson(spec.constraints()), passRow.vectorJson(), now);
    }

    /**
     * 批次全部测量向量记录（按提交顺序），历史不因换版改写。
     */
    public List<MeasurementResponse> listMeasurements(String batchKey) {
        return specRepo.findMeasurements(batchKey).stream()
                .map(this::toMeasurementResponse)
                .toList();
    }

    private MeasurementResponse toMeasurementResponse(SpecRepository.MeasurementRow row) {
        return new MeasurementResponse(row.batchKey(), row.measurementKey(), row.specKey(),
                row.specVersion(), parseVector(row.vectorJson()), row.outcome(), row.operator(),
                BatchStatus.valueOf(row.batchStatus()), Instant.parse(row.createdAt()));
    }

    private boolean allRequiredPassed(String batchKey) {
        Set<String> passed = new HashSet<>();
        for (BatchRepository.TestRow t : batchRepo.findTests(batchKey)) {
            if (TestOutcome.PASS.name().equals(t.outcome())) {
                passed.add(t.testItem());
            }
        }
        return passed.containsAll(batchRepo.findRequiredTests(batchKey));
    }

    private String canonicalVector(String vectorJson) {
        return String.join("|", parseVector(vectorJson).stream()
                .map(SpecEvaluator::canonicalDecimal).toList());
    }

    private String canonicalVector(List<BigDecimal> values) {
        return String.join("|", values.stream().map(SpecEvaluator::canonicalDecimal).toList());
    }

    private List<BigDecimal> parseVector(String vectorJson) {
        try {
            return objectMapper.readValue(vectorJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class,
                            BigDecimal.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("测量向量反序列化失败", e);
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
