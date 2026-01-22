package com.dnstt.client.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
import com.dnstt.client.models.CustomDnsList;
import com.dnstt.client.models.DnsConfig;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import mobile.Mobile;
import mobile.ResolverCallback;

/**
 * Fragment for managing custom DNS configurations
 */
public class CustomDnsFragment extends Fragment implements DnsConfigAdapter.DnsConfigListener {

    private static final int PICK_FILE_REQUEST = 101;

    private RecyclerView recyclerView;
    private FloatingActionButton fabAdd;
    private View emptyState;
    private DnsConfigAdapter adapter;
    private DnsConfigManager configManager;
    private Handler handler;

    private CustomDnsList currentList;
    private Spinner listSpinner;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dns_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setHasOptionsMenu(true);

        handler = new Handler(Looper.getMainLooper());
        configManager = new DnsConfigManager(requireContext());

        recyclerView = view.findViewById(R.id.recyclerView);
        fabAdd = view.findViewById(R.id.fabAdd);
        emptyState = view.findViewById(R.id.emptyState);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        // Show edit/delete for custom DNS
        adapter = new DnsConfigAdapter(true, this);
        recyclerView.setAdapter(adapter);

        // Show FAB for adding new DNS
        fabAdd.setVisibility(View.VISIBLE);
        fabAdd.setOnClickListener(v -> showAddDnsDialog());

