package com.example.starter.service;

import com.example.starter.domain.PressureSample;
import com.example.starter.domain.RunSegment;
import com.example.starter.domain.SwitchRequest;
import java.util.List;

/**
 * 井时间线视图：运行段序列与全部切换申请（含审核冻结的采样快照和拒绝原因）。
 *
 * @param wellNo 井号
 * @param segments 运行段，按开始时刻升序
 * @param switchRequests 切换申请视图，按申请时刻升序
 */
public record TimelineView(String wellNo, List<RunSegment> segments, List<SwitchRequestView> switchRequests) {

    /**
     * 单次切换申请及其审核所用采样快照。
     *
     * @param request 切换申请
     * @param frozenSamples 批准时冻结的采样（四个及以上）；未批准时为空列表
     */
    public record SwitchRequestView(SwitchRequest request, List<PressureSample> frozenSamples) {
    }
}