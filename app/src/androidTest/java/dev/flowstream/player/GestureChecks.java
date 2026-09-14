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
public final class GestureChecks extends Instrumentation {
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
 int result=Activity.RESULT_OK;String previousServer=null;
 try{
 startActivitySync(new Intent(getTargetContext(),dev.flowstream.player.ui.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
 FlowApp app=(FlowApp)getTargetContext().getApplicationContext();previousServer=app.store.activeServer();
 Server endpoint=new Server();endpoint.name="USB gesture fixture";endpoint.protocol="WEBDAV";endpoint.url="http://127.0.0.1:8089/dav/";endpoint.allowHttp=true;endpoint.username="demo";endpoint.password="demo-only";serverId=endpoint.id;app.store.saveServer(endpoint);
 java.util.List<RemoteItem> entries=Browsers.list(endpoint,"");sessionId=app.store.newSession(endpoint,"",entries,0);launch();ready();Thread.sleep(1200);android.view.accessibility.AccessibilityNodeInfo ui=getUiAutomation().getRootInActiveWindow();if(ui!=null)for(android.view.accessibility.AccessibilityNodeInfo node:ui.findAccessibilityNodeInfosByText("知道了")){node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);}Thread.sleep(500);ExoPlayer e=player();
 int[] wh=new int[2];main(()->{wh[0]=activity.getWindow().getDecorView().getWidth();wh[1]=activity.getWindow().getDecorView().getHeight();});float x=wh[0]/2f,y=wh[1]/2f;
 View timeline=(View)field(activity,"timeline");android.view.ViewGroup top=(android.view.ViewGroup)field(activity,"top");
 check(((android.view.ViewGroup)top.getChildAt(0)).getChildCount()==3,"top has only rotate pip speed");invoke("showControls");invoke("toggleControls");check(timeline.getVisibility()==View.VISIBLE,"timeline persists when menu hidden");
 main(()->{e.pause();e.seekTo(10000);});ready();Thread.sleep(1000);long before=position();int beforeIndex=index();
 long probe=SystemClock.uptimeMillis();touch(0,x,y,probe);Thread.sleep(80);touch(2,x+wh[0]*.15f,y,probe);Object gm=field(field(activity,"gestures"),"machine");screenshot("probe-070");check((boolean)field(activity,"scrubbing"),"scrub begun mode="+((GestureMachine)gm).mode()+" timeline="+timeline.getTop()+"-"+timeline.getBottom());touch(1,x+wh[0]*.15f,y,probe);Thread.sleep(500);ready();check(position()>before+2000,"middle horizontal drag advances progress before="+before+" after="+position()+" size="+wh[0]+"x"+wh[1]+" mode="+viewport().mode());check(!playing()&&index()==beforeIndex,"scrub keeps pause and never changes video");
 before=position();long d=SystemClock.uptimeMillis();touch(0,x,y,d);touch(2,x-80,y,d);touch(3,x-80,y,d);check(Math.abs(position()-before)<200,"cancelled middle drag keeps position");
 Object trick=field(activity,"trick");main(()->{try{trick.getClass().getMethod("setBaseSpeed",float.class).invoke(trick,1.35f);}catch(Exception ex){throw new RuntimeException(ex);}});
 float cm=activity.getResources().getDisplayMetrics().xdpi/2.54f;float[] rate={0};
 for(float start:new float[]{wh[0]*.35f,wh[0]*.65f}){
  main(()->{e.pause();e.seekTo(10000);});ready();d=SystemClock.uptimeMillis();touch(0,start,wh[1]*.23f,d);Thread.sleep(450);Object clock=field(trick,"clock");check(Math.abs((float)field(clock,"magnitude")-1)<.001,"top starts reverse 1x");touch(2,start-cm,wh[1]*.23f,d);check(Math.abs((float)field(clock,"magnitude")-4)<.001,"one cm left reaches reverse 4x");touch(2,start+cm,wh[1]*.23f,d);check(Math.abs((float)field(clock,"magnitude")-.1f)<.001,"one cm right reaches reverse 0.1x");
  View hud=(View)field(activity,"hud");check(hud.getTop()<wh[1]/3&&hud.getVisibility()==View.VISIBLE,"speed hint at top second row");check(timeline.getVisibility()==View.VISIBLE,"timeline remains visible during reverse");screenshot("gesture-070");touch(1,start+cm,wh[1]*.23f,d);check(!playing(),"reverse release restores pause");
  d=SystemClock.uptimeMillis();touch(0,start,wh[1]*.75f,d);Thread.sleep(450);touch(2,start+cm,wh[1]*.75f,d);main(()->rate[0]=e.getPlaybackParameters().speed);check(Math.abs(rate[0]-5)<.001,"one cm right reaches forward 5x");touch(2,start-cm,wh[1]*.75f,d);main(()->rate[0]=e.getPlaybackParameters().speed);check(Math.abs(rate[0]-.1f)<.001,"one cm left reaches forward 0.1x");touch(1,start-cm,wh[1]*.75f,d);main(()->rate[0]=e.getPlaybackParameters().speed);check(Math.abs(rate[0]-1.35f)<.001&&!playing(),"forward release restores base speed and pause");
 }
 d=SystemClock.uptimeMillis();touch(0,x,y,d);Thread.sleep(450);check(!(boolean)field(trick,"held"),"middle long hold never starts transport");touch(2,x+wh[0]*.18f,y,d);screenshot("probe-070");check((boolean)field(activity,"scrubbing"),"middle can scrub after waiting");touch(3,x+wh[0]*.18f,y,d);
 dbl(x,y);check(viewport().mode()==VideoViewport.Mode.HEIGHT,"double still cycles size");invoke("fitVideo");invoke("toggleControls");main(()->{((View)getUnchecked(activity,"top")).setVisibility(View.GONE);((View)getUnchecked(activity,"bottom")).setVisibility(View.GONE);});
 drag(x,y+wh[1]*.1f,x,y-wh[1]*.25f,200);ready();check(index()!=beforeIndex,"middle vertical swipe still switches video");check(timeline.getVisibility()==View.VISIBLE,"timeline stays visible after video switch");
 invoke("showControls");screenshot("controls-070");
 }catch(Throwable t){result=Activity.RESULT_CANCELED;report.append("FAIL ").append(android.util.Log.getStackTraceString(t));}
 finally{try{if(activity!=null)main(()->activity.finish());FlowApp app=(FlowApp)getTargetContext().getApplicationContext();if(sessionId!=null)app.store.deleteSession(sessionId);if(serverId!=null)app.store.deleteServer(serverId);if(previousServer!=null)app.store.selectServer(previousServer);}catch(Throwable ignored){}}
 Bundle out=new Bundle();out.putString("stream",report.toString());finish(result,out);
 }
 private Object getUnchecked(Object object,String name){try{return field(object,name);}catch(Exception e){throw new RuntimeException(e);}}
}
