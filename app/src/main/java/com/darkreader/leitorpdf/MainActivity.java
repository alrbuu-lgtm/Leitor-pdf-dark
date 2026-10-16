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
import android.widget.Button;
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

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(16,18,23));
        getWindow().setNavigationBarColor(Color.rgb(16,18,23));
        buildUi();
        Intent initial = getIntent();
        if (initial != null && Intent.ACTION_VIEW.equals(initial.getAction()) && initial.getData() != null) {
            open(initial.getData());
        }
    }

    private Button button(String label, LinearLayout panel, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setOnClickListener(listener);
        panel.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16,18,23));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        button("Abrir PDF", top, v -> selectFile());
        button("Modo escuro", top, v -> { dark = !dark; view.invalidate(); });
        root.addView(top);
        view = new PdfView();
        root.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        button("◀ Anterior", bottom, v -> changePage(-1));
        pageLabel = new TextView(this);
        pageLabel.setTextColor(Color.WHITE);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setText("Abra um PDF");
        bottom.addView(pageLabel, new LinearLayout.LayoutParams(0, -2, 1));
        button("Próxima ▶", bottom, v -> changePage(1));
        root.addView(bottom);
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

    private void open(Uri uri) {
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
            pageIndex = 0;
            showPage();
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
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
