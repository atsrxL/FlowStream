package dev.flowstream.player.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.view.MotionEvent;
import android.widget.SeekBar;

/** A movement starts seeking immediately; stationary taps never seek. Accessibility stays native. */
public final class ScrubBar extends SeekBar {
    private OnSeekBarChangeListener listener;
    private boolean touching,tracking;
    private float downX;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public ScrubBar(Context context){
        super(context);
        setThumb(null);setSplitTrack(false);setProgressDrawable(null);
    }
    @Override protected synchronized void onDraw(Canvas canvas){
        float left=getPaddingLeft(),right=getWidth()-getPaddingRight(),y=getHeight()/2f,half=Ui.dp(getContext(),3)/2f;
        paint.setColor(0xff666666);canvas.drawRoundRect(left,y-half,right,y+half,half,half,paint);
        paint.setColor(Color.WHITE);float end=left+(right-left)*getProgress()/Math.max(1f,getMax());
        canvas.drawRoundRect(left,y-half,end,y+half,half,half,paint);
    }
    @Override public void setOnSeekBarChangeListener(OnSeekBarChangeListener l){listener=l;super.setOnSeekBarChangeListener(l);}
    private void updatePosition(float x){
        float width=getWidth()-getPaddingLeft()-getPaddingRight();if(width<=0)return;
        int progress=Math.round(getMax()*Math.max(0,Math.min(1,(x-getPaddingLeft())/width)));
        setProgress(progress);if(listener!=null)listener.onProgressChanged(this,progress,true);
    }
    public void cancelTracking(){touching=tracking=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!isEnabled())return false;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downX=event.getX();touching=true;return true;
            case MotionEvent.ACTION_MOVE:
                if(!touching)return true;
                if(!tracking&&Math.abs(event.getX()-downX)>=Ui.dp(getContext(),3)){
                    tracking=true;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                    if(listener!=null)listener.onStartTrackingTouch(this);
                }
                if(tracking)updatePosition(event.getX());return true;
            case MotionEvent.ACTION_UP:
                if(tracking){updatePosition(event.getX());if(listener!=null)listener.onStopTrackingTouch(this);}
                cancelTracking();performClick();return true;
            case MotionEvent.ACTION_POINTER_DOWN:case MotionEvent.ACTION_CANCEL:
                boolean wasTracking=tracking;cancelTracking();
                if(wasTracking&&getContext() instanceof PlayerActivity)((PlayerActivity)getContext()).cancelScrub();return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){cancelTracking();super.onDetachedFromWindow();}
}
