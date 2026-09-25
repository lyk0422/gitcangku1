package com.example.starter.calibration.service;

import java.util.ArrayList;
import java.util.List;

import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;

/**
 * 放行校验规则：单条测量是否满足放行条件。
 * 既有同仪器批量放行与跨仪器联合批次放行共用本类，保证规则一致。
 */
final class ReleaseChecks {

    private ReleaseChecks() {
    }

    /**
     * 计算单条测量的放行失败原因；返回空列表表示可放行。
     * 规则：处于待放行、判定合格、证书未撤销、放行人不同于提交人。
     */
    static List<String> reasons(Measurement measurement, Certificate cert, String releaser) {
        List<String> reasons = new ArrayList<>();
        if (measurement.status() == MeasurementStatus.RELEASED) {
            reasons.add("ALREADY_RELEASED");
        } else if (measurement.status() != MeasurementStatus.PENDING) {
            reasons.add("NOT_PENDING");
        }
        if (!measurement.passed()) {
            reasons.add("NOT_PASSED");
        }
        if (cert.revoked()) {
            reasons.add("CERTIFICATE_REVOKED");
        }
        if (measurement.submittedBy().equals(releaser)) {
            reasons.add("SAME_ACTOR");
        }
        return reasons;
    }
}
