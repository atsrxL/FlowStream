package dev.flowstream.player.ui;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.SeekBar;

/** Touch seeking requires a stationary long press; accessibility seeking stays available. */
public final class HoldSeekBar extends SeekBar {
    private OnSeekBarChangeListener listener;
    private boolean waiting,tracking;
    private float downX,downY,currentX;
    private final Runnable activate=()->{if(waiting&&isEnabled()){waiting=false;tracking=true;getParent().requestDisallowInterceptTouchEvent(true);performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);if(listener!=null)listener.onStartTrackingTouch(this);updatePosition();}};
    public HoldSeekBar(Context context){super(context);}
    @Override public void setOnSeekBarChangeListener(OnSeekBarChangeListener l){listener=l;super.setOnSeekBarChangeListener(l);}
    private void updatePosition(){float width=getWidth()-getPaddingLeft()-getPaddingRight();if(width<=0)return;int progress=Math.round(getMax()*Math.max(0,Math.min(1,(currentX-getPaddingLeft())/width)));setProgress(progress);if(listener!=null)listener.onProgressChanged(this,progress,true);}
    public void cancelTracking(){removeCallbacks(activate);waiting=false;tracking=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!isEnabled())return false;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:downX=currentX=event.getX();downY=event.getY();waiting=true;postDelayed(activate,ViewConfiguration.getLongPressTimeout());return true;
            case MotionEvent.ACTION_MOVE:currentX=event.getX();if(waiting&&Math.hypot(currentX-downX,event.getY()-downY)>ViewConfiguration.get(getContext()).getScaledTouchSlop()){waiting=false;removeCallbacks(activate);}if(tracking)updatePosition();return true;
            case MotionEvent.ACTION_UP:if(tracking){currentX=event.getX();updatePosition();if(listener!=null)listener.onStopTrackingTouch(this);}cancelTracking();performClick();return true;
            case MotionEvent.ACTION_POINTER_DOWN:case MotionEvent.ACTION_CANCEL:cancelTracking();if(getContext() instanceof PlayerActivity)((PlayerActivity)getContext()).cancelScrub();return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){cancelTracking();super.onDetachedFromWindow();}
}
