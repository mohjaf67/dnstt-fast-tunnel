package com.dnstt.client.models;

import java.io.Serializable;

/**
 * DNS configuration model
 */
public class DnsConfig implements Serializable {
    private String id;
    private String name;
    private String address;
    private String description;
    private boolean isGlobal;
    private String listName; // For custom lists
    private long lastTestTime;
    private TestStatus testStatus;
    private long latencyMs;
    private String errorMessage;

    public enum TestStatus {
        NOT_TESTED,
        TESTING,
        SUCCESS,
        FAILED
    }

    public DnsConfig(String id, String name, String address, String description, boolean isGlobal) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.description = description;
        this.isGlobal = isGlobal;
        this.testStatus = TestStatus.NOT_TESTED;
        this.listName = "Default";
    }

    public DnsConfig(String id, String name, String address, String description, boolean isGlobal, String listName) {
        this(id, name, address, description, isGlobal);
        this.listName = listName;
    }

    // Getters and setters
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
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isGlobal() {
        return isGlobal;
    }

    public void setGlobal(boolean global) {
        isGlobal = global;
    }

    public String getListName() {
        return listName;
    }

    public void setListName(String listName) {
        this.listName = listName;
    }

    public long getLastTestTime() {
        return lastTestTime;
    }

    public void setLastTestTime(long lastTestTime) {
        this.lastTestTime = lastTestTime;
    }

    public TestStatus getTestStatus() {
        return testStatus;
    }

    public void setTestStatus(TestStatus testStatus) {
        this.testStatus = testStatus;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getFormattedAddress() {
        if (address.contains(":")) {
            return address;
        }
        return address + ":53";
    }
}
