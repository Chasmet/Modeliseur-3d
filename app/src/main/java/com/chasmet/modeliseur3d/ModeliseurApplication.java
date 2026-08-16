package com.chasmet.modeliseur3d;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Initialise le diagnostic utilisateur sans modifier le moteur de reconstruction.
 * Le journal suit automatiquement le texte d'état du mode 3D et ajoute un bouton
 * permettant de copier toutes les étapes et la mémoire dans le presse-papiers.
 */
public final class ModeliseurApplication extends Application
        implements Application.ActivityLifecycleCallbacks {

    private static final String COPY_BUTTON_TAG = "modeliseur_copy_diagnostic_logs";

    private final Set<TextView> watchedStatuses = Collections.newSetFromMap(
            new WeakHashMap<>()
    );

    @Override
    public void onCreate() {
        super.onCreate();
        DiagnosticLogStore.initialize(this);
        installCrashRecorder();
        registerActivityLifecycleCallbacks(this);
    }

    private void installCrashRecorder() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            DiagnosticLogStore.append(
                    this,
                    "CRASH",
                    "Thread=" + thread.getName() + " — " + throwable.getClass().getName()
            );
            DiagnosticLogStore.appendThrowable(this, "STACKTRACE", throwable);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private void attachDiagnostics(Activity activity) {
        TextView status = activity.findViewById(R.id.manualStatusText);
        if (status == null) {
            return;
        }

        if (watchedStatuses.add(status)) {
            status.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable editable) {
                    DiagnosticLogStore.append(activity, "3D", editable);
                }
            });
            DiagnosticLogStore.append(activity, "3D", status.getText());
        }

        addCopyButton(activity, status);
    }

    private void addCopyButton(Activity activity, TextView status) {
        if (!(status.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) status.getParent();
        for (int index = 0; index < parent.getChildCount(); index++) {
            View child = parent.getChildAt(index);
            if (COPY_BUTTON_TAG.equals(child.getTag())) {
                return;
            }
        }

        Button copy = new Button(activity);
        copy.setTag(COPY_BUTTON_TAG);
        copy.setText("Copier logs");
        copy.setAllCaps(false);
        copy.setTextSize(11f);
        copy.setSingleLine(true);
        copy.setMinWidth(dp(activity, 96));
        copy.setMinHeight(dp(activity, 42));
        copy.setOnClickListener(view -> DiagnosticLogStore.copyToClipboard(activity));

        if (parent instanceof LinearLayout) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMarginStart(dp(activity, 6));
            copy.setLayoutParams(params);
        }
        parent.addView(copy);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        attachDiagnostics(activity);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        attachDiagnostics(activity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        attachDiagnostics(activity);
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
