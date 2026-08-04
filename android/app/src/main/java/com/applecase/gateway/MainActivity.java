package com.applecase.gateway;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面:权限申请 + 服务启停 + 状态显示。
 * 骨架期做得极简,只保证能授权、能起服务。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 100;

    private TextView tvStatus;
    private Button btnStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        btnStart = findViewById(R.id.btnStart);

        btnStart.setOnClickListener(v -> {
            if (permissionsGranted()) {
                startGateway();
            } else {
                requestPermissions();
            }
        });

        if (!permissionsGranted()) {
            requestPermissions();
        }
    }

    private boolean permissionsGranted() {
        String[] required = requiredPermissions();
        for (String p : required) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** 阶段一必需的权限(语音/电话相关在阶段二三再加)。 */
    private String[] requiredPermissions() {
        List<String> ps = new ArrayList<>();
        ps.add(Manifest.permission.RECEIVE_SMS);
        ps.add(Manifest.permission.READ_SMS);
        ps.add(Manifest.permission.SEND_SMS);
        ps.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ps.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return ps.toArray(new String[0]);
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQ_PERMS);
    }

    private void startGateway() {
        Intent svc = new Intent(this, GatewayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        tvStatus.setText("网关服务已启动(看通知栏)。短信将自动转发到 MQTT。");
        tvStatus.setVisibility(View.VISIBLE);
        btnStart.setText("已启动");
        btnStart.setEnabled(false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (permissionsGranted()) {
            tvStatus.setText("权限已授予,点击启动。");
        } else {
            tvStatus.setText("部分权限缺失,短信功能可能不工作。");
        }
        tvStatus.setVisibility(View.VISIBLE);
    }
}
