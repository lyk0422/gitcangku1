package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CoefficientResponse;
import com.example.starter.calibration.api.dto.PublishCoefficientRequest;
import com.example.starter.calibration.model.CompensationCoefficient;
import com.example.starter.calibration.repo.CompensationCoefficientRepository;

/**
 * 仪器型号环境补偿系数版本服务：发布新版本（仅影响后续测量）、查询当前生效版本与版本历史。
 * 同一型号的版本发布通过型号行锁串行化，并发按事务提交顺序裁决。
 */
@Service
public class CoefficientService {

    private final CompensationCoefficientRepository coefficients;
    private final IdempotentExecutor idempotency;

    public CoefficientService(CompensationCoefficientRepository coefficients,
                              IdempotentExecutor idempotency) {
        this.coefficients = coefficients;
        this.idempotency = idempotency;
    }

    /**
     * 发布新生效系数版本。旧版本转为历史行（active_model=NULL），新版本生效；
     * 不改写任何已固化测量的系数快照。calcKey 同键同指纹重放首次结果。
     */
    public CoefficientResponse publish(PublishCoefficientRequest request) {
        ParsedCoeff parsed = parse(request);
        IdempotentExecutor.Result<CoefficientResponse> result = idempotency.execute(
                request.calcKey(), "PUBLISH_COEFFICIENT",
                () -> doPublish(parsed),
                resp -> coeffFingerprintContent(parsed)
                        + "|id=" + resp.id() + "|version=" + resp.versionNo(),
                CoefficientResponse.class);
        return result.value();
    }

    private record ParsedCoeff(String model, BigDecimal k0, BigDecimal kTemperature, BigDecimal kHumidity,
                               BigDecimal tempMin, BigDecimal tempMax,
                               BigDecimal humidityMin, BigDecimal humidityMax) {
    }

    private ParsedCoeff parse(PublishCoefficientRequest request) {
        String model = Inputs.requireText(request.instrumentModel(), "instrumentModel");
        BigDecimal k0 = Inputs.requireDecimal(request.k0(), "k0");
        BigDecimal kTemperature = Inputs.requireDecimal(request.kTemperature(), "kTemperature");
        BigDecimal kHumidity = Inputs.requireDecimal(request.kHumidity(), "kHumidity");
        BigDecimal tempMin = Inputs.requireEnvDecimal(request.tempMin(), "tempMin");
        BigDecimal tempMax = Inputs.requireEnvDecimal(request.tempMax(), "tempMax");
        BigDecimal humidityMin = Inputs.requireEnvDecimal(request.humidityMin(), "humidityMin");
        BigDecimal humidityMax = Inputs.requireEnvDecimal(request.humidityMax(), "humidityMax");
        if (tempMin.compareTo(tempMax) >= 0) {
            throw ApiException.badRequest("tempMin 必须小于 tempMax");
        }
        if (humidityMin.compareTo(humidityMax) >= 0) {
            throw ApiException.badRequest("humidityMin 必须小于 humidityMax");
        }
        if (humidityMin.compareTo(BigDecimal.ZERO) < 0
                || humidityMax.compareTo(new BigDecimal("100")) > 0) {
            throw ApiException.badRequest("湿度适用区间必须在 0～100（%RH）内");
        }
        return new ParsedCoeff(model, k0, kTemperature, kHumidity,
                tempMin, tempMax, humidityMin, humidityMax);
    }

    private String coeffFingerprintContent(ParsedCoeff p) {
        return String.join("|",
                "model=" + p.model(),
                "k0=" + p.k0().stripTrailingZeros().toPlainString(),
                "kt=" + p.kTemperature().stripTrailingZeros().toPlainString(),
                "kh=" + p.kHumidity().stripTrailingZeros().toPlainString(),
                "tmin=" + p.tempMin().stripTrailingZeros().toPlainString(),
                "tmax=" + p.tempMax().stripTrailingZeros().toPlainString(),
                "hmin=" + p.humidityMin().stripTrailingZeros().toPlainString(),
                "hmax=" + p.humidityMax().stripTrailingZeros().toPlainString());
    }

    /**
     * 发布后指纹：输入 + 新版本的 ID/版本号；重放时以首次响应中的 ID/版本号重建，须一致。
     */
    private IdempotentExecutor.Outcome<CoefficientResponse> doPublish(ParsedCoeff p) {
        coefficients.lockModel(p.model());
        int nextVersion = coefficients.maxVersionNo(p.model()) + 1;
        // 旧生效版本转历史；与新版本插入在同一事务，保证同型号始终唯一生效版本。
        coefficients.deactivateModel(p.model());
        CompensationCoefficient created = new CompensationCoefficient(
                0L, p.model(), nextVersion, p.k0(), p.kTemperature(), p.kHumidity(),
                p.tempMin(), p.tempMax(), p.humidityMin(), p.humidityMax(), Instant.now());
        long id = coefficients.insertActive(created);
        CompensationCoefficient saved = coefficients.findById(id).orElseThrow();
        CoefficientResponse response = DtoMapper.toResponse(saved);
        String fp = coeffFingerprintContent(p) + "|id=" + id + "|version=" + nextVersion;
        return IdempotentExecutor.Outcome.of(response, fp);
    }

    /**
     * 查询型号当前生效系数版本；无版本返回 404。
     */
    @Transactional(readOnly = true)
    public CoefficientResponse active(String model) {
        String m = Inputs.requireText(model, "instrumentModel");
        CompensationCoefficient coeff = coefficients.findActive(m)
                .orElseThrow(() -> ApiException.notFound("仪器型号无生效补偿系数版本: " + m));
        return DtoMapper.toResponse(coeff);
    }

    /**
     * 查询型号全部系数版本（版本号升序）。
     */
    @Transactional(readOnly = true)
    public List<CoefficientResponse> history(String model) {
        String m = Inputs.requireText(model, "instrumentModel");
        return coefficients.findByModel(m).stream().map(DtoMapper::toResponse).toList();
    }
}
