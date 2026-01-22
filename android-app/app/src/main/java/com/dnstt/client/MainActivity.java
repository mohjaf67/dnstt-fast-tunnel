package com.dnstt.client;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import mobile.Client;
import mobile.Config;
import mobile.Mobile;
import mobile.ResolverCallback;
import mobile.StatusCallback;

public class MainActivity extends AppCompatActivity implements StatusCallback {

    private static final String PREFS_NAME = "dnstt_prefs";

    private Client client;
    private Handler handler;
    private volatile boolean isConnected = false;
    private volatile boolean isSearching = false;
    private volatile boolean cancelSearch = false;
    private boolean vpnMode = true;
    private boolean autoConnect = false;
    private boolean useAutoDns = true;  // Auto DNS: test and select best resolver
    private boolean hasAutoConnected = false;

    // Performance settings
    private int dnsDigConcurrency = 50;    // Phase 1: DNS dig scan concurrency (10-200)
    private int dnsTunnelConcurrency = 10; // Phase 2: DNS tunnel scan concurrency (1-20)
    private int dnsTimeout = 3000;         // DNS test timeout in milliseconds (500-10000)
    private String currentConnectedDns = null;  // Track current connected DNS for retry

    private DnsServerManager dnsServerManager;
    private Thread searchThread = null;
    private ExecutorService dnsTestExecutor = null;  // Track parallel DNS testing executor

    // DoH provider presets - name -> URL mapping
    private static final String[][] DOH_PROVIDERS = {
        {"Google", "https://dns.google/dns-query"},
        {"Cloudflare", "https://cloudflare-dns.com/dns-query"},
        {"Cloudflare (1.1.1.1)", "https://1.1.1.1/dns-query"},
        {"Quad9", "https://dns.quad9.net/dns-query"},
        {"AdGuard", "https://dns.adguard.com/dns-query"},
        {"NextDNS", "https://dns.nextdns.io/dns-query"},
        {"OpenDNS", "https://doh.opendns.com/dns-query"},
        {"Shecan (Iran)", "https://free.shecan.ir/dns-query"},
        {"403.online (Iran)", "https://dns.403.online/dns-query"},
        {"Electro (Iran)", "https://electro.ir/dns-query"},
        {"Custom", ""}  // Custom option for manual entry
    };

    // UI Elements
    private TextView statusText;
    private TextView statusSubtext;
    private View statusCircle;
    private MaterialButton connectButton;
    private MaterialButton updateButton;
    private TextView versionText;
    private MaterialCardView statsCard;
    private TextView bytesInText;
    private TextView bytesOutText;
    private TextView qualityText;
    private TextView latencyText;
    private TextView speedText;
    private ProgressBar qualityBar;
    private View qualityBarLayout;
    private TextView logText;
    private ScrollView logScrollView;
    private MaterialButton btnViewAllLogs;
    private AutoCompleteTextView transportType;
    private AutoCompleteTextView dohProvider;
    private TextInputLayout dohProviderLayout;
    private TextInputLayout transportAddrLayout;
    private TextInputEditText transportAddr;
    private TextInputEditText domain;
    private TextInputEditText pubkey;
    private TextInputEditText tunnels;
    private SwitchMaterial vpnModeSwitch;
    private SwitchMaterial autoConnectSwitch;
    private SwitchMaterial autoDnsSwitch;
    private TextView autoDnsLabel;
    private AutoCompleteTextView dnsSourceDropdown;
    private MaterialButton btnConfigureDns;
    private MaterialButton btnClearDnsCache;
    private MaterialButton retryButton;
    private TextInputEditText dnsDigConcurrencyInput;
    private TextInputEditText dnsTunnelConcurrencyInput;
    private TextInputEditText dnsTimeoutInput;
    private View dnsDigConcurrencyLayout;
    private View dnsTunnelConcurrencyLayout;
    private View dnsTimeoutLayout;
    private View advancedToggle;
    private View advancedContent;
    private android.widget.ImageView advancedArrow;
    private boolean advancedExpanded = false;

    // App updater
    private AppUpdater appUpdater;

    // DNS config manager
    private DnsConfigManager dnsConfigManager;

    // Activity result launcher for configuration activity
    private ActivityResultLauncher<Intent> configActivityLauncher;

    // Connection quality tracking
    private long lastBytesIn = 0;
    private long lastBytesOut = 0;
    private long lastUpdateTime = 0;
    private long currentLatencyMs = 0;
    private double smoothedSpeedKBps = 0;  // Smoothed speed to prevent flickering
    private static final double SPEED_SMOOTHING_FACTOR = 0.3;  // Lower = smoother, higher = more responsive

    // Auto-reconnect settings
    private boolean autoReconnectEnabled = true;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long RECONNECT_DELAY_MS = 2000;
    private boolean wasConnectedBeforeDisconnect = false;
    private boolean userInitiatedDisconnect = false;

    // VPN permission launcher
    private ActivityResultLauncher<Intent> vpnPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        handler = new Handler(Looper.getMainLooper());
        client = mobile.Mobile.newClient();
        client.setCallback(this);

        // Initialize app updater
        appUpdater = new AppUpdater(this);

        // Initialize DNS server manager
        dnsServerManager = new DnsServerManager(this);
        appendLog("Loaded " + dnsServerManager.getServerCount() + " DNS servers");

        // Initialize DNS config manager
        dnsConfigManager = new DnsConfigManager(this);

        // Register Configuration Activity launcher
        configActivityLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // Reload custom lists from storage to get latest changes
                    dnsConfigManager.reloadCustomLists();

                    // Refresh dropdown (user might have added/deleted custom lists)
                    setupDnsSourceDropdown();

                    // Update Auto DNS label to reflect any changes in DNS count
                    updateAutoDnsLabel();

                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String selectedDns = result.getData().getStringExtra("selected_dns");
                        String selectedDnsName = result.getData().getStringExtra("selected_dns_name");

