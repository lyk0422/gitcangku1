package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CreateThresholdRequest;
import com.example.starter.calibration.api.dto.ThresholdResponse;
import com.example.starter.calibration.model.ThresholdVersion;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.ThresholdVersionRepository;

/**
 * 仪器不确定度阈值版本服务。版本只增不改（阈值取值不变），区间左闭右开：
 * 有界版本区间不得重叠（相邻合法）；新建开放区间版本时，同一事务内把旧开放版本的终点
 * 收敛到新版本起点（时间轴衔接），因此历史时刻仍解析到旧版本、放行快照不受影响。
 * 创建通过仪器锁串行化。
 */
@Service
public class ThresholdService {

    private final ThresholdVersionRepository thresholds;
    private final CertificateRepository certificates;

    public ThresholdService(ThresholdVersionRepository thresholds,
                            CertificateRepository certificates) {
        this.thresholds = thresholds;
        this.certificates = certificates;
    }

    /**
     * 创建阈值版本。阈值为负 422；区间重叠 409；开放区间起点不晚于既有开放区间起点 409。
     */
    @Transactional
    public ThresholdResponse create(CreateThresholdRequest request) {
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant validFrom = Inputs.requireInstant(request.validFrom(), "validFrom");
        Instant validTo = Inputs.requireOptionalInstant(request.validTo(), "validTo");
        BigDecimal max = Inputs.requireNonNegativeDecimal(request.maxUncertainty(), "maxUncertainty");
        if (validTo != null && !validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("validFrom 必须早于 validTo");
        }

        certificates.lockInstrument(instrumentId);
        List<ThresholdVersion> openEnded = thresholds.findOpenEnded(instrumentId);
        ThresholdVersion open = openEnded.isEmpty() ? null : openEnded.get(0);

        if (validTo == null) {
            // 新开放区间：必须严格晚于既有开放区间起点；同一事务内收敛旧开放区间终点。
            if (open != null && !validFrom.isAfter(open.validFrom())) {
                throw ApiException.conflict("THRESHOLD_OVERLAP",
                        "新开放区间起点必须晚于既有开放区间起点");
            }
            // 有界版本不得延伸到新开放区间起点之后（相邻合法）。
            if (thresholds.countBoundedOverlap(instrumentId, validFrom, null) > 0) {
                throw ApiException.conflict("THRESHOLD_OVERLAP", "同一仪器存在区间重叠的阈值版本");
            }
            if (open != null) {
                thresholds.closeOpenEnded(open.id(), validFrom);
            }
        } else {
            // 新有界区间：不得与既有有界版本重叠；与开放区间重叠也拒绝（仅允许完全位于其起点之前且相邻）。
            if (thresholds.countBoundedOverlap(instrumentId, validFrom, validTo) > 0
                    || (open != null && open.validFrom().isBefore(validTo))) {
                throw ApiException.conflict("THRESHOLD_OVERLAP", "同一仪器存在区间重叠的阈值版本");
            }
        }

        long version = thresholds.nextVersion(instrumentId);
        long id = thresholds.insert(instrumentId, version, validFrom, validTo, max, Instant.now());
        return DtoMapper.toResponse(thresholds.findById(id).orElseThrow());
    }

    /**
     * 按 ID 查询阈值版本，不存在 404。只读。
     */
    @Transactional(readOnly = true)
    public ThresholdResponse get(long id) {
        return DtoMapper.toResponse(thresholds.findById(id)
                .orElseThrow(() -> ApiException.notFound("阈值版本不存在: " + id)));
    }
}
