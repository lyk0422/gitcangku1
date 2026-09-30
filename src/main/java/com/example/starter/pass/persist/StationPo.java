package com.example.starter.pass.persist;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 接收站点及其唯一一部天线的能力快照行。
 *
 * <p>一个站点对应一部物理天线（{@code antenna_code}），天线冲突即同站点时隙冲突。</p>
 */
public class StationPo {

    /** 自增主键。 */
    private Long id;

    /** 站点业务编码，唯一，例如 {@code A}。 */
    private String code;

    /** 站点显示名称。 */
    private String name;

    /** 天线编码；同编码天线的已确认时隙不得重叠。 */
    private String antennaCode;

    /** 站点可接受的最低仰角门槛，单位：度；预测过境最低仰角低于该值即拒绝。 */
    private BigDecimal minElevationDeg;

    /** 天线每分钟可接收容量，单位：字节/分钟。 */
    private long bytesPerMinute;

    /** 行创建时间，UTC 时刻。 */
    private Instant createdAt;

    /** 行最近更新时间，UTC 时刻。 */
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAntennaCode() {
        return antennaCode;
    }

    public void setAntennaCode(String antennaCode) {
        this.antennaCode = antennaCode;
    }

    public BigDecimal getMinElevationDeg() {
        return minElevationDeg;
    }

    public void setMinElevationDeg(BigDecimal minElevationDeg) {
        this.minElevationDeg = minElevationDeg;
    }

    public long getBytesPerMinute() {
        return bytesPerMinute;
    }

    public void setBytesPerMinute(long bytesPerMinute) {
        this.bytesPerMinute = bytesPerMinute;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
