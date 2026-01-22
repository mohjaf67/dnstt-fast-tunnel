package com.dnstt.client;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.dnstt.client.fragments.CustomDnsFragment;
import com.dnstt.client.fragments.GlobalDnsFragment;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

/**
 * Configuration Activity for managing DNS settings
 */
public class ConfigurationActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private TabLayout tabLayout;
    private MaterialToolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        toolbar = findViewById(R.id.toolbar);
        tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.viewPager);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        toolbar.setNavigationOnClickListener(v -> finish());

        setupViewPager();
    }

    private void setupViewPager() {
        DnsPagerAdapter adapter = new DnsPagerAdapter(this);
        viewPager.setAdapter(adapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            // Only one tab: Custom DNS
            tab.setText("Custom DNS");
        }).attach();
    }

    private static class DnsPagerAdapter extends FragmentStateAdapter {
        public DnsPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
            super(fragmentActivity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            // Only show Custom DNS fragment
            // Global DNS still accessible via dropdown in MainActivity
            return new CustomDnsFragment();
        }

        @Override
        public int getItemCount() {
            return 1; // Only Custom DNS tab
        }
    }
}
