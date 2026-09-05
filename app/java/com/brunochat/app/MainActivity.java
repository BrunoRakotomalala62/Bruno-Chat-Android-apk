package com.brunochat.app;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.OvershootInterpolator;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * Bruno Chat — application Android.
 *
 * Enveloppe WebView autour de https://site-gratuit-dynamique.vercel.app/
 * Aucune logique du site n'est modifiée.
 *
 * Démarrage :
 *  - écran d'accueil (splash) dynamique : petit logo + grand « BRUNO CHAT »,
 *    animations d'entrée, puis transition douce vers l'interface de chat ;
 *  - plein écran immersif permanent (heure/batterie masquées, y compris
 *    pendant la saisie) : la hauteur du clavier est mesurée et appliquée
 *    en padding bas sur la page, qui reste donc visible au-dessus du
 *    clavier (windowSoftInputMode="adjustResize" est souvent ignoré en
 *    plein écran → on pousse la page manuellement) ;
 *  - sélection de photos (pièces jointes), liens externes dans le navigateur,
 *    bouton « retour » = historique du chat ;
 *  - si l'appareil est lent (fps < 35), le fond décoratif animé du site est
 *    figé automatiquement pour rester fluide.
 */
public class MainActivity extends Activity {

    private static final String APP_URL = "https://site-gratuit-dynamique.vercel.app/";
    private static final String APP_HOST = "site-gratuit-dynamique.vercel.app";
    private static final long MIN_SPLASH_MS = 2400; // durée min du splash
    private static final long MAX_SPLASH_MS = 9000; // sécurité si réseau lent

    private WebView webView;
    private View splash;
    private View rootView; // vue racine (padding bas = hauteur du clavier)
    private boolean keyboardVisible = false;
    private boolean pageLoaded = false;
    private boolean splashHidden = false;
    private long startTime;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ValueCallback<Uri[]> filePathCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startTime = System.currentTimeMillis();

        // ---------- Racine ----------
        RelativeLayout root = new RelativeLayout(this);
        root.setBackgroundColor(0xFF0B0F1E);
        rootView = root;

        // WebView (chargée immédiatement, révélée après le splash)
        webView = new WebView(this);
        webView.setAlpha(0f);
        root.addView(webView, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        // Plein écran immersif : masque heure / batterie / % et barre du bas.
        hideSystemUI();

        // Détecte l'ouverture/fermeture du clavier : on pousse alors la page
        // au-dessus du clavier via un padding mesuré (le plein écran reste
        // actif — windowSoftInputMode est "adjustNothing", car en plein écran
        // Android ignore adjustResize et le clavier recouvrirait le champ).
        root.getViewTreeObserver().addOnGlobalLayoutListener(onLayoutChange);

        // ---------- Écran d'accueil (splash) ----------
        splash = buildSplash();
        root.addView(splash, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------- Réglages WebView ----------
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage (historique du chat)
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setOffscreenPreRaster(true);
        webView.setBackgroundColor(0xFF0B0F1E);

        // ---------- Navigation : site dans l'app, liens externes ailleurs ----------
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                if (host == null) return false;
                if (host.equals(APP_HOST) || host.endsWith(".vercel.app")) {
                    return false; // on reste dans l'application
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                tryHideSplash(false);
            }
        });

