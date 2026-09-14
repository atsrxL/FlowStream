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
public final class OdsChecks extends Instrumentation {
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
 @Override public void onStart(){
 int result=Activity.RESULT_OK;String previousServer=null;Store store=null;String[][] previous=new String[3][4];String[] kinds={"tap","double","hold","drag"};int oldTimeout=3500;boolean oldMemory=false;float oldSpeed=1;
 try{
 startActivitySync(new Intent(getTargetContext(),dev.flowstream.player.ui.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
 FlowApp app=(FlowApp)getTargetContext().getApplicationContext();store=app.store;previousServer=store.activeServer();oldTimeout=store.hudTimeoutMs();oldMemory=store.memoryPreload();oldSpeed=store.globalSpeed();
 for(int z=0;z<3;z++)for(int k=0;k<4;k++){previous[z][k]=store.gestureAction(z,kinds[k]);store.setGestureAction(z,kinds[k],GestureBindings.defaultAction(z,kinds[k]));}
 store.setHudTimeoutMs(0);store.setMemoryPreload(true);store.setGlobalSpeed(1f);
 check(store.hudTimeoutMs()==0&&store.memoryPreload(),"settings retain HUD timeout and RAM mode");
 dev.flowstream.player.net.MemoryPrefixChecks.run();check(true,"RAM prefix range EOF budget cancellation tests");
 Server endpoint=new Server();endpoint.name="ODS USB fixture";endpoint.protocol="WEBDAV";endpoint.url="http://127.0.0.1:8089/dav/";endpoint.allowHttp=true;endpoint.username="demo";endpoint.password="demo-only";serverId=endpoint.id;store.saveServer(endpoint);
 java.util.List<RemoteItem> entries=Browsers.list(endpoint,"");sessionId=store.newSession(endpoint,"",entries,0);launch();ready();Thread.sleep(1200);
 android.view.accessibility.AccessibilityNodeInfo ui=getUiAutomation().getRootInActiveWindow();if(ui!=null)for(android.view.accessibility.AccessibilityNodeInfo node:ui.findAccessibilityNodeInfosByText("知道了"))node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);Thread.sleep(300);
 ExoPlayer e=player();int[] wh=new int[2];main(()->{wh[0]=activity.getWindow().getDecorView().getWidth();wh[1]=activity.getWindow().getDecorView().getHeight();e.pause();e.seekTo(10000);});ready();Thread.sleep(500);
 float x=wh[0]/2f,upper=zoneY(0,wh[1]),middle=zoneY(1,wh[1]),lower=zoneY(2,wh[1]);
 View top=(View)field(activity,"top"),timeline=(View)field(activity,"timeline");
 check(upper<middle&&middle<lower,"three gesture zones ordered within actual bounds");
 invoke("fitVideo");dbl(x,middle);check(viewport().mode()==VideoViewport.Mode.HEIGHT&&!playing(),"middle double fit preserves pause");dbl(x,middle);check(viewport().mode()==VideoViewport.Mode.NATIVE,"middle double native");dbl(x,middle);check(viewport().mode()==VideoViewport.Mode.WIDTH,"middle double width");
 int oldVisibility=top.getVisibility();tap(x,upper);Thread.sleep(400);check(top.getVisibility()==oldVisibility&&!playing(),"upper single has no action");
 Object overlay=field(activity,"gestures");java.lang.reflect.Method zone=overlay.getClass().getMethod("zoneAt",float.class);float blank=0;for(int y=(int)upper;y>=0;y--)if((int)zone.invoke(overlay,(float)y)<0){blank=y;break;}
 tap(x,blank);Thread.sleep(400);check(top.getVisibility()==oldVisibility&&!playing(),"blank above gesture zones has no tap action");
 dbl(x,lower);check(playing()&&viewport().mode()==VideoViewport.Mode.WIDTH,"lower double plays without fit");dbl(x,lower);check(!playing(),"lower double pauses");
 invoke("showControls");tap(x,lower);Thread.sleep(400);check(top.getVisibility()==View.GONE&&timeline.getVisibility()==View.VISIBLE,"lower single hides HUD keeps timeline");tap(x,lower);Thread.sleep(400);check(top.getVisibility()==View.VISIBLE,"lower single shows HUD");
 main(()->e.seekTo(10000));ready();Thread.sleep(300);click("+3s");ready();check(Math.abs(position()-13000)<300,"plus three seconds");click("-3s");ready();check(Math.abs(position()-10000)<300,"minus three seconds");
 invoke("playbackInfo");View info=(View)field(activity,"mediaInfo");check(info.getVisibility()==View.VISIBLE,"media info opens");tap(wh[0]-20,timeline.getTop()+timeline.getHeight()/2f);Thread.sleep(400);check(info.getVisibility()==View.GONE&&!playing(),"outside info tap dismisses without playback");
 invoke("toggleLock");boolean prior=playing();dbl(x,lower);check(playing()==prior&&timeline.getVisibility()==View.VISIBLE,"lock blocks playback gestures keeps timeline");invoke("toggleLock");
 long before=position();drag(x,middle,x+wh[0]*.15f,middle,200);ready();check(position()>before+1000&&!playing(),"middle drag scrubs preserving pause");

 // A real held scrub must show an asynchronously decoded preview above the timeline.
 main(()->e.seekTo(8000));ready();long contact=SystemClock.uptimeMillis();touch(0,x,middle,contact);touch(2,x+wh[0]*.12f,middle,contact);
 check((boolean)field(activity,"scrubbing"),"middle drag owns scrub");
 android.widget.ImageView preview=(android.widget.ImageView)field(activity,"preview");
 for(int i=0;i<150 && preview.getDrawable()==null;i++)Thread.sleep(100);
 check(preview.getVisibility()==View.VISIBLE&&preview.getDrawable()!=null,"scrub shows decoded thumbnail");
 check(preview.getBottom()<=timeline.getTop(),"preview sits above bottom timeline");screenshot("preview-080");touch(1,x+wh[0]*.12f,middle,contact);ready();
 check(preview.getVisibility()==View.GONE&&!playing(),"scrub release hides preview and preserves pause");
 Object trick=field(activity,"trick");main(()->{try{trick.getClass().getMethod("setBaseSpeed",float.class).invoke(trick,1.35f);}catch(Exception ex){throw new RuntimeException(ex);}});
 float cm=activity.getResources().getDisplayMetrics().xdpi/2.54f;float[] rate={0};
 main(()->e.seekTo(12000));ready();contact=SystemClock.uptimeMillis();touch(0,x,upper,contact);Thread.sleep(450);touch(2,x-cm*1.02f,upper,contact);
 Object clock=field(trick,"clock");check(Math.abs((float)field(clock,"magnitude")-5)<.001,"upper hold one cm reaches reverse 5x");
 check(timeline.getVisibility()==View.VISIBLE,"reverse hold preserves timeline");screenshot("hold-080");touch(2,x+cm*1.02f,upper,contact);check(Math.abs((float)field(clock,"magnitude")-.1f)<.001,"upper hold right one cm reverse 0.1x");touch(1,x+cm*1.02f,upper,contact);check(!playing(),"reverse release preserves pause");
 contact=SystemClock.uptimeMillis();touch(0,x,lower,contact);Thread.sleep(450);touch(2,x+cm*1.02f,lower,contact);main(()->rate[0]=e.getPlaybackParameters().speed);check(Math.abs(rate[0]-5)<.001,"lower hold one cm reaches forward 5x");
 touch(2,x-cm*1.02f,lower,contact);main(()->rate[0]=e.getPlaybackParameters().speed);check(Math.abs(rate[0]-.1f)<.001,"lower hold left one cm forward 0.1x");touch(1,x-cm*1.02f,lower,contact);main(()->rate[0]=e.getPlaybackParameters().speed);check(Math.abs(rate[0]-1.35f)<.001&&!playing(),"forward release restores base and pause");
 int initialIndex=index();drag(x,lower,x,upper,220);ready();check(index()!=initialIndex,"upward swipe within gesture bounds opens next");Thread.sleep(500);drag(x,upper,x,lower,220);ready();check(index()==initialIndex,"downward swipe returns previous");
 main(()->e.pause());invoke("fitVideo");contact=SystemClock.uptimeMillis();touch(0,x-wh[0]*.12f,middle,contact);multi(MotionEvent.ACTION_POINTER_DOWN|(1<<8),contact,x-wh[0]*.12f,x+wh[0]*.12f,middle);
 for(int i=1;i<=10;i++){Thread.sleep(30);float span=wh[0]*(.12f+.022f*i);multi(MotionEvent.ACTION_MOVE,contact,x-span,x+span,middle);}
 multi(MotionEvent.ACTION_POINTER_UP|(1<<8),contact,x-wh[0]*.34f,x+wh[0]*.34f,middle);touch(MotionEvent.ACTION_UP,x-wh[0]*.34f,middle,contact);Thread.sleep(300);
 check(viewport().mode()==VideoViewport.Mode.CUSTOM,"two finger pinch enters custom zoom");
 contact=SystemClock.uptimeMillis();touch(0,x,upper,contact);Thread.sleep(450);check(!(boolean)field(trick,"held"),"custom zoom upper long hold never starts reverse");touch(MotionEvent.ACTION_CANCEL,x,upper,contact);
 int zoomIndex=index();drag(x,lower,x,upper,220);check(viewport().mode()==VideoViewport.Mode.CUSTOM&&index()==zoomIndex,"zoom vertical drag pans without switching");
 dbl(x,lower);check(viewport().mode()==VideoViewport.Mode.WIDTH&&!playing(),"custom lower double restores width without play");
 invoke("showControls");screenshot("controls-080");
 }catch(Throwable t){result=Activity.RESULT_CANCELED;report.append("FAIL ").append(android.util.Log.getStackTraceString(t));}
 finally{try{if(activity!=null)main(()->activity.finish());if(store!=null){if(sessionId!=null)store.deleteSession(sessionId);if(serverId!=null)store.deleteServer(serverId);if(previousServer!=null)store.selectServer(previousServer);store.setHudTimeoutMs(oldTimeout);store.setMemoryPreload(oldMemory);store.setGlobalSpeed(oldSpeed);for(int z=0;z<3;z++)for(int k=0;k<4;k++)if(previous[z][k]!=null)store.setGestureAction(z,kinds[k],previous[z][k]);}}catch(Throwable ignored){}}
 Bundle out=new Bundle();out.putString("stream",report.toString());finish(result,out);
 }
}
