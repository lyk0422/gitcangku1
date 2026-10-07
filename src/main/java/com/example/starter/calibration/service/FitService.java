package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.FitApproveRequest;
import com.example.starter.calibration.api.dto.FitPointInput;
import com.example.starter.calibration.api.dto.FitResponse;
import com.example.starter.calibration.api.dto.FitSubmitRequest;
import com.example.starter.calibration.model.FitPoint;
import com.example.starter.calibration.model.FitRequestRecord;
import com.example.starter.calibration.model.FitStatus;
import com.example.starter.calibration.model.ReferenceFit;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.FitRepository;
import com.example.starter.calibration.repo.FitRequestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 参考点线性拟合服务：提交（精确有理数最小二乘、残差上限校验、幂等占键）、
 * 批准（另一名放行人显式批准，一次创建不可变证书）、历史查询。
 * 系数 a=(nΣxy-ΣxΣy)/(nΣx²-(Σx)²)，b=(Σy-aΣx)/n，全程 BigDecimal 精确计算，
 * 不能精确表示为最多 6 位小数时拒绝（422），不强行舍入。
 */
@Service
public class FitService {

    /** 参考点对数量下限。 */
    static final int MIN_POINTS = 3;
    /** 参考点对数量上限。 */
    static final int MAX_POINTS = 8;

    private static final String OP_SUBMIT = "FIT_SUBMIT";
    private static final String OP_APPROVE = "FIT_APPROVE";

    /** 解析后的参考点。 */
    private record XY(BigDecimal x, BigDecimal y) {
    }

    private final FitRepository fits;
    private final FitRequestRepository requests;
    private final CertificateRepository certificates;
    private final ObjectMapper objectMapper;

    public FitService(FitRepository fits,
                      FitRequestRepository requests,
                      CertificateRepository certificates,
                      ObjectMapper objectMapper) {
        this.fits = fits;
        this.requests = requests;
        this.certificates = certificates;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交拟合。校验通过且残差均不超上限才冻结参考点与评分并占幂等键；
     * 任何失败随事务回滚不占键。同 requestId 同参重放首次完整响应，异参 409。
     */
    @Transactional
    public String submit(FitSubmitRequest request) {
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant validFrom = Inputs.requireInstant(request.validFrom(), "validFrom");
        Instant validTo = Inputs.requireInstant(request.validTo(), "validTo");
        BigDecimal limit = Inputs.requireDecimal(request.residualLimit(), "residualLimit");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("validFrom 必须早于 validTo");
        }
        if (limit.signum() < 0) {
            throw ApiException.badRequest("residualLimit 必须为非负残差上限");
        }
        List<FitPointInput> rawPoints = request.points();
        if (rawPoints == null || rawPoints.size() < MIN_POINTS || rawPoints.size() > MAX_POINTS) {
            throw ApiException.badRequest("points 必须为 " + MIN_POINTS + "～" + MAX_POINTS + " 对参考点");
        }
        List<XY> points = new ArrayList<>();
        TreeSet<BigDecimal> seenX = new TreeSet<>();
        for (FitPointInput raw : rawPoints) {
            BigDecimal x = Inputs.requireDecimal(raw == null ? null : raw.x(), "points[].x");
            BigDecimal y = Inputs.requireDecimal(raw == null ? null : raw.y(), "points[].y");
            if (!seenX.add(x)) {
                throw ApiException.badRequest("参考点 x 互不相同，存在重复: " + x.toPlainString());
            }
            points.add(new XY(x, y));
        }

        int n = points.size();
        BigDecimal nn = BigDecimal.valueOf(n);
        BigDecimal sx = BigDecimal.ZERO;
        BigDecimal sy = BigDecimal.ZERO;
        BigDecimal sxy = BigDecimal.ZERO;
        BigDecimal sxx = BigDecimal.ZERO;
        for (XY p : points) {
            sx = sx.add(p.x());
            sy = sy.add(p.y());
            sxy = sxy.add(p.x().multiply(p.y()));
            sxx = sxx.add(p.x().multiply(p.x()));
        }
        BigDecimal denominator = nn.multiply(sxx).subtract(sx.multiply(sx));
        if (denominator.signum() == 0) {
            throw ApiException.badRequest("拟合分母为 0（参考点 x 无有效离散度）");
        }
        BigDecimal a = exactCoefficient(nn.multiply(sxy).subtract(sx.multiply(sy)), denominator, "a");
        BigDecimal b = exactCoefficient(sy.subtract(a.multiply(sx)), nn, "b");

        List<BigDecimal> residuals = new ArrayList<>();
        BigDecimal maxAbsResidual = BigDecimal.ZERO;
        for (XY p : points) {
            BigDecimal residual = p.y().subtract(a.multiply(p.x()).add(b));
            residuals.add(residual);
            BigDecimal abs = residual.abs();
            if (abs.compareTo(limit) > 0) {
                throw ApiException.unprocessable("RESIDUAL_LIMIT_EXCEEDED",
                        "参考点残差超过上限: |" + abs.toPlainString() + "| > " + limit.toPlainString());
            }
            maxAbsResidual = maxAbsResidual.max(abs);
        }

        String canonical = canonicalSubmit(instrumentId, validFrom, validTo, limit,
                request.expectedVersion(), submittedBy, points);
        String replay = tryRegister(requestId, OP_SUBMIT, instrumentId, canonical);
        if (replay != null) {
            return replay;
        }

        ReferenceFit fit = new ReferenceFit(0L, requestId, instrumentId, validFrom, validTo, a, b, limit,
                maxAbsResidual, n, request.expectedVersion(), submittedBy, null, null,
                FitStatus.PENDING, null, Instant.now());
        long fitId = fits.insert(fit);
        for (int i = 0; i < points.size(); i++) {
            XY p = points.get(i);
            fits.insertPoint(new FitPoint(0L, fitId, i, p.x(), p.y(), residuals.get(i)));
        }
        String body = toJson(DtoMapper.toResponse(fits.findById(fitId).orElseThrow(), fits.findPoints(fitId)));
        requests.updateResponse(requestId, body);
        return body;
    }

