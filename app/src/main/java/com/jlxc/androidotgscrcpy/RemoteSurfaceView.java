package com.jlxc.androidotgscrcpy;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;

public final class RemoteSurfaceView extends SurfaceView {
    private int videoWidth = 0;
    private int videoHeight = 0;
    private boolean fillMode = false;

    public RemoteSurfaceView(Context context) {
        super(context);
    }

    public RemoteSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setVideoSize(int width, int height) {
        this.videoWidth = Math.max(1, width);
        this.videoHeight = Math.max(1, height);
        requestLayout();
    }

    public void setFillMode(boolean fillMode) {
        this.fillMode = fillMode;
        requestLayout();
    }

    public boolean isFillMode() {
        return fillMode;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxW = MeasureSpec.getSize(widthMeasureSpec);
        int maxH = MeasureSpec.getSize(heightMeasureSpec);
        if (maxW <= 0 || maxH <= 0 || videoWidth <= 0 || videoHeight <= 0 || fillMode) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        float videoRatio = videoWidth / (float) videoHeight;
        int outW = maxW;
        int outH = Math.round(outW / videoRatio);
        if (outH > maxH) {
            outH = maxH;
            outW = Math.round(outH * videoRatio);
        }
        setMeasuredDimension(Math.max(1, outW), Math.max(1, outH));
    }
}
