package com.uneedzf.web_screen;

import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class AdminActivity extends AppCompatActivity {
    private static final String TAG = AdminActivity.class.getSimpleName();
    static final int RESULT_ENABLE = 1;

    DevicePolicyManager deviceManger;
    ComponentName compName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin);

        deviceManger = (DevicePolicyManager)getSystemService(Context.DEVICE_POLICY_SERVICE);
        compName = new ComponentName(this, AdminReceiver.class);
        if (deviceManger.isAdminActive(compName)) {
            deviceManger.lockNow();
            finish();
        } else {
            askAdminPermission();
        }
    }

    private void askAdminPermission() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getResources().getString(R.string.ask_admin_permission));
        startActivityForResult(intent, RESULT_ENABLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
         switch (requestCode) {
             case RESULT_ENABLE:
                 if (resultCode == Activity.RESULT_OK) {
                     Log.d(TAG, "Admin permission granted");
                     deviceManger.lockNow();
                     finish();
                 } else {
                     Log.i(TAG, "Admin permission denied");
                     finish();
                 }
                 return;
         }
         super.onActivityResult(requestCode, resultCode, data);
    }

    public static void lockScreen(Context context) {
        DevicePolicyManager deviceManger =
                 (DevicePolicyManager)context.getSystemService(Context.DEVICE_POLICY_SERVICE);
         ComponentName compName = new ComponentName(context, AdminReceiver.class);
         // Start activity only if need permission
         if (deviceManger.isAdminActive(compName)) {
             deviceManger.lockNow();
         } else {
             Intent intent = new Intent(context, AdminActivity.class);
             context.startActivity(intent);
         }
    }
}