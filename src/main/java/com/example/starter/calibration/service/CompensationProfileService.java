package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CompensationProfileResponse;
import com.example.starter.calibration.api.dto.UpsertProfileRequest;
import com.example.starter.calibration.model.CompensationProfile;
import com.example.starter.calibration.repo.CompensationProfileRepository;

/**
 * 环境补偿系数版本服务：按仪器型号配置公开的线性补偿系数与温湿度适用区间。
 *
 * <p>更新只增不改：在型号级行锁内追加并激活新版本，旧版本保留供已固化测量快照；
 * 新版本只影响后续测量。同一组输入的并发/重复提交由 calcKey 幂等重放首个版本，不重复追加。
 */
@Service
public class CompensationProfileService {

    private final CompensationProfileRepository profiles;
    private final IdempotentExecutor idempotency;

    public CompensationProfileService(CompensationProfileRepository profiles, IdempotentExecutor idempotency) {
        this.profiles = profiles;
        this.idempotency = idempotency;
    }

    /**
     * 创建或更新（追加新版本）某型号的补偿系数。
     */
    @Transactional
    public CompensationProfileResponse upsert(UpsertProfileRequest request) {
        String model = Inputs.requireText(request.instrumentModel(), "instrumentModel");
        BigDecimal tempCoeff = Inputs.requireDecimal(request.tempCoeff(), "tempCoeff");
        BigDecimal humidityCoeff = Inputs.requireDecimal(request.humidityCoeff(), "humidityCoeff");
        BigDecimal tempMin = Inputs.requireDecimal(request.tempMin(), "tempMin");
        BigDecimal tempMax = Inputs.requireDecimal(request.tempMax(), "tempMax");
        BigDecimal humidityMin = Inputs.requireDecimal(request.humidityMin(), "humidityMin");
        BigDecimal humidityMax = Inputs.requireDecimal(request.humidityMax(), "humidityMax");
        if (tempMin.compareTo(tempMax) > 0 || humidityMin.compareTo(humidityMax) > 0) {
            throw ApiException.badRequest("适用区间下限不能大于上限");
        }

        String fingerprint = Fingerprints.of("UPDATE_PROFILE", model, tempCoeff, humidityCoeff,
                tempMin, tempMax, humidityMin, humidityMax);
        IdempotentExecutor.Outcome<CompensationProfileResponse> outcome = idempotency.run(
                fingerprint, "UPDATE_PROFILE", fingerprint, 201,
                CompensationProfileResponse.class,
                () -> createVersion(model, tempCoeff, humidityCoeff, tempMin, tempMax, humidityMin, humidityMax));
        return outcome.body();
    }

    private CompensationProfileResponse createVersion(String model, BigDecimal tempCoeff, BigDecimal humidityCoeff,
                                                      BigDecimal tempMin, BigDecimal tempMax,
                                                      BigDecimal humidityMin, BigDecimal humidityMax) {
        profiles.lockModel(model);
        int nextVersion = profiles.maxVersionNo(model) + 1;
        profiles.deactivateActive(model);
        CompensationProfile profile = new CompensationProfile(
                0L, model, nextVersion, tempCoeff, humidityCoeff,
                tempMin, tempMax, humidityMin, humidityMax, true, Instant.now());
        long id = profiles.insertActive(model, nextVersion, profile);
        return DtoMapper.toResponse(profiles.findById(id).orElseThrow());
    }

    /**
     * 按 ID 查询系数版本，不存在返回 404。
     */
    @Transactional(readOnly = true)
    public CompensationProfileResponse get(long id) {
        return DtoMapper.toResponse(profiles.findById(id)
                .orElseThrow(() -> ApiException.notFound("补偿系数版本不存在: " + id)));
    }

    /**
     * 查询某型号全部系数版本（按版本号升序）。
     */
    @Transactional(readOnly = true)
    public List<CompensationProfileResponse> listByModel(String model) {
        String required = Inputs.requireText(model, "instrumentModel");
        return profiles.findByModel(required).stream().map(DtoMapper::toResponse).toList();
    }
}
