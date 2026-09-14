package dev.flowstream.player.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import dev.flowstream.player.FlowApp;
import dev.flowstream.core.GestureBindings;
import dev.flowstream.player.data.Server;
import dev.flowstream.player.net.Browsers;
import java.util.*;

public final class SettingsActivity extends BackActivity {
    private FlowApp app;
    private LinearLayout servers;
    private int generation;
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);app=(FlowApp)getApplication();
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Ui.bg(this));
        LinearLayout toolbar=Ui.row(this);toolbar.setBackgroundColor(Ui.panel(this));
        toolbar.addView(Ui.button(this,"返回",this::finish));toolbar.addView(Ui.text(this,"设置",20,Ui.TEXT));root.addView(toolbar);
        ScrollView scroll=new ScrollView(this);LinearLayout body=Ui.column(this);Ui.pad(body,16);scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body.addView(Ui.button(this,"主题 · "+(app.store.theme().equals("black")?"黑":app.store.theme().equals("white")?"白":"跟随系统"),()->new AlertDialog.Builder(this).setTitle("主题").setSingleChoiceItems(new String[]{"黑","白","跟随系统"},app.store.theme().equals("black")?0:app.store.theme().equals("white")?1:2,(d,which)->{app.store.setTheme(new String[]{"black","white","system"}[which]);d.dismiss();recreate();}).setNegativeButton("取消",null).show()));
        Button clearThumbs=Ui.button(this,"清除文件缩略图缓存",()->{});clearThumbs.setOnClickListener(v->{clearThumbs.setEnabled(false);app.thumbnails.clear(()->{if(!isDestroyed()){clearThumbs.setEnabled(true);Ui.toast(this,"文件缩略图缓存已清除");}});});body.addView(clearThumbs);
        body.addView(Ui.text(this,"缩略图缓存上限 128 MB。",12,Ui.MUTED));
        TextView preload=Ui.text(this,"下一条预读 · "+app.store.preloadMb()+" MB",17,Ui.TEXT);body.addView(preload);
        SeekBar preloadSize=new SeekBar(this);preloadSize.setMax(50);preloadSize.setProgress(app.store.preloadMb());preloadSize.setContentDescription("下一条预读大小，0 到 50 MB");body.addView(preloadSize);
        preloadSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){preload.setText("下一条预读 · "+(p==0?"关闭":p+" MB"));if(user)app.store.setPreloadMb(p);}
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        });
        body.addView(Ui.text(this,"0 为关闭；HLS / DASH 暂不支持。",12,Ui.MUTED));
        Switch memory=new Switch(this);memory.setText("预读保存在内存");memory.setChecked(app.store.memoryPreload());memory.setOnCheckedChangeListener((v,checked)->app.store.setMemoryPreload(checked));body.addView(memory);
        body.addView(Ui.text(this,"关闭时使用磁盘缓存。",12,Ui.MUTED));
        TextView timeout=Ui.text(this,timeoutLabel(app.store.hudTimeoutMs()),17,Ui.TEXT);body.addView(timeout);
        SeekBar timeoutSlider=new SeekBar(this);timeoutSlider.setMax(60);timeoutSlider.setProgress(app.store.hudTimeoutMs()/500);timeoutSlider.setContentDescription("菜单自动隐藏时间，0 到 30 秒，0 为常驻");body.addView(timeoutSlider);
        timeoutSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){timeout.setText(timeoutLabel(p*500));if(user)app.store.setHudTimeoutMs(p*500);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        body.addView(Ui.text(this,"分区手势",18,Ui.BLUE));
        body.addView(Ui.text(this,"缩放时仅平移和双击适屏。",12,Ui.MUTED));
        for(int zone=0;zone<3;zone++){
            body.addView(Ui.text(this,new String[]{"上区","中区","下区"}[zone],16,Ui.TEXT));
            addGestureSetting(body,zone,"tap","单击");addGestureSetting(body,zone,"double","双击");
            addGestureSetting(body,zone,"hold","长按");addGestureSetting(body,zone,"drag","左右拖动");
        }
        body.addView(Ui.text(this,"当前来源",18,Ui.BLUE));
        body.addView(Ui.text(this,"选择播放来源。",13,Ui.MUTED));servers=Ui.column(this);body.addView(servers);
        body.addView(Ui.button(this,"＋ 添加服务器",()->form(new Server())));
        TextView info=Ui.text(this,"WebDAV 支持 Basic 认证及匿名访问。",14,Ui.MUTED);info.setPadding(0,Ui.dp(this,18),0,Ui.dp(this,18));body.addView(info);
        body.addView(Ui.text(this,"FlowStream 0.8.1",13,Ui.MUTED));
        setContentView(root);Ui.safeInsets(this,root);Ui.systemBars(this);refresh();
    }
    private String timeoutLabel(int ms){return "菜单自动隐藏 · "+(ms==0?"常驻":String.format(Locale.ROOT,"%.1f 秒",ms/1000f));}
    private void addGestureSetting(LinearLayout body,int zone,String kind,String title){
        Button button=Ui.button(this,title+" · "+GestureBindings.label(app.store.gestureAction(zone,kind)),()->{});
        button.setOnClickListener(v->{String[] values=GestureBindings.actions(kind);String[] labels=new String[values.length];int selected=0;
            for(int i=0;i<values.length;i++){labels[i]=GestureBindings.label(values[i]);if(values[i].equals(app.store.gestureAction(zone,kind)))selected=i;}
            new AlertDialog.Builder(this).setTitle(new String[]{"上区","中区","下区"}[zone]+" · "+title).setSingleChoiceItems(labels,selected,(d,which)->{app.store.setGestureAction(zone,kind,values[which]);button.setText(title+" · "+labels[which]);d.dismiss();}).setNegativeButton("取消",null).show();
        });body.addView(button);
    }
    private void refresh(){final int token=++generation;app.disk.execute(()->{
        try{List<Server> result=app.store.servers();app.main.post(()->{
            if(token!=generation || isDestroyed())return;servers.removeAllViews();
            if(result.isEmpty())servers.addView(Ui.text(this,"尚未添加服务器",15,Ui.MUTED));
            for(Server s:result){LinearLayout card=Ui.column(this);Ui.pad(card,8);
                Button choose=Ui.button(this,(app.store.activeServer().equals(s.id)?"● ":"○ ")+s.name,()->{app.store.selectServer(s.id);refresh();});card.addView(choose);
                card.addView(Ui.text(this,s.description(),12,Ui.MUTED));LinearLayout actions=Ui.row(this);
                actions.addView(Ui.button(this,"编辑",()->app.disk.execute(()->{try{Server full=app.store.server(s.id);app.main.post(()->{if(!isDestroyed())form(full);});}catch(Exception e){app.main.post(()->Ui.toast(this,Ui.error(e)));}})));
                actions.addView(Ui.button(this,"删除",()->new AlertDialog.Builder(this).setTitle("删除服务器配置？").setMessage(s.name+"\n不会删除服务器内容；该来源将无法继续播放。")
                        .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->app.disk.execute(()->{app.store.deleteServer(s.id);app.main.post(this::refresh);})).show()));card.addView(actions);servers.addView(card);
            }
        });}catch(Exception e){app.main.post(()->Ui.toast(this,Ui.error(e)));}
    });}
    private EditText field(LinearLayout body,String label,String value,int type){
        body.addView(Ui.text(this,label,13,Ui.MUTED));EditText e=new EditText(this);e.setText(value);e.setSingleLine(true);e.setInputType(type);e.setTextColor(Ui.fg(this));e.setTextSize(16);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);body.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;
    }
    private void form(Server existing){
        ScrollView scroll=new ScrollView(this);LinearLayout body=Ui.column(this);Ui.pad(body,18);scroll.addView(body);
        EditText name=field(body,"名称",existing.name,InputType.TYPE_CLASS_TEXT);
        Spinner protocol=new Spinner(this);protocol.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"SMB / Samba","WebDAV"}));
        protocol.setSelection(existing.protocol.equals("SMB")?0:1);body.addView(protocol);
        LinearLayout smb=Ui.column(this);body.addView(smb);
        EditText host=field(smb,"主机 IP / 域名",existing.host,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        EditText port=field(smb,"端口",String.valueOf(existing.port),InputType.TYPE_CLASS_NUMBER);
        EditText share=field(smb,"共享名",existing.share,InputType.TYPE_CLASS_TEXT);
        EditText root=field(smb,"共享内根目录，可留空",existing.rootPath,InputType.TYPE_CLASS_TEXT);
        EditText domain=field(smb,"域 / 工作组，可留空",existing.domain,InputType.TYPE_CLASS_TEXT);
        CheckBox encrypt=new CheckBox(this);encrypt.setText("要求 SMB3 传输加密");encrypt.setChecked(existing.encryptSmb);smb.addView(encrypt);
        LinearLayout dav=Ui.column(this);body.addView(dav);
        EditText url=field(dav,"WebDAV 地址",existing.url,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        CheckBox http=new CheckBox(this);http.setText("允许明文 HTTP");http.setChecked(existing.allowHttp);dav.addView(http);
        EditText username=field(body,"用户名（匿名访问可留空）",existing.username,InputType.TYPE_CLASS_TEXT);
        EditText password=field(body,"密码",existing.password,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView status=Ui.text(this,"",12,Ui.MUTED);body.addView(status);
        Runnable visibility=()->{smb.setVisibility(protocol.getSelectedItemPosition()==0?View.VISIBLE:View.GONE);dav.setVisibility(protocol.getSelectedItemPosition()==1?View.VISIBLE:View.GONE);};visibility.run();
        protocol.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){visibility.run();}public void onNothingSelected(AdapterView<?> p){}});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("服务器配置").setView(scroll).setNegativeButton("取消",null).setNeutralButton("测试连接",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->{
            dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);
            View.OnClickListener perform=v->{
                final boolean save=v==dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                Server s=new Server();s.id=existing.id;s.name=name.getText().toString();s.protocol=protocol.getSelectedItemPosition()==0?"SMB":"WEBDAV";
                s.host=host.getText().toString();s.share=share.getText().toString();s.rootPath=root.getText().toString();s.domain=domain.getText().toString();s.url=url.getText().toString();
                s.username=username.getText().toString();s.password=password.getText().toString();s.allowHttp=http.isChecked();s.encryptSmb=encrypt.isChecked();
                try{s.port=s.protocol.equals("SMB")?Integer.parseInt(port.getText().toString()):445;s.validate();}catch(Exception e){status.setText(Ui.error(e));return;}
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);status.setText(save?"正在保存…":"正在连接…");
                Runnable task=()->{try{String message;if(save){app.store.saveServer(s);message="服务器已保存";}else{message="连接成功，项目数："+Browsers.list(s,"").size();}
                    app.main.post(()->{if(isDestroyed() || !dialog.isShowing())return;status.setText(message);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);if(save){dialog.dismiss();refresh();}});
                }catch(Exception e){app.main.post(()->{if(isDestroyed() || !dialog.isShowing())return;status.setText(Ui.error(e));dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);});}};
                if(save)app.disk.execute(task);else app.io.execute(task);
            };
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(perform);dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(perform);
        });dialog.show();
    }
    @Override protected void onDestroy(){generation++;super.onDestroy();}
}
