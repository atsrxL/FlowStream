package dev.flowstream.player.ui;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.window.OnBackInvokedDispatcher;

public abstract class BackActivity extends Activity {
    private boolean appliedDark;
    @Override protected void onResume(){super.onResume();if(appliedDark!=Ui.dark(this))recreate();}
    @Override protected void onCreate(Bundle state) {
        appliedDark=Ui.dark(this);setTheme(this instanceof PlayerActivity?dev.flowstream.player.R.style.PlayerTheme:appliedDark?dev.flowstream.player.R.style.AppTheme:dev.flowstream.player.R.style.LightTheme);
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
    }
    protected void back() { finish(); }
    // API 33+ uses the registered platform callback above; this override serves API 26–32.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation") @Override public void onBackPressed() { back(); }
}