    /**
     * 批准拟合。批准人须不同于提交人；批准时仍核验原证书不重叠规则，冲突则整次不建证书。
     * 与证书登记/撤销共用仪器级锁共同裁决；已成功批准的拟合只重放首次证书，不再签发。
     */
    @Transactional
    public String approve(long fitId, FitApproveRequest body, String actor) {
        String approver = Inputs.requireText(actor, "X-Actor-Id");
        ReferenceFit fit = fits.findByIdForUpdate(fitId)
                .orElseThrow(() -> ApiException.notFound("拟合不存在: " + fitId));
        if (fit.status() == FitStatus.APPROVED) {
            return toJson(DtoMapper.toResponse(fit, fits.findPoints(fitId)));
        }
        if (fit.submittedBy().equals(approver)) {
            throw ApiException.conflict("SAME_ACTOR", "批准人不能与提交人相同");
        }
        String requestId = body == null || body.requestId() == null || body.requestId().isBlank()
                ? null : body.requestId().trim();
        if (requestId != null) {
            String canonical = "op=" + OP_APPROVE + "|fit=" + fitId + "|approver=" + approver;
            String replay = tryRegister(requestId, OP_APPROVE, fit.instrumentId(), canonical);
            if (replay != null) {
                return replay;
            }
        }

        certificates.lockInstrument(fit.instrumentId());
        if (certificates.countActiveOverlap(fit.instrumentId(), fit.validFrom(), fit.validTo()) > 0) {
            throw ApiException.conflict("CERTIFICATE_OVERLAP", "同一仪器存在区间重叠的未撤销证书");
        }
        long certificateId = certificates.insert(fit.instrumentId(), fit.validFrom(), fit.validTo(),
                fit.a(), fit.b(), Instant.now());
        fits.markApproved(fitId, approver, Instant.now(), certificateId);
        String responseBody = toJson(DtoMapper.toResponse(fits.findById(fitId).orElseThrow(),
                fits.findPoints(fitId)));
        if (requestId != null) {
            requests.updateResponse(requestId, responseBody);
        }
        return responseBody;
    }

    /**
     * 按拟合 ID 查询历史（含冻结参考点与评分）；不存在返回 404。撤证不改写历史。
     */
    @Transactional(readOnly = true)
    public FitResponse get(long fitId) {
        ReferenceFit fit = fits.findById(fitId)
                .orElseThrow(() -> ApiException.notFound("拟合不存在: " + fitId));
        return DtoMapper.toResponse(fit, fits.findPoints(fitId));
    }

    /**
     * 精确相除并要求结果最多 6 位小数；不能精确表示时抛 422，不强行舍入。
     */
    private static BigDecimal exactCoefficient(BigDecimal numerator, BigDecimal denominator, String field) {
        final BigDecimal quotient;
        try {
            quotient = numerator.divide(denominator);
        } catch (ArithmeticException ex) {
            throw ApiException.unprocessable("COEFFICIENT_NOT_REPRESENTABLE",
                    "拟合系数 " + field + " 不能精确表示为有限小数");
        }
        if (quotient.stripTrailingZeros().scale() > 6) {
            throw ApiException.unprocessable("COEFFICIENT_NOT_REPRESENTABLE",
                    "拟合系数 " + field + " 不能精确表示为最多 6 位小数: " + quotient.toPlainString());
        }
        return quotient;
    }

    /**
     * 登记幂等键。已存在时结构化比较 操作+资源+规范化全参数：
     * 一致返回首次完整响应（重放），不一致抛 409；返回 null 表示本次新占键。
     * 并发竞争由唯一键兜底，不向调用方泄漏唯一键异常。
     */
    private String tryRegister(String requestId, String operation, String resource, String canonical) {
        try {
            requests.insertPending(requestId, operation, resource, canonical, Instant.now());
            return null;
        } catch (DuplicateKeyException ex) {
            FitRequestRecord existing = requests.findById(requestId).orElseThrow();
            if (existing.operation().equals(operation)
                    && existing.resource().equals(resource)
                    && existing.canonicalParams().equals(canonical)) {
                return existing.responseBody();
            }
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "请求 ID 已用于其他参数: " + requestId);
        }
    }

    /**
     * 规范化全参数：十进制按值（去尾零）、时间按 UTC 归一、文本原样。
     */
    private static String canonicalSubmit(String instrumentId, Instant from, Instant to, BigDecimal limit,
                                          Long expectedVersion, String submittedBy, List<XY> points) {
        StringBuilder sb = new StringBuilder("op=" + OP_SUBMIT)
                .append("|instrument=").append(instrumentId)
                .append("|from=").append(from)
                .append("|to=").append(to)
                .append("|limit=").append(limit.stripTrailingZeros().toPlainString())
                .append("|expectedVersion=").append(expectedVersion)
                .append("|submittedBy=").append(submittedBy)
                .append("|points=[");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('(').append(points.get(i).x().stripTrailingZeros().toPlainString())
                    .append(',').append(points.get(i).y().stripTrailingZeros().toPlainString())
                    .append(')');
        }
        return sb.append(']').toString();
    }

    private String toJson(FitResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("拟合响应序列化失败", ex);
        }
    }
}
