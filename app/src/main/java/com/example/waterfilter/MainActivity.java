
package com.example.waterfilter;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends android.app.Activity {
    static final String PREFS = "filterprefs";
    static final String DATA_KEY = "stagedata";
    static final String ENABLED_KEY = "notifications_enabled";
    static final String CHANNEL_ID = "filter_reminders";
    private WebView webView;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createChannel();
        webView = new WebView(this);
        webView.setBackgroundColor(0xFFF2F7FB);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(false);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7001);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "تذكيرات فلتر المياه", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("تذكيرات مواعيد تغيير شمعات فلتر المياه");
            channel.enableVibration(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    public class AndroidBridge {
        @JavascriptInterface public boolean getNotificationsEnabled() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ENABLED_KEY, true);
        }

        @JavascriptInterface public void setNotificationsEnabled(boolean enabled) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED_KEY, enabled).apply();
            if (enabled) {
                String data = getSharedPreferences(PREFS, MODE_PRIVATE).getString(DATA_KEY, "[]");
                scheduleAll(MainActivity.this, data);
            } else {
                cancelAll(MainActivity.this);
            }
        }

        @JavascriptInterface public void updateSchedule(String json) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(DATA_KEY, json).apply();
            if (getNotificationsEnabled()) scheduleAll(MainActivity.this, json);
        }

        @JavascriptInterface public void testNotification() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(MainActivity.this, "اسمح للتطبيق بالإشعارات من إعدادات الهاتف", Toast.LENGTH_LONG).show();
                    return;
                }
                ReminderReceiver.showNotification(MainActivity.this,
                    "تجربة إشعار", "الإشعارات شغالة في متابع فلتر المياه");
            });
        }

        @JavascriptInterface public void openExactAlarmSettings() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        }
    }

    static void cancelAll(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        SharedPreferences p = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        String json = p.getString(DATA_KEY, "[]");
        try {
            JSONArray filters = new JSONArray(json);
            for (int i = 0; i < filters.length(); i++) {
                JSONObject f = filters.getJSONObject(i);
                JSONArray stages = f.optJSONArray("stages");
                if (stages == null) continue;
                for (int j = 0; j < stages.length(); j++) {
                    JSONObject s = stages.getJSONObject(j);
                    int base = requestBase(f.optString("id", "filter"+i), s.optString("id", "stage"+j));
                    for (int k = 0; k < 4; k++) {
                        PendingIntent pi = pending(context, base + k, s.optString("name", "شمعة"), "", false);
                        if (pi != null) am.cancel(pi);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    static void scheduleAll(Context context, String json) {
        if (!context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ENABLED_KEY, true)) return;
        AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        cancelAll(context);
        try {
            JSONArray filters = new JSONArray(json);
            Calendar now = Calendar.getInstance();
            for (int i = 0; i < filters.length(); i++) {
                JSONObject f = filters.getJSONObject(i);
                String filterName = f.optString("name", "فلتر المياه");
                JSONArray stages = f.optJSONArray("stages");
                if (stages == null) continue;
                for (int j = 0; j < stages.length(); j++) {
                    JSONObject s = stages.getJSONObject(j);
                    if (!s.optBoolean("active", true)) continue;
                    String due = s.optString("dueDate", "");
                    if (due.length() < 10) continue;
                    Calendar dueCal = Calendar.getInstance();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    sdf.setTimeZone(TimeZone.getDefault());
                    Date d = sdf.parse(due.substring(0, 10));
                    if (d == null) continue;
                    dueCal.setTime(d);
                    dueCal.set(Calendar.HOUR_OF_DAY, 9);
                    dueCal.set(Calendar.MINUTE, 0);
                    dueCal.set(Calendar.SECOND, 0);
                    dueCal.set(Calendar.MILLISECOND, 0);

                    int base = requestBase(f.optString("id", "filter"+i), s.optString("id", "stage"+j));
                    String stageName = s.optString("name", "الشمعة");
                    long[] offsets = {7L, 2L, 0L, -1L};
                    String[] messages = {
                        "متبقي 7 أيام على تغيير " + stageName + " في " + filterName,
                        "متبقي يومان على تغيير " + stageName + " في " + filterName,
                        "حان موعد تغيير " + stageName + " في " + filterName,
                        "موعد تغيير " + stageName + " في " + filterName + " فات"
                    };
                    for (int k = 0; k < offsets.length; k++) {
                        Calendar alarmTime = (Calendar) dueCal.clone();
                        if (k == 3) alarmTime.add(Calendar.DAY_OF_MONTH, 1);
                        else alarmTime.add(Calendar.DAY_OF_MONTH, (int)-offsets[k]);
                        if (alarmTime.getTimeInMillis() <= now.getTimeInMillis()) continue;
                        PendingIntent pi = pending(context, base + k, stageName, messages[k], true);
                        if (pi != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime.getTimeInMillis(), pi);
                                } else {
                                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime.getTimeInMillis(), pi);
                                }
                            } else {
                                am.setExact(AlarmManager.RTC_WAKEUP, alarmTime.getTimeInMillis(), pi);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    static int requestBase(String filterId, String stageId) {
        int h = (filterId + "|" + stageId).hashCode() & 0x0fffffff;
        return 1000 + (h % 500000) * 4;
    }

    static PendingIntent pending(Context c, int requestCode, String title, String message, boolean create) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.putExtra("title", title);
        i.putExtra("message", message);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, requestCode, i, flags);
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
