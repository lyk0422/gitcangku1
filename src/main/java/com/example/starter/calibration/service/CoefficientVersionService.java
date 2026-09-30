package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CoefficientVersionResponse;
import com.example.starter.calibration.api.dto.CreateCoefficientVersionRequest;
import com.example.starter.calibration.repo.CoefficientVersionRepository;
import com.example.starter.calibration.repo.InstrumentLockRepository;

/**
 * 环境补偿系数版本服务。版本只增不改：写入系数时在仪器锁事务内把当前开放版本
 * 闭合到新生效起点（右开），再追加新版本；因此同一仪器任意时刻至多一个生效版本，
 * 且与环境补偿放行并发时，放行要么引用闭合前的完整旧版本、要么引用新版本，不会读到中间态。
 */
@Service
public class CoefficientVersionService {

    private final CoefficientVersionRepository versions;
    private final InstrumentLockRepository locks;
    private final BusinessClock clock;

    public CoefficientVersionService(CoefficientVersionRepository versions,
                                     InstrumentLockRepository locks,
                                     BusinessClock clock) {
        this.versions = versions;
        this.locks = locks;
        this.clock = clock;
    }

    /**
     * 写入（更新）系数版本。effectiveFrom 省略时取业务时钟当前时刻；
     * 新生效起点必须严格晚于当前开放版本的起点，避免产生零长度或重叠区间。
     */
    @Transactional
    public CoefficientVersionResponse createOrUpdate(CreateCoefficientVersionRequest request) {
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant effectiveFrom = Inputs.optionalInstant(request.effectiveFrom(), "effectiveFrom");
        if (effectiveFrom == null) {
            effectiveFrom = clock.now();
        }
        BigDecimal refTemperature = Inputs.requireDecimal(request.refTemperature(), "refTemperature");
        BigDecimal refHumidity = Inputs.requireDecimal(request.refHumidity(), "refHumidity");
        BigDecimal temperatureCoeff = Inputs.requireDecimal(request.temperatureCoeff(), "temperatureCoeff");
        BigDecimal humidityCoeff = Inputs.requireDecimal(request.humidityCoeff(), "humidityCoeff");

        // 仪器锁串行化“闭合旧版本＋插入新版本”与放行读版本，保证版本对放行始终是完整快照。
        locks.lock(instrumentId);
        var open = versions.findOpenForUpdate(instrumentId);
        if (open.isPresent()) {
            if (!open.get().effectiveFrom().isBefore(effectiveFrom)) {
                throw ApiException.badRequest("系数新生效起点必须晚于当前开放版本起点: "
                        + open.get().effectiveFrom());
            }
            versions.closeVersion(open.get().id(), effectiveFrom);
        }
        Instant createdAt = clock.now();
        long id = versions.insert(instrumentId, effectiveFrom, refTemperature, refHumidity,
                temperatureCoeff, humidityCoeff, createdAt);
        return DtoMapper.toResponse(versions.findById(id).orElseThrow());
    }

    /**
     * 列出仪器全部系数版本（按生效起点升序）。
     */
    @Transactional(readOnly = true)
    public List<CoefficientVersionResponse> list(String instrumentId) {
        String instrument = Inputs.requireText(instrumentId, "instrumentId");
        return versions.findByInstrument(instrument).stream().map(DtoMapper::toResponse).toList();
    }
}