        // Load first custom list
        loadCustomLists();
    }

    private void loadCustomLists() {
        List<CustomDnsList> lists = configManager.getCustomLists();
        if (lists.isEmpty()) {
            // Create default list
            currentList = configManager.createCustomList("My DNS List");
        } else {
            currentList = lists.get(0);
        }

        loadDnsConfigs();
    }

    private void loadDnsConfigs() {
        if (currentList != null) {
            List<DnsConfig> configs = currentList.getDnsConfigs();
            adapter.setDnsConfigs(configs);

            if (configs.isEmpty()) {
                emptyState.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
            } else {
                emptyState.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void showAddDnsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Add Custom DNS");

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText nameInput = new EditText(requireContext());
        nameInput.setHint("Name (e.g., My DNS Server)");
        layout.addView(nameInput);

        final EditText addressInput = new EditText(requireContext());
        addressInput.setHint("Address (e.g., 1.1.1.1 or 1.1.1.1:53)");
        addressInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        layout.addView(addressInput);

        final EditText descriptionInput = new EditText(requireContext());
        descriptionInput.setHint("Description (optional)");
        layout.addView(descriptionInput);

        builder.setView(layout);

        builder.setPositiveButton("Add", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            String address = addressInput.getText().toString().trim();
            String description = descriptionInput.getText().toString().trim();

            if (name.isEmpty() || address.isEmpty()) {
                Toast.makeText(requireContext(), "Name and address are required",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            // Ensure address has port
            if (!address.contains(":")) {
                address += ":53";
            }

            DnsConfig config = new DnsConfig(
                    UUID.randomUUID().toString(),
                    name,
                    address,
                    description.isEmpty() ? "Custom DNS server" : description,
                    false,
                    currentList.getName()
            );

            configManager.addDnsConfigToList(currentList.getId(), config);
            currentList = configManager.getCustomList(currentList.getId());
            loadDnsConfigs();

            Toast.makeText(requireContext(), "DNS added successfully", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void showEditDnsDialog(DnsConfig config) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Edit DNS");

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText nameInput = new EditText(requireContext());
        nameInput.setHint("Name");
        nameInput.setText(config.getName());
        layout.addView(nameInput);

        final EditText addressInput = new EditText(requireContext());
        addressInput.setHint("Address");
        addressInput.setText(config.getAddress());
        layout.addView(addressInput);

        final EditText descriptionInput = new EditText(requireContext());
        descriptionInput.setHint("Description");
        descriptionInput.setText(config.getDescription());
        layout.addView(descriptionInput);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            String address = addressInput.getText().toString().trim();
            String description = descriptionInput.getText().toString().trim();

            if (name.isEmpty() || address.isEmpty()) {
                Toast.makeText(requireContext(), "Name and address are required",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            // Ensure address has port
            if (!address.contains(":")) {
                address += ":53";
            }

            config.setName(name);
            config.setAddress(address);
            config.setDescription(description);

            configManager.updateDnsConfig(currentList.getId(), config);
            currentList = configManager.getCustomList(currentList.getId());
            loadDnsConfigs();

            Toast.makeText(requireContext(), "DNS updated successfully", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    @Override
    public void onTest(DnsConfig config) {
        // Same as GlobalDnsFragment
        config.setTestStatus(DnsConfig.TestStatus.TESTING);
        adapter.updateDnsConfig(config);

        new Thread(() -> {
            try {
                String domain = getActivity().getSharedPreferences("dnstt_prefs", 0)
                        .getString("domain", "t.example.com");
                String pubkey = getActivity().getSharedPreferences("dnstt_prefs", 0)
                        .getString("pubkey", "");

                if (pubkey.isEmpty()) {
                    handler.post(() -> {
                        config.setTestStatus(DnsConfig.TestStatus.FAILED);
                        config.setErrorMessage("Public key not set");
                        adapter.updateDnsConfig(config);
                    });
                    return;
                }

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
                        5000,
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
        showEditDnsDialog(config);
    }

    @Override
    public void onDelete(DnsConfig config) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete DNS")
                .setMessage("Are you sure you want to delete " + config.getName() + "?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    configManager.removeDnsConfig(currentList.getId(), config.getId());
                    currentList = configManager.getCustomList(currentList.getId());
                    loadDnsConfigs();
                    Toast.makeText(requireContext(), "DNS deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        menu.add(0, 1, 0, "Import from file")
                .setIcon(R.drawable.ic_import)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            openFilePicker();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("text/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_FILE_REQUEST);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_FILE_REQUEST && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri uri = data.getData();
                importDnsFromFile(uri);
            }
        }
    }

    private void importDnsFromFile(Uri uri) {
        new Thread(() -> {
            List<DnsConfig> importedConfigs = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            int lineNumber = 0;

            try {
                InputStream inputStream = requireContext().getContentResolver().openInputStream(uri);
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String line;

                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    line = line.trim();

                    // Skip empty lines and comments
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }

                    // Validate format
                    if (!isValidDnsAddress(line)) {
                        errors.add("Line " + lineNumber + ": Invalid format '" + line + "'");
                        continue;
                    }

                    // Get IP address (without port)
                    String ipAddress = line.contains(":") ? line.substring(0, line.indexOf(":")) : line;

                    // Ensure address has port for storage
                    String fullAddress = line.contains(":") ? line : line + ":53";

                    // Create DnsConfig using IP as name
                    DnsConfig config = new DnsConfig(
                            UUID.randomUUID().toString(),
                            ipAddress,  // Use IP as name instead of "Imported DNS N"
                            fullAddress,
                            "Imported DNS server",
                            false,
                            currentList.getName()
                    );
                    importedConfigs.add(config);
                }
                reader.close();

                // Update UI on main thread
                final List<DnsConfig> finalImportedConfigs = importedConfigs;
                final List<String> finalErrors = errors;
                handler.post(() -> {
                    if (!finalErrors.isEmpty() && finalImportedConfigs.isEmpty()) {
                        // All entries failed validation
                        showImportErrorDialog(finalErrors, 0);
                    } else if (!finalErrors.isEmpty()) {
                        // Some entries failed, show confirmation dialog
                        showImportConfirmationDialog(finalErrors, finalImportedConfigs);
                    } else if (finalImportedConfigs.isEmpty()) {
                        Toast.makeText(requireContext(),
                                "No valid DNS entries found in file",
                                Toast.LENGTH_LONG).show();
                    } else {
                        // All entries valid, import directly
                        replaceCurrentListWithImported(finalImportedConfigs);
                    }
                });

            } catch (Exception e) {
                handler.post(() -> {
                    Toast.makeText(requireContext(),
                            "Error reading file: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private boolean isValidDnsAddress(String address) {
        // Remove port if present for validation
        String ipOnly = address.contains(":") ? address.substring(0, address.indexOf(":")) : address;

        // Regex pattern for IPv4 format
        String ipPattern = "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$";

        if (!ipOnly.matches(ipPattern)) {
            return false;
        }

        // Validate IP octets (0-255)
        String[] ipParts = ipOnly.split("\\.");
        for (String octet : ipParts) {
            int value = Integer.parseInt(octet);
            if (value < 0 || value > 255) {
                return false;
            }
        }

        // If port is present, validate it
        if (address.contains(":")) {
            String[] parts = address.split(":");
            if (parts.length != 2) {
                return false;
            }
            try {
                int port = Integer.parseInt(parts[1]);
                if (port < 1 || port > 65535) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }

        return true;
    }

    private void replaceCurrentListWithImported(List<DnsConfig> importedConfigs) {
        // Clear current list
        currentList.getDnsConfigs().clear();

        // Add imported configs
        for (DnsConfig config : importedConfigs) {
            currentList.addDnsConfig(config);
        }

        // Save to storage
        configManager.updateCustomList(currentList);

        // Reload display
        loadDnsConfigs();

        Toast.makeText(requireContext(),
                "Imported " + importedConfigs.size() + " DNS servers successfully",
                Toast.LENGTH_SHORT).show();
    }

    private void showImportErrorDialog(List<String> errors, int successCount) {
        StringBuilder message = new StringBuilder();
        message.append("Found ").append(errors.size()).append(" error(s):\n\n");

        // Show first 10 errors
        int displayCount = Math.min(10, errors.size());
        for (int i = 0; i < displayCount; i++) {
            message.append(errors.get(i)).append("\n");
        }

        if (errors.size() > 10) {
            message.append("\n... and ").append(errors.size() - 10).append(" more errors");
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("Import Errors")
                .setMessage(message.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void showImportConfirmationDialog(List<String> errors, List<DnsConfig> importedConfigs) {
        StringBuilder message = new StringBuilder();
        message.append("Found ").append(errors.size()).append(" error(s):\n\n");

        // Show first 5 errors
        int displayCount = Math.min(5, errors.size());
        for (int i = 0; i < displayCount; i++) {
            message.append(errors.get(i)).append("\n");
        }

        if (errors.size() > 5) {
            message.append("\n... and ").append(errors.size() - 5).append(" more errors");
        }

        message.append("\n\n").append(importedConfigs.size())
                .append(" valid DNS servers found.\n\nDo you want to import the valid entries?");

        new AlertDialog.Builder(requireContext())
                .setTitle("Import Validation")
                .setMessage(message.toString())
                .setPositiveButton("Import", (dialog, which) -> {
                    replaceCurrentListWithImported(importedConfigs);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
}
