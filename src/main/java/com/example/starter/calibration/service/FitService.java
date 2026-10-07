package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.FitApproveRequest;
import com.example.starter.calibration.api.dto.FitPoint;
import com.example.starter.calibration.api.dto.FitPointResult;
import com.example.starter.calibration.api.dto.FitResponse;
import com.example.starter.calibration.api.dto.FitSubmitRequest;
import com.example.starter.calibration.model.FitStatus;
import com.example.starter.calibration.model.IdempotencyRecord;
import com.example.starter.calibration.model.ReferenceFit;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.example.starter.calibration.repo.ReferenceFitRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 参考点拟合服务：提交（精确拟合并冻结参考点与残差）、批准（创建不可变证书）、历史查询。
 * 所有写操作携带全局 requestId 幂等：同参重放首次完整响应、异参 409、失败不占键；
 * 业务写入与去重记录同事务提交，并发竞争不泄漏唯一键异常。
 */
@Service
public class FitService {

    private static final String OP_SUBMIT = "FIT_SUBMIT";
    private static final String OP_APPROVE = "FIT_APPROVE";

    private final ReferenceFitRepository fits;
    private final IdempotencyRepository idempotency;
    private final CertificateRepository certificates;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    public FitService(ReferenceFitRepository fits,
                      IdempotencyRepository idempotency,
                      CertificateRepository certificates,
                      PlatformTransactionManager transactionManager,
                      ObjectMapper objectMapper) {
        this.fits = fits;
        this.idempotency = idempotency;
        this.certificates = certificates;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 提交拟合：校验并精确计算 a/b 与残差，冻结拟合记录（PENDING）。
     * 201 首次创建；同 requestId 同参重放首次响应；异参 409；校验失败 400/422 不占键。
     */
    public FitOutcome submit(FitSubmitRequest request) {
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String fingerprint = FitFingerprints.ofSubmit(request);
        return runIdempotent(OP_SUBMIT, request.instrumentId(), request.requestId(),
                fingerprint, request.submittedBy(), () -> doSubmit(request));
    }

    /**
     * 批准拟合：批准人须不同于提交人；批准时核验区间不重叠，冲突整次不建证书。
     * 首次批准 201 并创建不可变证书；已成功批准的拟合再次批准只重放首次证书，不再签发。
     */
    public FitOutcome approve(long fitId, FitApproveRequest request, String actor) {
        String approver = Inputs.requireText(actor, "X-Actor-Id");
        Long expectedVersion = request == null ? null : request.expectedVersion();
        String fingerprint = FitFingerprints.ofApprove(fitId, approver, expectedVersion);
        return runIdempotent(OP_APPROVE, "fit:" + fitId, request == null ? null : request.requestId(),
                fingerprint, approver, () -> doApprove(fitId, approver));
    }

    /**
     * 查询拟合历史：冻结的参考点、系数与状态；证书撤销不回写本记录。不存在返回 404。
     */
    @Transactional(readOnly = true)
    public FitResponse get(long id) {
        return toResponse(fits.findById(id)
                .orElseThrow(() -> ApiException.notFound("拟合记录不存在: " + id)));
    }

    /**
     * 幂等骨架：先查去重记录（命中则重放或 409），未命中执行业务并在同事务写入响应快照；
     * 并发唯一键竞争时重查重放，不向调用方泄漏唯一键异常。
     */
    private FitOutcome runIdempotent(String operation, String resource, String requestId,
                                     String fingerprint, String actor, Supplier<FitOutcome> business) {
        String rid = Inputs.requireText(requestId, "requestId");
        String operator = Inputs.requireText(actor, "操作者");
        try {
            return txTemplate.execute(tx -> executeOnce(operation, resource, rid, fingerprint, operator, business));
        } catch (DuplicateKeyException race) {
            return afterRace(operation, resource, rid, fingerprint, operator, business);
        }
    }

    private FitOutcome executeOnce(String operation, String resource, String requestId,
                                   String fingerprint, String actor, Supplier<FitOutcome> business) {
        var existing = idempotency.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), operation, fingerprint);
        }
        FitOutcome outcome = business.get();
        idempotency.insert(new IdempotencyRecord(requestId, operation,
                resource == null ? "" : resource, fingerprint, actor,
                outcome.status(), outcome.body(), Instant.now()));
        return outcome;
    }

    /**
     * 唯一键竞争后的裁决：对方已提交则按记录重放或 409；对方已回滚则重试一次，仍冲突返回 409。
     */
    private FitOutcome afterRace(String operation, String resource, String requestId,
                                 String fingerprint, String actor, Supplier<FitOutcome> business) {
        var existing = idempotency.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), operation, fingerprint);
        }
        try {
            return txTemplate.execute(tx -> executeOnce(operation, resource, requestId, fingerprint, actor, business));
        } catch (DuplicateKeyException again) {
            return idempotency.findByRequestId(requestId)
                    .map(record -> replayOrConflict(record, operation, fingerprint))
                    .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_RACE",
                            "相同 requestId 的并发请求未决，请重试: " + requestId));
        }
    }

    /**
     * 命中去重记录：操作与全参数指纹一致（十进制按值、文本原样）则重放首次完整响应，否则 409。
     */
    private FitOutcome replayOrConflict(IdempotencyRecord record, String operation, String fingerprint) {
        if (!record.operation().equals(operation) || !record.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 携带不同参数，拒绝重放: " + record.requestId());
        }
        return new FitOutcome(record.responseStatus(), record.responseBody());
    }

    /**
     * 提交主流程（事务内）：参数校验 → 精确拟合 → 残差上限核验 → 冻结落库 → 201。
     */
    private FitOutcome doSubmit(FitSubmitRequest request) {
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant validFrom = Inputs.requireInstant(request.validFrom(), "validFrom");
        Instant validTo = Inputs.requireInstant(request.validTo(), "validTo");
        BigDecimal maxResidual = Inputs.requireDecimal(request.maxResidual(), "maxResidual");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");
        if (maxResidual.signum() < 0) {
            throw ApiException.badRequest("maxResidual 不能为负");
        }
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("validFrom 必须早于 validTo");
        }

        List<LinearFit.Point> points = parsePoints(request.points());
        LinearFit.Result result = LinearFit.fit(points);
        if (result.maxAbsResidual().compareTo(maxResidual) > 0) {
            throw ApiException.unprocessable("RESIDUAL_LIMIT_EXCEEDED",
                    "最大绝对残差 " + result.maxAbsResidual().toPlainString()
                            + " 超过上限 " + maxResidual.toPlainString() + "，拒绝签发");
        }

        List<FitPointResult> frozen = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            frozen.add(new FitPointResult(
                    DtoMapper.format(points.get(i).x()),
                    DtoMapper.format(points.get(i).y()),
                    DtoMapper.format(result.residuals().get(i))));
        }
        ReferenceFit fit = new ReferenceFit(0L, Inputs.requireText(request.requestId(), "requestId"),
                instrumentId, validFrom, validTo, maxResidual, result.a(), result.b(),
                result.maxAbsResidual(), toJson(frozen), submittedBy, FitStatus.PENDING,
                null, null, null, Instant.now());
        long id = fits.insert(fit);
        return new FitOutcome(201, toJson(toResponse(fits.findById(id).orElseThrow())));
    }

    /**
     * 批准主流程（事务内）：行锁拟合记录；已批准则重放首次证书（200）；
     * 提交人不得批准（409）；区间重叠整次不建证书（409）；成功创建不可变证书并登记（201）。
     */
    private FitOutcome doApprove(long fitId, String approver) {
        ReferenceFit fit = fits.findByIdForUpdate(fitId)
                .orElseThrow(() -> ApiException.notFound("拟合记录不存在: " + fitId));
        if (fit.status() == FitStatus.APPROVED) {
            return new FitOutcome(200, toJson(toResponse(fit)));
        }
        if (fit.submittedBy().equals(approver)) {
            throw ApiException.conflict("SAME_ACTOR", "批准人不能与拟合提交人相同: " + approver);
        }
        certificates.lockInstrument(fit.instrumentId());
        if (certificates.countActiveOverlap(fit.instrumentId(), fit.validFrom(), fit.validTo()) > 0) {
            throw ApiException.conflict("CERTIFICATE_OVERLAP", "同一仪器存在区间重叠的未撤销证书，整次不建证书");
        }
        long certificateId = certificates.insert(fit.instrumentId(), fit.validFrom(), fit.validTo(),
                fit.a(), fit.b(), Instant.now());
        fits.markApproved(fitId, certificateId, approver, Instant.now());
        return new FitOutcome(201, toJson(toResponse(fits.findById(fitId).orElseThrow())));
    }

    private List<LinearFit.Point> parsePoints(List<FitPoint> raw) {
        if (raw == null) {
            throw ApiException.badRequest("points 不能为空");
        }
        List<LinearFit.Point> points = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            FitPoint point = raw.get(i);
            if (point == null) {
                throw ApiException.badRequest("points[" + i + "] 不能为空");
            }
            points.add(new LinearFit.Point(
                    Inputs.requireDecimal(point.x(), "points[" + i + "].x"),
                    Inputs.requireDecimal(point.y(), "points[" + i + "].y")));
        }
        return points;
    }

    private FitResponse toResponse(ReferenceFit fit) {
        return new FitResponse(
                fit.id(),
                fit.requestId(),
                fit.instrumentId(),
                fit.validFrom(),
                fit.validTo(),
                DtoMapper.format(fit.maxResidual()),
                DtoMapper.format(fit.a()),
                DtoMapper.format(fit.b()),
                DtoMapper.format(fit.maxAbsResidual()),
                fromJson(fit.pointsJson()),
                fit.submittedBy(),
                fit.status().name(),
                fit.certificateId(),
                fit.approvedBy(),
                fit.approvedAt(),
                fit.createdAt());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }

    private List<FitPointResult> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<FitPointResult>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("冻结参考点反序列化失败", ex);
        }
    }
}
