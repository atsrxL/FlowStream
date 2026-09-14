package dev.flowstream.player.playback;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import dev.flowstream.core.ReverseClock;
import dev.flowstream.core.SpeedScale;
import java.util.function.Consumer;

/** Main-looper only. Reverse mode is muted sampled seeking, not negative-rate decoding. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class TrickPlayController {
    private final ExoPlayer player;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Consumer<String> hint;
    private final ReverseClock clock=new ReverseClock();
    private boolean held,reversing,restorePlaying,audioDisabled;
    private float baseSpeed=1f,requestedSpeed=1f,restoreVolume=1f;
    private long lastSeek=-1;
    public TrickPlayController(ExoPlayer player,Consumer<String> hint){this.player=player;this.hint=hint;}
    public float baseSpeed(){return baseSpeed;}
    public boolean isHeld(){return held;}
    public boolean isReversing(){return reversing;}
    public void setBaseSpeed(float speed){endHold();baseSpeed=SpeedScale.normalize(speed);applySpeed(baseSpeed);}
    public void beginHold(){if(held)return;held=true;restorePlaying=player.getPlayWhenReady();restoreVolume=player.getVolume();}
    public void doNotResumeAfterHold(){restorePlaying=false;}
    public void forward(float speed){
        if(!held)return;
        stopReverse();applySpeed(speed);player.play();
        hint.accept("快进 "+rateLabel(speed));
    }
    public void reverse(float magnitude){
        if(!held)return;
        if(!player.isCurrentMediaItemSeekable()){hint.accept("无法快退");return;}
        if(!reversing){
            reversing=true;player.pause();player.setVolume(0);player.setSeekParameters(SeekParameters.EXACT);
            clock.start(player.getCurrentPosition(),SystemClock.uptimeMillis(),magnitude);lastSeek=-1;handler.post(tick);
        }else clock.setMagnitude(magnitude);
        hint.accept("快退 "+rateLabel(magnitude));
    }
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(!reversing)return;
        long target=clock.target(SystemClock.uptimeMillis());
        // Never pile seek requests on top of a network/decode operation already buffering.
        if(player.getPlaybackState()==androidx.media3.common.Player.STATE_READY && target!=lastSeek){lastSeek=target;player.seekTo(target);}
        if(target==0)hint.accept("已到开头");
        handler.postDelayed(this,150);
    }};
    private static String rateLabel(float speed){return java.math.BigDecimal.valueOf(Math.round(speed*100f),2).stripTrailingZeros().toPlainString()+"×";}
    public void endHold(){
        if(!held)return;held=false;stopReverse();applySpeed(baseSpeed);player.setVolume(restoreVolume);
        player.setPlayWhenReady(restorePlaying);hint.accept("");
    }
    private void stopReverse(){
        if(!reversing)return;reversing=false;handler.removeCallbacks(tick);player.setSeekParameters(SeekParameters.DEFAULT);player.setVolume(restoreVolume);
    }
    private void applySpeed(float value){
        requestedSpeed=Math.max(.05f,Math.min(5f,value));
        // A video-only clock avoids the audio sink's extreme-slow-speed limits. 0.05x is intentionally silent.
        boolean disableAudio=requestedSpeed<.1f;
        if(disableAudio!=audioDisabled){audioDisabled=disableAudio;player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,disableAudio).build());}
        player.setPlaybackSpeed(requestedSpeed);
    }
    /** Track changes can replace the audio/standalone clock, so reassert the requested rate. */
    public void tracksChanged(){if(!reversing && Math.abs(player.getPlaybackParameters().speed-requestedSpeed)>.001f)player.setPlaybackSpeed(requestedSpeed);}
    public void dispose(){endHold();stopReverse();handler.removeCallbacksAndMessages(null);}
}
