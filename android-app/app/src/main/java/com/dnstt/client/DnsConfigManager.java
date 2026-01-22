package com.dnstt.client;

import android.content.Context;
import android.content.SharedPreferences;

import com.dnstt.client.models.CustomDnsList;
import com.dnstt.client.models.DnsConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages DNS configurations and custom lists
 */
public class DnsConfigManager {
    private static final String PREFS_NAME = "dns_config_prefs";
    private static final String KEY_CUSTOM_LISTS = "custom_lists";
    private static final String KEY_SELECTED_SOURCE = "selected_source";
    private static final String KEY_SELECTED_LIST_ID = "selected_list_id";

    public static final String SOURCE_GLOBAL = "global";
    public static final String SOURCE_CUSTOM = "custom";

    private final Context context;
    private final SharedPreferences prefs;
    private final Gson gson;
    private List<CustomDnsList> customLists;

    public DnsConfigManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
        loadCustomLists();
    }

    /**
     * Get global DNS configurations
     * Loads all DNS servers from dns_servers.txt asset file
     */
    public List<DnsConfig> getGlobalDnsConfigs() {
        List<DnsConfig> configs = new ArrayList<>();

        try {
            java.io.InputStream is = context.getAssets().open("dns_servers.txt");
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is));
            String line;
            int index = 1;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // Skip empty lines and comments
                if (!line.isEmpty() && !line.startsWith("#")) {
                    // Create DnsConfig from IP address
                    String address = line.contains(":") ? line : line + ":53";
                    configs.add(new DnsConfig(
                            UUID.randomUUID().toString(),
                            "DNS Server " + index,
                            address,
                            "Public DNS server",
                            true
                    ));
                    index++;
                }
            }
            reader.close();
        } catch (java.io.IOException e) {
            // Fallback to popular DNS servers if file can't be read
            configs.add(new DnsConfig(
                    UUID.randomUUID().toString(),
                    "Cloudflare DNS",
                    "1.1.1.1:53",
                    "Fast and privacy-focused DNS",
                    true
            ));
            configs.add(new DnsConfig(
                    UUID.randomUUID().toString(),
                    "Google DNS",
                    "8.8.8.8:53",
                    "Reliable Google DNS",
                    true
            ));
        }

        return configs;
    }

    /**
     * Load custom DNS lists from storage
     */
    private void loadCustomLists() {
        String json = prefs.getString(KEY_CUSTOM_LISTS, null);
        if (json != null) {
            Type type = new TypeToken<List<CustomDnsList>>() {}.getType();
            customLists = gson.fromJson(json, type);
        } else {
            customLists = new ArrayList<>();
            // Create default custom list
            CustomDnsList defaultList = new CustomDnsList(UUID.randomUUID().toString(), "My DNS List");
            customLists.add(defaultList);
            saveCustomLists();
        }
    }

    /**
     * Reload custom DNS lists from storage (call when lists may have been modified externally)
     */
    public void reloadCustomLists() {
        loadCustomLists();
    }

    /**
     * Save custom DNS lists to storage
     */
    private void saveCustomLists() {
        String json = gson.toJson(customLists);
        prefs.edit().putString(KEY_CUSTOM_LISTS, json).apply();
    }

    /**
     * Get all custom DNS lists
     */
    public List<CustomDnsList> getCustomLists() {
        return new ArrayList<>(customLists);
    }

    /**
     * Get a specific custom list by ID
     */
    public CustomDnsList getCustomList(String listId) {
        for (CustomDnsList list : customLists) {
            if (list.getId().equals(listId)) {
                return list;
            }
        }
        return null;
    }

    /**
     * Create a new custom DNS list
     */
    public CustomDnsList createCustomList(String name) {
        CustomDnsList newList = new CustomDnsList(UUID.randomUUID().toString(), name);
        customLists.add(newList);
        saveCustomLists();
        return newList;
    }

    /**
     * Update a custom DNS list
     */
    public void updateCustomList(CustomDnsList list) {
        for (int i = 0; i < customLists.size(); i++) {
            if (customLists.get(i).getId().equals(list.getId())) {
                customLists.set(i, list);
                saveCustomLists();
                return;
            }
        }
    }

    /**
     * Delete a custom DNS list
     */
    public void deleteCustomList(String listId) {
        customLists.removeIf(list -> list.getId().equals(listId));
        saveCustomLists();

        // If deleted list was selected, reset to global
        if (listId.equals(getSelectedListId())) {
            setSelectedSource(SOURCE_GLOBAL, null);
        }
    }

    /**
     * Add DNS config to a custom list
     */
    public void addDnsConfigToList(String listId, DnsConfig config) {
        CustomDnsList list = getCustomList(listId);
        if (list != null) {
            config.setListName(list.getName());
            list.addDnsConfig(config);
            updateCustomList(list);
        }
    }

    /**
     * Update DNS config in a custom list
     */
    public void updateDnsConfig(String listId, DnsConfig config) {
        CustomDnsList list = getCustomList(listId);
        if (list != null) {
            List<DnsConfig> configs = list.getDnsConfigs();
            for (int i = 0; i < configs.size(); i++) {
                if (configs.get(i).getId().equals(config.getId())) {
                    configs.set(i, config);
                    updateCustomList(list);
                    return;
                }
            }
        }
    }

    /**
     * Remove DNS config from a custom list
     */
    public void removeDnsConfig(String listId, String configId) {
        CustomDnsList list = getCustomList(listId);
        if (list != null) {
            list.removeDnsConfig(configId);
            updateCustomList(list);
        }
    }

    /**
     * Get selected DNS source (global or custom)
     */
    public String getSelectedSource() {
        return prefs.getString(KEY_SELECTED_SOURCE, SOURCE_GLOBAL);
    }

    /**
     * Get selected custom list ID (if source is custom)
     */
    public String getSelectedListId() {
        return prefs.getString(KEY_SELECTED_LIST_ID, null);
    }

    /**
     * Set selected DNS source
     */
    public void setSelectedSource(String source, String listId) {
        prefs.edit()
                .putString(KEY_SELECTED_SOURCE, source)
                .putString(KEY_SELECTED_LIST_ID, listId)
                .apply();
    }

    /**
     * Get DNS servers for auto DNS based on selected source
     */
    public String getDnsServersForAutoSearch() {
        StringBuilder sb = new StringBuilder();

        if (SOURCE_GLOBAL.equals(getSelectedSource())) {
            // Use existing DnsServerManager for global servers
            DnsServerManager manager = new DnsServerManager(context);
            return manager.getAllServersAsString();
        } else {
            // Use selected custom list
            String listId = getSelectedListId();
            if (listId != null) {
                CustomDnsList list = getCustomList(listId);
                if (list != null) {
                    for (DnsConfig config : list.getDnsConfigs()) {
                        String address = config.getAddress();
                        // Extract IP without port
                        if (address.contains(":")) {
                            address = address.substring(0, address.indexOf(":"));
                        }
                        sb.append(address).append("\n");
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * Get display name for selected source
     */
    public String getSelectedSourceDisplayName() {
        if (SOURCE_GLOBAL.equals(getSelectedSource())) {
            return "Global DNS";
        } else {
            String listId = getSelectedListId();
            if (listId != null) {
                CustomDnsList list = getCustomList(listId);
                if (list != null) {
                    return list.getName();
                }
            }
            return "Custom List";
        }
    }

    /**
     * Save last successful DNS server for prioritization
     */
    public void saveLastSuccessfulDns(String address) {
        prefs.edit().putString("last_successful_dns", address).apply();
    }

    /**
     * Get last successful DNS server
     */
    public String getLastSuccessfulDns() {
        return prefs.getString("last_successful_dns", null);
    }

    /**
     * Get list of deprioritized DNS servers (those that failed)
     */
    public java.util.Set<String> getDeprioritizedDns() {
        String deprioritized = prefs.getString("deprioritized_dns", "");
        java.util.Set<String> set = new java.util.HashSet<>();
        if (!deprioritized.isEmpty()) {
            for (String dns : deprioritized.split(",")) {
                if (!dns.trim().isEmpty()) {
                    set.add(dns.trim());
                }
            }
        }
        return set;
    }

    /**
     * Add a DNS to the deprioritized list (move to end)
     */
    public void moveDnsToEnd(String address) {
        if (address == null || address.isEmpty()) {
            return;
        }

        java.util.Set<String> deprioritized = getDeprioritizedDns();
        deprioritized.add(address);

        // Save back to preferences
        StringBuilder sb = new StringBuilder();
        for (String dns : deprioritized) {
            if (sb.length() > 0) sb.append(",");
            sb.append(dns);
        }
        prefs.edit().putString("deprioritized_dns", sb.toString()).apply();

        // For custom lists, actually reorder the list
        if (SOURCE_CUSTOM.equals(getSelectedSource())) {
            String listId = getSelectedListId();
            if (listId != null) {
                CustomDnsList list = getCustomList(listId);
                if (list != null) {
                    List<DnsConfig> configs = list.getDnsConfigs();
                    DnsConfig toMove = null;

                    // Find the DNS config to move
                    for (DnsConfig config : configs) {
                        String configAddress = config.getAddress();
                        // Extract IP without port for comparison
                        if (configAddress.contains(":")) {
                            configAddress = configAddress.substring(0, configAddress.indexOf(":"));
                        }
                        if (configAddress.equals(address)) {
                            toMove = config;
                            break;
                        }
                    }

                    // Move to end if found
                    if (toMove != null) {
                        configs.remove(toMove);
                        configs.add(toMove);
                        list.setDnsConfigs(configs);
                        updateCustomList(list);
                    }
                }
            }
        }
    }

    /**
     * Clear deprioritized DNS list (reset to default order)
     */
    public void clearDeprioritizedDns() {
        prefs.edit().remove("deprioritized_dns").apply();
    }

    /**
     * Get DNS servers with last successful one first, optionally excluding an address
     * Deprioritized servers are moved to the end
     */
    public String getDnsServersForAutoSearchWithPriority(String excludeAddress) {
        String lastSuccessful = getLastSuccessfulDns();
        java.util.Set<String> deprioritized = getDeprioritizedDns();
        StringBuilder sb = new StringBuilder();
        List<String> deprioritizedServers = new ArrayList<>();

        // Get all DNS servers from current source
        String allServers = getDnsServersForAutoSearch();

        // Build a set of servers in the current source for quick lookup
        java.util.Set<String> sourceServers = new java.util.HashSet<>();
        if (allServers != null && !allServers.isEmpty()) {
            for (String server : allServers.split("\n")) {
                server = server.trim();
                if (!server.isEmpty()) {
                    sourceServers.add(server);
                }
            }
        }

        // Add last successful DNS first ONLY if it exists in the current source
        if (lastSuccessful != null && !lastSuccessful.isEmpty() &&
            sourceServers.contains(lastSuccessful) &&
            !deprioritized.contains(lastSuccessful) &&
            (excludeAddress == null || !lastSuccessful.equals(excludeAddress))) {
            sb.append(lastSuccessful).append("\n");
        }

        // Separate normal and deprioritized servers
        if (allServers != null && !allServers.isEmpty()) {
            String[] servers = allServers.split("\n");
            for (String server : servers) {
                server = server.trim();
                if (!server.isEmpty() &&
                    (lastSuccessful == null || !server.equals(lastSuccessful)) &&
                    (excludeAddress == null || !server.equals(excludeAddress))) {

                    if (deprioritized.contains(server)) {
                        // Add to deprioritized list (will be added at the end)
                        deprioritizedServers.add(server);
                    } else {
                        // Add to normal priority
                        sb.append(server).append("\n");
                    }
                }
            }
        }

        // Add deprioritized servers at the end
        for (String server : deprioritizedServers) {
            sb.append(server).append("\n");
        }

        return sb.toString();
    }
}