        // Sélection de fichiers (photos des pièces jointes)
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, 1001);
                } catch (ActivityNotFoundException e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl(APP_URL);

        // Sécurité : si le site n'a pas fini de charger, on montre quand même
        // l'interface après MAX_SPLASH_MS (le chargement continue en fond).
        handler.postDelayed(() -> tryHideSplash(true), MAX_SPLASH_MS);
    }

    /* ================= Écran d'accueil dynamique ================= */

    private View buildSplash() {
        RelativeLayout sc = new RelativeLayout(this);

        // Fond en dégradé (violet nuit → bleu nuit, comme le site)
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF251E4E, 0xFF141A3D, 0xFF0B0F1E});
        sc.setBackground(bg);

        // Bloc central vertical
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        RelativeLayout.LayoutParams colLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.addRule(RelativeLayout.CENTER_IN_PARENT);
        col.setLayoutParams(colLp);

        // Petit logo (icône de l'app)
        ImageView logo = new ImageView(this);
        Drawable icon = null;
        try { icon = getApplicationInfo().loadIcon(getPackageManager()); } catch (Exception ignored) { }
        logo.setImageDrawable(icon);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int logoPx = (int) (96 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(logoPx, logoPx);
        logo.setLayoutParams(logoLp);

        // Grand nom « BRUNO CHAT » (BRUNO blanc + CHAT cyan, comme le site)
        LinearLayout rowTitle = new LinearLayout(this);
        rowTitle.setOrientation(LinearLayout.HORIZONTAL);
        rowTitle.setGravity(Gravity.CENTER);

        TextView tvBruno = new TextView(this);
        tvBruno.setText("BRUNO");
        tvBruno.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        tvBruno.setTextSize(38);
        tvBruno.setLetterSpacing(0.05f);
        tvBruno.setIncludeFontPadding(false);
        tvBruno.setTextColor(Color.WHITE);

        TextView tvChat = new TextView(this);
        tvChat.setText("CHAT");
        tvChat.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        tvChat.setTextSize(38);
        tvChat.setLetterSpacing(0.18f);
        tvChat.setIncludeFontPadding(false);
        tvChat.setTextColor(0xFF7DD3FC);
        tvChat.setPadding(dp(10), 0, 0, 0);

        rowTitle.addView(tvBruno);
        rowTitle.addView(tvChat);

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(18);
        rowTitle.setLayoutParams(titleLp);

        // Sous-titre
        TextView sub = new TextView(this);
        sub.setText("IA Premium · Gratuite");
        sub.setGravity(Gravity.CENTER);
        sub.setTextColor(0xFF9AA3C7);
        sub.setTextSize(14);
        sub.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sub.setLetterSpacing(0.2f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(10);
        sub.setLayoutParams(subLp);

        col.addView(logo, logoLp);
        col.addView(rowTitle, titleLp);
        col.addView(sub, subLp);
        sc.addView(col, colLp);

        // État initial (invisible) puis animations d'entrée « dynamiques ».
        logo.setAlpha(0f);
        logo.setScaleX(0.4f);
        logo.setScaleY(0.4f);
        rowTitle.setAlpha(0f);
        rowTitle.setTranslationY(dp(22));
        sub.setAlpha(0f);
        sub.setTranslationY(dp(14));

        ObjectAnimator logoIn = ObjectAnimator.ofFloat(logo, "scaleX", 0.4f, 1f);
        ObjectAnimator logoInY = ObjectAnimator.ofFloat(logo, "scaleY", 0.4f, 1f);
        ObjectAnimator logoAlpha = ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f);
        logoIn.setDuration(600);
        logoInY.setDuration(600);
        logoAlpha.setDuration(450);
        logoIn.setInterpolator(new OvershootInterpolator(2.6f));
        logoInY.setInterpolator(new OvershootInterpolator(2.6f));

        ObjectAnimator titleAlpha = ObjectAnimator.ofFloat(rowTitle, "alpha", 0f, 1f);
        ObjectAnimator titleY = ObjectAnimator.ofFloat(rowTitle, "translationY", dp(22), 0f);
        titleAlpha.setDuration(550);
        titleY.setDuration(550);
        titleAlpha.setStartDelay(180);
        titleY.setStartDelay(180);

        ObjectAnimator subAlpha = ObjectAnimator.ofFloat(sub, "alpha", 0f, 1f);
        ObjectAnimator subY = ObjectAnimator.ofFloat(sub, "translationY", dp(14), 0f);
        subAlpha.setDuration(450);
        subY.setDuration(450);
        subAlpha.setStartDelay(360);
        subY.setStartDelay(360);

        // Léger battement du logo après l'entrée (effet vivant)
        ObjectAnimator pulse = ObjectAnimator.ofFloat(logo, "scaleX", 1f, 1.06f);
        ObjectAnimator pulseY = ObjectAnimator.ofFloat(logo, "scaleY", 1f, 1.06f);
        pulse.setDuration(700);
        pulseY.setDuration(700);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulseY.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulseY.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setStartDelay(900);
        pulseY.setStartDelay(900);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(logoIn, logoInY, logoAlpha, titleAlpha, titleY, subAlpha, subY, pulse, pulseY);
        set.start();
        return sc;
    }

    /** Masque le splash quand le site est prêt (et après un délai minimum). */
    private void tryHideSplash(boolean force) {
        if (splashHidden || splash == null) return;
        long elapsed = System.currentTimeMillis() - startTime;
        if (!force && !pageLoaded) return;
        if (!force && elapsed < MIN_SPLASH_MS) {
            handler.postDelayed(() -> tryHideSplash(true), Math.max(50, MIN_SPLASH_MS - elapsed));
            return;
        }
        splashHidden = true;
        splash.animate().alpha(0f).setDuration(550).withEndAction(() -> {
            if (splash != null) splash.setVisibility(View.GONE);
            webView.animate().alpha(1f).setDuration(500).start();
            measureSmoothness();
        }).start();
    }

    /* ================= Gestion clavier + plein écran ================= */

    private final ViewTreeObserver.OnGlobalLayoutListener onLayoutChange = () -> {
        View decor = getWindow().getDecorView();
        Rect r = new Rect();
        decor.getWindowVisibleDisplayFrame(r);
        int total = decor.getHeight();
        int diff = total - (r.bottom - r.top);
        boolean kb = diff > Math.max(dp(140), total / 4);
        if (kb != keyboardVisible) {
            keyboardVisible = kb;
            applySystemUi();
        }
        // Pousse la page au-dessus du clavier : un padding bas égal à la
        // hauteur du clavier (diff) réduit la zone de la WebView. Le plein
        // écran reste actif — l'heure/la batterie ne réapparaissent pas.
        if (rootView != null) {
            int pad = kb ? Math.max(diff, 0) : 0;
            if (rootView.getPaddingBottom() != pad) {
                rootView.setPadding(0, 0, 0, pad);
            }
        }
    };

    /**
     * Plein écran permanent : on ne réaffiche JAMAIS les barres système,
     * même quand le clavier est ouvert. Le champ de saisie reste visible
     * car la page est poussée au-dessus du clavier par le padding appliqué
     * dans onLayoutChange (windowSoftInputMode="adjustNothing" : le
     * redimensionnement est entièrement manuel, fiable même en plein écran).
     */
    private void applySystemUi() {
        hideSystemUI();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Toujours en plein écran dès que la fenêtre a le focus (même si le
        // clavier est ouvert) : les barres système ne réapparaissent pas.
        if (hasFocus) hideSystemUI();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUI() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /* ================= Appareil lent : fond décoratif figé ================= */

    private void measureSmoothness() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){var n=0,t=performance.now();" +
                "(function f(){n++;if(performance.now()-t<900){requestAnimationFrame(f);}else{" +
                "window.__fps=Math.round(n/((performance.now()-t)/1000));}})();})()", null);
        new Handler(getMainLooper()).postDelayed(() -> {
            if (webView == null) return;
            webView.evaluateJavascript("String(window.__fps||-1)", value -> {
                try {
                    double fps = Double.parseDouble(value);
                    if (fps > 0 && fps < 35) freezeDecor();
                } catch (Exception ignored) {
                }
            });
        }, 1300);
    }

    private void freezeDecor() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){var st=document.createElement('style');" +
                "st.textContent='.orb{animation:none!important;}" +
                "#particles{display:none!important;}';" +
                "document.head.appendChild(st);})()", null);
    }

    /* ================= Résultat du sélecteur de photos ================= */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1001) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
                    for (int i = 0; i < n; i++) uris.add(data.getClipData().getItemAt(i).getUri());
                    if (!uris.isEmpty()) results = uris.toArray(new Uri[0]);
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /* ================= Navigation / cycle de vie ================= */

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (webView != null) webView.restoreState(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
