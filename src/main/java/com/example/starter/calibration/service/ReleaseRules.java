package com.example.starter.calibration.service;

import java.util.ArrayList;
import java.util.List;

import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;

/**
 * 放行共享校验规则：同仪器批量放行与跨仪器联合批次放行共用同一套单项判定。
 * 规则：状态为待放行、判定合格、关联证书未撤销、放行人不同于提交人。
 */
final class ReleaseRules {

    private ReleaseRules() {
    }

    /**
     * 评估单条测量的放行条件，返回不满足的原因码列表；空列表表示全部满足。
     * 原因码：ALREADY_RELEASED / NOT_PENDING / NOT_PASSED / CERTIFICATE_REVOKED / SAME_ACTOR。
     *
     * @param measurement 当前（持锁后重读的）测量记录
     * @param certificate 当前（持锁后重读的）关联证书
     * @param releaser    放行人
     */
    static List<String> evaluate(Measurement measurement, Certificate certificate, String releaser) {
        List<String> reasons = new ArrayList<>();
        if (measurement.status() == MeasurementStatus.RELEASED) {
            reasons.add("ALREADY_RELEASED");
        } else if (measurement.status() != MeasurementStatus.PENDING) {
            reasons.add("NOT_PENDING");
        }
        if (!measurement.passed()) {
            reasons.add("NOT_PASSED");
        }
        if (certificate.revoked()) {
            reasons.add("CERTIFICATE_REVOKED");
        }
        if (measurement.submittedBy().equals(releaser)) {
            reasons.add("SAME_ACTOR");
        }
        return reasons;
    }
}
