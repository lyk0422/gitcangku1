package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

import com.example.starter.calibration.repo.ComponentRepository;

/**
 * 合成不确定度计算：公开口径为平方和开根（RSS, root-sum-square），
 * 结果按 HALF_UP 保留 4 位小数返回；阈值判定基于该 4 位小数合成值。
 */
public final class UncertaintyCalculator {

    /** 计算口径标识，固化于预算修订与放行快照。 */
    public static final String FORMULA = "RSS-4DP-HALF_UP";

    private static final MathContext SQRT_CONTEXT = new MathContext(40, RoundingMode.HALF_UP);

    private UncertaintyCalculator() {
    }

    /**
     * 合成不确定度：sqrt(Σ value_i²)，HALF_UP 保留 4 位小数。空集合返回 0.0000。
     */
    public static BigDecimal combine(List<ComponentRepository.ComponentValue> components) {
        BigDecimal sumOfSquares = BigDecimal.ZERO;
        for (ComponentRepository.ComponentValue component : components) {
            sumOfSquares = sumOfSquares.add(component.value().multiply(component.value()));
        }
        return sumOfSquares.sqrt(SQRT_CONTEXT).setScale(4, RoundingMode.HALF_UP);
    }
}
