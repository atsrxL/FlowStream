package dev.flowstream.player;
import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import androidx.media3.exoplayer.ExoPlayer;
import dev.flowstream.player.ui.PlayerActivity;
import dev.flowstream.player.data.*;
import dev.flowstream.player.net.Browsers;
import dev.flowstream.core.*;
import java.lang.reflect.Field;
import java.util.*;

/** Real-device checks against an explicitly started local media fixture. */
public final class DeviceChecks extends Instrumentation {
 private PlayerActivity activity; private Bundle args; private String serverId,sessionId;
 private final StringBuilder report=new StringBuilder();
 @Override public void onCreate(Bundle a){args=a;start();}
 private Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 private void main(Runnable r){runOnMainSync(r);}
 private void invoke(String name){main(()->{try{java.lang.reflect.Method m=PlayerActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}catch(Exception ex){throw new RuntimeException(ex);}});}
 private void screenshot(String name)throws Exception{waitForIdleSync();Thread.sleep(350);android.graphics.Bitmap b=getUiAutomation().takeScreenshot();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),name+".png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
 private ExoPlayer player()throws Exception{return (ExoPlayer)field(activity,"player");}
 private long position()throws Exception{long[] p={0};ExoPlayer e=player();main(()->p[0]=e.getCurrentPosition());return p[0];}
 private boolean playing()throws Exception{boolean[] p={false};ExoPlayer e=player();main(()->p[0]=e.getPlayWhenReady());return p[0];}
 private void check(boolean ok,String text){if(!ok)throw new AssertionError(text);report.append("PASS ").append(text).append((char)10);Bundle b=new Bundle();b.putString("stream","PASS "+text+(char)10);sendStatus(0,b);}
 private void ready()throws Exception{for(int i=0;i<200;i++){ExoPlayer e=player();if(e!=null){int[] s={0};Throwable[] err={null};main(()->{s[0]=e.getPlaybackState();err[0]=e.getPlayerError();});if(err[0]!=null)throw new AssertionError("Playback error",err[0]);if(s[0]==3)return;}Thread.sleep(100);}throw new AssertionError("READY timeout");}
 private void touch(int a,float x,float y,long d){main(()->{MotionEvent e=MotionEvent.obtain(d,SystemClock.uptimeMillis(),a,x,y,0);activity.dispatchTouchEvent(e);e.recycle();});}
 private void tap(float x,float y){long d=SystemClock.uptimeMillis();touch(0,x,y,d);touch(1,x,y,d);}
 private void dbl(float x,float y)throws Exception{tap(x,y);Thread.sleep(75);tap(x,y);Thread.sleep(400);}
 private void drag(float x,float y,float xx,float yy,int ms)throws Exception{long d=SystemClock.uptimeMillis();touch(0,x,y,d);for(int i=1;i<=12;i++){Thread.sleep(ms/12);touch(2,x+(xx-x)*i/12,y+(yy-y)*i/12,d);}touch(1,xx,yy,d);Thread.sleep(500);}
 private VideoViewport viewport()throws Exception{return (VideoViewport)field(field(activity,"transform"),"viewport");}
 private int index()throws Exception{return ((Session)field(activity,"session")).index;}
 private void launch(){activity=(PlayerActivity)startActivitySync(new Intent(getTargetContext(),PlayerActivity.class).putExtra("session_id",sessionId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
 private void multi(int a,long d,float x1,float x2,float y){main(()->{MotionEvent.PointerProperties[] pp=new MotionEvent.PointerProperties[2];MotionEvent.PointerCoords[] pc=new MotionEvent.PointerCoords[2];for(int i=0;i<2;i++){pp[i]=new MotionEvent.PointerProperties();pp[i].id=i;pp[i].toolType=1;pc[i]=new MotionEvent.PointerCoords();pc[i].x=i==0?x1:x2;pc[i].y=y;pc[i].pressure=1;pc[i].size=1;}MotionEvent e=MotionEvent.obtain(d,SystemClock.uptimeMillis(),a,2,pp,pc,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);activity.dispatchTouchEvent(e);e.recycle();});}

 private void triple(float x,float y)throws Exception{tap(x,y);Thread.sleep(70);tap(x,y);Thread.sleep(70);tap(x,y);Thread.sleep(400);}
 @Override public void onStart(){
 int result=Activity.RESULT_OK;String previousServer=null;boolean oldThumbnails=false,oldRandom=false;String oldTheme=null;
 try{
 Activity home=startActivitySync(new Intent(getTargetContext(),dev.flowstream.player.ui.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));main(()->home.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));Thread.sleep(800);
 FlowApp app=(FlowApp)getTargetContext().getApplicationContext();oldTheme=app.store.theme();previousServer=app.store.activeServer();oldThumbnails=app.store.thumbnailMode();oldRandom=app.store.randomMode();app.store.setRandomMode(false);
 Server server=new Server();server.name="FlowStream 0.6.3 fixture";server.protocol="WEBDAV";server.url="http://127.0.0.1:8089/dav/";server.allowHttp=true;server.username="demo";server.password="demo-only";serverId=server.id;app.store.saveServer(server);
 List<RemoteItem> entries=Browsers.list(server,"");check(entries.size()>=2,"network directory");app.store.selectServer(serverId);app.store.setThumbnailMode(true);
 main(()->{try{java.lang.reflect.Method m=home.getClass().getDeclaredMethod("reload");m.setAccessible(true);m.invoke(home);}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(4000);android.widget.GridView grid=(android.widget.GridView)field(home,"list");
 check(grid.getNumColumns()==2,"thumbnail mode has two columns");check(grid.getAdapter().getCount()>=2,"thumbnail grid lists media");
 main(()->{View tile=grid.getChildAt(0);if(!(tile instanceof android.widget.LinearLayout))throw new AssertionError("tile");android.widget.LinearLayout col=(android.widget.LinearLayout)tile;if(!(col.getChildAt(0) instanceof android.widget.ImageView)||!(col.getChildAt(1) instanceof android.widget.TextView))throw new AssertionError("filename must be below thumbnail");});
 java.util.concurrent.CountDownLatch covers=new java.util.concurrent.CountDownLatch(2);java.util.concurrent.atomic.AtomicInteger decoded=new java.util.concurrent.atomic.AtomicInteger();
 for(int i=0;i<2;i++)app.thumbnails.load(server,entries.get(i),b->{if(b!=null)decoded.incrementAndGet();covers.countDown();});
 check(covers.await(55,java.util.concurrent.TimeUnit.SECONDS)&&decoded.get()==2,"grid thumbnails decode both test videos, decoded="+decoded.get());
 main(()->{try{java.util.List<RemoteItem> items=new java.util.ArrayList<>((java.util.List<RemoteItem>)field(home,"entries"));items.add(new RemoteItem("sample-folder","文件夹示例",true,0));Field ef=home.getClass().getDeclaredField("entries");ef.setAccessible(true);ef.set(home,items);java.lang.reflect.Method refresh=home.getClass().getDeclaredMethod("showRows");refresh.setAccessible(true);refresh.invoke(home);}catch(Exception ex){throw new RuntimeException(ex);}});screenshot("grid-063");
 check(grid.getAdapter().getCount()==3,"folder icon displayed alongside video thumbnails");
 for(String theme:new String[]{"black","white","system"}){
  app.store.setTheme(theme);Activity settings=startActivitySync(new Intent(getTargetContext(),dev.flowstream.player.ui.SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  boolean expected=theme.equals("black")||(theme.equals("system")&&(settings.getResources().getConfiguration().uiMode&48)==32);
  check(dev.flowstream.player.ui.Ui.dark(settings)==expected,"theme "+theme+" resolves correctly");
  final int[] color={0};main(()->{View root=((android.view.ViewGroup)settings.findViewById(android.R.id.content)).getChildAt(0);color[0]=((android.graphics.drawable.ColorDrawable)root.getBackground()).getColor();});check(color[0]==(expected?android.graphics.Color.BLACK:android.graphics.Color.WHITE),"theme "+theme+" background");screenshot("theme-063-"+theme);main(settings::finish);
 }
 app.store.setTheme(oldTheme);

 sessionId=app.store.newSession(server,"",entries,0);launch();ready();ExoPlayer e=player();check(((java.util.concurrent.atomic.AtomicLong)field(activity,"loadedBytes")).get()>0,"loading meter counts real network media bytes");
 check(((android.widget.TextView)field(activity,"loadingRate")).getText().toString().contains("/s"),"loading meter shows speed unit");
 main(()->{android.view.ViewGroup top=(android.view.ViewGroup)getUnchecked(activity,"top");if(top.getChildCount()!=1)throw new AssertionError("one top row");android.view.ViewGroup row=(android.view.ViewGroup)top.getChildAt(0);String[] order={"旋转","退出","小窗","播放速度"};for(int i=0;i<4;i++)if(!row.getChildAt(i).getContentDescription().toString().startsWith(order[i]))throw new AssertionError("top order "+i);android.view.ViewGroup bottom=(android.view.ViewGroup)getUnchecked(activity,"bottom");if(bottom.getChildCount()!=3)throw new AssertionError("three bottom rows");android.view.ViewGroup controls=(android.view.ViewGroup)bottom.getChildAt(2);if(controls.getChildCount()!=7)throw new AssertionError("seven controls");});check(true,"top and bottom exact layout");
 invoke("playbackInfo");check(((View)field(activity,"mediaInfo")).getVisibility()==View.VISIBLE,"info opens");invoke("playbackInfo");check(((View)field(activity,"mediaInfo")).getVisibility()==View.GONE,"info toggles closed");
 main(()->{e.pause();e.seekTo(2000);});ready();invoke("showControls");
 android.widget.SeekBar seek=(android.widget.SeekBar)field(activity,"seek");int[] location=new int[2];main(()->seek.getLocationOnScreen(location));float sx=location[0]+seek.getWidth()*.65f,sy=location[1]+seek.getHeight()/2f;
 long beforeSeek=position();tap(sx,sy);Thread.sleep(100);check(Math.abs(position()-beforeSeek)<150,"short timeline tap does not seek");
 long sd=SystemClock.uptimeMillis();touch(0,sx,sy,sd);Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout()+100);touch(2,sx+20,sy,sd);check((boolean)field(activity,"scrubbing"),"long press starts timeline drag");check(((View)field(activity,"previewTime")).getVisibility()==View.VISIBLE,"drag shows thumbnail preview label");
 for(int i=0;i<100&&((View)field(activity,"preview")).getVisibility()!=View.VISIBLE;i++)Thread.sleep(100);
 check(((View)field(activity,"preview")).getVisibility()==View.VISIBLE,"drag decodes preview image");screenshot("preview-063");touch(1,sx+20,sy,sd);ready();check(position()>beforeSeek+1000&&!playing(),"timeline release seeks and preserves pause");
 long cancelPosition=position();sd=SystemClock.uptimeMillis();touch(0,sx,sy,sd);Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout()+80);touch(2,sx-150,sy,sd);Thread.sleep(350);check(((android.widget.TextView)field(activity,"time")).getText().toString().startsWith(dev.flowstream.player.ui.Ui.time((long)(24000*seek.getProgress()/10000.0))),"drag time stays at target");touch(3,sx-150,sy,sd);check(Math.abs(position()-cancelPosition)<150&&!playing(),"cancel drag preserves position and pause");

 main(()->e.seekTo(10000));ready();invoke("showControls");main(()->{android.view.ViewGroup bottom=(android.view.ViewGroup)getUnchecked(activity,"bottom");android.view.ViewGroup row=(android.view.ViewGroup)bottom.getChildAt(2);row.getChildAt(2).performClick();});ready();check(Math.abs(position()-8000)<250,"minus two seconds");
 main(()->{android.view.ViewGroup row=(android.view.ViewGroup)((android.view.ViewGroup)getUnchecked(activity,"bottom")).getChildAt(2);row.getChildAt(4).performClick();});ready();check(Math.abs(position()-10000)<250,"plus two seconds");
 try(android.database.Cursor count=app.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sessions",null)){count.moveToFirst();check(count.getInt(0)==0,"no playback history persisted");}
 main(()->{e.seekTo(0);e.play();});ready();
 int[] wh=new int[2];main(()->{wh[0]=activity.getWindow().getDecorView().getWidth();wh[1]=activity.getWindow().getDecorView().getHeight();});float x=wh[0]/2f,y=wh[1]/2f;
 check(viewport().mode()==VideoViewport.Mode.WIDTH,"default width fit");check(Math.abs(viewport().width()-wh[0])<1,"width fit actual geometry");
 invoke("showControls");tap(x,y*.45f);Thread.sleep(400);check(playing(),"black area single does not pause");check(((View)field(activity,"top")).getVisibility()==View.GONE,"black single hides menu");
 tap(x,y*.45f);Thread.sleep(400);check(((View)field(activity,"top")).getVisibility()==View.VISIBLE,"black single shows menu");tap(x,y);Thread.sleep(400);check(!playing(),"image single pauses");tap(x,y);Thread.sleep(400);check(playing(),"image single plays");
 tap(x,y);Thread.sleep(400);check(!playing(),"image single pauses before double test");
 tap(x,y);Thread.sleep(75);tap(x,y);check(viewport().mode()==VideoViewport.Mode.HEIGHT,"second UP immediately selects height fit");check(!playing(),"double preserves pause without single firing");Thread.sleep(400);
 dbl(x,y);check(viewport().mode()==VideoViewport.Mode.NATIVE,"double selects original size");check(Math.abs(viewport().width()-640)<1,"native decoded pixel size");
 tap(wh[0]*.12f,y);Thread.sleep(400);check(!playing(),"native side black bar never toggles playback");tap(x,y);Thread.sleep(400);check(playing(),"native image plays");
 tap(x,y);Thread.sleep(75);tap(x,y);check(viewport().mode()==VideoViewport.Mode.WIDTH,"second UP immediately cycles to width fit");check(playing(),"double preserves playing state");Thread.sleep(400);
 for(int preset=0;preset<3;preset++){
  final int mode=preset;main(()->{try{Object t=field(activity,"transform");t.getClass().getMethod("setPreset",int.class).invoke(t,mode);app.store.saveSourcePreset(serverId,mode);}catch(Exception ex){throw new RuntimeException(ex);}});
  int before=index();drag(x,y+150,x,y-450,240);ready();check(index()!=before,"preset "+preset+" upward swipe switches");drag(x,y-150,x,y+450,240);ready();check(index()==before,"preset "+preset+" downward swipe restores");
 }
 invoke("fitVideo");main(()->e.pause());
 long d=SystemClock.uptimeMillis();touch(0,x-70,y,d);multi(5|(1<<8),d,x-70,x+70,y);for(int i=0;i<14;i++){Thread.sleep(20);multi(2,d,x-70-i*16,x+70+i*16,y);}multi(6|(1<<8),d,x-278,x+278,y);touch(2,x-228,y-100,d);touch(1,x-228,y-100,d);Thread.sleep(350);
 check(viewport().mode()==VideoViewport.Mode.CUSTOM,"pinch enters persistent custom");drag(x,y,x,y-650,240);check(index()==0,"custom zoom drag never switches");check(!playing(),"pinch and pan never unpause");tap(x,y);Thread.sleep(400);check(playing(),"zoomed image single plays");tap(x,y);Thread.sleep(400);dbl(x,y);check(!playing()&&viewport().mode()==VideoViewport.Mode.WIDTH,"double exits custom zoom without toggling playback");
 Object trick=field(activity,"trick");main(()->{try{trick.getClass().getMethod("setBaseSpeed",float.class).invoke(trick,1.35f);}catch(Exception ex){throw new RuntimeException(ex);}});
 for(float start:new float[]{wh[0]*.15f,wh[0]*.5f,wh[0]*.85f}){
  main(()->e.pause());invoke("toggleControls"); // Explicitly hide controls before holds.
  main(()->{((View)getUnchecked(activity,"top")).setVisibility(View.GONE);((View)getUnchecked(activity,"bottom")).setVisibility(View.GONE);});
  d=SystemClock.uptimeMillis();touch(0,start,wh[1]*.75f,d);Thread.sleep(450);touch(2,wh[0],wh[1]*.75f,d);float[] speed={0};main(()->speed[0]=e.getPlaybackParameters().speed);check(Math.abs(speed[0]-5)<.001,"right edge gives 5x from "+start);touch(2,0,wh[1]*.75f,d);main(()->speed[0]=e.getPlaybackParameters().speed);check(Math.abs(speed[0]-.1f)<.001,"left edge gives 0.1x from "+start);touch(1,0,wh[1]*.75f,d);main(()->speed[0]=e.getPlaybackParameters().speed);check(Math.abs(speed[0]-1.35f)<.001&&!playing(),"hold restores menu speed and pause");
  main(()->e.seekTo(10000));ready();d=SystemClock.uptimeMillis();touch(0,start,y,d);Thread.sleep(450);touch(2,0,y,d);Thread.sleep(150);Object clock=field(trick,"clock");check(Math.abs((float)field(clock,"magnitude")-4)<.001,"left edge gives 4x reverse from "+start);touch(3,0,y,d);
 }
 invoke("showControls");screenshot("controls-063");
 int orientation=activity.getResources().getConfiguration().orientation;invoke("rotate");Thread.sleep(1000);check(activity.getResources().getConfiguration().orientation!=orientation,"rotation");invoke("enterPip");Thread.sleep(1000);check(activity.isInPictureInPictureMode(),"PiP");main(()->activity.startActivity(new Intent(activity,PlayerActivity.class).putExtra("session_id",sessionId)));Thread.sleep(1000);ready();check(!activity.isInPictureInPictureMode(),"PiP return");
 }catch(Throwable t){result=Activity.RESULT_CANCELED;report.append("FAIL ").append(android.util.Log.getStackTraceString(t));}
 finally{try{if(activity!=null)main(()->activity.finish());FlowApp app=(FlowApp)getTargetContext().getApplicationContext();if(sessionId!=null)app.store.deleteSession(sessionId);if(serverId!=null)app.store.deleteServer(serverId);if(previousServer!=null){app.store.selectServer(previousServer);app.store.setThumbnailMode(oldThumbnails);app.store.setRandomMode(oldRandom);if(oldTheme!=null)app.store.setTheme(oldTheme);}}catch(Throwable ignored){}}
 Bundle out=new Bundle();out.putString("stream",report.toString());finish(result,out);
 }
 private Object getUnchecked(Object object,String name){try{return field(object,name);}catch(Exception e){throw new RuntimeException(e);}}
}
