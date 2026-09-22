package com.example.starter.observation.model;

import java.time.Instant;

/**
 * 观测记录单个版本快照（含墓碑版本）。
 */
public class ObservationSnapshot {

    private Long id;
    private String observationId;
    private int version;
    private String location;
    private String reading;
    private String remark;
    private boolean deleted;
    private Instant createdAt;

    public ObservationSnapshot() {
    }

    public ObservationSnapshot(String observationId, int version, String location, String reading,
                               String remark, boolean deleted, Instant createdAt) {
        this.observationId = observationId;
        this.version = version;
        this.location = location;
        this.reading = reading;
        this.remark = remark;
        this.deleted = deleted;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getObservationId() {
        return observationId;
    }

    public void setObservationId(String observationId) {
        this.observationId = observationId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getReading() {
        return reading;
    }
    public void setReading(String reading) {
        this.reading = reading;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
