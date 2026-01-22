package com.dnstt.client.models;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom DNS list model
 */
public class CustomDnsList implements Serializable {
    private String id;
    private String name;
    private List<DnsConfig> dnsConfigs;
    private long createdAt;
    private long updatedAt;

    public CustomDnsList(String id, String name) {
        this.id = id;
        this.name = name;
        this.dnsConfigs = new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.updatedAt = System.currentTimeMillis();
    }

    public List<DnsConfig> getDnsConfigs() {
        return dnsConfigs;
    }

    public void setDnsConfigs(List<DnsConfig> dnsConfigs) {
        this.dnsConfigs = dnsConfigs;
        this.updatedAt = System.currentTimeMillis();
    }

    public void addDnsConfig(DnsConfig config) {
        this.dnsConfigs.add(config);
        this.updatedAt = System.currentTimeMillis();
    }

    public void removeDnsConfig(String configId) {
        this.dnsConfigs.removeIf(config -> config.getId().equals(configId));
        this.updatedAt = System.currentTimeMillis();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public int getSize() {
        return dnsConfigs.size();
    }
}
