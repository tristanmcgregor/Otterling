package test.blocker.victim;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Disposable stub used only by scripts/emulator suspend tests. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setText("otterling-victim-ok");
        view.setTextSize(20f);
        view.setPadding(48, 48, 48, 48);
        setContentView(view);
    }
}
