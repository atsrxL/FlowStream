package dev.flowstream.player.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;
import java.util.Locale;

public final class Ui {
    public static final int BG = Color.BLACK, PANEL = 0xff1d1d1f, TEXT = 0xfff1f5f9,
            MUTED = 0xff96969d, BLUE = 0xffe8e8ed;
    public static boolean dark(Context c){if(c instanceof PlayerActivity)return true;String mode=c.getSharedPreferences("config",0).getString("theme","system");return mode.equals("black") || (mode.equals("system") && (c.getResources().getConfiguration().uiMode & 48)==32);}
    public static int bg(Context c){return dark(c)?Color.BLACK:Color.WHITE;}
    public static int fg(Context c){return dark(c)?TEXT:0xff17171c;}
    public static int panel(Context c){return dark(c)?PANEL:0xfff0f0f3;}
    public static int muted(Context c){return dark(c)?0xffa4a4ac:0xff6e6e76;}
    public static void systemBars(Activity a){a.getWindow().setStatusBarColor(bg(a));a.getWindow().setNavigationBarColor(bg(a));if(Build.VERSION.SDK_INT>=30){WindowInsetsController w=a.getWindow().getInsetsController();int mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;if(w!=null)w.setSystemBarsAppearance(dark(a)?0:mask,mask);}}
    private Ui() { }
    public static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    public static TextView text(Context c, String value, int sp, int color) {
        TextView t = new TextView(c); t.setText(value); t.setTextSize(sp); t.setTextColor(color==TEXT||color==BLUE?fg(c):color==MUTED?muted(c):color); return t;
    }
    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l;
    }
    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l;
    }
    public static Button button(Context c, String label, Runnable action) {
        Button b = new Button(c); b.setText(label); b.setAllCaps(false); b.setTextColor(fg(c));
        b.setTextSize(14); b.setMinWidth(dp(c,48)); b.setMinimumHeight(dp(c,48));
        b.setBackground(background(panel(c),24,c)); b.setPadding(dp(c,10),0,dp(c,10),0);b.setStateListAnimator(null);
        b.setContentDescription(label); b.setOnClickListener(v -> action.run()); return b;
    }
    public static GradientDrawable background(int color, float radius, Context c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius)); return d;
    }
    public static void pad(View v, int d) { int p=dp(v.getContext(),d); v.setPadding(p,p,p,p); }
    public static void safeInsets(Activity a, View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            a.getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left,i.top,i.right,i.bottom); return insets;
            }); root.requestApplyInsets();
        } else root.setFitsSystemWindows(true);
    }
    public static String time(long millis) {
        if (millis < 0) return "--:--";
        long s = millis / 1000;
        return s >= 3600 ? String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60)
                : String.format(Locale.ROOT,"%02d:%02d",s/60,s%60);
    }
    public static String size(long bytes) {
        if (bytes <= 0) return "流式媒体";
        return bytes >= 1073741824L ? String.format(Locale.ROOT,"%.2f GB", bytes/1073741824.0)
                : String.format(Locale.ROOT,"%.1f MB", bytes/1048576.0);
    }
    public static View listRow(Context c, String symbol, String title, String subtitle) {
        LinearLayout row = row(c); row.setMinimumHeight(dp(c,78)); row.setPadding(dp(c,16),dp(c,8),dp(c,16),dp(c,8));
        TextView icon = text(c,symbol,22,BLUE); icon.setGravity(Gravity.CENTER); icon.setBackground(background(PANEL,14,c));
        row.addView(icon,new LinearLayout.LayoutParams(dp(c,50),dp(c,50)));
        LinearLayout col = column(c); col.setPadding(dp(c,14),0,0,0);
        TextView name = text(c,title,16,TEXT); name.setTypeface(null, Typeface.BOLD); name.setMaxLines(2);
        TextView detail = text(c,subtitle,12,MUTED); detail.setMaxLines(2); detail.setPadding(0,dp(c,4),0,0);
        col.addView(name); col.addView(detail); row.addView(col,new LinearLayout.LayoutParams(0,-2,1)); return row;
    }
    public static void toast(Context c, String message) { Toast.makeText(c,message,Toast.LENGTH_LONG).show(); }
    public static String error(Throwable e) {
        String m = e.getMessage(); return m == null || m.isEmpty() ? "操作失败，请检查配置" : m;
    }
}
