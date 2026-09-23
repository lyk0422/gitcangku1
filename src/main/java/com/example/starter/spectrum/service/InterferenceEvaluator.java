package com.example.starter.spectrum.service;

import com.example.starter.spectrum.dto.PlanResultResponse;
import com.example.starter.spectrum.exception.SpectrumException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 累计干扰裁决纯逻辑：对提交后的“完整网络”重新计算，而不是只看本次变更台站。
 *
 * <p>规则：逐个正在发射（频道 1~8）的接收台站 r，累计所有同频道“其他”发射台站 s
 * 指向 r 的有向边干扰量；方向不能倒置（只取 s-&gt;r）。静默台站（频道 0）
 * 既不发射也不参与预算校验。未定义边按 0 处理。累计值等于预算合法。</p>
 */
public final class InterferenceEvaluator {

    private InterferenceEvaluator() {
    }

    /** 有向边标识：from 发射台 -> to 接收台。 */
    public record EdgeKey(String from, String to) {
    }

    /**
     * 计算提交后每个正在发射的接收台站的同频道累计干扰，结果按台站ID升序。
     *
     * @param channels 完整网络台站ID到频道（0 静默）的映射
     * @param edges    有向边到干扰量的映射，缺失边视为 0
     */
    public static List<PlanResultResponse.InterferenceItem> summarize(
            Map<String, Integer> channels,
            Map<String, Integer> budgets,
            Map<EdgeKey, Integer> edges) {
        List<PlanResultResponse.InterferenceItem> items = new ArrayList<>();
        for (String receiver : channels.keySet().stream().sorted().toList()) {
            int channel = channels.get(receiver);
            if (channel == 0) {
                continue;
            }
            int accumulated = 0;
            for (Map.Entry<String, Integer> entry : channels.entrySet()) {
                String transmitter = entry.getKey();
                if (transmitter.equals(receiver) || entry.getValue() != channel) {
                    continue;
                }
                accumulated += edges.getOrDefault(new EdgeKey(transmitter, receiver), 0);
            }
            items.add(new PlanResultResponse.InterferenceItem(
                    receiver, accumulated, budgets.get(receiver)));
        }
        return items;
    }

    /** 返回全部超预算台站（实际累计 &gt; 预算），按台站ID升序。 */
    public static List<SpectrumException.BudgetExceeded.Violation> findViolations(
            List<PlanResultResponse.InterferenceItem> summary) {
        List<SpectrumException.BudgetExceeded.Violation> violations = new ArrayList<>();
        for (PlanResultResponse.InterferenceItem item : summary) {
            if (item.getAccumulated() > item.getBudget()) {
                violations.add(new SpectrumException.BudgetExceeded.Violation(
                        item.getStationId(), item.getAccumulated(), item.getBudget()));
            }
        }
        return violations;
    }
}
