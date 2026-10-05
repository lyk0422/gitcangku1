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
import com.example.starter.calibration.api.dto.NodeDto;
import com.example.starter.calibration.api.dto.RevokeRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CertificateNode;
import com.example.starter.calibration.model.CertificateType;
import com.example.starter.calibration.repo.CertificateNodeRepository;
import com.example.starter.calibration.repo.CertificateRepository;

/**
 * 校准证书服务：创建（线性与曲线，区间不重叠，并发安全）、撤销、查询。
 * 写操作经幂等去重包装：requestId 同键同参重放首次响应，异参 409，失败不占键。
 */
@Service
public class CertificateService {

    /** 曲线节点数量下限。 */
    static final int MIN_NODES = 3;
    /** 曲线节点数量上限。 */
    static final int MAX_NODES = 8;

    private final CertificateRepository certificates;
    private final CertificateNodeRepository nodes;
    private final IdempotencyService idempotency;

    public CertificateService(CertificateRepository certificates,
                              CertificateNodeRepository nodes,
                              IdempotencyService idempotency) {
        this.certificates = certificates;
        this.nodes = nodes;
        this.idempotency = idempotency;
    }

    /**
     * 创建证书（幂等入口）。同一仪器未撤销证书（不分类型）区间不得重叠（相邻合法）；
     * 通过仪器级行锁串行化，并发创建重叠证书时最多一张成功。
     */
    public CertificateResponse create(CreateCertificateRequest request, String operator) {
        return idempotency.execute(request.requestId(), "CREATE_CERTIFICATE", operator,
                "instrument:" + request.instrumentId(), request, request.expectedVersion(),
                () -> doCreate(request), CertificateResponse.class);
    }

    /**
     * 撤销证书（幂等入口）。不存在返回 404；重复撤销返回 409。
     * 与批量放行并发时按事务提交顺序生效：撤销先提交则放行整批拒绝。
     */
    public CertificateResponse revoke(long id, RevokeRequest request, String operator) {
        String requestId = request == null ? null : request.requestId();
        Long expectedVersion = request == null ? null : request.expectedVersion();
        return idempotency.execute(requestId, "REVOKE_CERTIFICATE", operator,
                "certificate:" + id, java.util.Map.of("certificateId", id), expectedVersion,
                () -> doRevoke(id), CertificateResponse.class);
    }

    /**
     * 按 ID 查询证书，不存在返回 404；曲线证书附带节点列表。
     */
    @Transactional(readOnly = true)
    public CertificateResponse get(long id) {
        Certificate cert = certificates.findById(id)
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + id));
        return DtoMapper.toResponse(cert, nodesOf(cert));
    }

    /**
     * 创建证书业务实现（在幂等事务内执行）。曲线证书需提供 3～8 个原始值严格递增的节点，
     * 且不得同时提供 a/b；线性证书必须提供 a/b。
     */
    private CertificateResponse doCreate(CreateCertificateRequest request) {
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant validFrom = Inputs.requireInstant(request.validFrom(), "validFrom");
        Instant validTo = Inputs.requireInstant(request.validTo(), "validTo");
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("validFrom 必须早于 validTo");
        }

        boolean curve = request.nodes() != null;
        BigDecimal a = null;
        BigDecimal b = null;
        List<CertificateNode> nodeList = null;
        if (curve) {
            if (request.a() != null || request.b() != null) {
                throw ApiException.badRequest("曲线证书不得同时提供 a/b");
            }
            nodeList = validateNodes(request.nodes());
        } else {
            a = Inputs.requireDecimal(request.a(), "a");
            b = Inputs.requireDecimal(request.b(), "b");
        }

        certificates.lockInstrument(instrumentId);
        if (certificates.countActiveOverlap(instrumentId, validFrom, validTo) > 0) {
            throw ApiException.conflict("CERTIFICATE_OVERLAP", "同一仪器存在区间重叠的未撤销证书");
        }
        CertificateType type = curve ? CertificateType.CURVE : CertificateType.LINEAR;
        long id = certificates.insert(instrumentId, type, validFrom, validTo, a, b, Instant.now());
        if (nodeList != null) {
            for (CertificateNode node : nodeList) {
                nodes.insert(id, node.nodeIndex(), node.raw(), node.corrected());
            }
            nodeList = nodes.findByCertificateId(id);
        }
        return DtoMapper.toResponse(certificates.findById(id).orElseThrow(), nodeList);
    }

    /**
     * 撤销证书业务实现（在幂等事务内执行）。
     */
    private CertificateResponse doRevoke(long id) {
        Certificate cert = certificates.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + id));
        if (cert.revoked()) {
            throw ApiException.conflict("ALREADY_REVOKED", "证书已撤销: " + id);
        }
        certificates.markRevoked(id, Instant.now());
        Certificate revoked = certificates.findById(id).orElseThrow();
        return DtoMapper.toResponse(revoked, nodesOf(revoked));
    }

    /**
     * 校验曲线节点：3～8 个，原始值与校正值均为最多 6 位小数的十进制，原始值严格递增。
     */
    private List<CertificateNode> validateNodes(List<NodeDto> dtos) {
        if (dtos.size() < MIN_NODES || dtos.size() > MAX_NODES) {
            throw ApiException.badRequest("曲线节点数量必须为 " + MIN_NODES + "～" + MAX_NODES);
        }
        List<CertificateNode> result = new ArrayList<>();
        BigDecimal previousRaw = null;
        for (int i = 0; i < dtos.size(); i++) {
            NodeDto dto = dtos.get(i);
            if (dto == null) {
                throw ApiException.badRequest("nodes[" + i + "] 不能为空");
            }
            BigDecimal raw = Inputs.requireDecimal(dto.raw(), "nodes[" + i + "].raw");
            BigDecimal corrected = Inputs.requireDecimal(dto.corrected(), "nodes[" + i + "].corrected");
            if (previousRaw != null && raw.compareTo(previousRaw) <= 0) {
                throw ApiException.badRequest("曲线节点原始值必须严格递增");
            }
            previousRaw = raw;
            result.add(new CertificateNode(0L, i, raw, corrected));
        }
        return result;
    }

    /**
     * 加载曲线证书节点；线性证书返回 null。
     */
    private List<CertificateNode> nodesOf(Certificate cert) {
        return cert.certType() == CertificateType.CURVE ? nodes.findByCertificateId(cert.id()) : null;
    }
}
