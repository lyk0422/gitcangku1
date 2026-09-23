package com.example.starter.spectrum.dto;

import java.util.List;

/**
 * 网络当前状态视图：台站按建网定义顺序输出，channel=0 表示静默。
 */
public record NetworkStateView(
        String networkId,
        String name,
        int version,
        List<StationView> stations,
        List<EdgeView> edges
) {
    public record StationView(
            String stationId,
            int position,
            int interferenceBudget,
            int channel
    ) {
    }

    public record EdgeView(
            String fromStationId,
            String toStationId,
            int interference
    ) {
    }
}
