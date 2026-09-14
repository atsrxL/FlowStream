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
public final class PreviewChecks extends Instrumentation {
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

 private float zoneY(int zone,int height)throws Exception{
  Object overlay=field(activity,"gestures");java.lang.reflect.Method m=overlay.getClass().getMethod("zoneAt",float.class);
  int first=-1,last=-1;for(int y=0;y<height;y++){if((int)m.invoke(overlay,(float)y)==zone){if(first<0)first=y;last=y;}}
  if(first<0)throw new AssertionError("zone missing "+zone);return (first+last)/2f;
 }
 private View named(View v,String text){
  if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
  if(v instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView)v).getText()))return v;
  if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=named(g.getChildAt(i),text);if(found!=null)return found;}}
  return null;
 }
 private void click(String label)throws Exception{View v=named(activity.getWindow().getDecorView(),label);check(v!=null,"control exists "+label);main(v::performClick);Thread.sleep(250);}
 private long nearestDistance()throws Exception{
  long[] distance={Long.MAX_VALUE};main(()->{try{long target=(long)field(activity,"previewTarget");java.util.TreeMap<Long,android.graphics.Bitmap> frames=(java.util.TreeMap<Long,android.graphics.Bitmap>)field(activity,"thumbnails");for(Long at:frames.keySet())distance[0]=Math.min(distance[0],Math.abs(at-target));}catch(Exception ex){throw new RuntimeException(ex);}});return distance[0];
 }
 private long renderedDigest()throws Exception{
  long[] result={0};main(()->{try{androidx.media3.ui.PlayerView playerView=(androidx.media3.ui.PlayerView)field(activity,"video");android.view.TextureView video=(android.view.TextureView)playerView.getVideoSurfaceView();android.graphics.Bitmap bitmap=video.getBitmap(64,36);if(bitmap==null)throw new AssertionError("rendered texture unavailable");int[] pixels=new int[64*36];bitmap.getPixels(pixels,0,64,0,0,64,36);long hash=1125899906842597L;for(int pixel:pixels)hash=31*hash+pixel;bitmap.recycle();result[0]=hash;}catch(Exception ex){throw new RuntimeException(ex);}});return result[0];
 }
 private void liveZoneScrub(float x,float y,float width)throws Exception{
  ExoPlayer e=player();main(()->{e.pause();e.seekTo(3000);});ready();Thread.sleep(350);
  long beforeFrame=renderedDigest();
  long contact=SystemClock.uptimeMillis();touch(0,x,y,contact);
  for(int i=1;i<=30;i++){Thread.sleep(40);touch(2,x+width*(.04f+.18f*i/30f),y,contact);}
  check((boolean)field(activity,"scrubbing"),"zone continuous drag owns scrub");
  check(position()>4500,"zone drag updates actual player before finger UP");
  for(int i=0;i<30&&renderedDigest()==beforeFrame;i++)Thread.sleep(50);
  check(renderedDigest()!=beforeFrame,"zone drag renders a changed video frame while finger remains down");screenshot("live-081");
  android.widget.ImageView preview=(android.widget.ImageView)field(activity,"preview");check(preview.getVisibility()!=View.VISIBLE,"zone live seek does not open thumbnail");
  touch(MotionEvent.ACTION_CANCEL,x+width*.22f,y,contact);ready();check(Math.abs(position()-3000)<300&&!playing(),"zone cancel restores original position and pause");
 }
 private void continuousTimeline(float x,float middle,float width)throws Exception{
  invoke("fitVideo");long contact=SystemClock.uptimeMillis();touch(0,x-width*.12f,middle,contact);multi(MotionEvent.ACTION_POINTER_DOWN|(1<<8),contact,x-width*.12f,x+width*.12f,middle);
  for(int i=1;i<=10;i++){Thread.sleep(30);float span=width*(.12f+.022f*i);multi(MotionEvent.ACTION_MOVE,contact,x-span,x+span,middle);}
  multi(MotionEvent.ACTION_POINTER_UP|(1<<8),contact,x-width*.34f,x+width*.34f,middle);touch(MotionEvent.ACTION_UP,x-width*.34f,middle,contact);Thread.sleep(300);
  check(viewport().mode()==VideoViewport.Mode.CUSTOM,"pinch enters zoom before timeline test");double oldWidth=viewport().width();
  View seek=(View)field(activity,"seek");int[] at=new int[2];main(()->seek.getLocationInWindow(at));float xx=at[0]+seek.getWidth()*.25f,yy=at[1]+seek.getHeight()/2f;
  contact=SystemClock.uptimeMillis();touch(0,xx,yy,contact);Thread.sleep(ViewConfiguration.getLongPressTimeout()+100);
  check((boolean)field(activity,"scrubbing"),"timeline long press starts scrub while zoomed");
  boolean decoded=false;android.widget.ImageView preview=(android.widget.ImageView)field(activity,"preview");
  // No pause between MOVE events exceeds 40 ms: repeatedly restarting a debounce would starve work.
  for(int i=1;i<=200;i++){Thread.sleep(40);touch(2,xx+seek.getWidth()*.45f*i/200f,yy,contact);if(preview.getVisibility()==View.VISIBLE&&preview.getDrawable()!=null)decoded=true;}
  check(decoded,"timeline decoded thumbnail appears during continuous MOVE events");
  for(int i=0;i<150&&nearestDistance()>750;i++)Thread.sleep(100);
  check(nearestDistance()<=750,"timeline preview catches up to last requested target");
  android.widget.TextView label=(android.widget.TextView)field(activity,"previewTime");check(!label.getText().toString().contains("加载中"),"final preview label no longer loading");
  View timeline=(View)field(activity,"timeline");check(preview.getBottom()<=timeline.getTop(),"preview stays above timeline");screenshot("preview-081");
  touch(1,xx+seek.getWidth()*.45f,yy,contact);ready();check(preview.getVisibility()==View.GONE&&!playing(),"timeline release hides preview and preserves pause");
  check(viewport().mode()==VideoViewport.Mode.CUSTOM&&Math.abs(viewport().width()-oldWidth)<1,"timeline drag preserves custom zoom");dbl(x,middle);check(viewport().mode()==VideoViewport.Mode.WIDTH,"double returns width after timeline scrub");
 }
 @Override public void onStart(){
  int result=Activity.RESULT_OK;Store store=null;String previousServer=null;String[][] previous=new String[3][4];String[] kinds={"tap","double","hold","drag"};int timeout=3500;float speed=1;boolean memory=false;dev.flowstream.player.ui.MainActivity home=null;
  try{
   home=(dev.flowstream.player.ui.MainActivity)startActivitySync(new Intent(getTargetContext(),dev.flowstream.player.ui.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
   dev.flowstream.player.net.PreviewMediaDataSourceChecks.run();check(true,"preview range cache and stream reuse");FlowApp app=(FlowApp)getTargetContext().getApplicationContext();store=app.store;previousServer=store.activeServer();timeout=store.hudTimeoutMs();speed=store.globalSpeed();memory=store.memoryPreload();store.setHudTimeoutMs(0);store.setGlobalSpeed(1);store.setMemoryPreload(false);
   for(int z=0;z<3;z++)for(int k=0;k<4;k++){previous[z][k]=store.gestureAction(z,kinds[k]);store.setGestureAction(z,kinds[k],GestureBindings.defaultAction(z,kinds[k]));}
   Server endpoint=new Server();endpoint.name="Preview081 fixture";endpoint.protocol="WEBDAV";endpoint.url="http://127.0.0.1:8089/dav/";endpoint.allowHttp=true;endpoint.username="demo";endpoint.password="demo-only";serverId=endpoint.id;store.saveServer(endpoint);
   ArrayList<RemoteItem> entries=new ArrayList<>();for(RemoteItem item:Browsers.list(endpoint,""))if(!item.directory)entries.add(item);check(!entries.isEmpty(),"fixture contains videos");sessionId=store.newSession(endpoint,"",entries,0);launch();ready();Thread.sleep(1000);
   android.view.accessibility.AccessibilityNodeInfo ui=getUiAutomation().getRootInActiveWindow();if(ui!=null)for(android.view.accessibility.AccessibilityNodeInfo node:ui.findAccessibilityNodeInfosByText("知道了"))node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
   ExoPlayer e=player();main(e::pause);invoke("showControls");String path=entries.get(0).path;
   click("标记");check(store.isMarked(serverId,path),"player mark button adds persistent media mark");
   Store reopened=new Store(getTargetContext());check(reopened.isMarked(serverId,path),"mark survives fresh Store instance");reopened.close();
   click("取消标记");check(!store.isMarked(serverId,path),"player mark button toggles off");click("标记");
   int[] wh=new int[2];main(()->{wh[0]=activity.getWindow().getDecorView().getWidth();wh[1]=activity.getWindow().getDecorView().getHeight();});float x=wh[0]*.4f,mid=zoneY(1,wh[1]),low=zoneY(2,wh[1]),upper=zoneY(0,wh[1]);
   liveZoneScrub(x,mid,wh[0]);
   liveZoneScrub(x,low,wh[0]);
   continuousTimeline(x,mid,wh[0]);
   main(()->e.seekTo(3000));ready();long contact=SystemClock.uptimeMillis();touch(0,x,low,contact);touch(2,x+wh[0]*.12f,low,contact);check((boolean)field(activity,"scrubbing"),"lower horizontal drag starts progress scrub");touch(1,x+wh[0]*.12f,low,contact);ready();check(position()>3500&&!playing(),"lower scrub changes position and preserves pause");
   float cm=activity.getResources().getDisplayMetrics().xdpi/2.54f;
   contact=SystemClock.uptimeMillis();touch(0,x,low,contact);Thread.sleep(450);touch(2,x+cm*1.02f,low,contact);String hint=((android.widget.TextView)field(activity,"hud")).getText().toString();check(hint.equals("快进 5×"),"forward hold HUD is concise: "+hint);touch(1,x+cm*1.02f,low,contact);
   main(()->e.seekTo(12000));ready();contact=SystemClock.uptimeMillis();touch(0,x,upper,contact);Thread.sleep(450);touch(2,x-cm*1.02f,upper,contact);hint=((android.widget.TextView)field(activity,"hud")).getText().toString();check(hint.equals("快退 5×"),"reverse hold HUD is concise: "+hint);touch(1,x-cm*1.02f,upper,contact);
   main(()->activity.finish());Thread.sleep(500);dev.flowstream.player.ui.MainActivity screen=(dev.flowstream.player.ui.MainActivity)startActivitySync(new Intent(getTargetContext(),dev.flowstream.player.ui.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));home=screen;
   View tab=named(screen.getWindow().getDecorView(),"标记");check(tab!=null,"main has marks navigation");main(tab::performClick);android.widget.GridView list=(android.widget.GridView)field(screen,"list");
   for(int i=0;i<100&&((View)field(screen,"progress")).getVisibility()==View.VISIBLE;i++)Thread.sleep(100);
   java.util.List<MarkedItem> marks=(java.util.List<MarkedItem>)field(screen,"markedEntries");int at=-1;for(int i=0;i<marks.size();i++)if(marks.get(i).serverId.equals(serverId)&&marks.get(i).item.path.equals(path))at=i;check(at>=0,"marks tab shows marked fixture");final int selected=at;
   ActivityMonitor monitor=addMonitor(PlayerActivity.class.getName(),null,false);main(()->list.performItemClick(null,selected,selected));Activity opened=monitor.waitForActivityWithTimeout(15000);removeMonitor(monitor);check(opened instanceof PlayerActivity,"marked entry opens player");activity=(PlayerActivity)opened;ready();Session openedSession=(Session)field(activity,"session");sessionId=openedSession.id;check(openedSession.current().path.equals(path)&&openedSession.queue.size()==entries.size()&&openedSession.folder.equals(""),"marked entry rebuilds full original-folder queue");
  }catch(Throwable error){result=Activity.RESULT_CANCELED;report.append("FAIL ").append(android.util.Log.getStackTraceString(error));}
  finally{try{if(activity!=null)main(()->activity.finish());if(home!=null){dev.flowstream.player.ui.MainActivity screen=home;main(screen::finish);}if(store!=null){if(sessionId!=null)store.deleteSession(sessionId);if(serverId!=null)store.deleteServer(serverId);if(previousServer!=null)store.selectServer(previousServer);store.setHudTimeoutMs(timeout);store.setGlobalSpeed(speed);store.setMemoryPreload(memory);for(int z=0;z<3;z++)for(int k=0;k<4;k++)if(previous[z][k]!=null)store.setGestureAction(z,kinds[k],previous[z][k]);}}catch(Throwable ignored){}}
  Bundle out=new Bundle();out.putString("stream",report.toString());finish(result,out);
 }
}
