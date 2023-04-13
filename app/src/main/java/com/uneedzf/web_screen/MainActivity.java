package com.uneedzf.web_screen;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjectionManager;
import android.net.LinkAddress;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ToggleButton;

//import com.google.android.gms.ads.AdRequest;
//import com.google.android.gms.ads.AdSize;
//import com.google.android.gms.ads.AdView;
//import com.google.android.gms.ads.MobileAds;
//import com.google.android.gms.ads.initialization.InitializationStatus;
//import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;

import java.net.Inet6Address;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    private static final int PERM_ACTION_ACCESSIBILITY_SERVICE = 100;
    private static final int PERM_MEDIA_PROJECTION_SERVICE = 101;

    private static final int HANDLER_MESSAGE_UPDATE_NETWORK = 0;

    private int httpServerPort;

    private AppService appService = null;
    private AppServiceConnection serviceConnection = null;

    private NetworkHelper networkHelper = null;
    private SettingsHelper settingsHelper = null;
    private PermissionHelper permissionHelper;

//    private AdView adView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "Activity create");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

//        initAds();

        initSettings();

        ToggleButton startButton = findViewById(R.id.startButton);
        startButton.setOnCheckedChangeListener(new ToggleButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    buttonView.setBackground(getDrawable(R.drawable.bg_button_on));
                    start();
                }
                else {
                    buttonView.setBackground(getDrawable(R.drawable.bg_button_off));
                    stop();
                }
            }
        });

        ToggleButton remoteControl = findViewById(R.id.remoteControlEnableSwitch);
        remoteControl.setOnCheckedChangeListener(new Switch.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                buttonView.setBackground(getDrawable(isChecked ? R.drawable.bg_button_on :
                        R.drawable.bg_button_off));
                remoteControlEnable(isChecked);
            }
        });
        if (settingsHelper.isRemoteControlEnabled())
            remoteControl.setChecked(true);

        if (AppService.isServiceRunning())
            setStartButton();

        initPermission();

        initUrl();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Activity destroy");

        if (networkHelper != null)
            networkHelper.close();
        unbindService();
        uninitSettings();
        super.onDestroy();
    }

    private void start() {
        Log.d(TAG, "Stream start");
        if (AppService.isServiceRunning()) {
            bindService();
            return;
        }

        permissionHelper.requestInternetPermission();
    }

    private void stop() {
        Log.d(TAG, "Stream stop");
        if (!AppService.isServiceRunning())
            return;

        stopService();
    }

    private void initPermission() {
        permissionHelper = new PermissionHelper(this, new OnPermissionGrantedListener());
    }

    private class OnPermissionGrantedListener implements
            PermissionHelper.OnPermissionGrantedListener {
        @Override
        public void onAccessNetworkStatePermissionGranted(boolean isGranted) {
            if (!isGranted)
                return;
            networkHelper = new NetworkHelper(getApplicationContext(),
                    new OnNetworkChangeListener());
            urlUpdate();
        }

        @Override
        public void onInternetPermissionGranted(boolean isGranted) {
            if (isGranted)
                permissionHelper.requestReadExternalStoragePermission();
            else
                resetStartButton();
        }

        @Override
        public void onReadExternalStoragePermissionGranted(boolean isGranted) {
            if (isGranted)
                permissionHelper.requestWakeLockPermission();
            else
                resetStartButton();
        }

        @Override
        public void onWakeLockPermissionGranted(boolean isGranted) {
            if (isGranted)
                permissionHelper.requestForegroundServicePermission();
            else
                resetStartButton();
        }

        @Override
        public void onForegroundServicePermissionGranted(boolean isGranted) {
            if (isGranted) {
                //TODO permissionHelper.requestRecordAudioPermission();
                startService();
            }
            else
                resetStartButton();
        }

        @Override
        public void onRecordAudioPermissionGranted(boolean isGranted) {
            if (isGranted)
                startService();
            else
                resetStartButton();
        }

        @Override
        public void onCameraPermissionGranted(boolean isGranted) {
            if (isGranted)
                startService();
            else
                resetStartButton();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        permissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void startService() {
        Intent serviceIntent = new Intent(this, AppService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        serviceConnection = new AppServiceConnection();
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void stopService() {
        unbindService();
        Intent serviceIntent = new Intent(this, AppService.class);
        stopService(serviceIntent);
    }

    private void bindService() {
        Intent serviceIntent = new Intent(this, AppService.class);
        serviceConnection = new AppServiceConnection();
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void unbindService() {
        if (serviceConnection == null)
            return;

        unbindService(serviceConnection);
        serviceConnection = null;
    }

    private class AppServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AppService.AppServiceBinder binder = (AppService.AppServiceBinder)service;
            appService = binder.getService();

            if (!appService.isServerRunning())
                askMediaProjectionPermission();
            else if (appService.isMouseAccessibilityServiceAvailable())
                setRemoteControlSwitch();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            appService = null;
            resetStartButton();
            Log.e(TAG, "Service unexpectedly exited");
        }
    }

    private void askMediaProjectionPermission() {
        MediaProjectionManager mediaProjectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(),
                PERM_MEDIA_PROJECTION_SERVICE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case PERM_MEDIA_PROJECTION_SERVICE:
                if (resultCode == RESULT_OK) {
                    if (!appService.serverStart(data, httpServerPort,
                            isAccessibilityServiceEnabled(), getApplicationContext())) {
                        resetStartButton();
                        return;
                    }
                }
                else
                    resetStartButton();
                break;
            case PERM_ACTION_ACCESSIBILITY_SERVICE:
                if (isAccessibilityServiceEnabled())
                    enableAccessibilityService(true);
                else
                    resetRemoteControlSwitch();
                break;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void setStartButton() {
        ToggleButton startButton = findViewById(R.id.startButton);
        startButton.setChecked(true);
    }

    private void resetStartButton() {
        ToggleButton startButton = findViewById(R.id.startButton);
        startButton.setChecked(false);
    }

    private void enableAccessibilityService(boolean isEnabled) {
        settingsHelper.setRemoteControlEnabled(isEnabled);

        if (appService != null)
            appService.accessibilityServiceSet(getApplicationContext(), isEnabled);
    }

    private boolean isAccessibilityServiceEnabled() {
        Context context = getApplicationContext();
        ComponentName compName = new ComponentName(context, MouseAccessibilityService.class);
        String flatName = compName.flattenToString();
        String enabledList = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabledList != null && enabledList.contains(flatName);
    }

    private void setRemoteControlSwitch() {
        ToggleButton remoteControl = findViewById(R.id.remoteControlEnableSwitch);
        remoteControl.setChecked(true);
    }

    private void resetRemoteControlSwitch() {
        ToggleButton remoteControl = findViewById(R.id.remoteControlEnableSwitch);
        remoteControl.setChecked(false);
    }

    private void remoteControlEnable(boolean isEnabled) {
        if (isEnabled) {
            if (!isAccessibilityServiceEnabled()) {
                startActivityForResult(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                        PERM_ACTION_ACCESSIBILITY_SERVICE);
            } else {
                enableAccessibilityService(true);
            }
        } else {
            enableAccessibilityService(false);
        }

    }

    public void initUrl() {
        LinearLayout urlLayout = findViewById(R.id.urlLinerLayout);
        urlLayout.setVisibility(View.INVISIBLE);
        permissionHelper.requestAccessNetworkStatePermission();
    }

    private class OnNetworkChangeListener implements NetworkHelper.OnNetworkChangeListener {
        @Override
        public void onChange() {
            // Interfaces need some time to update
            handler.sendEmptyMessageDelayed(HANDLER_MESSAGE_UPDATE_NETWORK, 1000);
        }
    }

    private Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case HANDLER_MESSAGE_UPDATE_NETWORK:
                    urlUpdate();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    };

    private void urlUpdate() {
        TextView urlsHeader = findViewById(R.id.urls_header);
        urlsHeader.setText(getResources().getString(R.string.no_active_connections));
        LinearLayout urlLayout = findViewById(R.id.urlLinerLayout);
        urlLayout.setVisibility(View.INVISIBLE);

        List<NetworkHelper.IpInfo> ipInfoList = networkHelper.getIpInfo();
        for (NetworkHelper.IpInfo ipInfo : ipInfoList) {
//            if (!ipInfo.interfaceType.equals("Wi-Fi"))
//                continue;

            String type = ipInfo.interfaceType + " (" + ipInfo.interfaceName + ")";
            TextView connectionType = findViewById(R.id.connectionTypeHeader);
            connectionType.setText(type);

            List<LinkAddress> addresses = ipInfo.addresses;
            for (LinkAddress address : addresses) {
                if (address.getAddress() instanceof Inet6Address)
                    continue;

                String url = "http://" + address.getAddress().getHostAddress() + ":" +
                        httpServerPort;
                TextView connectionURL = findViewById(R.id.connectionURL);
                connectionURL.setText(url);
                urlsHeader.setText(getResources().getString(R.string.urls_header));
                urlLayout.setVisibility(View.VISIBLE);
                break;
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.xml.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

//    private void initAds() {
//        MobileAds.initialize(this, new OnInitializationCompleteListener() {
//            @Override
//            public void onInitializationComplete(InitializationStatus initializationStatus) {
//            }
//        });
//
////        RelativeLayout adLayout = findViewById(R.id.adLayout);
////        adView = new AdView(this);
//        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(RelativeLayout.
//                LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
//        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
//
////        String adUnitId = getString(BuildConfig.DEBUG ? R.string.adaptive_banner_ad_unit_id_test :
////                R.string.adaptive_banner_ad_unit_id);
////        adView.setAdUnitId(adUnitId);
////        adLayout.addView(adView, layoutParams);;
//
////        AdSize adSize = getAdSize();
////        adView.setAdSize(adSize);
//
////        AdRequest adRequest = new AdRequest.Builder().build();
////        adView.loadAd(adRequest);
//    }

//    private AdSize getAdSize() {
//        Display display = getWindowManager().getDefaultDisplay();
//        DisplayMetrics outMetrics = new DisplayMetrics();
//        display.getMetrics(outMetrics);
//
//        float widthPixels = outMetrics.widthPixels;
//        float density = outMetrics.density;
//
//        int adWidth = (int) (widthPixels / density);
//
//        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidth);
//    }

    public void initSettings() {
        settingsHelper = new SettingsHelper(getApplicationContext(),
                new OnSettingsChangeListener());
        httpServerPort = settingsHelper.getPort();
    }

    public void uninitSettings() {
        settingsHelper.close();
        settingsHelper = null;
    }

    private class OnSettingsChangeListener implements SettingsHelper.OnSettingsChangeListener {
        @Override
        public void onPortChange(int port) {
            httpServerPort = port;
            urlUpdate();
            if (AppService.isServiceRunning()) {
                if (!appService.serverRestart(httpServerPort))
                    resetStartButton();
            }
        }
    }
}


