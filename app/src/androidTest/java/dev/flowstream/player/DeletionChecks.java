package dev.flowstream.player;
import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import dev.flowstream.player.data.*;
import dev.flowstream.player.ui.PlayerActivity;
import dev.flowstream.player.net.Browsers;
import dev.flowstream.core.RemoteItem;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.*;

/** Destructive checks use only disposable files on the explicitly started localhost fixture. */
public final class DeletionChecks extends Instrumentation {
 private PlayerActivity activity;private final StringBuilder report=new StringBuilder();
 @Override public void onCreate(Bundle b){start();}
 private Object field(Object o,String n){try{java.lang.reflect.Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
 private void invoke(String n){runOnMainSync(()->{try{java.lang.reflect.Method m=PlayerActivity.class.getDeclaredMethod(n);m.setAccessible(true);m.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});}
 private void check(boolean b,String msg){if(!b)throw new AssertionError(msg);report.append("PASS ").append(msg).append('\n');Bundle out=new Bundle();out.putString("stream","PASS "+msg+"\n");sendStatus(0,out);}
 private void ready()throws Exception{for(int i=0;i<250;i++){int[] state={0};runOnMainSync(()->{ExoPlayer p=(ExoPlayer)field(activity,"player");if(p!=null)state[0]=p.getPlaybackState();});if(state[0]==3)return;Thread.sleep(100);}throw new AssertionError("not ready: "+((android.widget.TextView)field(activity,"errorMessage")).getText()+" player="+field(activity,"player")+" visible="+field(activity,"visible")+" deleting="+field(activity,"deleting"));}
 private void deleted()throws Exception{for(int i=0;i<400;i++){boolean[] busy={true};runOnMainSync(()->busy[0]=(boolean)field(activity,"deleting"));if(!busy[0])return;Thread.sleep(100);}throw new AssertionError("delete timed out");}
 private void confirm(boolean yes){invoke("confirmDelete");runOnMainSync(()->((AlertDialog)field(activity,"deleteConfirm")).getButton(yes?AlertDialog.BUTTON_POSITIVE:AlertDialog.BUTTON_NEGATIVE).performClick());}
 @Override public void onStart(){startActivitySync(new Intent(getTargetContext(),dev.flowstream.player.ui.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));FlowApp app=(FlowApp)getTargetContext().getApplicationContext();String old=app.store.activeServer();Server server=new Server();String id=null;int code=Activity.RESULT_OK;boolean random=app.store.randomMode();
 try{
  app.store.setRandomMode(false);server.name="Delete fixture";server.protocol="WEBDAV";server.url="http://127.0.0.1:8089/dav/";server.allowHttp=true;server.username="demo";server.password="demo-only";app.store.saveServer(server);
  List<RemoteItem> files=Browsers.list(server,"");check(files.size()==2,"isolated fixture has exactly two disposable videos");id=app.store.newSession(server,"",files,0);
  activity=(PlayerActivity)startActivitySync(new Intent(getTargetContext(),PlayerActivity.class).putExtra("session_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));ready();invoke("showControls");
  runOnMainSync(()->{android.view.ViewGroup bottom=(android.view.ViewGroup)field(activity,"bottom");android.view.ViewGroup row=(android.view.ViewGroup)bottom.getChildAt(0);if(!"菜单".contentEquals(row.getChildAt(row.getChildCount()-1).getContentDescription()))throw new AssertionError("menu on right");android.view.ViewGroup controls=(android.view.ViewGroup)bottom.getChildAt(2);for(int i=1;i<controls.getChildCount();i++)if(controls.getChildAt(i).getLeft()-controls.getChildAt(i-1).getRight()<6*activity.getResources().getDisplayMetrics().density)throw new AssertionError("button gap");});check(true,"right menu and separated bottom buttons");
  android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),"controls-061.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}shot.recycle();
  confirm(false);check(Browsers.list(server,"").size()==2,"cancel keeps real files");
  Server endpoint=(Server)field(activity,"server");Server denied=app.store.server(server.id);denied.password="invalid-fixture-password";
  runOnMainSync(()->{try{java.lang.reflect.Field f=PlayerActivity.class.getDeclaredField("server");f.setAccessible(true);f.set(activity,denied);}catch(Exception ex){throw new RuntimeException(ex);}});confirm(true);
  runOnMainSync(()->{try{java.lang.reflect.Field f=PlayerActivity.class.getDeclaredField("server");f.setAccessible(true);f.set(activity,endpoint);}catch(Exception ex){throw new RuntimeException(ex);}});deleted();
  check(Browsers.list(server,"").size()==2,"denied request leaves files intact");check(((Session)field(activity,"session")).queue.size()==2,"failure keeps playback queue");ready();
  String first=((Session)field(activity,"session")).current().path;confirm(true);deleted();ready();
  List<RemoteItem> remaining=Browsers.list(server,"");check(remaining.size()==1&&!remaining.get(0).path.equals(first),"delete removes actual remote file");
  Session queue=(Session)field(activity,"session");check(queue.queue.size()==1&&queue.current().path.equals(remaining.get(0).path),"queue advances to remaining file");check(app.store.session(id).queue.size()==1,"stored in-memory queue also removes file");
  invoke("rotate");Thread.sleep(800);ready();check(((Session)field(activity,"session")).queue.size()==1,"rotation retains updated queue");
  confirm(true);deleted();check(Browsers.list(server,"").isEmpty(),"last video physically deleted");check(activity.isFinishing()||activity.isDestroyed(),"last delete closes player");
 }catch(Throwable e){code=Activity.RESULT_CANCELED;report.append("FAIL ").append(android.util.Log.getStackTraceString(e));}
 finally{if(activity!=null)runOnMainSync(()->activity.finish());if(id!=null)app.store.deleteSession(id);app.store.deleteServer(server.id);app.store.selectServer(old);app.store.setRandomMode(random);}
 Bundle b=new Bundle();b.putString("stream",report.toString());finish(code,b);
 }
}
