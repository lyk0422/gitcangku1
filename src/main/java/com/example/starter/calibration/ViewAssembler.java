package com.example.starter.calibration;

import java.util.List;

/**
 * 查询视图装配：把实体记录组装为带天线编号、任务编号的展示视图。
 */
public final class ViewAssembler {

    private ViewAssembler() {
    }

    /** 组装绑定视图。 */
    public static BindingView toBindingView(TaskBinding b, String taskCode, String antennaCode) {
        return new BindingView(b.id(), b.requestId(), b.taskId(), taskCode, b.calibrationId(),
                antennaCode, b.azimuthDeviationDeg(), b.elevationDeviationDeg(),
                b.status(), b.calibrationInvalidated(), b.createdAt());
    }

    /** 组装校准视图。 */
    public static CalibrationView toCalibrationView(AntennaCalibration c, String antennaCode,
            List<BindingView> bindings) {
        return new CalibrationView(c.id(), antennaCode, c.calibratedAt(),
                c.azimuthDeviationDeg(), c.elevationDeviationDeg(), c.passed(),
                c.validFrom(), c.validUntil(), c.status(), c.invalidatedAt(),
                c.invalidateReason(), bindings);
    }

    /** 组装任务视图。 */
    public static TaskView toTaskView(ReceiveTask t, String antennaCode, List<BindingView> bindings) {
        return new TaskView(t.id(), t.taskCode(), antennaCode, t.startTime(), t.endTime(),
                t.status(), bindings);
    }
}