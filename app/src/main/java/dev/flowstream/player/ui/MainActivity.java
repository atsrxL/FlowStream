package dev.flowstream.player.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import dev.flowstream.core.*;
import dev.flowstream.player.FlowApp;
import dev.flowstream.player.data.Server;
import dev.flowstream.player.data.Session;
import dev.flowstream.player.net.Browsers;
import java.text.DateFormat;
import java.util.*;

public final class MainActivity extends BackActivity {
    private FlowApp app;
    private TextView heading, path, empty;
    private ListView list;
    private ProgressBar progress;
    private String serverId="", folder="";
    private Server server;
    private boolean history;
    private int generation;
    private List<RemoteItem> entries = new ArrayList<>();
    private List<Session> sessions = new ArrayList<>();
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); app=(FlowApp)getApplication();
        LinearLayout root=Ui.column(this); root.setBackgroundColor(Ui.BG);
        LinearLayout bar=Ui.row(this); Ui.pad(bar,10); bar.setBackgroundColor(Ui.PANEL);
        heading=Ui.text(this,"流映 FlowStream",21,Ui.TEXT); bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        bar.addView(Ui.button(this,"设置",()->startActivity(new Intent(this,SettingsActivity.class)))); root.addView(bar);
        LinearLayout tabs=Ui.row(this);
        tabs.addView(Ui.button(this,"文件",()->{history=false; reload();}),new LinearLayout.LayoutParams(0,-2,1));
        tabs.addView(Ui.button(this,"会话历史",()->{history=true; reload();}),new LinearLayout.LayoutParams(0,-2,1)); root.addView(tabs);tabs.addView(Ui.button(this,"收藏",this::showBookmarks),new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout crumbs=Ui.row(this); crumbs.setPadding(Ui.dp(this,10),0,Ui.dp(this,8),0);
        path=Ui.text(this,"",13,Ui.MUTED); crumbs.addView(path,new LinearLayout.LayoutParams(0,-2,1));
        crumbs.addView(Ui.button(this,"上一级",()->{if(!history && !folder.isEmpty()){folder=MediaPaths.parent(folder);reload();}}));
        crumbs.addView(Ui.button(this,"刷新",this::reload)); root.addView(crumbs);
        progress=new ProgressBar(this); root.addView(progress,new LinearLayout.LayoutParams(-1,Ui.dp(this,36)));
        FrameLayout content=new FrameLayout(this); list=new ListView(this); list.setDividerHeight(1);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Ui.PANEL)); content.addView(list,new FrameLayout.LayoutParams(-1,-1));
        empty=Ui.text(this,"在设置中添加并选择服务器\n支持 SMB / Samba、WebDAV\nZFS 存储通过共享协议访问",16,Ui.MUTED);
        empty.setGravity(Gravity.CENTER); Ui.pad(empty,24); content.addView(empty,new FrameLayout.LayoutParams(-1,-1)); list.setEmptyView(empty);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root); Ui.safeInsets(this,root);
        list.setOnItemClickListener((p,v,pos,id)->open(pos));
        list.setOnItemLongClickListener((p,v,pos,id)->{
            if(!history || pos>=sessions.size())return false;
            Session s=sessions.get(pos);
            new AlertDialog.Builder(this).setTitle("删除这条会话？").setMessage("仅清除本机记录，不会删除服务器视频。")
                    .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->app.disk.execute(()->{app.store.deleteSession(s.id);app.main.post(this::reload);})).show();return true;
        });
    }
    private void showBookmarks(){
        org.json.JSONArray all=app.store.bookmarks();String[] labels=new String[all.length()];
        for(int i=0;i<labels.length;i++){org.json.JSONObject b=all.optJSONObject(i);labels[i]=(b.optLong("position",-1)<0?"☆ ":"⚑ "+Ui.time(b.optLong("position"))+" · ")+b.optString("name");}
        if(labels.length==0){Ui.toast(this,"还没有收藏或书签，可在播放器中添加");return;}
        new AlertDialog.Builder(this).setTitle("收藏与时间点书签").setItems(labels,(d,index)->{
            org.json.JSONObject b=all.optJSONObject(index);
            new AlertDialog.Builder(this).setTitle(labels[index]).setItems(new String[]{"播放","删除"},(dialog,action)->{
                if(action==1){app.store.removeBookmark(index);showBookmarks();return;}
                app.disk.execute(()->{try{Server endpoint=app.store.server(b.getString("server"));java.util.List<RemoteItem> q=java.util.Collections.singletonList(new RemoteItem(b.getString("path"),b.getString("name"),false,0));String id=app.store.newSession(endpoint,MediaPaths.parent(b.getString("path")),q,0);app.store.checkpoint(id,0,Math.max(0,b.optLong("position")),0,app.store.sourceSpeed(endpoint.id));app.main.post(()->launch(id));}catch(Exception e){app.main.post(()->Ui.toast(this,Ui.error(e)));}});
            }).show();
        }).setNegativeButton("关闭",null).show();
    }
    @Override protected void onResume(){super.onResume();reload();}
    private void reload(){
        final int token=++generation; progress.setVisibility(View.VISIBLE); empty.setText("正在读取…");
        if(history){
            heading.setText("会话历史");path.setText("最近 100 个会话 · 点击续播 · 长按删除");
            app.disk.execute(()->{
                try{List<Session> result=app.store.history();app.main.post(()->{if(stale(token))return;sessions=result;progress.setVisibility(View.GONE);showRows();});}
                catch(Exception e){fail(token,e);}
            });return;
        }
        String active=app.store.activeServer();
        if(!active.equals(serverId)){serverId=active;folder=app.store.lastFolder(active);}
        heading.setText("流映 FlowStream");path.setText(folder.isEmpty()?"根目录":folder);
        if(active.isEmpty()){entries.clear();progress.setVisibility(View.GONE);showRows();empty.setText("还没有选择服务器\n打开右上角设置，添加 SMB 或 WebDAV 来源");return;}
        final String requestedFolder=folder;
        app.disk.execute(()->{
            try{Server s=app.store.server(active);app.io.execute(()->{
                try{List<RemoteItem> result=Browsers.list(s,requestedFolder);app.main.post(()->{
                    if(stale(token))return;server=s;entries=result;heading.setText(s.name);path.setText("/"+requestedFolder);
                    app.store.setLastFolder(active,requestedFolder);progress.setVisibility(View.GONE);showRows();
                });}catch(Exception e){fail(token,e);}
            });}catch(Exception e){fail(token,e);}
        });
    }
    private boolean stale(int token){return token!=generation || isDestroyed() || isFinishing();}
    private void fail(int token,Exception e){app.main.post(()->{if(stale(token))return;progress.setVisibility(View.GONE);entries.clear();sessions.clear();showRows();empty.setText(Ui.error(e)+"\n可点击刷新重试，或返回上一级");});}
    private void showRows(){
        empty.setText(history?"尚无播放会话":"这个目录没有可显示的视频或子目录");
        final boolean showingHistory=history;
        final List<Session> visibleSessions=new ArrayList<>(sessions);
        final List<RemoteItem> visibleEntries=new ArrayList<>(entries);
        list.setAdapter(new BaseAdapter(){
            @Override public int getCount(){return showingHistory?visibleSessions.size():visibleEntries.size();}
            @Override public Object getItem(int p){return showingHistory?visibleSessions.get(p):visibleEntries.get(p);}
            @Override public long getItemId(int p){return p;}
            @Override public View getView(int p,View convert,ViewGroup parent){
                if(showingHistory){Session s=visibleSessions.get(p);return Ui.listRow(MainActivity.this,"↺",s.queue.get(0).name,
                        s.serverName+" · "+Ui.time(s.position)+" / "+Ui.time(s.duration)+" · "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(s.updated)));}
                RemoteItem item=visibleEntries.get(p);return Ui.listRow(MainActivity.this,item.directory?"▤":"▶",item.name,item.directory?"文件夹":Ui.size(item.size)+" · 点击播放");
            }
        });
    }
    private void open(int pos){
        if(progress.getVisibility()==View.VISIBLE)return;
        if(history){if(pos<sessions.size())launch(sessions.get(pos).id);return;}
        if(pos>=entries.size() || server==null)return;
        RemoteItem selected=entries.get(pos);
        if(selected.directory){folder=selected.path;reload();return;}
        ArrayList<RemoteItem> queue=new ArrayList<>();for(RemoteItem i:entries)if(!i.directory)queue.add(i);
        final int index=queue.indexOf(selected);final Server chosen=server;final String dir=folder;final int token=++generation;
        progress.setVisibility(View.VISIBLE);
        app.disk.execute(()->{
            try{String id=app.store.newSession(chosen,dir,queue,index);app.main.post(()->{if(stale(token))return;progress.setVisibility(View.GONE);launch(id);});}
            catch(Exception e){fail(token,e);}
        });
    }
    private void launch(String id){startActivity(new Intent(this,PlayerActivity.class).putExtra("session_id",id));}
    @Override protected void back(){if(history){history=false;reload();}else if(!folder.isEmpty()){folder=MediaPaths.parent(folder);reload();}else super.back();}
    @Override protected void onDestroy(){generation++;super.onDestroy();}
}
