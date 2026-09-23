package com.example.starter.calibration.service;

import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CreateStandardRequest;
import com.example.starter.calibration.api.dto.StandardResponse;
import com.example.starter.calibration.model.StandardVersion;
import com.example.starter.calibration.repo.StandardRepository;

/**
 * 标准器版本服务：创建（血缘无环、子级窗口不得超出父级窗口，并发安全）与查询。
 */
@Service
public class StandardService {

    private final StandardRepository standards;

    public StandardService(StandardRepository standards) {
        this.standards = standards;
    }

    /**
     * 创建标准器版本。父级必须存在；子级窗口不得超出父级窗口；血缘必须无环。
     * 通过血缘全局锁串行化，与测量绑定、失效激活按事务提交顺序互斥。
     */
    @Transactional
    public StandardResponse create(CreateStandardRequest request) {
        String standardId = Inputs.requireText(request.standardId(), "standardId");
        Instant validFrom = Inputs.requireInstant(request.validFrom(), "validFrom");
        Instant validTo = Inputs.requireInstant(request.validTo(), "validTo");
        String certificateNo = Inputs.requireText(request.certificateNo(), "certificateNo");
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("validFrom 必须早于 validTo");
        }
        String parentStandardId = request.parentStandardId() == null || request.parentStandardId().isBlank()
                ? null : request.parentStandardId().trim();
        if (standardId.equals(parentStandardId)) {
            throw ApiException.conflict("LINEAGE_CYCLE", "标准器版本不能作为自身的上级: " + standardId);
        }

        standards.lockLineage();
        if (parentStandardId != null) {
            StandardVersion parent = standards.findByStandardId(parentStandardId)
                    .orElseThrow(() -> ApiException.unprocessable("PARENT_STANDARD_NOT_FOUND",
                            "上级标准器版本不存在: " + parentStandardId));
            if (validFrom.isBefore(parent.validFrom()) || validTo.isAfter(parent.validTo())) {
                throw ApiException.conflict("WINDOW_EXCEEDS_PARENT",
                        "子级窗口不得超出父级窗口: " + standardId);
            }
            assertNoCycle(standardId, parent);
        }

        long id;
        try {
            id = standards.insert(standardId, parentStandardId, validFrom, validTo,
                    certificateNo, Instant.now());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_STANDARD_ID", "标准器版本业务键已存在: " + standardId);
        }
        return toResponse(standards.findById(id).orElseThrow());
    }

    /**
     * 按业务键查询标准器版本，不存在返回 404。
     */
    @Transactional(readOnly = true)
    public StandardResponse get(String standardId) {
        return toResponse(standards.findByStandardId(standardId)
                .orElseThrow(() -> ApiException.notFound("标准器版本不存在: " + standardId)));
    }

    /**
     * 血缘无环校验：从父级沿血缘向上，若遇到待创建的 standardId 则成环。
     */
    private void assertNoCycle(String standardId, StandardVersion parent) {
        StandardVersion current = parent;
        while (current != null) {
            if (current.standardId().equals(standardId)) {
                throw ApiException.conflict("LINEAGE_CYCLE", "血缘存在环: " + standardId);
            }
            current = current.parentStandardId() == null ? null
                    : standards.findByStandardId(current.parentStandardId()).orElse(null);
        }
    }

    static StandardResponse toResponse(StandardVersion version) {
        return new StandardResponse(
                version.id(),
                version.standardId(),
                version.parentStandardId(),
                version.validFrom(),
                version.validTo(),
                version.certificateNo(),
                version.status().name(),
                version.version(),
                version.createdAt());
    }
}
