package com.dnstt.client.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dnstt.client.DnsConfigManager;
import com.dnstt.client.MainActivity;
import com.dnstt.client.R;
import com.dnstt.client.adapters.DnsConfigAdapter;
import com.dnstt.client.models.DnsConfig;

import java.util.List;

import mobile.Mobile;
import mobile.ResolverCallback;

/**
 * Fragment for displaying global DNS servers
 */
public class GlobalDnsFragment extends Fragment implements DnsConfigAdapter.DnsConfigListener {

    private RecyclerView recyclerView;
    private DnsConfigAdapter adapter;
    private DnsConfigManager configManager;
    private Handler handler;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dns_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        handler = new Handler(Looper.getMainLooper());
        configManager = new DnsConfigManager(requireContext());

        recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        // Don't show edit/delete for global DNS
        adapter = new DnsConfigAdapter(false, this);
        recyclerView.setAdapter(adapter);

        // Load global DNS configs
        loadGlobalDns();
    }

    private void loadGlobalDns() {
        List<DnsConfig> configs = configManager.getGlobalDnsConfigs();
        adapter.setDnsConfigs(configs);
    }

    @Override
    public void onTest(DnsConfig config) {
        // Update status to testing
        config.setTestStatus(DnsConfig.TestStatus.TESTING);
        adapter.updateDnsConfig(config);

        // Test DNS in background thread
        new Thread(() -> {
            try {
                // Get domain and pubkey from MainActivity's shared preferences
                String domain = getActivity().getSharedPreferences("dnstt_prefs", 0)
                        .getString("domain", "t.example.com");
                String pubkey = getActivity().getSharedPreferences("dnstt_prefs", 0)
                        .getString("pubkey", "");

                if (pubkey.isEmpty()) {
                    handler.post(() -> {
                        config.setTestStatus(DnsConfig.TestStatus.FAILED);
                        config.setErrorMessage("Public key not set");
                        adapter.updateDnsConfig(config);
                        Toast.makeText(requireContext(), "Please set public key in main screen first",
                                Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // Extract IP from address
                String address = config.getAddress();
                if (address.contains(":")) {
                    address = address.substring(0, address.indexOf(":"));
                }

                final String testAddress = address;
                final long[] result = {-1};

                ResolverCallback callback = new ResolverCallback() {
                    @Override
                    public void onProgress(long tested, long total, String currentResolver) {
                    }

                    @Override
                    public void onResult(String resolver, boolean success, long latencyMs, String errorMsg) {
                        if (resolver.equals(testAddress)) {
                            if (success) {
                                result[0] = latencyMs;
                            }
                        }
                    }
                };

                String workingResolver = Mobile.findFirstWorkingResolver(
                        testAddress + "\n",
                        domain,
                        pubkey,
                        5000, // 5 second timeout
                        callback
                );

                handler.post(() -> {
                    if (workingResolver != null && !workingResolver.isEmpty()) {
                        config.setTestStatus(DnsConfig.TestStatus.SUCCESS);
                        config.setLatencyMs(result[0] > 0 ? result[0] : 0);
                        config.setLastTestTime(System.currentTimeMillis());
                    } else {
                        config.setTestStatus(DnsConfig.TestStatus.FAILED);
                        config.setErrorMessage("Connection timeout");
                    }
                    adapter.updateDnsConfig(config);
                });

            } catch (Exception e) {
                handler.post(() -> {
                    config.setTestStatus(DnsConfig.TestStatus.FAILED);
                    config.setErrorMessage(e.getMessage());
                    adapter.updateDnsConfig(config);
                });
            }
        }).start();
    }

    @Override
    public void onSelect(DnsConfig config) {
        // Send result back to MainActivity
        Intent intent = new Intent();
        intent.putExtra("selected_dns", config.getAddress());
        intent.putExtra("selected_dns_name", config.getName());

        if (getActivity() != null) {
            getActivity().setResult(MainActivity.RESULT_OK, intent);
            getActivity().finish();
        }
    }

    @Override
    public void onEdit(DnsConfig config) {
        // Not applicable for global DNS
    }

    @Override
    public void onDelete(DnsConfig config) {
        // Not applicable for global DNS
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
}
