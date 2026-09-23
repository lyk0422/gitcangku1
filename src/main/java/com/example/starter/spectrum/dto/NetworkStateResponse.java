package com.example.starter.spectrum.dto;

import java.util.List;

/**
 * 网络当前状态视图：配置不可修改，台站初始静默（channel=0）。
 */
public class NetworkStateResponse {

    /** 网络ID。 */
    private String networkId;

    /** 当前版本号，创建为 1，每次成功方案加一。 */
    private int version;

    /** 全部台站的当前频道与预算，按台站ID排序。 */
    private List<StationState> stations;

    /** 有向干扰边，按 (from,to) 排序。 */
    private List<EdgeView> edges;

    public String getNetworkId() {
        return networkId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<StationState> getStations() {
        return stations;
    }

    public void setStations(List<StationState> stations) {
        this.stations = stations;
    }

    public List<EdgeView> getEdges() {
        return edges;
    }

    public void setEdges(List<EdgeView> edges) {
        this.edges = edges;
    }

    /** 台站当前状态。 */
    public static class StationState {

        /** 台站ID。 */
        private String stationId;

        /** 干扰预算，0~1000，创建后不可修改。 */
        private int budget;

        /** 当前频道，0 静默，1~8 发射。 */
        private int channel;

        public StationState() {
        }

        public StationState(String stationId, int budget, int channel) {
            this.stationId = stationId;
            this.budget = budget;
            this.channel = channel;
        }

        public String getStationId() {
            return stationId;
        }

        public void setStationId(String stationId) {
            this.stationId = stationId;
        }

        public int getBudget() {
            return budget;
        }

        public void setBudget(int budget) {
            this.budget = budget;
        }

        public int getChannel() {
            return channel;
        }

        public void setChannel(int channel) {
            this.channel = channel;
        }
    }

    /** 有向干扰边视图。 */
    public static class EdgeView {

        /** 干扰来源台站ID。 */
        private String from;

        /** 干扰指向台站ID。 */
        private String to;

        /** 干扰量，未列出边视为 0。 */
        private int amount;

        public EdgeView() {
        }

        public EdgeView(String from, String to, int amount) {
            this.from = from;
            this.to = to;
            this.amount = amount;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public int getAmount() {
            return amount;
        }

        public void setAmount(int amount) {
            this.amount = amount;
        }
    }
}
