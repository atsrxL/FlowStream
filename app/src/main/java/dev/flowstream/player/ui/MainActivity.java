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
import dev.flowstream.player.data.MarkedItem;
import dev.flowstream.player.net.Browsers;
import java.text.DateFormat;
import java.util.*;

public final class MainActivity extends BackActivity {
    private FlowApp app;
    private TextView heading, path, empty;
    private GridView list;
    private ProgressBar progress;
    private Button thumbnailsToggle;
    private Switch randomToggle;
    private String serverId="", folder="";
    private Server server;
    private boolean showingMarks;
    private List<MarkedItem> markedEntries=new ArrayList<>();
    private Map<String,Server> markedSources=new HashMap<>();

    private int generation;
    private List<RemoteItem> entries = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); app=(FlowApp)getApplication();
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.bg(this));
        LinearLayout bar=Ui.row(this);bar.setPadding(Ui.dp(this,16),Ui.dp(this,6),Ui.dp(this,16),Ui.dp(this,8));
        thumbnailsToggle=new IconButton(this,"grid","切换列表或双列缩略图",()->{app.store.setThumbnailMode(!app.store.thumbnailMode());showRows();});
        thumbnailsToggle.setTextColor(Ui.fg(this));thumbnailsToggle.setBackground(Ui.background(Ui.panel(this),28,this));bar.addView(thumbnailsToggle,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        bar.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        Button settings=new IconButton(this,"more","设置",()->startActivity(new Intent(this,SettingsActivity.class)));settings.setTextColor(Ui.fg(this));settings.setBackground(Ui.background(Ui.panel(this),28,this));bar.addView(settings,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));root.addView(bar);
        heading=Ui.text(this,"文件",30,Ui.fg(this));heading.setMaxLines(1);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setPadding(Ui.dp(this,18),Ui.dp(this,8),Ui.dp(this,18),Ui.dp(this,6));root.addView(heading);
        LinearLayout tabs=Ui.row(this);tabs.setBackground(Ui.background(Ui.panel(this),32,this));tabs.setPadding(Ui.dp(this,8),0,Ui.dp(this,8),0);
        addNavigation(tabs,"files","文件",()->{showingMarks=false;reload();});
        addNavigation(tabs,"mark","标记",()->{showingMarks=true;reload();});
        addNavigation(tabs,"settings","设置",()->startActivity(new Intent(this,SettingsActivity.class)));
        LinearLayout crumbs=Ui.row(this); crumbs.setPadding(Ui.dp(this,10),0,Ui.dp(this,8),0);
        path=Ui.text(this,"",12,0xff85858c); crumbs.addView(path,new LinearLayout.LayoutParams(0,-2,1));
        Button up=new IconButton(this,"back","上一级",()->{if(showingMarks){showingMarks=false;reload();}else if(!folder.isEmpty()){folder=MediaPaths.parent(folder);reload();}});up.setTextColor(Ui.fg(this));crumbs.addView(up,new LinearLayout.LayoutParams(Ui.dp(this,40),Ui.dp(this,40)));
        Button refresh=new IconButton(this,"rotate","刷新",this::reload);refresh.setTextColor(Ui.fg(this));crumbs.addView(refresh,new LinearLayout.LayoutParams(Ui.dp(this,40),Ui.dp(this,40))); root.addView(crumbs);
        LinearLayout modes=Ui.row(this);
        modes.setPadding(Ui.dp(this,18),0,Ui.dp(this,18),Ui.dp(this,8));
        TextView modeHint=Ui.text(this,"当前文件夹",12,0xff96969d);modes.addView(modeHint,new LinearLayout.LayoutParams(0,-2,1));
        Switch random=new Switch(this);randomToggle=random;random.setText("随机");random.setTextColor(Ui.muted(this));random.setTextSize(12);random.setChecked(app.store.randomMode());random.setOnCheckedChangeListener((v,on)->app.store.setRandomMode(on));modes.addView(random);root.addView(modes);
        progress=new ProgressBar(this); root.addView(progress,new LinearLayout.LayoutParams(-1,Ui.dp(this,36)));
        FrameLayout content=new FrameLayout(this); list=new GridView(this);list.setNumColumns(1);list.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);list.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),0);list.setClipToPadding(false);list.setHorizontalSpacing(Ui.dp(this,8));list.setVerticalSpacing(Ui.dp(this,8)); content.addView(list,new FrameLayout.LayoutParams(-1,-1));
        empty=Ui.text(this,"在设置中添加并选择服务器\n支持 SMB / Samba、WebDAV",16,Ui.MUTED);
        empty.setGravity(Gravity.CENTER); Ui.pad(empty,24); content.addView(empty,new FrameLayout.LayoutParams(-1,-1)); list.setEmptyView(empty);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1)); root.addView(tabs,new LinearLayout.LayoutParams(-1,Ui.dp(this,70)));setContentView(root); Ui.safeInsets(this,root);Ui.systemBars(this);
        list.setOnItemClickListener((p,v,pos,id)->open(pos));
        list.setOnItemLongClickListener((p,v,pos,id)->{if(!showingMarks||pos>=markedEntries.size())return false;MarkedItem mark=markedEntries.get(pos);new AlertDialog.Builder(this).setTitle("取消标记？").setMessage(mark.item.name).setNegativeButton("取消",null).setPositiveButton("取消标记",(d,w)->{app.store.removeMarked(mark.serverId,mark.item.path);reload();}).show();return true;});
    }
    private void addNavigation(LinearLayout row,String icon,String label,Runnable action){
        LinearLayout col=Ui.column(this);col.setGravity(Gravity.CENTER);Button button=new IconButton(this,icon,label,action);button.setTextColor(label.equals("文件")?0xff0784ff:Ui.fg(this));col.addView(button,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,42)));TextView text=Ui.text(this,label,11,Ui.muted(this));text.setGravity(Gravity.CENTER);col.addView(text);col.setOnClickListener(v->action.run());row.addView(col,new LinearLayout.LayoutParams(0,-1,1));
    }
    private View browserRow(RemoteItem item,String detail,Server source){
        LinearLayout row=Ui.row(this);row.setPadding(Ui.dp(this,6),Ui.dp(this,8),Ui.dp(this,6),Ui.dp(this,8));
        ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(Ui.background(Ui.panel(this),14,this));image.setClipToOutline(true);image.setImageResource(item.directory?dev.flowstream.player.R.drawable.ic_folder:dev.flowstream.player.R.drawable.ic_launcher);row.addView(image,new LinearLayout.LayoutParams(Ui.dp(this,104),Ui.dp(this,64)));
        LinearLayout text=Ui.column(this);text.setPadding(Ui.dp(this,12),0,0,0);TextView name=Ui.text(this,item.name,15,Ui.fg(this));name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);text.addView(name);TextView info=Ui.text(this,detail,12,0xff919197);info.setPadding(0,Ui.dp(this,5),0,0);text.addView(info);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        if(!item.directory&&source!=null){final int token=generation;app.thumbnails.load(source,item,b->{if(!stale(token)&&b!=null)image.setImageBitmap(b);});}return row;
    }
    @Override protected void onResume(){super.onResume();randomToggle.setChecked(app.store.randomMode());thumbnailsToggle.setText(app.store.thumbnailMode()?"▧ 缩略图":"☰ 列表");reload();}
    private void reload(){
        if(showingMarks){reloadMarks();return;}
        final int token=++generation; progress.setVisibility(View.VISIBLE); empty.setText("正在读取…");
        String active=app.store.activeServer();
        if(!active.equals(serverId)){serverId=active;folder=app.store.lastFolder(active);}
        heading.setText("文件");path.setText(folder.isEmpty()?"根目录":folder);
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
    private void reloadMarks(){
        final int token=++generation;progress.setVisibility(View.VISIBLE);heading.setText("标记");path.setText("所有来源 · 长按取消标记");
        app.disk.execute(()->{List<MarkedItem> marks=app.store.markedItems();Map<String,Server> sources=new HashMap<>();for(MarkedItem mark:marks){if(sources.containsKey(mark.serverId))continue;try{sources.put(mark.serverId,app.store.server(mark.serverId));}catch(Exception ignored){}}
            app.main.post(()->{if(stale(token))return;markedEntries=marks;markedSources=sources;entries=new ArrayList<>();for(MarkedItem mark:marks)entries.add(mark.item);progress.setVisibility(View.GONE);showRows();});
        });
    }
    private void openMarked(int pos){
        if(pos>=markedEntries.size())return;final MarkedItem mark=markedEntries.get(pos);final Server source=markedSources.get(mark.serverId);if(source==null){Ui.toast(this,"标记的来源不可用，请检查服务器设置");return;}
        final int token=++generation;progress.setVisibility(View.VISIBLE);final String dir=MediaPaths.parent(mark.item.path);
        app.io.execute(()->{try{List<RemoteItem> found=Browsers.list(source,dir);ArrayList<RemoteItem> queue=new ArrayList<>();int chosen=-1;for(RemoteItem item:found)if(!item.directory){if(item.path.equals(mark.item.path))chosen=queue.size();queue.add(item);}if(chosen<0)throw new java.io.IOException("标记的视频已不存在，可长按取消标记");final int index=chosen;
            app.disk.execute(()->{try{String id=app.store.newSession(source,dir,queue,index);app.main.post(()->{if(stale(token))return;progress.setVisibility(View.GONE);launch(id);});}catch(Exception e){markedOpenFailed(token,e);}});
        }catch(Exception e){markedOpenFailed(token,e);}});
    }
    private void markedOpenFailed(int token,Exception error){app.main.post(()->{if(stale(token))return;progress.setVisibility(View.GONE);Ui.toast(this,Ui.error(error));});}
    private boolean stale(int token){return token!=generation || isDestroyed() || isFinishing();}
    private void fail(int token,Exception e){app.main.post(()->{if(stale(token))return;progress.setVisibility(View.GONE);entries.clear();showRows();empty.setText(Ui.error(e)+"\n可点击刷新重试，或返回上一级");});}
    private void showRows(){
        empty.setText(showingMarks?"还没有标记的视频\n播放时点击“标记”即可加入这里":"这个目录没有可显示的视频或子目录");

        final boolean grid=app.store.thumbnailMode();list.setNumColumns(grid?2:1);

        final List<RemoteItem> visibleEntries=new ArrayList<>(entries);
        final List<MarkedItem> visibleMarks=new ArrayList<>(markedEntries);final boolean marked=showingMarks;final Map<String,Server> sources=new HashMap<>(markedSources);
        list.setAdapter(new BaseAdapter(){
            @Override public int getCount(){return visibleEntries.size();}
            @Override public Object getItem(int p){return visibleEntries.get(p);}
            @Override public long getItemId(int p){return p;}
            @Override public View getView(int p,View convert,ViewGroup parent){
                RemoteItem item=visibleEntries.get(p);
                Server rowSource=marked?sources.get(visibleMarks.get(p).serverId):server;
                String detail=item.directory?"文件夹":Ui.size(item.size);if(marked)detail=(rowSource==null?"来源不可用":rowSource.name)+" · "+detail;
                if(grid){
                    LinearLayout tile=Ui.column(MainActivity.this);Ui.pad(tile,6);
                    ImageView thumb=new ImageView(MainActivity.this){@Override protected void onMeasure(int w,int h){int width=View.MeasureSpec.getSize(w);super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(width*9/16,View.MeasureSpec.EXACTLY));}};
                    thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);thumb.setBackground(Ui.background(Ui.panel(MainActivity.this),14,MainActivity.this));thumb.setClipToOutline(true);thumb.setImageResource(item.directory?dev.flowstream.player.R.drawable.ic_folder:dev.flowstream.player.R.drawable.ic_launcher);thumb.setContentDescription(item.directory?"文件夹":item.name+" 缩略图");
                    tile.addView(thumb,new LinearLayout.LayoutParams(-1,-2));
                    TextView name=Ui.text(MainActivity.this,item.name,14,Ui.fg(MainActivity.this));name.setMaxLines(2);name.setMinLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(0,Ui.dp(MainActivity.this,6),0,0);tile.addView(name);
                    tile.addView(Ui.text(MainActivity.this,detail,11,0xff8d8d94));
                    if(!item.directory&&rowSource!=null){final int token=generation;app.thumbnails.load(rowSource,item,b->{if(!stale(token)&&b!=null)thumb.setImageBitmap(b);});}return tile;
                }
                return browserRow(item,detail,rowSource);
            }
        });
    }
    private void open(int pos){
        if(progress.getVisibility()==View.VISIBLE)return;
        if(showingMarks){openMarked(pos);return;}
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
    @Override protected void back(){if(showingMarks){showingMarks=false;reload();}else if(!folder.isEmpty()){folder=MediaPaths.parent(folder);reload();}else super.back();}
    @Override protected void onDestroy(){generation++;super.onDestroy();}
}
