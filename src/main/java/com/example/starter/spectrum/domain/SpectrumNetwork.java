package com.example.starter.spectrum.domain;

import java.util.List;

/**
 * 频率协同网络聚合：配置（台站、边）不可变；仅当前频道与版本随成功方案推进。
 */
public record SpectrumNetwork(
        String networkId,
        String name,
        int version,
        List<Station> stations,
        List<Edge> edges
) {
    /**
     * 台站。channel：0 静默（初始），1~8 频道。
     */
    public record Station(String stationId, int position, int interferenceBudget, int channel) {
    }

    /**
     * 有向干扰边 from->to。
     */
    public record Edge(String fromStationId, String toStationId, int interference) {
    }
}
