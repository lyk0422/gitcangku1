package com.example.starter.spectrum.service;

import com.example.starter.spectrum.domain.SpectrumNetwork;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯逻辑：对一份完整候选频道方案累计同频干扰并裁决。
 *
 * <p>规则：逐个正在发射（频道 1~8）的接收台站，累计所有同频道其他发射台站
 * <em>指向它</em>的有向干扰量；静默台站（频道0）不发射，也不参与预算校验；
 * 未定义边视为0；恰好等于预算合法。方向不可倒置。
 */
public final class InterferenceEvaluator {

    private InterferenceEvaluator() {
    }

    /**
     * 单台站裁决结果。
     */
    public record StationResult(String stationId, int channel, int total, int budget, boolean withinBudget) {
    }

    /**
     * 对候选方案做全量裁决（不能只判断本次变更台站）。
     *
     * @param network          网络配置（不可变）
     * @param candidateChannel 全量台站频道（0 静默，1~8 发射）
     * @return 按台站定义顺序排列的全部台站裁决结果（含静默台站，total=0）
     */
    public static List<StationResult> evaluate(SpectrumNetwork network, Map<String, Integer> candidateChannel) {
        List<StationResult> results = new java.util.ArrayList<>(network.stations().size());
        for (SpectrumNetwork.Station receiver : network.stations()) {
            int receiverChannel = candidateChannel.getOrDefault(receiver.stationId(), 0);
            int total = 0;
            if (receiverChannel >= 1 && receiverChannel <= 8) {
                for (SpectrumNetwork.Edge edge : network.edges()) {
                    // 方向：edge 必须指向当前接收台站
                    if (!edge.toStationId().equals(receiver.stationId())) {
                        continue;
                    }
                    // 自己不能干扰自己（建网已禁自环，此处双保险）
                    if (edge.fromStationId().equals(receiver.stationId())) {
                        continue;
                    }
                    Integer transmitterChannel = candidateChannel.get(edge.fromStationId());
                    if (transmitterChannel != null
                            && transmitterChannel >= 1 && transmitterChannel <= 8
                            && transmitterChannel == receiverChannel) {
                        total += edge.interference();
                    }
                }
            }
            results.add(new StationResult(
                    receiver.stationId(), receiverChannel, total,
                    receiver.interferenceBudget(), total <= receiver.interferenceBudget()));
        }
        return results;
    }

    /**
     * 从全量裁决结果中提取超预算台站，按台站 ID 字典序排序。
     */
    public static List<StationResult> violationsSortedById(List<StationResult> results) {
        return results.stream()
                .filter(r -> !r.withinBudget())
                .sorted(java.util.Comparator.comparing(StationResult::stationId))
                .toList();
    }

    /**
     * 基于当前台站频道构造全量频道映射。
     */
    public static Map<String, Integer> currentChannels(SpectrumNetwork network) {
        Map<String, Integer> channels = new LinkedHashMap<>();
        for (SpectrumNetwork.Station station : network.stations()) {
            channels.put(station.stationId(), station.channel());
        }
        return channels;
    }
}
