package dev.flowstream.player.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.flowstream.core.GestureBindings;
import dev.flowstream.player.data.Store;

/** A phone-shaped editor whose regions match the player's gesture hit areas. */
public final class GestureSettingsView extends LinearLayout {
    private static final String[] KINDS = {"tap", "double", "hold", "drag"};
    private static final String[] TITLES = {"单击", "双击", "长按", "左右拖动"};
    private static final String[] BADGES = {"单", "双", "长", "拖"};
    private static final String[] ZONES = {"上左", "上中", "上右", "中左", "中央", "中右", "下左", "下中", "下右"};
    private final Store store;
    private final TextView[][] actions = new TextView[9][4];
    private final TextView[][] badges = new TextView[9][4];

    public GestureSettingsView(Context context, Store store) {
        super(context);
        this.store = store;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        TextView legend = Ui.text(context, "单 · 单击    双 · 双击    长 · 长按    拖 · 左右拖动", 12, Ui.MUTED);
        legend.setGravity(Gravity.CENTER);
        legend.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 12));
        addView(legend);

        LinearLayout grid = Ui.column(context);
        LinearLayout phone = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = Math.min(MeasureSpec.getSize(widthSpec), Ui.dp(context, 430));
                // Four controls remain separate, single-line targets even on narrow phones.
                grid.getLayoutParams().height = Math.max(Ui.dp(context, 552), (width - Ui.dp(context, 16)) * 13 / 9);
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        phone.setOrientation(VERTICAL);
        Ui.pad(phone, 8);
        GradientDrawable frame = Ui.background(Ui.panel(context), 30, context);
        frame.setStroke(Ui.dp(context, 2), Ui.muted(context));
        phone.setBackground(frame);
        phone.addView(chrome("⌄       ↻       ▣       1×", 44));
        phone.addView(grid, new LinearLayout.LayoutParams(-1, Ui.dp(context, 552)));
        for (int row = 0; row < 3; row++) {
            LinearLayout band = Ui.row(context);
            grid.addView(band, new LinearLayout.LayoutParams(-1, 0, 1));
            for (int col = 0; col < 3; col++) {
                band.addView(zone(row * 3 + col), new LinearLayout.LayoutParams(0, -1, col == 1 ? 3 : 1));
            }
        }
        phone.addView(chrome("ⓘ       ☆       ▶       ♧       ⋯", 52));
        addView(phone, new LinearLayout.LayoutParams(-1, -2));
        TextView help = Ui.text(context, "点每区里的动作即可更换。上中下三行等高，左右两列各占 20%。顶部与底部控件区域不响应手势。缩放后只能平移，双击恢复宽度适屏。", 12, Ui.MUTED);
        help.setPadding(0, Ui.dp(context, 12), 0, Ui.dp(context, 8));
        addView(help);
        Button reset = Ui.button(context, "恢复默认", () -> new AlertDialog.Builder(context)
                .setTitle("恢复九宫格默认手势？")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复默认", (dialog, which) -> {
                    for (int z = 0; z < 9; z++) for (String kind : KINDS)
                        store.setGestureAction(z, kind, GestureBindings.defaultAction(z, kind));
                    refresh();
                }).show());
        addView(reset);
        refresh();
    }

    private TextView chrome(String text, int height) {
        TextView view = Ui.text(getContext(), text, 14, Ui.MUTED);
        view.setGravity(Gravity.CENTER);
        view.setBackground(Ui.background(Ui.bg(getContext()), 22, getContext()));
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(getContext(), height)));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private LinearLayout zone(int zone) {
        Context context = getContext();
        LinearLayout cell = Ui.column(context);
        cell.setPadding(Ui.dp(context, 2), Ui.dp(context, 2), Ui.dp(context, 2), Ui.dp(context, 2));
        int tint = new int[]{0x20808080, 0x203080ff, 0x20ff9020}[zone / 3];
        GradientDrawable background = Ui.background(tint, 0, context);
        background.setStroke(Ui.dp(context, 0.5f), Ui.muted(context));
        cell.setBackground(background);
        TextView title = Ui.text(context, ZONES[zone], 11, Ui.MUTED);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        cell.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(context, 22)));
        for (int k = 0; k < KINDS.length; k++) {
            final int kind = k;
            LinearLayout control = Ui.row(context);
            control.setPadding(Ui.dp(context, 2), 0, Ui.dp(context, 2), 0);
            control.setBackground(Ui.background(Ui.panel(context), 5, context));
            control.setOnClickListener(v -> choose(zone, kind));
            control.setFocusable(true);
            // The entire row is one accessibility target with an unabridged label.
            control.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            TextView badge = Ui.text(context, BADGES[k], 9, Ui.TEXT);
            badge.setGravity(Gravity.CENTER);
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            control.addView(badge, new LinearLayout.LayoutParams(Ui.dp(context, 13), -1));
            TextView action = Ui.text(context, "", 12, Ui.TEXT);
            action.setSingleLine(true);
            action.setGravity(Gravity.CENTER_VERTICAL);
            action.setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, TypedValue.COMPLEX_UNIT_SP);
            action.setEllipsize(TextUtils.TruncateAt.END);
            action.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            control.addView(action, new LinearLayout.LayoutParams(0, -1, 1));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 0, 1);
            params.topMargin = Ui.dp(context, 2);
            cell.addView(control, params);
            actions[zone][k] = action;
            badges[zone][k] = badge;
        }
        return cell;
    }

    private void choose(int zone, int kind) {
        String[] values = GestureBindings.actions(KINDS[kind]);
        String[] labels = new String[values.length];
        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = GestureBindings.label(values[i]);
            if (values[i].equals(store.gestureAction(zone, KINDS[kind]))) selected = i;
        }
        new AlertDialog.Builder(getContext()).setTitle(ZONES[zone] + " · " + TITLES[kind])
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    store.setGestureAction(zone, KINDS[kind], values[which]);
                    refresh();
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void refresh() {
        for (int z = 0; z < 9; z++) for (int k = 0; k < KINDS.length; k++) {
            String action = store.gestureAction(z, KINDS[k]);
            String label = GestureBindings.label(action);
            TextView view = actions[z][k];
            view.setText(shortLabel(action));
            view.setTextColor("none".equals(action) ? Ui.muted(getContext()) : Ui.fg(getContext()));
            view.setTypeface(null, "none".equals(action) ? Typeface.NORMAL : Typeface.BOLD);
            badges[z][k].setAlpha("none".equals(action) ? 0.45f : 1f);
            ((View) view.getParent()).setContentDescription(ZONES[z] + " " + TITLES[k] + "：" + label);
        }
    }

    private String shortLabel(String action) {
        switch (action) {
            case "none": return "无";
            case "hud": return "菜单";
            case "play": return "播放";
            case "fit": return "适屏";
            case "reverse": return "快退";
            case "forward": return "快进";
            case "scrub": return "进度";
            case "scrub_fine": return "微调";
            case "seek_back_1": return "−1秒";
            case "seek_forward_1": return "+1秒";
            default: return GestureBindings.label(action);
        }
    }
}
