package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Internet wallpaper picker, self-drawn chrome (see {@link SelfDrawnUi}). The search field, the
 * "set this image" confirmation and the progress/status feedback are all custom onDraw views; the
 * only native views are an invisible EditText used to raise the system keyboard and the WebView that
 * shows the image results.
 */
public final class InternetWallpaperActivity extends Activity {
    private static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView browser;
    private SelfDrawnUi.Banner banner;
    private SearchBar searchBar;
    private EditText hiddenInput;
    private FrameLayout confirmOverlay;
    private boolean downloading;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);
        root.addView(new SelfDrawnUi.GradientView(this), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padH = SelfDrawnUi.dpi(this, 32), padV = SelfDrawnUi.dpi(this, 20);
        column.setPadding(padH, padV, padH, padV);

        column.addView(new SelfDrawnUi.TextLine(this, getString(R.string.wallpaper_internet),
                SelfDrawnUi.TextLine.TITLE), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, SelfDrawnUi.dpi(this, 56));
        barLp.topMargin = SelfDrawnUi.dpi(this, 12);
        searchBar = new SearchBar(this);
        searchBar.setOnClickListener(v -> openKeyboard());
        bar.addView(searchBar, new LinearLayout.LayoutParams(0, -1, 1));
        SelfDrawnUi.RowView searchBtn = new SelfDrawnUi.RowView(this, getString(R.string.wallpaper_search), null, false);
        searchBtn.setOnClickListener(v -> search());
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(SelfDrawnUi.dpi(this, 150), -1);
        sLp.leftMargin = SelfDrawnUi.dpi(this, 12);
        bar.addView(searchBtn, sLp);
        column.addView(bar, barLp);

        SelfDrawnUi.TextLine hint = new SelfDrawnUi.TextLine(this, getString(R.string.wallpaper_long_press),
                SelfDrawnUi.TextLine.HINT);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-1, -2);
        hLp.topMargin = SelfDrawnUi.dpi(this, 8);
        column.addView(hint, hLp);

        browser = new WebView(this);
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setSupportZoom(true);
        browser.setWebChromeClient(new WebChromeClient());
        browser.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                view.loadUrl("javascript:(function(){document.addEventListener('contextmenu',function(e){"
                        + "var n=e.target;while(n&&n.tagName!=='IMG')n=n.parentElement;"
                        + "if(n&&n.src){e.preventDefault();WallpaperBridge.pick(n.currentSrc||n.src);}},true);})()");
            }
        });
        browser.addJavascriptInterface(new ImageBridge(), "WallpaperBridge");
        browser.setOnLongClickListener(v -> {
            WebView.HitTestResult hit = browser.getHitTestResult();
            if (hit != null && hit.getExtra() != null
                    && (hit.getType() == WebView.HitTestResult.IMAGE_TYPE
                    || hit.getType() == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                confirmImage(hit.getExtra());
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(-1, 0, 1);
        webLp.topMargin = SelfDrawnUi.dpi(this, 10);
        column.addView(browser, webLp);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        // Invisible EditText: raises the system keyboard; its text feeds the self-drawn search bar.
        hiddenInput = new EditText(this);
        hiddenInput.setAlpha(0f);
        hiddenInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        hiddenInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { searchBar.setQuery(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        hiddenInput.setOnEditorActionListener((v, actionId, event) -> { search(); return true; });
        root.addView(hiddenInput, new FrameLayout.LayoutParams(1, 1));

        banner = new SelfDrawnUi.Banner(this);
        root.addView(banner, new FrameLayout.LayoutParams(-1, -1));

        confirmOverlay = buildConfirmOverlay();
        root.addView(confirmOverlay, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        searchBar.post(searchBar::requestFocus);
        searchBar.setQuery("wallpaper");
        search();
    }

    private void openKeyboard() {
        hiddenInput.requestFocus();
        InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (ime != null) ime.showSoftInput(hiddenInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void search() {
        String text = searchBar.getQuery().trim();
        if (text.isEmpty()) return;
        if (text.startsWith("http://") || text.startsWith("https://")) {
            browser.loadUrl(text);
        } else {
            try {
                browser.loadUrl("https://www.bing.com/images/search?q=" + URLEncoder.encode(text, "UTF-8"));
            } catch (Exception ignored) { }
        }
        InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(hiddenInput.getWindowToken(), 0);
        browser.requestFocus();
    }

    private final class ImageBridge {
        @JavascriptInterface public void pick(String url) {
            main.post(() -> confirmImage(url));
        }
    }

    private FrameLayout buildConfirmOverlay() {
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setVisibility(View.GONE);
        View scrim = new View(this);
        scrim.setBackgroundColor(0xC0000000);
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.setOnClickListener(v -> overlay.setVisibility(View.GONE));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(SelfDrawnUi.dpi(this, 28), SelfDrawnUi.dpi(this, 24),
                SelfDrawnUi.dpi(this, 28), SelfDrawnUi.dpi(this, 24));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(SelfDrawnUi.ROW);
        bg.setCornerRadius(SelfDrawnUi.dpi(this, 18));
        bg.setStroke(SelfDrawnUi.dpi(this, 1), SelfDrawnUi.ACCENT);
        panel.setBackgroundDrawable(bg);
        panel.addView(new SelfDrawnUi.TextLine(this, getString(R.string.wallpaper_set_this),
                SelfDrawnUi.TextLine.SECTION), new LinearLayout.LayoutParams(-1, -2));

        SelfDrawnUi.RowView yes = new SelfDrawnUi.RowView(this, getString(R.string.enable), null, false);
        final String[] pending = {null};
        yes.setOnClickListener(v -> {
            overlay.setVisibility(View.GONE);
            if (pending[0] != null) download(pending[0]);
        });
        LinearLayout.LayoutParams yLp = new LinearLayout.LayoutParams(-1, -2);
        yLp.topMargin = SelfDrawnUi.dpi(this, 18);
        panel.addView(yes, yLp);

        SelfDrawnUi.RowView no = new SelfDrawnUi.RowView(this, getString(R.string.qr_back), null, false);
        no.setOnClickListener(v -> overlay.setVisibility(View.GONE));
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(-1, -2);
        nLp.topMargin = SelfDrawnUi.dpi(this, 10);
        panel.addView(no, nLp);

        overlay.setTag(pending);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                SelfDrawnUi.dpi(this, 460), -2, Gravity.CENTER);
        overlay.addView(panel, panelLp);
        return overlay;
    }

    private void confirmImage(String imageUrl) {
        if (downloading || imageUrl == null
                || !(imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) return;
        @SuppressWarnings("unchecked")
        String[] pending = (String[]) confirmOverlay.getTag();
        pending[0] = imageUrl;
        confirmOverlay.setVisibility(View.VISIBLE);
        View panel = confirmOverlay.getChildAt(1);
        if (panel instanceof LinearLayout) ((LinearLayout) panel).getChildAt(1).requestFocus();
    }

    private void download(String imageUrl) {
        downloading = true;
        banner.show(getString(R.string.wallpaper_downloading), 60000);
        new Thread(() -> {
            boolean saved = false;
            File temp = null;
            try {
                File dir = Config.dir(this);
                if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new Exception("No storage");
                temp = new File(dir, Config.WALLPAPER_NAME + ".download");
                HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 4.4) AppleWebKit/537.36 Chrome/70 Mobile Safari/537.36");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw new Exception("HTTP " + status);
                int length = connection.getContentLength();
                if (length > MAX_IMAGE_BYTES) throw new Exception("Image too large");
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[8192];
                    int count, total = 0;
                    while ((count = in.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_IMAGE_BYTES) throw new Exception("Image too large");
                        out.write(buffer, 0, count);
                    }
                } finally {
                    connection.disconnect();
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(temp.getAbsolutePath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("Not an image");
                File target = new File(dir, Config.WALLPAPER_NAME);
                if (target.exists() && !target.delete()) throw new Exception("Cannot replace wallpaper");
                if (!temp.renameTo(target)) throw new Exception("Cannot save wallpaper");
                saved = true;
            } catch (Exception ignored) {
                if (temp != null) temp.delete();
            }
            final boolean result = saved;
            main.post(() -> {
                downloading = false;
                if (result) {
                    setResult(RESULT_OK);
                    banner.show(getString(R.string.wallpaper_selected), 1500);
                    main.postDelayed(this::finish, 900);
                } else {
                    banner.show(getString(R.string.wallpaper_download_failed), 3000);
                }
            });
        }, "wallpaper-download").start();
    }

    @Override public void onBackPressed() {
        if (confirmOverlay != null && confirmOverlay.getVisibility() == View.VISIBLE) {
            confirmOverlay.setVisibility(View.GONE);
        } else if (browser != null && browser.canGoBack()) {
            browser.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        if (browser != null) {
            browser.removeJavascriptInterface("WallpaperBridge");
            browser.stopLoading();
            browser.destroy();
        }
        super.onDestroy();
    }

    /** Self-drawn search field: shows the current query or a hint, highlighted when focused. */
    private static final class SearchBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private String query = "";
        private boolean focused;

        SearchBar(android.content.Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            textPaint.setAntiAlias(true);
        }

        void setQuery(String q) { query = q == null ? "" : q; invalidate(); }
        String getQuery() { return query; }

        @Override protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
            super.onFocusChanged(gain, dir, prev);
            focused = gain;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float pad = SelfDrawnUi.dpi(getContext(), 3);
            rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
            float radius = SelfDrawnUi.dpi(getContext(), 14);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(focused ? SelfDrawnUi.ROW_FOCUS : SelfDrawnUi.ROW);
            canvas.drawRoundRect(rect, radius, radius, paint);
            if (focused) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(SelfDrawnUi.dpi(getContext(), 2));
                paint.setColor(SelfDrawnUi.ACCENT);
                rect.inset(SelfDrawnUi.dpi(getContext(), 1.5f), SelfDrawnUi.dpi(getContext(), 1.5f));
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
            textPaint.setTextSize(SelfDrawnUi.sp(getContext(), 18));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            float left = SelfDrawnUi.dpi(getContext(), 20);
            if (query.isEmpty()) {
                textPaint.setColor(SelfDrawnUi.DIM);
                textPaint.setTypeface(Typeface.DEFAULT);
                canvas.drawText(getContext().getString(R.string.wallpaper_search_hint), left, baseline, textPaint);
            } else {
                textPaint.setColor(SelfDrawnUi.TEXT);
                textPaint.setTypeface(Typeface.DEFAULT);
                canvas.drawText(query, left, baseline, textPaint);
            }
        }
    }
}
