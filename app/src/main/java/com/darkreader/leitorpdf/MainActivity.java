package com.darkreader.leitorpdf;

import android.app.Activity;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;

public class MainActivity extends Activity {
    private static final int OPEN_PDF = 100;
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private Bitmap bitmap;
    private PdfView view;
    private TextView pageLabel;
    private int pageIndex = 0;
    private boolean dark = true;
    private Uri currentUri;
    private boolean landscapeMode;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(16,18,23));
        getWindow().setNavigationBarColor(Color.rgb(16,18,23));
        buildUi();
        if (state != null) {
            dark = state.getBoolean("dark_mode", true);
            String saved = state.getString("open_pdf_uri");
            if (saved != null) open(Uri.parse(saved), state.getInt("pdf_page", 0));
        }
        Intent initial = getIntent();
        if (renderer == null && initial != null
                && Intent.ACTION_VIEW.equals(initial.getAction()) && initial.getData() != null) {
            open(initial.getData());
        }
        applyOrientation(getResources().getConfiguration());
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (currentUri != null) outState.putString("open_pdf_uri", currentUri.toString());
        outState.putInt("pdf_page", pageIndex);
        outState.putBoolean("dark_mode", dark);
        super.onSaveInstanceState(outState);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Sem recriar Activity: mantém o PdfRenderer, a página e o zoom.
        applyOrientation(newConfig);
    }

    private void setLandscapeSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void applyOrientation(Configuration config) {
        landscapeMode = config.orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (landscapeMode) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            setLandscapeSystemBars();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        // PDF aberto: ler sem painel. Um toque no PDF revela controles em paisagem.
        setControls(renderer == null);
        if (view != null) view.invalidate();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && landscapeMode) setLandscapeSystemBars();
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private Button button(String label, LinearLayout group, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.rgb(18, 25, 39));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(221, 231, 249)));
        b.setMinHeight(dp(52));
        b.setMinimumWidth(0);
        b.setOnClickListener(action);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(56), 1f);
        p.setMargins(dp(3), dp(4), dp(3), dp(4));
        group.addView(b, p);
        return b;
    }

    private LinearLayout controlsPanel;
    private Button showControlsButton;

    private void setControls(boolean expanded) {
        if (controlsPanel == null || showControlsButton == null) return;
        controlsPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        // Em modo paisagem o botão não ocupa espaço da página; toque no PDF para abrir.
        showControlsButton.setVisibility(expanded || landscapeMode ? View.GONE : View.VISIBLE);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(17, 19, 24));

        view = new PdfView();
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        controlsPanel = new LinearLayout(this);
        controlsPanel.setOrientation(LinearLayout.VERTICAL);
        controlsPanel.setGravity(Gravity.CENTER);
        controlsPanel.setPadding(dp(13), dp(16), dp(13), dp(16));
        android.graphics.drawable.GradientDrawable panelBackground = new android.graphics.drawable.GradientDrawable();
        panelBackground.setColor(Color.rgb(30, 34, 43));
        panelBackground.setCornerRadius(dp(18));
        controlsPanel.setBackground(panelBackground);
        controlsPanel.setElevation(dp(9));

        TextView title = new TextView(this);
        title.setText("Leitor PDF Dark");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        controlsPanel.addView(title, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout first = new LinearLayout(this);
        first.setGravity(Gravity.CENTER);
        button("Abrir PDF", first, v -> selectFile());
        button("Modo escuro", first, v -> {
            dark = !dark;
            view.invalidate();
        });
        controlsPanel.addView(first, new LinearLayout.LayoutParams(-1, -2));

        pageLabel = new TextView(this);
        pageLabel.setTextColor(Color.LTGRAY);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setTextSize(16);
        pageLabel.setText("Selecione um PDF");
        controlsPanel.addView(pageLabel, new LinearLayout.LayoutParams(-1, dp(38)));

        LinearLayout second = new LinearLayout(this);
        second.setGravity(Gravity.CENTER);
        button("◀ Anterior", second, v -> changePage(-1));
        button("Próxima ▶", second, v -> changePage(1));
        controlsPanel.addView(second, new LinearLayout.LayoutParams(-1, -2));

        Button hide = new Button(this);
        hide.setText("Ocultar controles e ler");
        hide.setAllCaps(false);
        hide.setTextColor(Color.WHITE);
        hide.setOnClickListener(v -> setControls(false));
        controlsPanel.addView(hide, new LinearLayout.LayoutParams(-1, dp(54)));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        panelParams.leftMargin = dp(16);
        panelParams.rightMargin = dp(16);
        root.addView(controlsPanel, panelParams);

        showControlsButton = new Button(this);
        showControlsButton.setText("☰");
        showControlsButton.setTextSize(22);
        showControlsButton.setContentDescription("Mostrar controles do leitor");
        showControlsButton.setTextColor(Color.rgb(18, 25, 39));
        showControlsButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(221, 231, 249)));
        showControlsButton.setOnClickListener(v -> setControls(true));
        FrameLayout.LayoutParams compactParams = new FrameLayout.LayoutParams(
                dp(60), dp(60), Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        compactParams.rightMargin = dp(10);
        root.addView(showControlsButton, compactParams);
        setControls(true);
        setContentView(root);
    }

    private void selectFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/pdf");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, OPEN_PDF);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OPEN_PDF && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {}
            open(uri);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null)
            open(intent.getData());
    }

    private void open(Uri uri) { open(uri, 0); }

    private void open(Uri uri, int restoredPage) {
        try {
            ParcelFileDescriptor nextDescriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (nextDescriptor == null) throw new IOException("Documento indisponível");
            PdfRenderer nextRenderer;
            try { nextRenderer = new PdfRenderer(nextDescriptor); }
            catch (Exception ex) { nextDescriptor.close(); throw ex; }
            closePdf();
            descriptor = nextDescriptor;
            renderer = nextRenderer;
            currentUri = uri;
            pageIndex = Math.max(0, Math.min(restoredPage, renderer.getPageCount() - 1));
            showPage();
            setControls(false);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir este PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void changePage(int by) {
        if (renderer == null) return;
        int candidate = pageIndex + by;
        if (candidate >= 0 && candidate < renderer.getPageCount()) {
            pageIndex = candidate;
            showPage();
        }
    }

    private void showPage() {
        if (renderer == null || renderer.getPageCount() == 0) return;
        PdfRenderer.Page p = null;
        try {
            p = renderer.openPage(pageIndex);
            int targetWidth = Math.min(2200, Math.max(900, getResources().getDisplayMetrics().widthPixels * 2));
            float ratio = targetWidth / (float) p.getWidth();
            int targetHeight = Math.max(1, Math.round(p.getHeight() * ratio));
            long pixels = (long) targetWidth * targetHeight;
            if (pixels > 12000000L) {
                ratio *= Math.sqrt(12000000.0 / pixels);
                targetWidth = Math.max(1, Math.round(p.getWidth() * ratio));
                targetHeight = Math.max(1, Math.round(p.getHeight() * ratio));
            }
            Bitmap updated = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
            updated.eraseColor(Color.WHITE);
            p.render(updated, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            Bitmap previous = bitmap;
            bitmap = updated;
            view.reset();
            if (previous != null && !previous.isRecycled()) previous.recycle();
            pageLabel.setText((pageIndex+1) + " / " + renderer.getPageCount());
        } catch (Exception e) {
            Toast.makeText(this, "Erro ao renderizar página", Toast.LENGTH_SHORT).show();
        } finally {
            if (p != null) p.close();
        }
    }

    private void closePdf() {
        view.reset();
        if (bitmap != null) { bitmap.recycle(); bitmap = null; }
        if (renderer != null) { renderer.close(); renderer = null; }
        if (descriptor != null) {
            try { descriptor.close(); } catch (IOException ignored) {}
            descriptor = null;
        }
    }

    @Override protected void onDestroy() {
        closePdf();
        super.onDestroy();
    }

    private class PdfView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final ColorMatrixColorFilter invert;
        private final ScaleGestureDetector detector;
        private float zoom = 1f;
        private float dx = 0f, dy = 0f;
        private float lastX, lastY;
        private float downX, downY;
        private boolean dragging = false;
        PdfView() {
            super(MainActivity.this);
            ColorMatrix matrix = new ColorMatrix(new float[] {
                -1,0,0,0,255, 0,-1,0,0,255, 0,0,-1,0,255, 0,0,0,1,0
            });
            invert = new ColorMatrixColorFilter(matrix);
            detector = new ScaleGestureDetector(MainActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    zoom = Math.max(1f, Math.min(5f, zoom * d.getScaleFactor()));
                    constrain();
                    invalidate();
                    return true;
                }
            });
        }
        void reset() { zoom = 1f; dx = dy = 0; invalidate(); }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            constrain();
        }
        private float fittedWidth() {
            if (bitmap == null) return 0;
            return Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight()) * bitmap.getWidth() * zoom;
        }
        private float fittedHeight() {
            if (bitmap == null) return 0;
            return Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight()) * bitmap.getHeight() * zoom;
        }
        private void constrain() {
            dx = Math.max(-Math.max(0, (fittedWidth()-getWidth())/2), Math.min(Math.max(0, (fittedWidth()-getWidth())/2), dx));
            dy = Math.max(-Math.max(0, (fittedHeight()-getHeight())/2), Math.min(Math.max(0, (fittedHeight()-getHeight())/2), dy));
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(30,32,37));
            if (bitmap == null || bitmap.isRecycled()) {
                Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
                text.setColor(Color.LTGRAY);
                text.setTextSize(42);
                text.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("Toque em Abrir PDF", getWidth()/2f, getHeight()/2f, text);
                return;
            }
            float w = fittedWidth(), h = fittedHeight();
            RectF rect = new RectF((getWidth()-w)/2+dx, (getHeight()-h)/2+dy,
                    (getWidth()+w)/2+dx, (getHeight()+h)/2+dy);
            paint.setColorFilter(dark ? invert : null);
            canvas.drawBitmap(bitmap, null, rect, paint);
            paint.setColorFilter(null);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            detector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = downX = event.getX(); lastY = downY = event.getY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() == 1 && !detector.isInProgress()) {
                        float mx = event.getX()-lastX, my = event.getY()-lastY;
                        if (Math.abs(event.getX()-downX)>12 || Math.abs(event.getY()-downY)>12) dragging=true;
                        if (zoom > 1f) { dx+=mx; dy+=my; constrain(); invalidate(); }
                        lastX=event.getX(); lastY=event.getY();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    float horizontal = event.getX()-downX;
                    if (zoom==1f && dragging && Math.abs(horizontal)>85 && Math.abs(horizontal)>Math.abs(event.getY()-downY)*1.4f)
                        changePage(horizontal<0?1:-1);
                    else if (!dragging) performClick();
                    return true;
                default: return true;
            }
        }
        @Override public boolean performClick() {
            super.performClick();
            if (landscapeMode && renderer != null) {
                setControls(controlsPanel.getVisibility() != View.VISIBLE);
            }
            return true;
        }
    }
}
