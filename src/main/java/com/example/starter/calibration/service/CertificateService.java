package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.CreateCertificateRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CertificateType;
import com.example.starter.calibration.model.CurveNode;
import com.example.starter.calibration.repo.CertificateRepository;

/**
 * 校准证书服务：创建（区间不重叠，并发安全）、撤销、查询。
 * 支持线性（LINEAR）与分段线性曲线（CURVE）两类证书；曲线证书创建后不可修改，仅可撤销。
 */
@Service
public class CertificateService {

    /** 曲线节点数下限。 */
    static final int MIN_NODES = 3;
    /** 曲线节点数上限。 */
    static final int MAX_NODES = 8;

    private final CertificateRepository certificates;

    public CertificateService(CertificateRepository certificates) {
        this.certificates = certificates;
    }

    /**
     * 创建证书。同一仪器未撤销证书（不分类型）区间不得重叠（相邻合法）；
     * 通过仪器级行锁串行化，并发创建重叠证书时最多一张成功。
     */
    @Transactional
    public CertificateResponse create(CreateCertificateRequest request) {
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant validFrom = Inputs.requireInstant(request.validFrom(), "validFrom");
        Instant validTo = Inputs.requireInstant(request.validTo(), "validTo");
        CertificateType type = parseType(request.type());
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("validFrom 必须早于 validTo");
        }

        BigDecimal a = null;
        BigDecimal b = null;
        List<CurveNode> nodes = null;
        if (type == CertificateType.LINEAR) {
            if (request.nodes() != null && !request.nodes().isEmpty()) {
                throw ApiException.badRequest("LINEAR 证书不允许携带 nodes");
            }
            a = Inputs.requireDecimal(request.a(), "a");
            b = Inputs.requireDecimal(request.b(), "b");
        } else {
            if (request.a() != null || request.b() != null) {
                throw ApiException.badRequest("CURVE 证书不允许携带 a/b");
            }
            nodes = parseNodes(request.nodes());
        }

        certificates.lockInstrument(instrumentId);
        if (certificates.countActiveOverlap(instrumentId, validFrom, validTo) > 0) {
            throw ApiException.conflict("CERTIFICATE_OVERLAP", "同一仪器存在区间重叠的未撤销证书");
        }
        long id = certificates.insert(instrumentId, validFrom, validTo, type, a, b, Instant.now());
        if (type == CertificateType.CURVE) {
            certificates.insertNodes(id, nodes);
        }
        Certificate cert = certificates.findById(id).orElseThrow();
        return DtoMapper.toResponse(cert, type == CertificateType.CURVE ? nodes : List.of());
    }

    /**
     * 撤销证书。不存在返回 404；重复撤销返回 409。
     * 与批量放行并发时按事务提交顺序生效：撤销先提交则放行整批拒绝。
     */
    @Transactional
    public CertificateResponse revoke(long id) {
        Certificate cert = certificates.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + id));
        if (cert.revoked()) {
            throw ApiException.conflict("ALREADY_REVOKED", "证书已撤销: " + id);
        }
        certificates.markRevoked(id, Instant.now());
        return toResponse(certificates.findById(id).orElseThrow());
    }

    /**
     * 按 ID 查询证书，不存在返回 404。
     */
    @Transactional(readOnly = true)
    public CertificateResponse get(long id) {
        return toResponse(certificates.findById(id)
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + id)));
    }

    private CertificateResponse toResponse(Certificate cert) {
        List<CurveNode> nodes = cert.certType() == CertificateType.CURVE
                ? certificates.findNodes(cert.id())
                : List.of();
        return DtoMapper.toResponse(cert, nodes);
    }

    private static CertificateType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return CertificateType.LINEAR;
        }
        try {
            return CertificateType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("type 必须为 LINEAR 或 CURVE");
        }
    }

    /**
     * 校验并解析曲线节点：3～8 个，均最多 6 位小数，原始值严格递增。
     */
    private static List<CurveNode> parseNodes(List<CreateCertificateRequest.NodeRequest> rawNodes) {
        if (rawNodes == null || rawNodes.size() < MIN_NODES || rawNodes.size() > MAX_NODES) {
            throw ApiException.badRequest("CURVE 证书节点数必须为 " + MIN_NODES + "～" + MAX_NODES);
        }
        List<CurveNode> nodes = new ArrayList<>(rawNodes.size());
        BigDecimal previousRaw = null;
        for (int i = 0; i < rawNodes.size(); i++) {
            CreateCertificateRequest.NodeRequest node = rawNodes.get(i);
            if (node == null) {
                throw ApiException.badRequest("nodes[" + i + "] 不能为空");
            }
            BigDecimal raw = Inputs.requireDecimal(node.raw(), "nodes[" + i + "].raw");
            BigDecimal corrected = Inputs.requireDecimal(node.corrected(), "nodes[" + i + "].corrected");
            if (previousRaw != null && raw.compareTo(previousRaw) <= 0) {
                throw ApiException.badRequest("曲线节点原始值必须严格递增");
            }
            previousRaw = raw;
            nodes.add(new CurveNode(i, raw, corrected));
        }
        return nodes;
    }
}
