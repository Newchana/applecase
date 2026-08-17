package com.applecase.gateway;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面:配置(broker/设备ID)+ 权限申请 + 服务启停 + 状态显示。
 * 配置存 SharedPreferences(Config),网关服务启动时读取。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 100;

    private TextView tvStatus;
    private Button btnStart;
    private EditText etBrokerHost, etBrokerPort, etMqttUser, etMqttPass, etDeviceId;
    private EditText etSim0Number, etSim1Number;
    private Config config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        config = new Config(this);

        etBrokerHost = findViewById(R.id.etBrokerHost);
        etBrokerPort = findViewById(R.id.etBrokerPort);
        etMqttUser = findViewById(R.id.etMqttUser);
        etMqttPass = findViewById(R.id.etMqttPass);
        etDeviceId = findViewById(R.id.etDeviceId);
        etSim0Number = findViewById(R.id.etSim0Number);
        etSim1Number = findViewById(R.id.etSim1Number);
        tvStatus = findViewById(R.id.tvStatus);
        btnStart = findViewById(R.id.btnStart);

        loadConfigToUi();

        findViewById(R.id.btnSave).setOnClickListener(v -> saveConfigFromUi());
        btnStart.setOnClickListener(v -> {
            saveConfigFromUi();
            if (permissionsGranted()) {
                startGateway();
            } else {
                requestPermissions();
            }
        });
        findViewById(R.id.btnBattery).setOnClickListener(v -> requestBatteryWhitelist());

        if (!permissionsGranted()) {
            requestPermissions();
        }
        updateBatteryButtonText();
    }

    // ============ 配置 ============

    private void loadConfigToUi() {
        etBrokerHost.setText(config.brokerHost());
        etBrokerPort.setText(String.valueOf(config.brokerPort()));
        etMqttUser.setText(config.mqttUser());
        etMqttPass.setText(config.mqttPass());
        String devId = getSharedPreferences("applecase", MODE_PRIVATE)
                .getString("device_id", "");
        etDeviceId.setText(devId == null ? "" : devId);
        etSim0Number.setText(config.simNumber(0));
        etSim1Number.setText(config.simNumber(1));
    }

    private void saveConfigFromUi() {
        int port;
        try {
            port = Integer.parseInt(etBrokerPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            port = Config.DEF_BROKER_PORT;
        }
        config.save(
                etBrokerHost.getText().toString().trim(),
                port,
                etMqttUser.getText().toString().trim(),
                etMqttPass.getText().toString(),
                etDeviceId.getText().toString());
        config.saveSimNumbers(
                etSim0Number.getText().toString(),
                etSim1Number.getText().toString());
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
    }

    // ============ 权限 ============

    private boolean permissionsGranted() {
        String[] required = requiredPermissions();
        for (String p : required) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** 短信 + 电话(阶段二新增 CALL_PHONE / ANSWER_PHONE_CALLS / READ_CALL_LOG)。 */
    private String[] requiredPermissions() {
        List<String> ps = new ArrayList<>();
        ps.add(Manifest.permission.RECEIVE_SMS);
        ps.add(Manifest.permission.READ_SMS);
        ps.add(Manifest.permission.SEND_SMS);
        ps.add(Manifest.permission.READ_PHONE_STATE);
        ps.add(Manifest.permission.CALL_PHONE);
        ps.add(Manifest.permission.ANSWER_PHONE_CALLS);
        ps.add(Manifest.permission.READ_CALL_LOG);
        ps.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ps.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return ps.toArray(new String[0]);
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQ_PERMS);
    }

    // ============ 服务启动 / 保活 ============

    private void startGateway() {
        Intent svc = new Intent(this, GatewayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        tvStatus.setText("网关服务已启动(看通知栏)。短信/来电自动转发到 MQTT。");
        tvStatus.setVisibility(android.view.View.VISIBLE);
        btnStart.setText("已启动");
        btnStart.setEnabled(false);
    }

    /** 电池优化白名单:老人机厂商省电激进,这是保活的关键一步。 */
    private void requestBatteryWhitelist() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        String pkg = getPackageName();
        if (pm != null && pm.isIgnoringBatteryOptimizations(pkg)) {
            Toast.makeText(this, "已在白名单中", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            @SuppressWarnings("BatteryLife")
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + pkg));
            startActivity(i);
        } catch (Exception e) {
            // 部分 ROM 禁止直接跳转,退到详情页
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {}
        }
    }

    private void updateBatteryButtonText() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        Button b = findViewById(R.id.btnBattery);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            b.setText("✓ 已在电池优化白名单");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBatteryButtonText();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (permissionsGranted()) {
            tvStatus.setText("权限已授予,点击启动。");
        } else {
            tvStatus.setText("部分权限缺失,短信/来电功能可能不工作。");
        }
        tvStatus.setVisibility(android.view.View.VISIBLE);
    }
}
