package com.example.starter.spectrum.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建频率协同网络的请求体。
 * 网络配置（台站、预算、干扰边）创建后不可修改。
 */
public class CreateNetworkRequest {

    /** 业务网络ID，全局唯一；缺失或空白为非法参数(400)。 */
    @NotBlank
    @Size(max = 64)
    private String networkId;

    /** 1~20 个台站，网络内ID唯一，各自携带 0~1000 的整数干扰预算。 */
    @NotEmpty
    @Size(min = 1, max = 20)
    @Valid
    private List<StationSpec> stations;

    /** 有向台站对干扰量，0~1000；禁止自环与重复边，未定义边视为 0。 */
    @Valid
    private List<EdgeSpec> edges = List.of();

    public String getNetworkId() {
        return networkId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }

    public List<StationSpec> getStations() {
        return stations;
    }

    public void setStations(List<StationSpec> stations) {
        this.stations = stations;
    }

    public List<EdgeSpec> getEdges() {
        return edges;
    }

    public void setEdges(List<EdgeSpec> edges) {
        this.edges = edges;
    }

    /** 台站定义：网络内唯一ID与不可修改的干扰预算。 */
    public static class StationSpec {

        /** 网络内唯一台站ID。 */
        @NotBlank
        @Size(max = 64)
        private String stationId;

        /** 干扰预算，0~1000 的整数；累计干扰恰好等于预算仍合法。 */
        @NotNull
        @Min(0)
        @Max(1000)
        private Integer budget;

        public String getStationId() {
            return stationId;
        }

        public void setStationId(String stationId) {
            this.stationId = stationId;
        }

        public Integer getBudget() {
            return budget;
        }

        public void setBudget(Integer budget) {
            this.budget = budget;
        }
    }

    /** 有向干扰边：from 台站发射时对同频道 to 台站施加的干扰量。 */
    public static class EdgeSpec {

        /** 干扰来源台站ID。 */
        @NotBlank
        @Size(max = 64)
        private String from;

        /** 干扰指向台站ID；禁止与 from 相同（自环）。 */
        @NotBlank
        @Size(max = 64)
        private String to;

        /** 整数干扰量，0~1000。 */
        @NotNull
        @Min(0)
        @Max(1000)
        private Integer amount;

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

        public Integer getAmount() {
            return amount;
        }

        public void setAmount(Integer amount) {
            this.amount = amount;
        }
    }
}