                        if (selectedDns != null) {
                            handleDnsSelection(selectedDns, selectedDnsName);
                        }
                    }
                }
        );

        // Register VPN permission launcher
        vpnPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        appendLog("VPN permission granted");
                        startVpnService();
                    } else {
                        appendLog("VPN permission denied by user");
                        setInputsEnabled(true);
                    }
                }
        );

        initViews();
        loadSettings();
        setupTransportDropdown();

        // Set up UI callback for VPN service
        DnsttVpnService.setUiCallback(this);

        // Cleanup any orphaned services from previous sessions
        cleanupOrphanedServices();

        appendLog("DNSTT Client initialized");
        appendLog("VPN mode: " + (vpnMode ? "enabled" : "disabled"));
        appendLog("Auto-connect: " + (autoConnect ? "enabled" : "disabled"));
        appendLog("Auto DNS: " + (useAutoDns ? "enabled (will test resolvers)" : "disabled (manual)"));

        // Auto-connect if enabled and settings are valid
        if (autoConnect && !hasAutoConnected && hasValidSettings()) {
            hasAutoConnected = true;
            appendLog("Auto-connecting...");
            handler.postDelayed(this::connect, 500);
        }
    }

    private boolean hasValidSettings() {
        String pubkeyStr = getText(pubkey);
        String domainStr = getDomain();
        return pubkeyStr != null && !pubkeyStr.isEmpty() &&
               domainStr != null && !domainStr.isEmpty();
    }

    private void initViews() {
        statusText = findViewById(R.id.statusText);
        statusSubtext = findViewById(R.id.statusSubtext);
        statusCircle = findViewById(R.id.statusCircle);
        connectButton = findViewById(R.id.connectButton);
        updateButton = findViewById(R.id.updateButton);
        versionText = findViewById(R.id.versionText);
        statsCard = findViewById(R.id.statsCard);
        bytesInText = findViewById(R.id.bytesInText);
        bytesOutText = findViewById(R.id.bytesOutText);
        qualityText = findViewById(R.id.qualityText);
        latencyText = findViewById(R.id.latencyText);
        speedText = findViewById(R.id.speedText);
        qualityBar = findViewById(R.id.qualityBar);
        qualityBarLayout = findViewById(R.id.qualityBarLayout);
        logText = findViewById(R.id.logText);
        logScrollView = findViewById(R.id.logScrollView);
        btnViewAllLogs = findViewById(R.id.btnViewAllLogs);
        transportType = findViewById(R.id.transportType);
        dohProvider = findViewById(R.id.dohProvider);
        dohProviderLayout = findViewById(R.id.dohProviderLayout);
        transportAddrLayout = findViewById(R.id.transportAddrLayout);
        transportAddr = findViewById(R.id.transportAddr);
        domain = findViewById(R.id.domain);
        pubkey = findViewById(R.id.pubkey);
        tunnels = findViewById(R.id.tunnels);
        vpnModeSwitch = findViewById(R.id.vpnModeSwitch);
        autoConnectSwitch = findViewById(R.id.autoConnectSwitch);
        autoDnsSwitch = findViewById(R.id.autoDnsSwitch);
        autoDnsLabel = findViewById(R.id.autoDnsLabel);
        dnsSourceDropdown = findViewById(R.id.dnsSourceDropdown);
        btnConfigureDns = findViewById(R.id.btnConfigureDns);
        btnClearDnsCache = findViewById(R.id.btnClearDnsCache);
        retryButton = findViewById(R.id.retryButton);
        dnsDigConcurrencyInput = findViewById(R.id.dnsDigConcurrencyInput);
        dnsTunnelConcurrencyInput = findViewById(R.id.dnsTunnelConcurrencyInput);
        dnsTimeoutInput = findViewById(R.id.dnsTimeoutInput);
        dnsDigConcurrencyLayout = findViewById(R.id.dnsDigConcurrencyLayout);
        dnsTunnelConcurrencyLayout = findViewById(R.id.dnsTunnelConcurrencyLayout);
        dnsTimeoutLayout = findViewById(R.id.dnsTimeoutLayout);
        advancedToggle = findViewById(R.id.advancedToggle);
        advancedContent = findViewById(R.id.advancedContent);
        advancedArrow = findViewById(R.id.advancedArrow);

        // Setup advanced settings toggle
        if (advancedToggle != null && advancedContent != null) {
            advancedToggle.setOnClickListener(v -> toggleAdvancedSettings());
        }

        // Set version text
        versionText.setText("v" + appUpdater.getCurrentVersion());

        // Setup DNS source dropdown
        setupDnsSourceDropdown();

        // Setup configure DNS button
        btnConfigureDns.setOnClickListener(v -> {
            Intent intent = new Intent(this, ConfigurationActivity.class);
            configActivityLauncher.launch(intent);
        });

        // Setup clear DNS cache button
        if (btnClearDnsCache != null) {
            btnClearDnsCache.setOnClickListener(v -> {
                clearAllDnsCache();
                Toast.makeText(this, "DNS cache cleared", Toast.LENGTH_SHORT).show();
                appendLog("DNS cache cleared by user");
            });
        }

        // Setup view all logs button
        if (btnViewAllLogs != null) {
            btnViewAllLogs.setOnClickListener(v -> showFullLogDialog());
        }

        // Setup retry button - initially hidden
        if (retryButton != null) {
            retryButton.setVisibility(View.GONE);
            retryButton.setOnClickListener(v -> retryWithDifferentDns());
        }

        // Setup DNS Dig Concurrency input with validation (Phase 1)
        if (dnsDigConcurrencyInput != null) {
            dnsDigConcurrencyInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    try {
                        int value = Integer.parseInt(s.toString());
                        if (value < 10) value = 10;
                        if (value > 200) value = 200;
                        dnsDigConcurrency = value;
                        saveSettings();
                    } catch (NumberFormatException e) {
                        dnsDigConcurrency = 50; // default
                    }
                }
            });
        }

        // Setup DNS Tunnel Concurrency input with validation (Phase 2)
        if (dnsTunnelConcurrencyInput != null) {
            dnsTunnelConcurrencyInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    try {
                        int value = Integer.parseInt(s.toString());
                        if (value < 1) value = 1;
                        if (value > 20) value = 20;
                        dnsTunnelConcurrency = value;
                        saveSettings();
                    } catch (NumberFormatException e) {
                        dnsTunnelConcurrency = 10; // default
                    }
                }
            });
        }

        // Setup DNS timeout input with validation
        if (dnsTimeoutInput != null) {
            dnsTimeoutInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    try {
                        int timeout = Integer.parseInt(s.toString());
                        if (timeout < 500) timeout = 500;
                        if (timeout > 10000) timeout = 10000;
                        dnsTimeout = timeout;
                        saveSettings();
                    } catch (NumberFormatException e) {
                        dnsTimeout = 3000; // default
                    }
                }
            });
        }

        // Setup update button
        updateButton.setOnClickListener(v -> checkForUpdates());

        connectButton.setOnClickListener(v -> {
            if (isSearching) {
                // Cancel search if currently searching
                cancelDnsSearch();
            } else if (isConnected) {
                disconnect();
            } else {
                connect();
            }
        });

        vpnModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            vpnMode = isChecked;
            appendLog("VPN mode " + (isChecked ? "enabled" : "disabled"));
            saveSettings();
        });

        autoConnectSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            autoConnect = isChecked;
            appendLog("Auto-connect " + (isChecked ? "enabled" : "disabled"));
            saveSettings();
        });

        autoDnsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            useAutoDns = isChecked;
            appendLog("Auto DNS " + (isChecked ? "enabled (will test resolvers)" : "disabled (manual mode)"));
            updateAutoDnsLabel();

            if (isChecked) {
                // Auto DNS only works with UDP - automatically switch to UDP
                transportType.setText("UDP", false);
                dohProviderLayout.setVisibility(View.GONE);
                transportAddrLayout.setVisibility(View.VISIBLE);
                transportAddr.setText("(auto-select best resolver)");
                transportAddr.setEnabled(false);
                appendLog("Transport switched to UDP for Auto DNS");
            } else {
                // Manual mode - enable transport address if UDP is selected
                if (transportType.getText().toString().equalsIgnoreCase("UDP")) {
                    transportAddr.setText("1.1.1.1:53");
                    transportAddr.setEnabled(true);
                }
            }
            saveSettings();
        });

        // Setup DoH provider dropdown
        setupDohProviderDropdown();
    }

    private void updateAutoDnsLabel() {
        if (useAutoDns) {
            // Get server count based on selected source
            int serverCount = 0;
            String selectedSource = dnsConfigManager.getSelectedSource();

            if (DnsConfigManager.SOURCE_GLOBAL.equals(selectedSource)) {
                serverCount = dnsServerManager.getServerCount();
            } else {
                // Custom list selected
                String listId = dnsConfigManager.getSelectedListId();
                if (listId != null) {
                    com.dnstt.client.models.CustomDnsList list = dnsConfigManager.getCustomList(listId);
                    if (list != null) {
                        serverCount = list.getSize();
                    }
                }
            }

            autoDnsLabel.setText("Auto DNS (" + serverCount + " servers)");
        } else {
            autoDnsLabel.setText("Auto DNS (manual mode)");
        }
    }

    private void setupDohProviderDropdown() {
        // Create array of provider names
        String[] providerNames = new String[DOH_PROVIDERS.length];
        for (int i = 0; i < DOH_PROVIDERS.length; i++) {
            providerNames[i] = DOH_PROVIDERS[i][0];
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.dropdown_item, providerNames);
        dohProvider.setAdapter(adapter);

        dohProvider.setOnItemClickListener((parent, view, position, id) -> {
            String url = DOH_PROVIDERS[position][1];
            if (position == DOH_PROVIDERS.length - 1) {
                // Custom option - enable manual entry
                transportAddrLayout.setVisibility(View.VISIBLE);
                transportAddr.setEnabled(true);
                transportAddr.setText("");
                transportAddr.requestFocus();
                appendLog("DoH Provider: Custom (enter URL manually)");
            } else {
                // Preset provider - set URL and hide manual entry
                transportAddr.setText(url);
                transportAddrLayout.setVisibility(View.GONE);
                appendLog("DoH Provider: " + DOH_PROVIDERS[position][0]);
            }
            saveSettings();
        });
    }

    private void toggleAdvancedSettings() {
        advancedExpanded = !advancedExpanded;

        if (advancedExpanded) {
            advancedContent.setVisibility(View.VISIBLE);
            // Rotate arrow to point up
            advancedArrow.animate().rotation(180).setDuration(200).start();
        } else {
            advancedContent.setVisibility(View.GONE);
            // Rotate arrow to point down
            advancedArrow.animate().rotation(0).setDuration(200).start();
        }
    }

    private void updateDohProviderVisibility() {
        String type = transportType.getText().toString();
        boolean isDoH = type.equalsIgnoreCase("DoH");
        dohProviderLayout.setVisibility(isDoH ? View.VISIBLE : View.GONE);

        // Show transport address field if not DoH OR if Custom is selected
        if (isDoH) {
            String selectedProvider = dohProvider.getText().toString();
            boolean isCustom = selectedProvider.equals("Custom");
            transportAddrLayout.setVisibility(isCustom ? View.VISIBLE : View.GONE);
        } else {
            transportAddrLayout.setVisibility(View.VISIBLE);
        }
    }

    private void setupTransportDropdown() {
        String[] types = {"DoH", "DoT", "UDP"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.dropdown_item, types);
        transportType.setAdapter(adapter);

        transportType.setOnItemClickListener((parent, view, position, id) -> {
            switch (position) {
                case 0: // DoH
                    // Auto DNS only works with UDP - disable it when switching to DoH
                    if (useAutoDns) {
                        useAutoDns = false;
                        autoDnsSwitch.setChecked(false);
                        updateAutoDnsLabel();
                        appendLog("Auto DNS disabled (only works with UDP)");
                    }
                    // Show DoH provider dropdown, hide manual address
                    dohProviderLayout.setVisibility(View.VISIBLE);
                    String selectedProvider = dohProvider.getText().toString();
                    boolean isCustom = selectedProvider.equals("Custom");
                    transportAddrLayout.setVisibility(isCustom ? View.VISIBLE : View.GONE);
                    // Set URL based on selected provider
                    for (String[] provider : DOH_PROVIDERS) {
                        if (provider[0].equals(selectedProvider)) {
                            if (!provider[1].isEmpty()) {
                                transportAddr.setText(provider[1]);
                            }
                            break;
                        }
                    }
                    transportAddr.setEnabled(true);
                    appendLog("Transport: DoH (DNS over HTTPS)");
                    break;
                case 1: // DoT
                    // Auto DNS only works with UDP - disable it when switching to DoT
                    if (useAutoDns) {
                        useAutoDns = false;
                        autoDnsSwitch.setChecked(false);
                        updateAutoDnsLabel();
                        appendLog("Auto DNS disabled (only works with UDP)");
                    }
                    dohProviderLayout.setVisibility(View.GONE);
                    transportAddrLayout.setVisibility(View.VISIBLE);
                    transportAddr.setText("dns.google:853");
                    transportAddr.setEnabled(true);
                    appendLog("Transport: DoT (DNS over TLS)");
                    break;
                case 2: // UDP
                    dohProviderLayout.setVisibility(View.GONE);
                    transportAddrLayout.setVisibility(View.VISIBLE);
                    if (useAutoDns) {
                        transportAddr.setText("(auto-select best resolver)");
                        transportAddr.setEnabled(false);
                        appendLog("Transport: UDP - Auto DNS will test and select best");
                    } else {
                        transportAddr.setText("1.1.1.1:53");
                        transportAddr.setEnabled(true);
                        appendLog("Transport: UDP (manual)");
                    }
                    break;
            }
            saveSettings();
        });
    }

    private void setupDnsSourceDropdown() {
        // Get list of DNS sources
        List<String> sources = new ArrayList<>();
        sources.add("Global DNS");

        // Add custom lists
        List<com.dnstt.client.models.CustomDnsList> customLists = dnsConfigManager.getCustomLists();
        for (com.dnstt.client.models.CustomDnsList list : customLists) {
            sources.add(list.getName());
        }

        // Create adapter for dropdown
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, sources);
        dnsSourceDropdown.setAdapter(adapter);

        // Make it non-editable but clickable (dropdown only)
        dnsSourceDropdown.setKeyListener(null);
        dnsSourceDropdown.setFocusable(false);
        dnsSourceDropdown.setClickable(true);

        // Set current selection
        String selectedSource = dnsConfigManager.getSelectedSourceDisplayName();
        dnsSourceDropdown.setText(selectedSource, false);

        // Handle dropdown item selection
        dnsSourceDropdown.setOnItemClickListener((parent, view, position, id) -> {
            String selected = sources.get(position);

            if (selected.equals("Global DNS")) {
                dnsConfigManager.setSelectedSource(DnsConfigManager.SOURCE_GLOBAL, null);
                appendLog("DNS Source changed to: Global DNS");
                Toast.makeText(this, "Using Global DNS for Auto DNS", Toast.LENGTH_SHORT).show();
            } else {
                // Find the custom list by name
                for (com.dnstt.client.models.CustomDnsList list : customLists) {
                    if (list.getName().equals(selected)) {
                        dnsConfigManager.setSelectedSource(DnsConfigManager.SOURCE_CUSTOM, list.getId());
                        appendLog("DNS Source changed to: " + list.getName() + " (" + list.getSize() + " servers)");
                        Toast.makeText(this, "Using " + list.getName() + " for Auto DNS", Toast.LENGTH_SHORT).show();
                        break;
                    }
                }
            }
            // Update Auto DNS label to reflect the new server count
            updateAutoDnsLabel();
            saveSettings();
        });

        // Show dropdown when clicked
        dnsSourceDropdown.setOnClickListener(v -> {
            dnsSourceDropdown.showDropDown();
        });
    }

    private void handleDnsSelection(String dnsAddress, String dnsName) {
        appendLog("DNS selected: " + dnsName + " (" + dnsAddress + ")");

        // Stop VPN if running
        if (isConnected) {
            appendLog("Stopping current connection...");
            disconnect();

            // Wait a moment for disconnect to complete
            handler.postDelayed(() -> applySelectedDns(dnsAddress, dnsName), 1500);
        } else {
            applySelectedDns(dnsAddress, dnsName);
        }
    }

    private void applySelectedDns(String dnsAddress, String dnsName) {
        // Disable auto DNS
        useAutoDns = false;
        autoDnsSwitch.setChecked(false);
        updateAutoDnsLabel();

        // Set UDP transport
        transportType.setText("UDP", false);

        // Set the DNS address
        transportAddr.setText(dnsAddress);
        transportAddr.setEnabled(true);

        // Update visibility
        dohProviderLayout.setVisibility(View.GONE);
        transportAddrLayout.setVisibility(View.VISIBLE);

        appendLog("Ready to connect with " + dnsName);
        appendLog("Auto DNS disabled - using selected DNS");

        saveSettings();

        Toast.makeText(this, "DNS configured: " + dnsName, Toast.LENGTH_SHORT).show();
    }

    private void connect() {
        if (!hasValidSettings()) {
            appendLog("Error: Domain and public key are required");
            return;
        }

        // Reset reconnect state for new manual connection
        userInitiatedDisconnect = false;
        reconnectAttempts = 0;

        saveSettings();
        setInputsEnabled(false);

        String type = transportType.getText().toString();
        String dom = getDomain();
        int numTunnels = 8;
        try {
            numTunnels = Integer.parseInt(getText(tunnels));
        } catch (NumberFormatException ignored) {}

        // If using Auto DNS and UDP, test resolvers and select the best one
        if (useAutoDns && type.equalsIgnoreCase("UDP")) {
            appendLog("Auto DNS: Testing resolvers to find best one...");
            // Use selected DNS source
            testAndConnectWithBestResolver(dom, numTunnels);
            return;
        }

        // Manual mode or non-UDP transport
        String addr = getText(transportAddr);
        appendLog("Connecting to " + dom);
        appendLog("Transport: " + type + " via " + addr);
        appendLog("Tunnels: " + numTunnels);

        if (vpnMode) {
            appendLog("Requesting VPN permission...");
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent);
            } else {
                appendLog("VPN permission already granted");
                startVpnService();
            }
        } else {
            appendLog("Starting SOCKS5 proxy mode...");
            connectSocksProxy();
        }
    }

    private void testAndConnectWithBestResolver(String dom, int numTunnels) {
        if (isSearching) {
            appendLog("Search already in progress");
            return;
        }

        String pubkeyHex = getText(pubkey);
        if (pubkeyHex == null || pubkeyHex.isEmpty()) {
            appendLog("Error: Public key is required");
            return;
        }

        // First check if the selected DNS source has any servers (without lastSuccessful fallback)
        String sourceServers = dnsConfigManager.getDnsServersForAutoSearch();
        if (sourceServers == null || sourceServers.trim().isEmpty()) {
            String sourceName = dnsConfigManager.getSelectedSourceDisplayName();
            appendLog("ERROR: DNS source '" + sourceName + "' is empty");
            appendLog("Please add DNS servers or switch to Global DNS");
            Toast.makeText(this, "DNS list '" + sourceName + "' is empty. Add servers or switch to Global DNS.", Toast.LENGTH_LONG).show();
            connectButton.setText(R.string.connect);
            statusText.setText(R.string.status_disconnected);
            statusText.setTextColor(getColor(R.color.disconnected));
            statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
            setInputsEnabled(true);
            return;
        }

        // Get resolvers with prioritization (last successful first, no exclusions)
        String resolversStr = dnsConfigManager.getDnsServersForAutoSearchWithPriority(null);

        // Parse resolvers into list
        java.util.List<String> resolverList = new java.util.ArrayList<>();
        for (String line : resolversStr.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                resolverList.add(line);
            }
        }

        int totalResolvers = resolverList.size();
        if (totalResolvers == 0) {
            appendLog("ERROR: No DNS servers available in selected source");
            appendLog("Please add DNS servers or switch to Global DNS");
            Toast.makeText(this, "No DNS servers available", Toast.LENGTH_SHORT).show();
            connectButton.setText(R.string.connect);
            statusText.setText(R.string.status_disconnected);
            statusText.setTextColor(getColor(R.color.disconnected));
            statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
            setInputsEnabled(true);
            return;
        }

        // Update button to show testing state
        isSearching = true;
        cancelSearch = false;
        connectButton.setText("Cancel");
        statusText.setText("Finding working resolver...");
        statusText.setTextColor(getColor(R.color.connecting));
        statusCircle.setBackgroundResource(R.drawable.status_circle_connecting);

        appendLog("Hybrid two-phase testing " + totalResolvers + " resolvers...");
        appendLog("Phase 1: Native Java DNS (fast)");
        appendLog("Phase 2: Go tunnel verification");

        final long searchStartTime = System.currentTimeMillis();

        // Configuration - use advanced settings directly
        final int phase1Concurrency = dnsDigConcurrency;     // DNS Dig Concurrency (Phase 1)
        final int phase2Concurrency = dnsTunnelConcurrency;  // DNS Tunnel Concurrency (Phase 2)
        final int phase1TimeoutMs = (int) dnsTimeout;
        final int phase2MaxToTest = 50;  // Test top 50 fastest resolvers in phase 2
        final long maxLatencyMs = 1000;  // Consider resolvers under 1000ms

        appendLog("Dig=" + phase1Concurrency + ", Tunnel=" + phase2Concurrency + ", Timeout=" + dnsTimeout + "ms");

        // Run hybrid search in background thread
        searchThread = new Thread(() -> {
            try {
                final long[] lastUIUpdate = {0};
                final long UI_UPDATE_INTERVAL = 50; // ms
                String workingResolver = null;

                // ================================================================
                // ATTEMPT 1: Try cached resolvers first (if available)
                // ================================================================
                java.util.List<FastDnsTester.ResolverResult> cachedResults = loadPhase1Cache(dom);
                if (cachedResults != null && !cachedResults.isEmpty() && !cancelSearch) {
                    final int cachedCount = cachedResults.size();
                    handler.post(() -> {
                        appendLog("Found " + cachedCount + " cached resolvers");
                        appendLog("Trying cached resolvers first (skipping Phase 1)...");
                        statusSubtext.setText("Testing cached resolvers...");
                    });

                    java.util.List<FastDnsTester.ResolverResult> cachePhase2 = cachedCount > phase2MaxToTest
                        ? cachedResults.subList(0, phase2MaxToTest)
                        : cachedResults;

                    // Run phase 2 on cached resolvers
                    workingResolver = runPhase2(cachePhase2, dom, pubkeyHex, phase2Concurrency, phase1TimeoutMs, lastUIUpdate, UI_UPDATE_INTERVAL);

                    if (workingResolver != null) {
                        handler.post(() -> appendLog("Cached resolver worked!"));
                    } else if (!cancelSearch) {
                        // Cache failed - clear it and do full scan
                        handler.post(() -> {
                            appendLog("Cached resolvers failed - clearing cache");
                            appendLog("Starting full scan...");
                        });
                        clearPhase1Cache(dom);
                    }
                }

                // ================================================================
                // ATTEMPT 2: Full scan (Phase 1 + Phase 2) if cache missed or failed
                // ================================================================
                if (workingResolver == null && !cancelSearch) {
                    // PHASE 1: Fast native Java DNS testing
                    handler.post(() -> {
                        appendLog("Phase 1: Testing " + totalResolvers + " DNS resolvers (Java native)...");
                        statusSubtext.setText("Scanning DNS (first time only, please wait)...");
                    });

                    java.util.List<FastDnsTester.ResolverResult> phase1Results = FastDnsTester.testResolvers(
                        resolverList,
                        dom,
                        phase1TimeoutMs,
                        phase1Concurrency,
                        new FastDnsTester.Callback() {
                            @Override
                            public void onProgress(int tested, int total, String currentResolver) {
                                if (cancelSearch) return;
                                long now = System.currentTimeMillis();
                                if (now - lastUIUpdate[0] < UI_UPDATE_INTERVAL) return;
                                lastUIUpdate[0] = now;

                                handler.post(() -> {
                                    statusText.setText("DNS Scan: " + tested + "/" + total);
                                });
                            }

                            @Override
                            public void onPhaseComplete(int passedCount, int totalTested, java.util.List<FastDnsTester.ResolverResult> results) {
                                if (cancelSearch) return;
                                handler.post(() -> {
                                    appendLog("Phase 1 complete: " + passedCount + "/" + totalTested + " passed DNS test");
                                });
                            }
                        }
                    );

                    if (cancelSearch) {
                        handler.post(() -> {
                            isSearching = false;
                            appendLog("DNS search cancelled by user");
                            connectButton.setText(R.string.connect);
                            statusText.setText(R.string.status_disconnected);
                            statusText.setTextColor(getColor(R.color.disconnected));
                            statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
                            setInputsEnabled(true);
                            cancelSearch = false;
                        });
                        return;
                    }

                    // Save phase 1 results to cache for next time
                    savePhase1Cache(dom, phase1Results);

                    // Get top fastest resolvers for phase 2
                    java.util.List<FastDnsTester.ResolverResult> phase2Candidates =
                        FastDnsTester.getTopFastest(phase1Results, phase2MaxToTest, maxLatencyMs);

                    if (phase2Candidates.isEmpty()) {
                        // Fallback: take any successful resolvers
                        phase2Candidates = FastDnsTester.getTopFastest(phase1Results, phase2MaxToTest, -1);
                    }

                    if (phase2Candidates.isEmpty()) {
                        handler.post(() -> {
                            isSearching = false;
                            long duration = System.currentTimeMillis() - searchStartTime;
                            appendLog("ERROR: No resolvers passed DNS test after " + (duration / 1000) + " seconds");
                            appendLog("Try switching to DoH or DoT transport");
                            connectButton.setText(R.string.connect);
                            statusText.setText(R.string.status_disconnected);
                            statusText.setTextColor(getColor(R.color.disconnected));
                            statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
                            setInputsEnabled(true);
                        });
                        return;
                    }

                    // PHASE 2: Go tunnel verification
                    workingResolver = runPhase2(phase2Candidates, dom, pubkeyHex, phase2Concurrency, phase1TimeoutMs, lastUIUpdate, UI_UPDATE_INTERVAL);
                }

                final long searchDuration = System.currentTimeMillis() - searchStartTime;
                final String finalResolver = workingResolver;

                handler.post(() -> {
                    isSearching = false;

                    if (cancelSearch) {
                        appendLog("DNS search cancelled by user");
                        connectButton.setText(R.string.connect);
                        statusText.setText(R.string.status_disconnected);
                        statusText.setTextColor(getColor(R.color.disconnected));
                        statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
                        setInputsEnabled(true);
                        cancelSearch = false;
                        return;
                    }

                    if (finalResolver == null || finalResolver.isEmpty()) {
                        appendLog("ERROR: No working resolver found after " + (searchDuration / 1000) + " seconds");
                        appendLog("Try switching to DoH or DoT transport");
                        connectButton.setText(R.string.connect);
                        statusText.setText(R.string.status_disconnected);
                        statusText.setTextColor(getColor(R.color.disconnected));
                        statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
                        setInputsEnabled(true);
                        return;
                    }

                    // Save successful DNS for future prioritization
                    currentConnectedDns = finalResolver;
                    dnsConfigManager.saveLastSuccessfulDns(finalResolver);

                    appendLog("====================================");
                    appendLog("USING DNS: " + finalResolver);
                    appendLog("Search completed in " + (searchDuration / 1000.0) + "s");
                    appendLog("====================================");
                    transportAddr.setText(finalResolver);
                    connectButton.setText(R.string.disconnect);

                    // Now connect with the working resolver
                    appendLog("Connecting via " + finalResolver);

                    if (vpnMode) {
                        appendLog("Requesting VPN permission...");
                        Intent vpnIntent = VpnService.prepare(MainActivity.this);
                        if (vpnIntent != null) {
                            vpnPermissionLauncher.launch(vpnIntent);
                        } else {
                            appendLog("VPN permission already granted");
                            startVpnService();
                        }
                    } else {
                        appendLog("Starting SOCKS5 proxy mode...");
                        connectSocksProxy();
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    isSearching = false;
                    appendLog("ERROR: Search failed - " + e.getMessage());
                    e.printStackTrace();
                    connectButton.setText(R.string.connect);
                    statusText.setText(R.string.status_disconnected);
                    statusText.setTextColor(getColor(R.color.disconnected));
                    statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
                    setInputsEnabled(true);
                });
            }
        }, "HybridDNSSearchThread");
        searchThread.start();
    }

    /**
     * Run Phase 2 (Go tunnel verification) on a list of resolver candidates.
     * Returns the first working resolver, or null if none work.
     */
    private String runPhase2(
            java.util.List<FastDnsTester.ResolverResult> candidates,
            String domain,
            String pubkeyHex,
            int concurrency,
            int timeoutMs,
            long[] lastUIUpdate,
            long uiUpdateInterval) {

        if (candidates == null || candidates.isEmpty() || cancelSearch) {
            return null;
        }

        final int phase2Total = candidates.size();
        handler.post(() -> {
            appendLog("Phase 2: Verifying " + phase2Total + " resolvers (Go tunnel)...");
            statusSubtext.setText("Phase 2: Tunnel verification...");
        });

        // Build resolver string for Go
        StringBuilder phase2Resolvers = new StringBuilder();
        for (FastDnsTester.ResolverResult r : candidates) {
            phase2Resolvers.append(r.resolver).append("\n");
        }

        // Use Go only for tunnel verification
        mobile.TwoPhaseConfig config = Mobile.newTwoPhaseConfig();
        config.setPhase1Concurrency(phase2Total);  // Skip phase 1 in Go - we already did it
        config.setPhase1TimeoutMs(500);  // Very short - just pass through
        config.setPhase2Concurrency(concurrency);  // Use advanced settings
        config.setPhase2TimeoutMs(timeoutMs);  // Use timeout from settings
        config.setPhase2MaxToTest(phase2Total);  // Test all candidates
        config.setMaxLatencyMs(10000);  // Accept all (we pre-filtered)

        final String[] foundResolver = {null};
        final long[] foundLatency = {0};

        String workingResolver = Mobile.findWorkingResolverTwoPhase(
            phase2Resolvers.toString(),
            domain,
            pubkeyHex,
            config,
            new mobile.TwoPhaseCallback() {
                @Override
                public void onPhaseChange(long phase, String message) {
                    if (cancelSearch || phase == 1) return;
                    handler.post(() -> statusSubtext.setText(message));
                }

                @Override
                public void onProgress(long phase, long tested, long total, String currentResolver) {
                    if (cancelSearch || phase == 1) return;
                    long now = System.currentTimeMillis();
                    if (now - lastUIUpdate[0] < uiUpdateInterval) return;
                    lastUIUpdate[0] = now;

                    handler.post(() -> statusText.setText("Tunnel Test: " + tested + "/" + total));
                }

                @Override
                public void onPhaseComplete(long phase, long passedCount, long totalTested) {
                    if (cancelSearch || phase == 1) return;
                    handler.post(() -> appendLog("Phase 2 complete: " + passedCount + " resolvers verified"));
                }

                @Override
                public void onResolverFound(String resolver, long latencyMs) {
                    if (cancelSearch) return;
                    foundResolver[0] = resolver;
                    foundLatency[0] = latencyMs;
                    handler.post(() -> {
                        appendLog("FOUND: " + resolver + " (" + latencyMs + "ms)");
                        currentLatencyMs = latencyMs;
                        latencyText.setText(latencyMs + " ms");
                    });
                }
            }
        );

        return workingResolver;
    }

    private void attemptAutoReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            appendLog("Auto-reconnect: Max attempts (" + MAX_RECONNECT_ATTEMPTS + ") reached, giving up");
            Toast.makeText(this, "Connection lost. Max reconnect attempts reached.", Toast.LENGTH_LONG).show();
            currentConnectedDns = null;
            reconnectAttempts = 0;
            return;
        }

        reconnectAttempts++;
        String failedDns = currentConnectedDns;

        appendLog("====================================");
        appendLog("AUTO-RECONNECT: Attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS);
        if (failedDns != null) {
            appendLog("Previous DNS failed: " + failedDns);
            // Deprioritize the failed DNS
            dnsConfigManager.moveDnsToEnd(failedDns);
        }
        appendLog("====================================");

        if (statusSubtext != null) {
            statusSubtext.setText("Reconnecting... (attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");
        }

        // Delay before reconnecting to avoid rapid reconnection loops
        handler.postDelayed(() -> {
            if (userInitiatedDisconnect) {
                appendLog("Auto-reconnect cancelled by user");
                return;
            }

            String dom = getDomain();
            int numTunnels = 8;
            try {
                numTunnels = Integer.parseInt(getText(tunnels));
            } catch (NumberFormatException ignored) {}

            appendLog("Starting auto-reconnect with different DNS...");
            testAndConnectWithBestResolver(dom, numTunnels);
        }, RECONNECT_DELAY_MS);
    }

    private void retryWithDifferentDns() {
        if (!isConnected || currentConnectedDns == null) {
            return;
        }

        appendLog("Moving " + currentConnectedDns + " to end of list");

        // Move current DNS to the end of the list
        dnsConfigManager.moveDnsToEnd(currentConnectedDns);

        appendLog("Retrying with different DNS from reordered list");

        // Disconnect current VPN
        disconnect();

        // Wait for disconnect to complete, then search for new DNS
        handler.postDelayed(() -> {
            String dom = getDomain();
            int numTunnels = 8;
            try {
                numTunnels = Integer.parseInt(getText(tunnels));
            } catch (NumberFormatException ignored) {}

            // Get resolvers with new order (failed DNS now at the end)
            String resolvers = dnsConfigManager.getDnsServersForAutoSearchWithPriority(null);

            if (resolvers == null || resolvers.trim().isEmpty()) {
                appendLog("ERROR: No DNS servers available");
                Toast.makeText(this, "No DNS servers available", Toast.LENGTH_SHORT).show();
                return;
            }

            appendLog("Searching from top of reordered list...");
            testAndConnectWithBestResolver(dom, numTunnels);
        }, 1500);
    }

    private void cancelDnsSearch() {
        if (!isSearching) {
            return;
        }

        appendLog("Cancelling DNS search...");
        cancelSearch = true;
        isSearching = false;

        // Shutdown parallel DNS test executor immediately
        if (dnsTestExecutor != null) {
            appendLog("Stopping parallel DNS tests...");
            dnsTestExecutor.shutdownNow();
            dnsTestExecutor = null;
        }

        // Interrupt the search thread
        if (searchThread != null && searchThread.isAlive()) {
            searchThread.interrupt();
            searchThread = null;
        }

        // Update UI immediately
        handler.post(() -> {
            connectButton.setText(R.string.connect);
            connectButton.setEnabled(true);
            statusText.setText(R.string.status_disconnected);
            statusText.setTextColor(getColor(R.color.disconnected));
            statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
            setInputsEnabled(true);
        });
    }

    private void startVpnService() {
        appendLog("Starting VPN service...");

        // Force stop any previous service first to avoid port conflicts
        try {
            Intent stopIntent = new Intent(this, DnsttVpnService.class);
            stopIntent.setAction(DnsttVpnService.ACTION_STOP);
            startService(stopIntent);
            Thread.sleep(500); // Give it time to stop
        } catch (Exception e) {
            // Ignore
        }

        Intent intent = new Intent(this, DnsttVpnService.class);
        intent.setAction(DnsttVpnService.ACTION_START);
        intent.putExtra(DnsttVpnService.EXTRA_TRANSPORT_TYPE, transportType.getText().toString().toLowerCase());
        intent.putExtra(DnsttVpnService.EXTRA_TRANSPORT_ADDR, getText(transportAddr));
        intent.putExtra(DnsttVpnService.EXTRA_DOMAIN, getDomain());
        intent.putExtra(DnsttVpnService.EXTRA_PUBKEY, getText(pubkey));

        try {
            intent.putExtra(DnsttVpnService.EXTRA_TUNNELS, Integer.parseInt(getText(tunnels)));
        } catch (NumberFormatException e) {
            intent.putExtra(DnsttVpnService.EXTRA_TUNNELS, 8);
        }

        startForegroundService(intent);
    }

    private void connectSocksProxy() {
        // Stop any previous client first to avoid port conflicts
        if (client != null) {
            try {
                client.stop();
            } catch (Exception e) {
                // Ignore
            }
            client = null;
        }

        Config config = mobile.Mobile.newConfig();

        String type = transportType.getText().toString().toLowerCase();
        config.setTransportType(type);
        config.setTransportAddr(getText(transportAddr));
        config.setDomain(getDomain());
        config.setPubkeyHex(getText(pubkey));
        config.setListenAddr("127.0.0.1:1080");

        try {
            config.setTunnels(Integer.parseInt(getText(tunnels)));
        } catch (NumberFormatException e) {
            config.setTunnels(8);
        }

        config.setMTU(1232);
        config.setUTLSFingerprint("none"); // Use standard TLS - uTLS causes errors on Android
        config.setUseZstd(true); // Enable zstd compression (server has it on by default)
        appendLog("Zstd compression: enabled");

        new Thread(() -> {
            try {
                appendLog("Establishing tunnels...");
                client.start(config);
            } catch (Exception e) {
                handler.post(() -> {
                    appendLog("Connection error: " + e.getMessage());
                    appendLog("Stack trace: " + android.util.Log.getStackTraceString(e));
                    setInputsEnabled(true);
                });
            }
        }).start();
    }

    /**
     * Cleanup any orphaned services from previous sessions.
     * This handles the case where the app crashes but the service keeps running.
     */
    private void cleanupOrphanedServices() {
        if (!isConnected) {
            // Force stop VPN service if we think we're disconnected
            try {
                Intent stopIntent = new Intent(this, DnsttVpnService.class);
                stopIntent.setAction(DnsttVpnService.ACTION_STOP);
                startService(stopIntent);
            } catch (Exception e) {
                // Ignore - service might not be running
            }

            // Also stop any SOCKS client that might be lingering
            if (client != null) {
                try {
                    client.stop();
                } catch (Exception e) {
                    // Ignore
                }
                client = null;
            }
        }
    }

    private void disconnect() {
        userInitiatedDisconnect = true;  // Mark as user-initiated
        appendLog("====================================");
        appendLog("Disconnecting and stopping all tunnels...");
        appendLog("====================================");
        connectButton.setEnabled(false);  // Prevent double-clicks
        connectButton.setText("Stopping...");

        // Cancel any ongoing search
        if (isSearching) {
            cancelDnsSearch();
        }

        // Force shutdown any remaining DNS test executor
        if (dnsTestExecutor != null) {
            appendLog("Stopping parallel DNS tests...");
            dnsTestExecutor.shutdownNow();
            dnsTestExecutor = null;
        }

        // Interrupt any search threads
        if (searchThread != null && searchThread.isAlive()) {
            appendLog("Stopping DNS search thread...");
            searchThread.interrupt();
            searchThread = null;
        }

        if (vpnMode) {
            // Stop VPN service
            appendLog("Stopping VPN service and all tunnels...");
            Intent intent = new Intent(this, DnsttVpnService.class);
            intent.setAction(DnsttVpnService.ACTION_STOP);
            startForegroundService(intent);
        } else {
            appendLog("Stopping SOCKS proxy and all tunnels...");
            new Thread(() -> {
                try {
                    if (client != null) {
                        appendLog("Stopping DNSTT client...");
                        client.stop();
                        appendLog("DNSTT client stopped");
                        client = null;
                    }
                } catch (Exception e) {
                    handler.post(() -> appendLog("Error stopping client: " + e.getMessage()));
                }
                handler.post(() -> {
                    connectButton.setEnabled(true);
                    setInputsEnabled(true);
                    appendLog("All tunnels stopped");
                });
            }, "DisconnectThread").start();
        }
    }

    private String getText(TextInputEditText editText) {
        if (editText == null) {
            return "";
        }
        CharSequence text = editText.getText();
        return text != null ? text.toString().trim() : "";
    }

    private String getDomain() {
        String dom = getText(domain);
        // Remove http:// or https:// prefix if present
        if (dom.startsWith("https://")) {
            dom = dom.substring(8);
        } else if (dom.startsWith("http://")) {
            dom = dom.substring(7);
        }
        // Remove trailing slash
        if (dom.endsWith("/")) {
            dom = dom.substring(0, dom.length() - 1);
        }
        return dom;
    }

    private void setInputsEnabled(boolean enabled) {
        transportType.setEnabled(enabled);
        // Only enable transport address if not in Auto DNS + UDP mode
        if (enabled && useAutoDns && transportType.getText().toString().equalsIgnoreCase("UDP")) {
            transportAddr.setEnabled(false);
        } else {
            transportAddr.setEnabled(enabled);
        }
        domain.setEnabled(enabled);
        pubkey.setEnabled(enabled);
        tunnels.setEnabled(enabled);
        vpnModeSwitch.setEnabled(enabled);
        autoConnectSwitch.setEnabled(enabled);
        autoDnsSwitch.setEnabled(enabled);
    }

    private void showFullLogDialog() {
        String logs = logText != null && logText.getText() != null
            ? logText.getText().toString()
            : "No logs available";

        // Create a scrollable TextView for the dialog
        ScrollView scrollView = new ScrollView(this);
        scrollView.setPadding(32, 32, 32, 32);

        TextView textView = new TextView(this);
        textView.setText(logs);
        textView.setTextSize(12);
        textView.setTypeface(android.graphics.Typeface.MONOSPACE);
        textView.setTextColor(getColor(R.color.text_primary));
        textView.setTextIsSelectable(true);
        scrollView.addView(textView);

        // Scroll to bottom after layout
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Full Log")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy", (dialog, which) -> {
                android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("DNSTT Log", logs);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private void appendLog(String message) {
        if (handler == null || logText == null) {
            return;
        }

        handler.post(() -> {
            try {
                String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(new java.util.Date());
                String logLine = "[" + timestamp + "] " + message;

                String current = logText.getText() != null ? logText.getText().toString() : "";
                String newText = current.isEmpty() ? logLine : current + "\n" + logLine;

                // Keep last 100 lines
                String[] lines = newText.split("\n");
                if (lines.length > 100) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = lines.length - 100; i < lines.length; i++) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(lines[i]);
                    }
                    newText = sb.toString();
                }
                logText.setText(newText);

                // Auto-scroll to bottom to show latest log
                if (logScrollView != null) {
                    logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                }
            } catch (Exception e) {
                // Ignore errors in logging
            }
        });
    }

    @Override
    public void onStatusChange(long state, String message) {
        if (handler == null) {
            return;
        }

        handler.post(() -> {
            try {
                // Don't show internal VPN logs in status bar, just in log
                if (state == -1) {
                    appendLog(message);
                    return;
                }

                appendLog(message);

                // Reset searching state when connection state changes
                if (state != 1) { // Not connecting
                    isSearching = false;
                    cancelSearch = false;
                }

                switch ((int) state) {
                    case 0: // Stopped
                        if (statusText != null) statusText.setText(R.string.status_disconnected);
                        if (statusText != null) statusText.setTextColor(getColor(R.color.disconnected));
                        if (statusSubtext != null) statusSubtext.setText("Tap connect to start");
                        if (statusCircle != null) statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
                        if (connectButton != null) connectButton.setText(R.string.connect);
                        if (connectButton != null) connectButton.setEnabled(true);
                        isConnected = false;
                        setInputsEnabled(true);
                        // Hide stats card
                        if (statsCard != null) statsCard.setVisibility(View.GONE);
                        if (qualityText != null) qualityText.setText("--");
                        if (latencyText != null) latencyText.setText("-- ms");
                        if (speedText != null) speedText.setText("-- KB/s");
                        // Hide retry button when disconnected
                        if (retryButton != null) retryButton.setVisibility(View.GONE);

                        // Auto-reconnect if tunnel dropped unexpectedly
                        if (wasConnectedBeforeDisconnect && !userInitiatedDisconnect && useAutoDns && autoReconnectEnabled) {
                            attemptAutoReconnect();
                        } else {
                            currentConnectedDns = null;
                        }
                        wasConnectedBeforeDisconnect = false;

                        lastBytesIn = 0;
                        lastBytesOut = 0;
                        lastUpdateTime = 0;
                        smoothedSpeedKBps = 0;
                        break;
                    case 1: // Connecting
                        if (statusText != null) statusText.setText(R.string.status_connecting);
                        if (statusText != null) statusText.setTextColor(getColor(R.color.connecting));
                        if (statusSubtext != null) statusSubtext.setText("Establishing connection...");
                        if (statusCircle != null) statusCircle.setBackgroundResource(R.drawable.status_circle_connecting);
                        if (connectButton != null) connectButton.setText(R.string.disconnect);
                        isConnected = false;
                        break;
                    case 2: // Connected
                        if (statusText != null) statusText.setText(R.string.status_connected);
                        if (statusText != null) statusText.setTextColor(getColor(R.color.connected));
                        if (statusSubtext != null) statusSubtext.setText("Your traffic is protected");
                        if (statusCircle != null) statusCircle.setBackgroundResource(R.drawable.status_circle_connected);
                        if (connectButton != null) connectButton.setText(R.string.disconnect);
                        isConnected = true;

                        // Reset auto-reconnect state on successful connection
                        wasConnectedBeforeDisconnect = true;
                        userInitiatedDisconnect = false;
                        reconnectAttempts = 0;

                        // Log connected DNS prominently
                        if (currentConnectedDns != null) {
                            appendLog("====================================");
                            appendLog("CONNECTED TO DNS: " + currentConnectedDns);
                            if (currentLatencyMs > 0) {
                                appendLog("  Latency: " + currentLatencyMs + "ms");
                            }
                            appendLog("====================================");
                        }

                        // Show stats card
                        if (statsCard != null) statsCard.setVisibility(View.VISIBLE);
                        // Show retry button when connected (only if using auto DNS)
                        if (retryButton != null && useAutoDns) retryButton.setVisibility(View.VISIBLE);
                        break;
                    case 3: // Error
                        if (statusText != null) statusText.setText("Error");
                        if (statusText != null) statusText.setTextColor(getColor(R.color.disconnected));
                        if (statusSubtext != null) statusSubtext.setText("Connection failed");
                        if (statusCircle != null) statusCircle.setBackgroundResource(R.drawable.status_circle_disconnected);
                        if (connectButton != null) connectButton.setText(R.string.connect);
                        if (connectButton != null) connectButton.setEnabled(true);
                        isConnected = false;
                        setInputsEnabled(true);
                        // Hide retry button on error
                        if (retryButton != null) retryButton.setVisibility(View.GONE);

                        // Auto-reconnect on error if we were connected and it wasn't user-initiated
                        if (wasConnectedBeforeDisconnect && !userInitiatedDisconnect && useAutoDns && autoReconnectEnabled) {
                            attemptAutoReconnect();
                        } else {
                            currentConnectedDns = null;
                        }
                        wasConnectedBeforeDisconnect = false;
                        break;
                }
            } catch (Exception e) {
                // Ignore UI update errors
            }
        });
    }

    @Override
    public void onBytesTransferred(long bytesIn, long bytesOut) {
        if (handler == null) {
            return;
        }

        handler.post(() -> {
            try {
                long currentTime = System.currentTimeMillis();

                // Always update total bytes counters
                if (bytesInText != null) bytesInText.setText(formatBytes(bytesIn));
                if (bytesOutText != null) bytesOutText.setText(formatBytes(bytesOut));

                // Calculate speed if we have previous data
                if (lastUpdateTime > 0) {
                    long timeDelta = currentTime - lastUpdateTime;

                    if (timeDelta > 0) {
                        long bytesInDelta = bytesIn - lastBytesIn;
                        long bytesOutDelta = bytesOut - lastBytesOut;

                        // Detect counter reset (negative deltas) - reset tracking
                        if (bytesInDelta < 0 || bytesOutDelta < 0) {
                            lastBytesIn = bytesIn;
                            lastBytesOut = bytesOut;
                            lastUpdateTime = currentTime;
                            smoothedSpeedKBps = 0;
                            return;
                        }

                        long totalBytesDelta = bytesInDelta + bytesOutDelta;

                        // Calculate instantaneous speed in KB/s
                        double instantSpeedKBps = (totalBytesDelta / 1024.0) / (timeDelta / 1000.0);

                        // Apply exponential smoothing to prevent flickering
                        smoothedSpeedKBps = SPEED_SMOOTHING_FACTOR * instantSpeedKBps +
                                           (1 - SPEED_SMOOTHING_FACTOR) * smoothedSpeedKBps;

                        // Update speed display
                        if (speedText != null) {
                            speedText.setText(String.format("%.1f KB/s", Math.max(0, smoothedSpeedKBps)));
                        }

                        // Estimate latency from response time (rough approximation)
                        if (totalBytesDelta > 0 && currentLatencyMs == 0) {
                            long estimatedRoundTrips = Math.max(1, totalBytesDelta / 1024);
                            currentLatencyMs = timeDelta / estimatedRoundTrips;
                            if (currentLatencyMs > 0 && currentLatencyMs < 5000 && latencyText != null) {
                                latencyText.setText(currentLatencyMs + " ms");
                            }
                        }

                        // Update quality indicator based on smoothed speed
                        updateConnectionQuality(smoothedSpeedKBps);
                    }
                }

                // Always update tracking values
                lastBytesIn = bytesIn;
                lastBytesOut = bytesOut;
                lastUpdateTime = currentTime;

                // Show quality bar when connected
                if (isConnected && qualityBarLayout != null && qualityBarLayout.getVisibility() != View.VISIBLE) {
                    qualityBarLayout.setVisibility(View.VISIBLE);
                }
            } catch (Exception e) {
                // Ignore UI update errors
            }
        });
    }

    private void updateConnectionQuality(double speedKBps) {
        // Quality score based on speed (0-100)
        int qualityScore;
        String qualityLabel;
        int qualityColor;

        if (speedKBps >= 100) {
            qualityScore = 100;
            qualityLabel = "Excellent";
            qualityColor = Color.parseColor("#4CAF50"); // Green
        } else if (speedKBps >= 50) {
            qualityScore = 80;
            qualityLabel = "Good";
            qualityColor = Color.parseColor("#8BC34A"); // Light green
        } else if (speedKBps >= 20) {
            qualityScore = 60;
            qualityLabel = "Fair";
            qualityColor = Color.parseColor("#FFEB3B"); // Yellow
        } else if (speedKBps >= 5) {
            qualityScore = 40;
            qualityLabel = "Poor";
            qualityColor = Color.parseColor("#FF9800"); // Orange
        } else if (speedKBps > 0) {
            qualityScore = 20;
            qualityLabel = "Very Poor";
            qualityColor = Color.parseColor("#F44336"); // Red
        } else {
            qualityScore = 0;
            qualityLabel = "No Data";
            qualityColor = Color.parseColor("#9E9E9E"); // Gray
        }

        qualityText.setText(qualityLabel);
        qualityText.setTextColor(qualityColor);
        qualityBar.setProgress(qualityScore);
        qualityBar.getProgressDrawable().setColorFilter(qualityColor, android.graphics.PorterDuff.Mode.SRC_IN);

        // Update latency display (if we have latency data)
        if (currentLatencyMs > 0) {
            latencyText.setText(currentLatencyMs + " ms");
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString("transportType", transportType.getText().toString())
                .putString("dohProvider", dohProvider.getText().toString())
                .putString("transportAddr", getText(transportAddr))
                .putString("domain", getDomain())
                .putString("pubkey", getText(pubkey))
                .putString("tunnels", getText(tunnels))
                .putBoolean("vpnMode", vpnMode)
                .putBoolean("autoConnect", autoConnect)
                .putBoolean("useAutoDns", useAutoDns)
                .putInt("dnsDigConcurrency", dnsDigConcurrency)
                .putInt("dnsTunnelConcurrency", dnsTunnelConcurrency)
                .putInt("dnsTimeout", dnsTimeout)
                .apply();
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        String type = prefs.getString("transportType", "DoH");
        transportType.setText(type, false);

        // Load DoH provider
        String provider = prefs.getString("dohProvider", "Google");
        dohProvider.setText(provider, false);

        transportAddr.setText(prefs.getString("transportAddr", "https://dns.google/dns-query"));
        domain.setText(prefs.getString("domain", "t.example.com"));
        pubkey.setText(prefs.getString("pubkey", ""));
        tunnels.setText(prefs.getString("tunnels", "8"));

        vpnMode = prefs.getBoolean("vpnMode", true);
        vpnModeSwitch.setChecked(vpnMode);

        autoConnect = prefs.getBoolean("autoConnect", false);
        autoConnectSwitch.setChecked(autoConnect);

        useAutoDns = prefs.getBoolean("useAutoDns", true);
        autoDnsSwitch.setChecked(useAutoDns);
        updateAutoDnsLabel();

        // Load performance settings
        dnsDigConcurrency = prefs.getInt("dnsDigConcurrency", 50);
        dnsTunnelConcurrency = prefs.getInt("dnsTunnelConcurrency", 10);
        dnsTimeout = prefs.getInt("dnsTimeout", 3000);

        // Set UI values for performance settings
        if (dnsDigConcurrencyInput != null) {
            dnsDigConcurrencyInput.setText(String.valueOf(dnsDigConcurrency));
        }
        if (dnsTunnelConcurrencyInput != null) {
            dnsTunnelConcurrencyInput.setText(String.valueOf(dnsTunnelConcurrency));
        }
        if (dnsTimeoutInput != null) {
            dnsTimeoutInput.setText(String.valueOf(dnsTimeout));
        }

        // Auto DNS always requires UDP - enforce this on load
        if (useAutoDns) {
            transportType.setText("UDP", false);
            dohProviderLayout.setVisibility(View.GONE);
            transportAddrLayout.setVisibility(View.VISIBLE);
            transportAddr.setText("(auto-select best resolver)");
            transportAddr.setEnabled(false);
        } else {
            // Update visibility based on transport type for manual mode
            updateDohProviderVisibility();
        }
    }

    // ============================================================================
    // Phase 1 DNS Cache - stores successful resolvers to skip phase 1 on reconnect
    // ============================================================================

    private static final String CACHE_KEY_PREFIX = "dns_cache_";
    private static final long CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000; // 24 hours

    /**
     * Get cache key that includes DNS source to prevent cross-source cache usage.
     */
    private String getCacheKey(String domain) {
        String sourceId = dnsConfigManager.getSelectedSource();
        if (DnsConfigManager.SOURCE_CUSTOM.equals(sourceId)) {
            // Include the custom list ID for custom sources
            String listId = dnsConfigManager.getSelectedListId();
            if (listId != null) {
                sourceId = "custom_" + listId;
            }
        }
        return CACHE_KEY_PREFIX + sourceId + "_" + domain.replace(".", "_");
    }

    /**
     * Save phase 1 results to cache for the given domain and current DNS source.
     * Format: "resolver1:latency1,resolver2:latency2,..."
     */
    private void savePhase1Cache(String domain, java.util.List<FastDnsTester.ResolverResult> results) {
        if (results == null || results.isEmpty()) return;

        StringBuilder cache = new StringBuilder();
        int count = 0;
        for (FastDnsTester.ResolverResult r : results) {
            if (r.success && count < 100) { // Cache top 100 successful resolvers
                if (cache.length() > 0) cache.append(",");
                cache.append(r.resolver).append(":").append(r.latencyMs);
                count++;
            }
        }

        if (count > 0) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String cacheKey = getCacheKey(domain);
            prefs.edit()
                .putString(cacheKey, cache.toString())
                .putLong(cacheKey + "_time", System.currentTimeMillis())
                .apply();
            appendLog("Cached " + count + " resolvers for " + domain);
        }
    }

    /**
     * Load cached phase 1 results for the given domain and current DNS source.
     * Returns null if cache is empty or expired.
     */
    private java.util.List<FastDnsTester.ResolverResult> loadPhase1Cache(String domain) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String cacheKey = getCacheKey(domain);

        long cacheTime = prefs.getLong(cacheKey + "_time", 0);
        if (System.currentTimeMillis() - cacheTime > CACHE_EXPIRY_MS) {
            return null; // Cache expired
        }

        String cache = prefs.getString(cacheKey, null);
        if (cache == null || cache.isEmpty()) {
            return null;
        }

        java.util.List<FastDnsTester.ResolverResult> results = new ArrayList<>();
        String[] entries = cache.split(",");
        for (String entry : entries) {
            String[] parts = entry.split(":");
            if (parts.length == 2) {
                try {
                    String resolver = parts[0];
                    long latency = Long.parseLong(parts[1]);
                    results.add(new FastDnsTester.ResolverResult(resolver, latency, true, null));
                } catch (NumberFormatException e) {
                    // Skip invalid entries
                }
            }
        }

        return results.isEmpty() ? null : results;
    }

    /**
     * Clear the phase 1 cache for a domain and current DNS source.
     */
    private void clearPhase1Cache(String domain) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String cacheKey = getCacheKey(domain);
        prefs.edit()
            .remove(cacheKey)
            .remove(cacheKey + "_time")
            .apply();
        appendLog("Cleared resolver cache for " + domain);
    }

    /**
     * Clear all DNS cache entries (called from UI button).
     */
    private void clearAllDnsCache() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // Find and remove all cache keys
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(CACHE_KEY_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        appendLog("App closing - cleaning up all resources...");

        // Cancel any ongoing search
        if (isSearching) {
            cancelSearch = true;
            if (searchThread != null && searchThread.isAlive()) {
                searchThread.interrupt();
                searchThread = null;
            }
        }

        // Shutdown DNS test executor
        if (dnsTestExecutor != null) {
            appendLog("Shutting down DNS test executor...");
            dnsTestExecutor.shutdownNow();
            dnsTestExecutor = null;
        }

        // Stop client if connected in non-VPN mode
        if (!vpnMode && client != null) {
            try {
                appendLog("Stopping SOCKS client on app close...");
                client.stop();
                appendLog("SOCKS client stopped");
            } catch (Exception e) {
                appendLog("Error stopping client on destroy: " + e.getMessage());
            }
        }

        // For VPN mode, ensure service is stopped
        if (vpnMode && isConnected) {
            try {
                appendLog("Stopping VPN service on app close...");
                Intent intent = new Intent(this, DnsttVpnService.class);
                intent.setAction(DnsttVpnService.ACTION_STOP);
                startService(intent);
            } catch (Exception e) {
                appendLog("Error stopping VPN on destroy: " + e.getMessage());
            }
        }

        // Remove UI callback to prevent memory leak
        DnsttVpnService.setUiCallback(null);

        // Cleanup app updater
        if (appUpdater != null) {
            appUpdater.cleanup();
            appUpdater = null;
        }

        // Remove all handler callbacks to prevent memory leaks
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        // Clear references
        client = null;

        appendLog("App cleanup completed");
    }

    private void checkForUpdates() {
        updateButton.setEnabled(false);
        updateButton.setText("Checking...");
        appendLog("Checking for updates...");

        appUpdater.setCallback(new AppUpdater.UpdateCallback() {
            @Override
            public void onCheckStarted() {
                // Already showing "Checking..."
            }

            @Override
            public void onUpdateAvailable(String version, String releaseNotes, String downloadUrl) {
                updateButton.setEnabled(true);
                updateButton.setText("Update");
                appendLog("Update available: v" + version);

                new MaterialAlertDialogBuilder(MainActivity.this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                        .setTitle("Update Available")
                        .setMessage("Version " + version + " is available.\n\n" + releaseNotes)
                        .setPositiveButton("Download", (dialog, which) -> {
                            appUpdater.downloadUpdate(downloadUrl, version);
                        })
                        .setNegativeButton("Later", null)
                        .show();
            }

            @Override
            public void onNoUpdate(String currentVersion) {
                updateButton.setEnabled(true);
                updateButton.setText("Check Update");
                appendLog("App is up to date (v" + currentVersion + ")");

                new MaterialAlertDialogBuilder(MainActivity.this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                        .setTitle("Up to Date")
                        .setMessage("You're running the latest version (v" + currentVersion + ")")
                        .setPositiveButton("OK", null)
                        .show();
            }

            @Override
            public void onError(String message) {
                updateButton.setEnabled(true);
                updateButton.setText("Check Update");
                appendLog("Update check failed: " + message);

                new MaterialAlertDialogBuilder(MainActivity.this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                        .setTitle("Update Check Failed")
                        .setMessage("Could not check for updates:\n" + message)
                        .setPositiveButton("OK", null)
                        .show();
            }

            @Override
            public void onDownloadStarted() {
                appendLog("Downloading update...");
                updateButton.setText("Downloading...");
            }

            @Override
            public void onDownloadComplete(Uri apkUri) {
                updateButton.setEnabled(true);
                updateButton.setText("Check Update");
                appendLog("Download complete, installing...");
                appUpdater.installApk(apkUri);
            }
        });

        appUpdater.checkForUpdates();
    }
}
