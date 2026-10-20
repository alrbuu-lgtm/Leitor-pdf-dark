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
import android.view.VelocityTracker;
import android.widget.OverScroller;
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
    private TextView floatingPageIndicator;
    private int pageIndex = 0;
    private boolean dark = true;
    private Uri currentUri;
    private boolean landscapeMode;
    private boolean translatedMode = false;
    private Bitmap translatedBitmap;
    private VisualPdfTranslator visualTranslator;
    private Button translationButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(16,18,23));
        getWindow().setNavigationBarColor(Color.rgb(16,18,23));
        buildUi();
        if (state != null) {
            dark = state.getBoolean("dark_mode", true);
            translatedMode = state.getBoolean("translated_mode", false);
            updateTranslationButton();
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
        outState.putBoolean("translated_mode", translatedMode);
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

        // Indicação sempre visível, inclusive com os controles recolhidos.
        floatingPageIndicator = new TextView(this);
        floatingPageIndicator.setTextColor(Color.WHITE);
        floatingPageIndicator.setTextSize(14f);
        floatingPageIndicator.setGravity(Gravity.CENTER);
        floatingPageIndicator.setPadding(dp(15), dp(7), dp(15), dp(7));
        android.graphics.drawable.GradientDrawable indicatorBg =
                new android.graphics.drawable.GradientDrawable();
        indicatorBg.setColor(Color.argb(220, 26, 30, 38));
        indicatorBg.setCornerRadius(dp(30));
        floatingPageIndicator.setBackground(indicatorBg);
        floatingPageIndicator.setElevation(dp(5));
        floatingPageIndicator.setVisibility(View.GONE);
        FrameLayout.LayoutParams numberParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        numberParams.topMargin = dp(17);
        root.addView(floatingPageIndicator, numberParams);

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

        LinearLayout translateRow = new LinearLayout(this);
        translateRow.setGravity(Gravity.CENTER);
        translationButton = button("Traduzir EN → PT", translateRow, v -> toggleVisualTranslation());
        controlsPanel.addView(translateRow, new LinearLayout.LayoutParams(-1, -2));

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

    private void updateTranslationButton() {
        if (translationButton != null) {
            translationButton.setText(translatedMode ? "Ver PDF original" : "Traduzir página EN → PT");
        }
    }

    private void cancelVisualTranslation() {
        if (visualTranslator != null) visualTranslator.cancel();
        if (translatedBitmap != null) {
            translatedBitmap.recycle();
            translatedBitmap = null;
        }
        if (view != null) view.invalidate();
    }

    private void toggleVisualTranslation() {
        if (renderer == null || bitmap == null) {
            Toast.makeText(this, "Abra um PDF antes de traduzir.", Toast.LENGTH_SHORT).show();
            return;
        }
        translatedMode = !translatedMode;
        updateTranslationButton();
        if (translatedMode) {
            startVisualTranslation();
        } else {
            cancelVisualTranslation();
            pageLabel.setText((pageIndex + 1) + " / " + renderer.getPageCount());
        }
        setControls(false);
    }

    private void startVisualTranslation() {
        if (!translatedMode || bitmap == null || renderer == null) return;
        if (visualTranslator == null) visualTranslator = new VisualPdfTranslator();
        final int requestedPage = pageIndex;
        final Bitmap requestedBitmap = bitmap;
        Toast.makeText(this, "Traduzindo a página. O primeiro uso precisa baixar o modelo EN–PT.",
                Toast.LENGTH_LONG).show();
        visualTranslator.translatePage(requestedBitmap, new VisualPdfTranslator.Callback() {
            @Override public void onStatus(String status) {
                if (translatedMode && pageIndex == requestedPage && bitmap == requestedBitmap) {
                    pageLabel.setText(status);
                }
            }
            @Override public void onTranslated(Bitmap result, int blocks) {
                if (!translatedMode || renderer == null || pageIndex != requestedPage
                        || bitmap != requestedBitmap) {
                    if (!result.isRecycled()) result.recycle();
                    return;
                }
                if (translatedBitmap != null) translatedBitmap.recycle();
                translatedBitmap = result;
                view.invalidate();
                pageLabel.setText((pageIndex + 1) + " / " + renderer.getPageCount() + " · PT");
                Toast.makeText(MainActivity.this,
                        "Tradução visual pronta (" + blocks + " blocos). Toque no PDF para abrir os controles.",
                        Toast.LENGTH_LONG).show();
            }
            @Override public void onError(String message) {
                if (translatedMode && renderer != null && pageIndex == requestedPage
                        && bitmap == requestedBitmap) {
                    translatedMode = false;
                    updateTranslationButton();
                    pageLabel.setText((pageIndex + 1) + " / " + renderer.getPageCount());
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        });
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

    private void updatePageIndicator() {
        if (renderer == null || renderer.getPageCount() == 0) {
            if (floatingPageIndicator != null) floatingPageIndicator.setVisibility(View.GONE);
            return;
        }
        String counter = (pageIndex + 1) + " / " + renderer.getPageCount();
        if (floatingPageIndicator != null) {
            floatingPageIndicator.setText(counter);
            floatingPageIndicator.setVisibility(View.VISIBLE);
        }
        if (pageLabel != null) {
            pageLabel.setText(counter + (translatedMode ? " · PT" : ""));
        }
    }

    private void changePage(int by) {
        if (renderer == null) return;
        int candidate = pageIndex + by;
        if (candidate < 0 || candidate >= renderer.getPageCount()) return;
        pageIndex = candidate;
        showPage(false);
    }

    // Visualização contínua usa uma miniatura da página vizinha. Não guarda o
    // documento inteiro na memória, mesmo quando contém centenas de páginas.
    private Bitmap renderPdfPage(int index, boolean preview) throws Exception {
        if (renderer == null || index < 0 || index >= renderer.getPageCount()) return null;
        PdfRenderer.Page pdf = renderer.openPage(index);
        try {
            int targetWidth = preview
                    ? Math.min(1000, Math.max(600, getResources().getDisplayMetrics().widthPixels))
                    : Math.min(2200, Math.max(900, getResources().getDisplayMetrics().widthPixels * 2));
            float ratio = targetWidth / (float) pdf.getWidth();
            int w = Math.max(1, Math.round(pdf.getWidth() * ratio));
            int h = Math.max(1, Math.round(pdf.getHeight() * ratio));
            long limit = preview ? 3000000L : 12000000L;
            long pixels = (long) w * h;
            if (pixels > limit) {
                ratio *= Math.sqrt(limit / (double) pixels);
                w = Math.max(1, Math.round(pdf.getWidth() * ratio));
                h = Math.max(1, Math.round(pdf.getHeight() * ratio));
            }
            Bitmap img = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            img.eraseColor(Color.WHITE);
            try {
                pdf.render(img, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return img;
            } catch (Exception e) {
                img.recycle();
                throw e;
            }
        } finally {
            pdf.close();
        }
    }

    private void showPage() { showPage(false); }

    private void showPage(boolean continuousScroll) {
        cancelVisualTranslation();
        if (renderer == null || renderer.getPageCount() == 0) return;
        try {
            Bitmap updated = renderPdfPage(pageIndex, false);
            if (updated == null) return;
            // Elimina as miniaturas antigas antes de reciclar o bitmap principal.
            view.clearPreviews();
            Bitmap previous = bitmap;
            bitmap = updated;
            if (!continuousScroll) view.reset();
            if (previous != null && !previous.isRecycled()) previous.recycle();
            updatePageIndicator();
            view.invalidate();
            if (translatedMode) startVisualTranslation();
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível carregar a página: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void closePdf() {
        cancelVisualTranslation();
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
        if (visualTranslator != null) {
            visualTranslator.close();
            visualTranslator = null;
        }
        super.onDestroy();
    }

    private class PdfView extends View {
        private static final float MAX_ZOOM = 4f;
        private final int gap = dp(12);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final ColorMatrixColorFilter invert;
        private final ScaleGestureDetector scaleDetector;
        private final OverScroller scroller = new OverScroller(MainActivity.this);
        private VelocityTracker velocityTracker;
        private Bitmap previousPreview, nextPreview;
        private int previousPreviewIndex = -1, nextPreviewIndex = -1;
        private float zoom = 1f;
        private float horizontalOffset = 0f;
        private float pageOffset = 0f;
        private float lastX, lastY, downX, downY;
        private boolean dragging;
        private int lastFlingY;

        PdfView() {
            super(MainActivity.this);
            invert = new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                    -1,0,0,0,255, 0,-1,0,0,255, 0,0,-1,0,255, 0,0,0,1,0
            }));
            scaleDetector = new ScaleGestureDetector(MainActivity.this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    if (bitmap == null) return false;
                    float oldZoom = zoom;
                    zoom = Math.max(1f, Math.min(MAX_ZOOM, zoom * d.getScaleFactor()));
                    float k = zoom / oldZoom;
                    pageOffset = (pageOffset + d.getFocusY()) * k - d.getFocusY();
                    horizontalOffset = (horizontalOffset - (d.getFocusX() - getWidth() / 2f)) * k
                            + (d.getFocusX() - getWidth() / 2f);
                    boundHorizontal();
                    invalidate();
                    return true;
                }
            });
        }

        void reset() {
            scroller.forceFinished(true);
            zoom = 1f;
            horizontalOffset = 0f;
            pageOffset = 0f;
            clearPreviews();
            invalidate();
        }

        void clearPreviews() {
            if (previousPreview != null && !previousPreview.isRecycled()) previousPreview.recycle();
            if (nextPreview != null && !nextPreview.isRecycled()) nextPreview.recycle();
            previousPreview = null; nextPreview = null;
            previousPreviewIndex = -1; nextPreviewIndex = -1;
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (oldWidth > 0 && width > 0) {
                pageOffset *= (float) width / oldWidth;
                horizontalOffset *= (float) width / oldWidth;
            }
            boundHorizontal();
        }

        private float pageHeight(Bitmap b) {
            if (b == null || b.isRecycled() || b.getWidth() == 0) return 0f;
            return getWidth() * zoom * b.getHeight() / (float) b.getWidth();
        }

        private void boundHorizontal() {
            float range = Math.max(0f, (getWidth() * zoom - getWidth()) / 2f);
            horizontalOffset = Math.max(-range, Math.min(range, horizontalOffset));
        }

        private Bitmap preview(int index, boolean before) {
            if (renderer == null || index < 0 || index >= renderer.getPageCount()) return null;
            Bitmap saved = before ? previousPreview : nextPreview;
            int savedIndex = before ? previousPreviewIndex : nextPreviewIndex;
            if (index == savedIndex && saved != null && !saved.isRecycled()) return saved;
            try {
                Bitmap rendered = renderPdfPage(index, true);
                if (before) {
                    if (previousPreview != null && !previousPreview.isRecycled()) previousPreview.recycle();
                    previousPreview = rendered;
                    previousPreviewIndex = index;
                } else {
                    if (nextPreview != null && !nextPreview.isRecycled()) nextPreview.recycle();
                    nextPreview = rendered;
                    nextPreviewIndex = index;
                }
                return rendered;
            } catch (Exception ex) {
                return null;
            }
        }

        private void drawPage(Canvas canvas, Bitmap b, float top) {
            if (b == null || b.isRecycled()) return;
            float w = getWidth() * zoom, h = pageHeight(b);
            if (top >= getHeight() || top + h <= 0f) return;
            float left = (getWidth() - w) / 2f + horizontalOffset;
            canvas.drawBitmap(b, null, new RectF(left, top, left + w, top + h), paint);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(30, 32, 37));
            if (bitmap == null || bitmap.isRecycled()) {
                Paint info = new Paint(Paint.ANTI_ALIAS_FLAG);
                info.setColor(Color.LTGRAY);
                info.setTextSize(dp(18));
                info.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("Toque em Abrir PDF", getWidth()/2f, getHeight()/2f, info);
                return;
            }
            paint.setColorFilter(dark ? invert : null);
            Bitmap displayed = translatedMode && translatedBitmap != null
                    && !translatedBitmap.isRecycled() ? translatedBitmap : bitmap;
            float currentTop = -pageOffset;
            float currentH = pageHeight(bitmap);

            // As páginas vizinhas já aparecem antes de a anterior sair da tela.
            if (renderer != null && pageIndex > 0 && currentTop > 0) {
                Bitmap prev = preview(pageIndex - 1, true);
                if (prev != null) drawPage(canvas, prev, currentTop - pageHeight(prev) - gap);
            }
            drawPage(canvas, displayed, currentTop);
            if (renderer != null && pageIndex + 1 < renderer.getPageCount()
                    && currentTop + currentH + gap < getHeight()) {
                Bitmap next = preview(pageIndex + 1, false);
                if (next != null) drawPage(canvas, next, currentTop + currentH + gap);
            }
            paint.setColorFilter(null);
        }

        /**
         * Desloca o documento sem trocar de tela. Quando o centro da tela passa
         * à página seguinte, atualiza o índice e carrega uma nova janela de
         * 3 páginas (anterior, atual e próxima).
         */
        private void scrollByDistance(float amount) {
            if (renderer == null || bitmap == null || getWidth() <= 0) return;
            pageOffset += amount;
            int attempts = 0;
            while (attempts++ < 5) {
                float currentH = pageHeight(bitmap);
                if (pageOffset + getHeight() / 2f >= currentH + gap
                        && pageIndex < renderer.getPageCount() - 1) {
                    pageOffset -= currentH + gap;
                    pageIndex++;
                    showPage(true);
                    continue;
                }
                if (pageOffset + getHeight() / 2f < 0 && pageIndex > 0) {
                    Bitmap prev = preview(pageIndex - 1, true);
                    float previousH = pageHeight(prev);
                    if (previousH <= 0) break;
                    pageOffset += previousH + gap;
                    pageIndex--;
                    showPage(true);
                    continue;
                }
                break;
            }
            if (pageIndex == 0) pageOffset = Math.max(0f, pageOffset);
            if (pageIndex == renderer.getPageCount() - 1) {
                float end = Math.max(0f, pageHeight(bitmap) - getHeight());
                pageOffset = Math.min(pageOffset, end);
            }
            invalidate();
        }

        @Override public void computeScroll() {
            if (scroller.computeScrollOffset()) {
                int nextY = scroller.getCurrY();
                scrollByDistance(nextY - lastFlingY);
                lastFlingY = nextY;
                postInvalidateOnAnimation();
            }
        }

        private void releaseVelocity() {
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scroller.forceFinished(true);
                    releaseVelocity();
                    velocityTracker = VelocityTracker.obtain();
                    velocityTracker.addMovement(event);
                    lastX = downX = event.getX();
                    lastY = downY = event.getY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    dragging = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (velocityTracker != null) velocityTracker.addMovement(event);
                    if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                        float mx = event.getX() - lastX;
                        float my = event.getY() - lastY;
                        if (Math.abs(event.getX()-downX) > dp(8)
                                || Math.abs(event.getY()-downY) > dp(8)) dragging = true;
                        if (zoom > 1f) {
                            horizontalOffset += mx;
                            boundHorizontal();
                        }
                        scrollByDistance(-my);
                        lastX = event.getX();
                        lastY = event.getY();
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerCount() > 1) {
                        int remaining = event.getActionIndex() == 0 ? 1 : 0;
                        lastX = downX = event.getX(remaining);
                        lastY = downY = event.getY(remaining);
                        dragging = true;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                        velocityTracker.computeCurrentVelocity(1000, 6500);
                        float speed = velocityTracker.getYVelocity();
                        if (dragging && !scaleDetector.isInProgress() && Math.abs(speed) > 550f) {
                            scroller.fling(0, 0, 0, (int) -speed,
                                    0, 0, -300000, 300000);
                            lastFlingY = 0;
                            postInvalidateOnAnimation();
                        }
                    }
                    if (!dragging) performClick();
                    releaseVelocity();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    releaseVelocity();
                    return true;
                default:
                    return true;
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
