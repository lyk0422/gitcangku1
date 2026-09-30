package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CoefficientVersionResponse;
import com.example.starter.calibration.api.dto.CreateCoefficientVersionRequest;
import com.example.starter.calibration.model.CoefficientVersion;
import com.example.starter.calibration.repo.CoefficientVersionRepository;

/**
 * 环境补偿系数版本服务：版本只增不改。
 * 创建在 coefficient_lock 哨兵行锁内完成，与放行事务互斥，
 * 保证放行读到的生效版本集合始终是某次提交后的完整快照。
 */
@Service
public class CoefficientVersionService {

    private final CoefficientVersionRepository versions;
    private final DomainClock clock;

    public CoefficientVersionService(CoefficientVersionRepository versions, DomainClock clock) {
        this.versions = versions;
        this.clock = clock;
    }

    /**
     * 创建系数版本。生效区间左闭右开且起点必须早于终点；版本标签全局唯一（重复返回 409）。
     * 允许生效区间与其他版本重叠，重叠期内放行将因命中多个版本而 422。
     */
    @Transactional
    public CoefficientVersionResponse create(CreateCoefficientVersionRequest request) {
        String tag = Inputs.requireText(request.versionTag(), "versionTag");
        BigDecimal referenceTemperature = Inputs.requireDecimal(request.referenceTemperature(),
                "referenceTemperature");
        BigDecimal referenceHumidity = Inputs.requireDecimal(request.referenceHumidity(),
                "referenceHumidity");
        BigDecimal temperatureCoefficient = Inputs.requireDecimal(request.temperatureCoefficient(),
                "temperatureCoefficient", 12);
        BigDecimal humidityCoefficient = Inputs.requireDecimal(request.humidityCoefficient(),
                "humidityCoefficient", 12);
        Instant effectiveFrom = Inputs.requireInstant(request.effectiveFrom(), "effectiveFrom");
        Instant effectiveTo = Inputs.requireInstant(request.effectiveTo(), "effectiveTo");
        if (!effectiveFrom.isBefore(effectiveTo)) {
            throw ApiException.badRequest("effectiveFrom 必须早于 effectiveTo");
        }

        versions.lockCoefficientTable();
        CoefficientVersion version = new CoefficientVersion(
                0L, tag, referenceTemperature, referenceHumidity,
                temperatureCoefficient, humidityCoefficient,
                effectiveFrom, effectiveTo, clock.instant());
        try {
            long id = versions.insert(version);
            return DtoMapper.toResponse(versions.findById(id).orElseThrow());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_COEFFICIENT_VERSION",
                    "系数版本标签已存在: " + tag);
        }
    }

    /**
     * 按 ID 查询系数版本，不存在返回 404。
     */
    @Transactional(readOnly = true)
    public CoefficientVersionResponse get(long id) {
        return DtoMapper.toResponse(versions.findById(id)
                .orElseThrow(() -> ApiException.notFound("系数版本不存在: " + id)));
    }
}
