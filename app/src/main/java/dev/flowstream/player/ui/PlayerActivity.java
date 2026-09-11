package dev.flowstream.player.ui;

import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.*;
import android.util.Rational;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.PlayerView;
import dev.flowstream.core.*;
import dev.flowstream.player.FlowApp;
import dev.flowstream.player.R;
import dev.flowstream.player.data.Server;
import dev.flowstream.player.data.Session;
import dev.flowstream.player.net.SourceBundle;
import dev.flowstream.player.net.NextVideoCache;
import dev.flowstream.player.playback.*;
import java.util.Locale;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends BackActivity implements GestureOverlay.Output {
    private FlowApp app;
    private FrameLayout root;
    private PlayerView video;
    private GestureOverlay gestures;
    private VideoTransformController transform;
    private LinearLayout top,bottom,errorBox;
    private TextView title,counter,time,hud,errorMessage;
    private Button speedButton,pauseButton;
    private HoldSeekBar seek;
    private TextView mediaInfo;
    private long previewTarget=-1;
    private ProgressBar buffering;
    private TextView loadingRate;
    private final java.util.concurrent.atomic.AtomicLong loadedBytes=new java.util.concurrent.atomic.AtomicLong();
    private long rateAt,rateBytes;
    private double loadRate;
    private ExoPlayer player;
    private MediaSession mediaSession;
    private TrickPlayController trick;
    private SourceBundle source;
    private Session session;
    private Server server;
    private String sessionId;
    private boolean deleting;
    private AlertDialog deleteConfirm;
    private boolean visible,loading,resumePlaying=true,scrubbing,scrubResume,pipTransition;
    private int generation;
    private final NextVideoCache nextCache=new NextVideoCache();
    private long loopA=-1,loopB=-1;
    private boolean gestureLocked;
    private Button lockButton,abButton;
    private RandomOrder randomOrder;
    private boolean usingRandom;
    private final dev.flowstream.player.net.FramePreview framePreview=new dev.flowstream.player.net.FramePreview();
    private Runnable pendingPreview;
    private ImageView preview;
    private TextView previewTime;
    
    private long lastPreviewCapture;
    private final java.util.TreeMap<Long,android.graphics.Bitmap> thumbnails=new java.util.TreeMap<>();
    private long networkStartBytes,networkStartTime;

    private long lastCheckpoint;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable hideControls=()->{if(!scrubbing && (mediaInfo==null||mediaInfo.getVisibility()!=View.VISIBLE) && player!=null && player.getPlayWhenReady() && (trick==null || !trick.isHeld())){top.setVisibility(View.GONE);bottom.setVisibility(View.GONE);}};
    private final Runnable hideHint=()->hud.setVisibility(View.GONE);
    private final Runnable update=new Runnable(){@Override public void run(){
        if(!visible)return;
        long now=SystemClock.elapsedRealtime(),bytes=loadedBytes.get();
        if(rateAt==0){rateAt=now;rateBytes=bytes;}
        else if(now-rateAt>=500){loadRate=Math.max(0,bytes-rateBytes)*1000d/(now-rateAt);rateAt=now;rateBytes=bytes;}
        if(loadingRate!=null){loadingRate.setVisibility(buffering.getVisibility());loadingRate.setText(String.format(Locale.ROOT,"加载 · %.1f %s",loadRate>=1048576?loadRate/1048576:loadRate/1024,loadRate>=1048576?"MB/s":"KB/s"));}

        if(player!=null && session!=null){
            long duration=player.getDuration(),position=player.getCurrentPosition();
            if(loopA>=0 && loopB>loopA && !scrubbing && player.getPlayWhenReady() && position>=loopB)player.seekTo(loopA);
            capturePreview(position);
            if(!scrubbing){seek.setEnabled(duration>0 && player.isCurrentMediaItemSeekable());seek.setProgress(duration>0?(int)Math.min(10000,position*10000.0/duration):0);}
            if(!scrubbing)time.setText(Ui.time(position)+" / "+Ui.time(duration));pauseButton.setText(player.getPlayWhenReady()?"Ⅱ":"▶");pauseButton.setContentDescription(player.getPlayWhenReady()?"暂停":"播放");
            if(SystemClock.uptimeMillis()-lastCheckpoint>=5000){checkpoint();lastCheckpoint=SystemClock.uptimeMillis();}
        }
        handler.postDelayed(this,250);
    }};
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);app=(FlowApp)getApplication();sessionId=getIntent().getStringExtra("session_id");
        if(state!=null){sessionId=state.getString("session_id",sessionId);resumePlaying=state.getBoolean("playing",true);}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(state==null?ActivityInfo.SCREEN_ORIENTATION_LOCKED:state.getInt("orientation",ActivityInfo.SCREEN_ORIENTATION_LOCKED));
        buildUi();immersive();networkStartBytes=android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());networkStartTime=SystemClock.elapsedRealtime();
    }
    private void buildUi(){
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        video=(PlayerView)getLayoutInflater().inflate(R.layout.flow_video,root,false);
        transform=new VideoTransformController(video);
        root.addView(video,new FrameLayout.LayoutParams(-1,-1));
        gestures=new GestureOverlay(this,this);gestures.setOnClickListener(v->singleTap(gestures.getWidth()/2f,gestures.getHeight()/2f));root.addView(gestures,new FrameLayout.LayoutParams(-1,-1));
        buffering=new ProgressBar(this);FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48),Gravity.CENTER);root.addView(buffering,bp);
        loadingRate=Ui.text(this,"加载 · 0.0 KB/s",13,Ui.TEXT);loadingRate.setGravity(Gravity.CENTER);loadingRate.setBackground(Ui.background(0xaa000000,10,this));FrameLayout.LayoutParams rp=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER);rp.topMargin=Ui.dp(this,84);root.addView(loadingRate,rp);
        hud=Ui.text(this,"",17,Ui.TEXT);hud.setGravity(Gravity.CENTER);Ui.pad(hud,16);hud.setBackground(Ui.background(0xb0000000,16,this));hud.setVisibility(View.GONE);
        root.addView(hud,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        top=Ui.column(this);top.setBackgroundColor(Color.TRANSPARENT);top.setPadding(Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,10));
        title=Ui.text(this,"",15,Ui.TEXT);counter=Ui.text(this,"",12,Ui.MUTED);
        LinearLayout toolbar=Ui.row(this);
        toolbar.addView(new IconButton(this,"rotate","旋转",this::rotate),new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));
        toolbar.addView(new IconButton(this,"close","退出",this::back),new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));
        toolbar.addView(new IconButton(this,"pip","小窗",this::enterPip),new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));
        speedButton=new IconButton(this,"speed","播放速度",this::speedMenu);toolbar.addView(speedButton,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));top.addView(toolbar);
        root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        bottom=Ui.column(this);bottom.setBackgroundColor(Color.TRANSPARENT);Ui.pad(bottom,8);
        LinearLayout infoRow=Ui.row(this);
        infoRow.addView(new IconButton(this,"info","媒体信息",this::playbackInfo),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        infoRow.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));infoRow.addView(new IconButton(this,"more","菜单",this::fileMenu),new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));bottom.addView(infoRow);
        LinearLayout timeline=Ui.row(this);
        time=Ui.text(this,"00:00 / --:--",11,Ui.TEXT);time.setGravity(Gravity.CENTER);timeline.addView(time,new LinearLayout.LayoutParams(Ui.dp(this,96),Ui.dp(this,48)));
        seek=new HoldSeekBar(this);seek.setMax(10000);seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));seek.setContentDescription("长按后拖动播放进度并预览缩略图");timeline.addView(seek,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));bottom.addView(timeline);
        LinearLayout buttons=Ui.row(this);buttons.setGravity(Gravity.CENTER);
        lockButton=new IconButton(this,"lock","锁定手势",this::toggleLock);buttons.addView(lockButton,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        circle(buttons,"-10s",()->seekBy(-10000,false));circle(buttons,"-2s",()->seekBy(-2000,false));
        pauseButton=new IconButton(this,"play","播放或暂停",this::playControl);buttons.addView(pauseButton,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        circle(buttons,"+2s",()->seekBy(2000,false));circle(buttons,"+10s",()->seekBy(10000,false));abButton=circle(buttons,"A/B",this::cycleAb);
        bottom.addView(buttons,new LinearLayout.LayoutParams(-1,-2));
        buttons.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            int width=r-l,gap=Ui.dp(this,8),size=Math.min(Ui.dp(this,44),Math.max(1,(width-gap*6)/7));
            int spacing=Math.max(gap,(width-size*7)/6);
            for(int i=0;i<buttons.getChildCount();i++){View child=buttons.getChildAt(i);LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)child.getLayoutParams();int margin=i==6?0:spacing;if(lp.width!=size||lp.height!=size||lp.rightMargin!=margin){lp.width=lp.height=size;lp.rightMargin=margin;child.setLayoutParams(lp);}}

        });
        mediaInfo=Ui.text(this,"",13,Ui.TEXT);Ui.pad(mediaInfo,16);mediaInfo.setBackground(Ui.background(0xee1d1d1f,16,this));mediaInfo.setVisibility(View.GONE);
        FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);ip.topMargin=Ui.dp(this,96);ip.leftMargin=ip.rightMargin=Ui.dp(this,16);root.addView(mediaInfo,ip);mediaInfo.setOnClickListener(v->mediaInfo.setVisibility(View.GONE));
        preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setVisibility(View.GONE);
        FrameLayout.LayoutParams previewParams=new FrameLayout.LayoutParams(Ui.dp(this,160),Ui.dp(this,90),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);previewParams.bottomMargin=Ui.dp(this,176);root.addView(preview,previewParams);
        previewTime=Ui.text(this,"",12,Ui.TEXT);previewTime.setGravity(Gravity.CENTER);previewTime.setVisibility(View.GONE);FrameLayout.LayoutParams pt=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);pt.bottomMargin=Ui.dp(this,154);root.addView(previewTime,pt);
        root.addView(bottom,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        bottom.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            int margin=root.getHeight()-t-Ui.dp(this,48);
            FrameLayout.LayoutParams imageParams=(FrameLayout.LayoutParams)preview.getLayoutParams();imageParams.bottomMargin=margin+Ui.dp(this,22);preview.setLayoutParams(imageParams);
            FrameLayout.LayoutParams labelParams=(FrameLayout.LayoutParams)previewTime.getLayoutParams();labelParams.bottomMargin=margin;previewTime.setLayoutParams(labelParams);
        });
        errorBox=Ui.column(this);errorBox.setGravity(Gravity.CENTER);Ui.pad(errorBox,20);errorBox.setBackground(Ui.background(0xdd17232f,14,this));
        errorMessage=Ui.text(this,"",15,Ui.TEXT);errorMessage.setGravity(Gravity.CENTER);errorBox.addView(errorMessage);
        errorBox.addView(Ui.button(this,"重试",()->{errorBox.setVisibility(View.GONE);if(player!=null){player.prepare();player.play();}else loadSession();}));
        FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);ep.leftMargin=ep.rightMargin=Ui.dp(this,24);root.addView(errorBox,ep);errorBox.setVisibility(View.GONE);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){gestures.cancelGesture();scrubbing=true;handler.removeCallbacks(hideControls);if(player!=null){scrubResume=player.getPlayWhenReady();player.pause();}}
            public void onProgressChanged(SeekBar b,int p,boolean fromUser){if(fromUser && player!=null && player.getDuration()>0){long target=(long)(player.getDuration()*(p/10000.0));time.setText(Ui.time(target)+" / "+Ui.time(player.getDuration()));showPreview(target);}}
            public void onStopTrackingTouch(SeekBar b){if(player!=null){if(player.getDuration()>0)player.seekTo((long)(player.getDuration()*(b.getProgress()/10000.0)));player.setPlayWhenReady(scrubResume);}cancelScrub();checkpoint();showControls();}
        });
        setContentView(root);
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets safe=insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                top.setPadding(safe.left+Ui.dp(this,8),safe.top+Ui.dp(this,6),safe.right+Ui.dp(this,8),Ui.dp(this,8));
                bottom.setPadding(safe.left+Ui.dp(this,8),Ui.dp(this,6),safe.right+Ui.dp(this,8),safe.bottom+Ui.dp(this,8));return insets;
            });
        }
        video.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updatePipParams());showControls();
    }
    private void loadSession(){
        if(deleting)return;
        if(loading || sessionId==null){if(sessionId==null)showError("缺少会话记录，请返回文件列表打开视频。");return;}
        final int token=++generation;final String requested=sessionId;loading=true;buffering.setVisibility(View.VISIBLE);
        app.disk.execute(()->{
            try{Session s=app.store.session(requested);Server endpoint=app.store.server(s.serverId);
                app.main.post(()->{if(token!=generation || isDestroyed())return;loading=false;session=s;server=endpoint;if(visible)createPlayer();});
            }catch(Exception e){app.main.post(()->{if(token!=generation || isDestroyed())return;loading=false;showError(Ui.error(e));});}
        });
    }
    private void createPlayer(){
        if(deleting || player!=null || session==null)return;
        try{
            source=new SourceBundle(server);
            DefaultLoadControl load=new DefaultLoadControl.Builder().setBufferDurationsMs(10000,30000,1200,2200).setBackBuffer(30000,true).setTargetBufferBytes(64*1024*1024).build();
            player=new ExoPlayer.Builder(this,new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(measuredSource())).setLoadControl(load).build();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
            player.setHandleAudioBecomingNoisy(true);player.setRepeatMode(Player.REPEAT_MODE_ONE);video.setPlayer(player);
            trick=new TrickPlayController(player,this::persistentHint);trick.setBaseSpeed(session.speed);
            player.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    buffering.setVisibility(state==Player.STATE_BUFFERING && !inPip()?View.VISIBLE:View.GONE);
                    if(state==Player.STATE_ENDED && player!=null){player.seekTo(0);player.play();}
                    if(state==Player.STATE_READY){updatePipParams();preloadNext();}
                }
                @Override public void onPlayerError(PlaybackException e){
                    if(trick!=null)trick.doNotResumeAfterHold();gestures.cancelGesture();
                    showError("播放失败："+e.getErrorCodeName()+"\n请检查网络、读取权限及视频编码。可重试或切换其他视频。");checkpoint();
                }
                @Override public void onTracksChanged(Tracks tracks){if(trick!=null)trick.tracksChanged();}
                @Override public void onVideoSizeChanged(VideoSize size){gestures.cancelGesture();transform.onVideoSize(size);counter.setText(transform.label()+" · 上下滑切片");updatePipParams();}
                @Override public void onPlayWhenReadyChanged(boolean ready,int reason){
                    if(!ready && (reason==Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || reason==Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)){
                        scrubResume=false;cancelScrub();if(trick!=null)trick.doNotResumeAfterHold();gestures.cancelGesture();
                    }
                }
            });
            mediaSession=new MediaSession.Builder(this,player).build();
            openCurrent(MediaPaths.resumePosition(session.position,session.duration),resumePlaying);
        }catch(Exception e){releasePlayer();showError("播放器初始化失败："+Ui.error(e));}
    }
    private void openCurrent(long position,boolean playing){
        if(player==null || source==null || session==null)return;
        clearPreviews();loopA=loopB=-1;updateAb();transform.setPreset(app.store.sourcePreset(server.id));transform.newVideo();
        syncRandom();randomOrder.visit(session.current().path);
        errorBox.setVisibility(View.GONE);RemoteItem item=session.current();
        MediaItem.Builder media=new MediaItem.Builder().setUri(source.uri(item.path)).setMediaId(session.id+":"+session.index)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(item.name).build());
        String ext=item.path.toLowerCase(Locale.ROOT);if(ext.endsWith(".m3u8"))media.setMimeType(MimeTypes.APPLICATION_M3U8);else if(ext.endsWith(".mpd"))media.setMimeType(MimeTypes.APPLICATION_MPD);
        player.setMediaItem(media.build(),Math.max(0,position));player.prepare();player.setPlayWhenReady(playing);
        title.setText(item.name);counter.setText((session.index+1)+" / "+session.queue.size()+" · "+session.serverName+" · 单条循环");
        speedButton.setContentDescription("播放速度 "+SpeedScale.label(trick.baseSpeed()));showControls();
    }
    private boolean inPip(){return isInPictureInPictureMode() || pipTransition;}
    private void showControls(){
        if(inPip())return;top.setVisibility(View.VISIBLE);bottom.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideControls);if(!gestureLocked && !scrubbing && player!=null && player.getPlayWhenReady())handler.postDelayed(hideControls,3500);
    }
    private void persistentHint(String text){
        handler.removeCallbacks(hideHint);
        if(inPip() || text.isEmpty()){hud.setVisibility(View.GONE);return;}
        hud.setText(text);hud.setVisibility(View.VISIBLE);
    }
    private void flash(String text){persistentHint(text);handler.postDelayed(hideHint,1600);}
    private void showError(String text){buffering.setVisibility(View.GONE);errorMessage.setText(text);if(!inPip())errorBox.setVisibility(View.VISIBLE);showControls();}
    private void toggleControls(){
        handler.removeCallbacks(hideControls);
        boolean shown=top.getVisibility()==View.VISIBLE || bottom.getVisibility()==View.VISIBLE;
        if(shown){mediaInfo.setVisibility(View.GONE);top.setVisibility(View.GONE);bottom.setVisibility(View.GONE);}else showControls();
    }
    private androidx.media3.datasource.DataSource.Factory measuredSource(){
        androidx.media3.datasource.DataSource.Factory cached=NextVideoCache.factory(this,source.factory);
        return ()->{androidx.media3.datasource.DataSource data=cached.createDataSource();data.addTransferListener(new androidx.media3.datasource.TransferListener(){
            public void onTransferInitializing(androidx.media3.datasource.DataSource s,androidx.media3.datasource.DataSpec spec,boolean network){}
            public void onTransferStart(androidx.media3.datasource.DataSource s,androidx.media3.datasource.DataSpec spec,boolean network){}
            public void onBytesTransferred(androidx.media3.datasource.DataSource s,androidx.media3.datasource.DataSpec spec,boolean network,int bytes){if(network)loadedBytes.addAndGet(bytes);}
            public void onTransferEnd(androidx.media3.datasource.DataSource s,androidx.media3.datasource.DataSpec spec,boolean network){}
        });return data;};
    }
    private void togglePlayback(){
        if(player==null){showControls();return;}
        player.setPlayWhenReady(!player.getPlayWhenReady());
        pauseButton.setText(player.getPlayWhenReady()?"Ⅱ":"▶");pauseButton.setContentDescription(player.getPlayWhenReady()?"暂停":"播放");
        showControls();hud.setVisibility(View.GONE);checkpoint();
    }
    @Override public void tap(){gestures.performClick();}
    @Override public boolean inspectAt(float x,float y){return transform!=null && transform.inspectAt(x,y);}
    @Override public boolean inspectMode(){return transform!=null && transform.inspectMode();}
    @Override public void singleTap(float x,float y){if(inPip())return;if(transform.imageAt(x,y,gestures))togglePlayback();else toggleControls();}
    @Override public void doubleTap(float x,float y){if(!inPip())fitVideo();}
    @Override public void tripleTap(){if(inPip())return;transform.cyclePreset();if(server!=null)app.store.saveSourcePreset(server.id,transform.presetIndex());counter.setText(transform.label()+" · 上下滑切片");}
    @Override public void panPixels(float dx,float dy){if(transform!=null)transform.pan(dx,dy);}
    @Override public void pinch(float factor,float previousX,float previousY,float x,float y){
        if(transform==null || inPip())return;
        transform.pinch(factor,previousX,previousY,x,y);counter.setText(transform.label());
    }
    @Override public void transformFinished(){if(transform!=null)transform.finish();}
    @Override public void holdStarted(){if(trick!=null){trick.beginHold();handler.removeCallbacks(hideControls);top.setVisibility(View.GONE);bottom.setVisibility(View.GONE);gestures.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}}
    @Override public void forward(float speed){if(trick!=null)trick.forward(speed);}
    @Override public void reverse(float magnitude){if(trick!=null)trick.reverse(magnitude);}
    @Override public void holdEnded(){if(trick!=null)trick.endHold();}
    @Override public void swipePreview(float dy,boolean enough){
        if(dy==0){video.animate().translationY(0).setDuration(160).start();if(trick==null || !trick.isHeld()){handler.removeCallbacks(hideHint);handler.postDelayed(hideHint,300);}return;}
        video.animate().cancel();video.setTranslationY(Math.max(-root.getHeight()*.35f,Math.min(root.getHeight()*.35f,dy*getResources().getDisplayMetrics().density)));
        persistentHint((dy<0?"↑ 下一条":"↓ 上一条")+(enough?"\n松手切换":"\n继续滑动"));
    }
    @Override public void switchVideo(int delta){
        if(player==null || session==null || scrubbing)return;
        syncRandom();int next;
        if(usingRandom){java.util.List<String> paths=queuePaths();String selected=delta>0?randomOrder.next(paths):randomOrder.previous(paths);next=selected==null?-1:paths.indexOf(selected);}
        else next=MediaPaths.adjacent(session.index,delta,session.queue.size());
        if(next<0){flash(usingRandom?(delta>0?"当前文件夹的视频已全部看过":"没有更早的播放记录"):(delta>0?"已经是最后一条":"已经是第一条"));return;}
        gestures.cancelGesture();checkpoint();session.index=next;session.position=0;session.duration=0;openCurrent(0,true);checkpoint();
        video.animate().cancel();video.setTranslationY(delta>0?root.getHeight()*.7f:-root.getHeight()*.7f);video.setAlpha(.4f);video.animate().translationY(0).alpha(1f).setDuration(240).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }
    private void speedMenu(){
        if(trick==null)return;gestures.cancelGesture();handler.removeCallbacks(hideControls);
        String[] labels=new String[SpeedScale.COUNT];for(int i=0;i<labels.length;i++)labels[i]=SpeedScale.label(SpeedScale.at(i))+(i==0?" · 静音慢放":"");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("播放速度").setSingleChoiceItems(labels,SpeedScale.nearest(trick.baseSpeed()),(d,which)->{
            if(trick!=null){trick.setBaseSpeed(SpeedScale.at(which));session.speed=trick.baseSpeed();app.store.saveSourcePlayback(server.id,session.speed,transform.presetIndex()==2);speedButton.setContentDescription("播放速度 "+SpeedScale.label(trick.baseSpeed()));checkpoint();if(which==0)flash("0.05× 使用静音视频慢放");}d.dismiss();
        }).setNegativeButton("关闭",null).create();dialog.setOnDismissListener(d->showControls());dialog.show();
    }
    private java.util.List<String> queuePaths(){java.util.List<String> paths=new java.util.ArrayList<>();for(RemoteItem item:session.queue)paths.add(item.path);return paths;}
    private void syncRandom(){
        if(session==null)return;
        String key=session.serverId+"\u0000"+session.folder;
        randomOrder=app.randomOrders.computeIfAbsent(key,k->new RandomOrder());
        usingRandom=app.store.randomMode();
    }
    private void toggleRandom(){gestures.cancelGesture();app.store.setRandomMode(!app.store.randomMode());syncRandom();if(session!=null){randomOrder.visit(session.current().path);preloadNext();}showControls();}
    private void preloadNext(){
        if(server==null||session==null)return;syncRandom();
        String path=usingRandom?randomOrder.peekNext(queuePaths()):(session.index+1<session.queue.size()?session.queue.get(session.index+1).path:null);
        nextCache.preload(this,server,path==null?"":path,path==null?0:app.store.preloadMb());
    }
    private Button circle(LinearLayout row,String label,Runnable action){
        Button b=Ui.button(this,label,action);b.setTextSize(11);b.setPadding(0,0,0,0);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);bg.setColor(0x22000000);bg.setStroke(Ui.dp(this,1),0xff999999);b.setBackground(bg);
        row.addView(b,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));return b;
    }
    private void updateAb(){if(abButton!=null){abButton.setText(loopB>loopA&&loopA>=0?"A↔B":loopA>=0?"A/•":"A/B");abButton.setTextColor(loopA>=0?0xff65b5ff:Ui.TEXT);}}
    private void fitVideo(){gestures.cancelGesture();transform.resetFit();if(server!=null)app.store.saveSourcePreset(server.id,0);counter.setText(transform.label()+" · 上下滑切片");showControls();}
    private void cycleAb(){
        if(player==null||!player.isCurrentMediaItemSeekable())return;gestures.cancelGesture();
        if(loopA<0){loopA=player.getCurrentPosition();loopB=-1;}
        else if(loopB<0){long p=player.getCurrentPosition();if(p<=loopA+200){Ui.toast(this,"B 点应在 A 点之后");return;}loopB=p;player.seekTo(loopA);player.play();}
        else loopA=loopB=-1;
        updateAb();showControls();
    }
    private void playControl(){
        gestures.cancelGesture();
        if(player!=null && loopA>=0){loopA=loopB=-1;updateAb();player.play();showControls();checkpoint();return;}
        togglePlayback();
    }
    private void toggleLock(){gestures.cancelGesture();gestureLocked=!gestureLocked;lockButton.setText(gestureLocked?"解锁":"锁");lockButton.setTextColor(gestureLocked?0xff65b5ff:Ui.TEXT);lockButton.setContentDescription(gestureLocked?"解锁手势":"锁定手势");handler.removeCallbacks(hideControls);showControls();if(gestureLocked)handler.removeCallbacks(hideControls);}
    private void seekBy(long delta,boolean frame){
        if(player==null || !player.isCurrentMediaItemSeekable())return;
        gestures.cancelGesture();long step=delta;
        if(frame){androidx.media3.common.Format f=player.getVideoFormat();if(f==null || f.frameRate<=0){Ui.toast(this,"帧率未知，无法按帧定位");return;}step=Math.round(1000d/f.frameRate)*(delta<0?-1:1);player.pause();}
        player.setSeekParameters(SeekParameters.EXACT);long target=Math.max(0,player.getCurrentPosition()+step);long duration=player.getDuration();if(duration>0)target=Math.min(duration-1,target);player.seekTo(target);checkpoint();showControls();
    }
    private void fileMenu(){
        if(deleting||session==null)return;gestures.cancelGesture();handler.removeCallbacks(hideControls);
        AlertDialog menu=new AlertDialog.Builder(this).setTitle("菜单").setItems(new String[]{"删除"},(d,w)->confirmDelete()).setNegativeButton("关闭",null).create();menu.setOnDismissListener(d->showControls());menu.show();
    }
    private void confirmDelete(){
        if(deleting||session==null)return;
        final RemoteItem selected=session.current();
        deleteConfirm=new AlertDialog.Builder(this).setTitle("删除当前视频？").setMessage(selected.name+"\n将从服务器磁盘删除原文件，不能在本应用内撤销。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{if(session!=null&&session.current().path.equals(selected.path))deleteCurrent();}).create();
        deleteConfirm.setOnDismissListener(d->showControls());deleteConfirm.show();
    }
    private void deleteCurrent(){
        if(deleting||player==null||session==null)return;
        cancelScrub();gestures.cancelGesture();checkpoint();resumePlaying=player.getPlayWhenReady();
        final String id=session.id;final RemoteItem item=session.current();final Server endpoint=server;
        deleting=true;nextCache.preload(this,server,"",0);clearPreviews();releasePlayer();mediaInfo.setVisibility(View.GONE);persistentHint("正在删除…");
        app.io.execute(()->{
            try{dev.flowstream.player.net.Browsers.deleteVideo(endpoint,item);
                app.disk.execute(()->{try{Session remaining=app.store.removeMedia(id,item.path);app.main.post(()->{
                    deleting=false;if(isDestroyed())return;if(!id.equals(sessionId)){loadSession();return;}session=remaining;hud.setVisibility(View.GONE);Ui.toast(this,"已从服务器删除 "+item.name);
                    if(remaining==null){finish();return;}if(visible)createPlayer();
                });}catch(Exception e){app.main.post(()->{deleting=false;if(!isDestroyed()){hud.setVisibility(View.GONE);finish();}});}});
            }catch(Exception e){app.main.post(()->{deleting=false;if(isDestroyed())return;if(!id.equals(sessionId)){loadSession();return;}hud.setVisibility(View.GONE);if(visible)createPlayer();Ui.toast(this,Ui.error(e));});}
        });
    }
    private void playbackInfo(){
        if(mediaInfo.getVisibility()==View.VISIBLE){mediaInfo.setVisibility(View.GONE);showControls();return;}
        if(player==null)return;handler.removeCallbacks(hideControls);androidx.media3.common.Format f=player.getVideoFormat();long elapsed=Math.max(1,SystemClock.elapsedRealtime()-networkStartTime);long bytes=android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())-networkStartBytes;
        String info=f==null?"视频轨尚未就绪":("编码  "+f.sampleMimeType+" / "+f.codecs+"\n尺寸  "+f.width+" × "+f.height+"\n帧率  "+(f.frameRate>0?f.frameRate:"未知")+"\n码率  "+(f.bitrate>0?Ui.size(f.bitrate/8)+"/s":"未知"));
        info+="\n播放速度  "+SpeedScale.label(player.getPlaybackParameters().speed)+"\n缓冲  "+Ui.time(player.getTotalBufferedDuration())+"\n本次平均接收  "+Ui.size(Math.max(0,bytes)*1000/elapsed)+"/s（含预读）\n下一条预读  "+app.store.preloadMb()+" MB\n画面  "+transform.label();
        mediaInfo.setText(session.current().name+"\n"+Ui.time(player.getDuration())+" · "+Ui.size(session.current().size)+"\n"+info);mediaInfo.setVisibility(View.VISIBLE);
    }
    private void capturePreview(long position){
        if(scrubbing || !player.isPlaying() || SystemClock.uptimeMillis()-lastPreviewCapture<3000)return;
        lastPreviewCapture=SystemClock.uptimeMillis();View surface=video.getVideoSurfaceView();if(!(surface instanceof TextureView))return;
        android.graphics.Bitmap b=((TextureView)surface).getBitmap(160,90);if(b==null)return;
        android.graphics.Bitmap old=thumbnails.put(position,b);if(old!=null)old.recycle();
        while(thumbnails.size()>160){java.util.Map.Entry<Long,android.graphics.Bitmap> removed=thumbnails.pollFirstEntry();removed.getValue().recycle();}
    }
    private void showPreview(long target){
        previewTarget=target;
        java.util.Map.Entry<Long,android.graphics.Bitmap> entry=thumbnails.floorEntry(target);if(entry==null)entry=thumbnails.ceilingEntry(target);
        previewTime.setVisibility(View.VISIBLE);
        if(entry==null || Math.abs(entry.getKey()-target)>10000){preview.setVisibility(View.GONE);previewTime.setText(Ui.time(target)+" · 正在获取预览…");
            if(pendingPreview!=null)handler.removeCallbacks(pendingPreview);
            pendingPreview=()->{if(session==null||server==null||!scrubbing)return;RemoteItem item=session.current();framePreview.request(server,item.path,item.size,target,b->handler.post(()->{if(isDestroyed()||!scrubbing||target!=previewTarget){if(b!=null)b.recycle();return;}if(b==null){previewTime.setText(Ui.time(target)+" · 预览不可用");return;}android.graphics.Bitmap old=thumbnails.put(target,b);if(old!=null)old.recycle();while(thumbnails.size()>160)thumbnails.pollFirstEntry().getValue().recycle();preview.setImageBitmap(b);preview.setVisibility(View.VISIBLE);previewTime.setText(Ui.time(target)+" · 邻近关键帧");}));};
            handler.postDelayed(pendingPreview,300);return;}
        preview.setImageBitmap(entry.getValue());preview.setVisibility(View.VISIBLE);previewTime.setText(Ui.time(target)+" · 预览 "+Ui.time(entry.getKey()));
    }
    private void clearPreviews(){framePreview.cancel();if(pendingPreview!=null)handler.removeCallbacks(pendingPreview);if(preview!=null)preview.setImageDrawable(null);for(android.graphics.Bitmap b:thumbnails.values())b.recycle();thumbnails.clear();}

    private void rotate(){
        cancelScrub();gestures.cancelGesture();transform.resetFit();checkpoint();boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);showControls();
    }
    private PictureInPictureParams pipParams(){
        float ratio=16f/9f;
        if(player!=null){VideoSize size=player.getVideoSize();if(size.width>0 && size.height>0)ratio=size.width*size.pixelWidthHeightRatio/size.height;}
        ratio=Math.max(1f/2.39f,Math.min(2.39f,ratio));Rect rect=new Rect();video.getGlobalVisibleRect(rect);
        PictureInPictureParams.Builder b=new PictureInPictureParams.Builder().setAspectRatio(new Rational(Math.round(ratio*10000),10000));
        if(!rect.isEmpty())b.setSourceRectHint(rect);
        if(Build.VERSION.SDK_INT>=31)b.setAutoEnterEnabled(false).setSeamlessResizeEnabled(true);
        return b.build();
    }
    private void updatePipParams(){if(player==null || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))return;try{setPictureInPictureParams(pipParams());}catch(IllegalArgumentException|IllegalStateException ignored){}}
    private void enterPip(){
        if(player==null)return;
        cancelScrub();
        if(!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)){Ui.toast(this,"设备不支持系统画中画");return;}
        gestures.cancelGesture();transform.resetFit();checkpoint();mediaInfo.setVisibility(View.GONE);handler.removeCallbacks(hideControls);top.setVisibility(View.GONE);bottom.setVisibility(View.GONE);hud.setVisibility(View.GONE);errorBox.setVisibility(View.GONE);buffering.setVisibility(View.GONE);
        try{pipTransition=true;if(!enterPictureInPictureMode(pipParams())){pipTransition=false;showControls();Ui.toast(this,"请在系统设置中允许本应用使用画中画");}}
        catch(IllegalStateException|IllegalArgumentException e){pipTransition=false;showControls();Ui.toast(this,"无法进入画中画，请检查系统权限");}
    }
    @Override public void onPictureInPictureModeChanged(boolean pip,Configuration config){
        super.onPictureInPictureModeChanged(pip,config);pipTransition=false;gestures.cancelGesture();transform.resetFit();gestures.setVisibility(pip?View.GONE:View.VISIBLE);
        if(pip){top.setVisibility(View.GONE);bottom.setVisibility(View.GONE);hud.setVisibility(View.GONE);errorBox.setVisibility(View.GONE);buffering.setVisibility(View.GONE);}
        else{if(!visible && player!=null)player.pause();immersive();showControls();if(player!=null && player.getPlayerError()!=null)showError("播放中断，请重试。");}
    }
    @SuppressWarnings("deprecation") private void immersive(){
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=getWindow().getInsetsController();if(c!=null){c.hide(WindowInsets.Type.systemBars());c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}}
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    void cancelScrub(){
        if(seek!=null)seek.cancelTracking();
        previewTarget=-1;if(pendingPreview!=null)handler.removeCallbacks(pendingPreview);framePreview.cancel();if(preview!=null)preview.setVisibility(View.GONE);if(previewTime!=null)previewTime.setVisibility(View.GONE);
        if(!scrubbing)return;scrubbing=false;
        if(player!=null)player.setPlayWhenReady(scrubResume);
    }
    private void checkpoint(){
        if(player==null || session==null || trick==null)return;
        session.position=player.getCurrentPosition();session.duration=Math.max(0,player.getDuration());session.speed=trick.baseSpeed();
        final String id=session.id;final int index=session.index;final long pos=session.position,dur=session.duration;final float speed=session.speed;
        app.disk.execute(()->{try{app.store.checkpoint(id,index,pos,dur,speed);}catch(Exception ignored){app.main.post(()->{if(!isDestroyed())Ui.toast(this,"播放进度保存失败，请检查本机存储空间");});}});
    }
    private void releasePlayer(){
        if(trick!=null){trick.dispose();trick=null;}
        if(mediaSession!=null){mediaSession.release();mediaSession=null;}
        video.setPlayer(null);if(player!=null){player.release();player=null;}
        if(source!=null){SourceBundle old=source;source=null;app.io.execute(old::close);}
    }
    @Override protected void onStart(){super.onStart();visible=true;handler.removeCallbacks(update);handler.post(update);if(session!=null)createPlayer();else loadSession();}
    @Override protected void onResume(){super.onResume();if(!isInPictureInPictureMode()){pipTransition=false;immersive();}}
    @Override protected void onPause(){cancelScrub();gestures.cancelGesture();checkpoint();super.onPause();}
    @Override protected void onStop(){
        visible=false;handler.removeCallbacks(update);handler.removeCallbacks(hideControls);gestures.cancelGesture();
        if(player!=null){resumePlaying=player.getPlayWhenReady();checkpoint();player.pause();}releasePlayer();super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out){
        checkpoint();out.putString("session_id",sessionId);out.putBoolean("playing",player!=null?player.getPlayWhenReady():resumePlaying);out.putInt("orientation",getRequestedOrientation());super.onSaveInstanceState(out);
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);gestures.cancelGesture();checkpoint();releasePlayer();generation++;loading=false;session=null;server=null;sessionId=intent.getStringExtra("session_id");resumePlaying=true;loadSession();
    }
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);cancelScrub();gestures.cancelGesture();transform.resetFit();if(!isInPictureInPictureMode())immersive();root.requestApplyInsets();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused && gestures!=null){cancelScrub();gestures.cancelGesture();}else if(focused && !isInPictureInPictureMode())immersive();}
    // A toolbar/seekbar DOWN cancels a still-pending single tap from the image below it.
    // Thus tapping pause/rotate/speed quickly after an image tap cannot toggle twice later.
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(deleting)return true;
        if(gestureLocked && !hit(lockButton,event))return true;
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN && gestures!=null
                && (hit(top,event)||hit(bottom,event)||hit(errorBox,event)||hit(mediaInfo,event)))gestures.cancelGesture();
        return super.dispatchTouchEvent(event);
    }
    private boolean hit(View view,MotionEvent event){
        if(view==null || view.getVisibility()!=View.VISIBLE)return false;
        Rect rect=new Rect();return view.getGlobalVisibleRect(rect) && rect.contains((int)event.getRawX(),(int)event.getRawY());
    }
    @Override protected void back(){cancelScrub();if(gestures!=null)gestures.cancelGesture();checkpoint();finish();}
    @Override protected void onDestroy(){generation++;handler.removeCallbacksAndMessages(null);if(gestures!=null)gestures.cancelGesture();releasePlayer();nextCache.close();framePreview.close();clearPreviews();super.onDestroy();}
}
