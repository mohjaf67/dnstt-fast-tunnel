package com.dnstt.client.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.dnstt.client.R;
import com.dnstt.client.models.DnsConfig;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for DNS configuration RecyclerView
 */
public class DnsConfigAdapter extends RecyclerView.Adapter<DnsConfigAdapter.DnsConfigViewHolder> {

    private List<DnsConfig> dnsConfigs;
    private DnsConfigListener listener;
    private boolean showEditDelete;

    public interface DnsConfigListener {
        void onTest(DnsConfig config);
        void onSelect(DnsConfig config);
        void onEdit(DnsConfig config);
        void onDelete(DnsConfig config);
    }

    public DnsConfigAdapter(boolean showEditDelete, DnsConfigListener listener) {
        this.dnsConfigs = new ArrayList<>();
        this.showEditDelete = showEditDelete;
        this.listener = listener;
    }

    public void setDnsConfigs(List<DnsConfig> configs) {
        this.dnsConfigs = configs != null ? new ArrayList<>(configs) : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void updateDnsConfig(DnsConfig config) {
        for (int i = 0; i < dnsConfigs.size(); i++) {
            if (dnsConfigs.get(i).getId().equals(config.getId())) {
                dnsConfigs.set(i, config);
                notifyItemChanged(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public DnsConfigViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_dns_config, parent, false);
        return new DnsConfigViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DnsConfigViewHolder holder, int position) {
        DnsConfig config = dnsConfigs.get(position);
        holder.bind(config);
    }

    @Override
    public int getItemCount() {
        return dnsConfigs.size();
    }

    class DnsConfigViewHolder extends RecyclerView.ViewHolder {
        TextView dnsName;
        TextView dnsAddress;
        TextView dnsDescription;
        View statusIndicator;
        View testResultLayout;
        ProgressBar testProgress;
        TextView testResultText;
        MaterialButton btnTest;
        MaterialButton btnSelect;
        MaterialButton btnEdit;
        MaterialButton btnDelete;

        public DnsConfigViewHolder(@NonNull View itemView) {
            super(itemView);
            dnsName = itemView.findViewById(R.id.dnsName);
            dnsAddress = itemView.findViewById(R.id.dnsAddress);
            dnsDescription = itemView.findViewById(R.id.dnsDescription);
            statusIndicator = itemView.findViewById(R.id.statusIndicator);
            testResultLayout = itemView.findViewById(R.id.testResultLayout);
            testProgress = itemView.findViewById(R.id.testProgress);
            testResultText = itemView.findViewById(R.id.testResultText);
            btnTest = itemView.findViewById(R.id.btnTest);
            btnSelect = itemView.findViewById(R.id.btnSelect);
            btnEdit = itemView.findViewById(R.id.btnEdit);
            btnDelete = itemView.findViewById(R.id.btnDelete);

            // Show/hide edit/delete buttons
            if (showEditDelete) {
                btnEdit.setVisibility(View.VISIBLE);
                btnDelete.setVisibility(View.VISIBLE);
            }
        }

        public void bind(DnsConfig config) {
            dnsName.setText(config.getName());
            dnsAddress.setText(config.getAddress());
            dnsDescription.setText(config.getDescription());

            // Update status indicator and test results
            updateTestStatus(config);

            // Set click listeners
            btnTest.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onTest(config);
                }
            });

            btnSelect.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onSelect(config);
                }
            });

            btnEdit.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onEdit(config);
                }
            });

            btnDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDelete(config);
                }
            });
        }

        private void updateTestStatus(DnsConfig config) {
            switch (config.getTestStatus()) {
                case NOT_TESTED:
                    statusIndicator.setBackgroundResource(R.drawable.status_circle_disconnected);
                    testResultLayout.setVisibility(View.GONE);
                    btnTest.setEnabled(true);
                    break;

                case TESTING:
                    statusIndicator.setBackgroundResource(R.drawable.status_circle_connecting);
                    testResultLayout.setVisibility(View.VISIBLE);
                    testProgress.setVisibility(View.VISIBLE);
                    testResultText.setText("Testing...");
                    btnTest.setEnabled(false);
                    break;

                case SUCCESS:
                    statusIndicator.setBackgroundResource(R.drawable.status_circle_connected);
                    testResultLayout.setVisibility(View.VISIBLE);
                    testProgress.setVisibility(View.GONE);
                    testResultText.setText("Success - " + config.getLatencyMs() + "ms");
                    testResultText.setTextColor(itemView.getContext().getColor(R.color.connected));
                    btnTest.setEnabled(true);
                    break;

                case FAILED:
                    statusIndicator.setBackgroundResource(R.drawable.status_circle_disconnected);
                    testResultLayout.setVisibility(View.VISIBLE);
                    testProgress.setVisibility(View.GONE);
                    String errorMsg = config.getErrorMessage();
                    testResultText.setText("Failed" + (errorMsg != null ? ": " + errorMsg : ""));
                    testResultText.setTextColor(itemView.getContext().getColor(R.color.disconnected));
                    btnTest.setEnabled(true);
                    break;
            }
        }
    }
}
