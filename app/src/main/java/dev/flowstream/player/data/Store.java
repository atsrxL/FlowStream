package dev.flowstream.player.data;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import dev.flowstream.core.*;
import org.json.*;
import java.io.IOException;
import java.util.*;

/** Call database methods on FlowApp.disk (single writer); no network work in this class. */
public final class Store extends SQLiteOpenHelper {
    private final SharedPreferences prefs;
    private final Vault vault = new Vault();
    public Store(Context c) { super(c, "flowstream.db", null, 1); prefs = c.getSharedPreferences("config", Context.MODE_PRIVATE); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE servers (id TEXT PRIMARY KEY, config TEXT NOT NULL)");
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, server_id TEXT NOT NULL, server_name TEXT NOT NULL, folder TEXT NOT NULL, idx INTEGER NOT NULL, position INTEGER NOT NULL, duration INTEGER NOT NULL, speed REAL NOT NULL, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE queue_items (session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, idx INTEGER NOT NULL, path TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, PRIMARY KEY(session_id,idx))");
        db.execSQL("CREATE INDEX session_updated ON sessions(updated DESC)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { throw new IllegalStateException("数据库迁移尚未定义"); }
    public synchronized List<MarkedItem> markedItems(){
        List<MarkedItem> result=new ArrayList<>();
        try{JSONArray rows=new JSONArray(prefs.getString("marked_media","[]"));
            for(int i=0;i<rows.length();i++){try{JSONObject row=rows.getJSONObject(i);result.add(new MarkedItem(row.getString("server"),new RemoteItem(row.getString("path"),row.optString("name"),false,row.optLong("size")),row.optLong("time")));}catch(JSONException|IllegalArgumentException ignored){}}
        }catch(JSONException ignored){}
        return result;
    }
    public synchronized boolean isMarked(String serverId,String path){for(MarkedItem mark:markedItems())if(mark.serverId.equals(serverId)&&mark.item.path.equals(MediaPaths.normalize(path)))return true;return false;}
    public synchronized boolean toggleMarked(String serverId,RemoteItem item){
        if(item.directory)throw new IllegalArgumentException("只能标记视频");
        List<MarkedItem> marks=markedItems();boolean removed=marks.removeIf(mark->mark.serverId.equals(serverId)&&mark.item.path.equals(item.path));
        if(!removed)marks.add(0,new MarkedItem(serverId,item,System.currentTimeMillis()));
        saveMarkedItems(marks);return !removed;
    }
    public synchronized void removeMarked(String serverId,String path){List<MarkedItem> marks=markedItems();marks.removeIf(mark->mark.serverId.equals(serverId)&&mark.item.path.equals(MediaPaths.normalize(path)));saveMarkedItems(marks);}
    private void saveMarkedItems(List<MarkedItem> marks){
        JSONArray rows=new JSONArray();for(MarkedItem mark:marks){JSONObject row=new JSONObject();try{row.put("server",mark.serverId).put("path",mark.item.path).put("name",mark.item.name).put("size",mark.item.size).put("time",mark.markedAt);}catch(JSONException impossible){throw new IllegalStateException(impossible);}rows.put(row);}
        prefs.edit().putString("marked_media",rows.toString()).apply();
    }
    public boolean thumbnailMode(){return prefs.getBoolean("thumbnail_mode",false);}
    public void setThumbnailMode(boolean enabled){prefs.edit().putBoolean("thumbnail_mode",enabled).apply();}
    public boolean randomMode(){return prefs.getBoolean("random_mode",false);}
    public void setRandomMode(boolean enabled){prefs.edit().putBoolean("random_mode",enabled).apply();}
    public int preloadMb() { return Math.max(50,Math.min(3072,prefs.getInt("preload_mb",50))); }
    public void setPreloadMb(int mb) { prefs.edit().putInt("preload_mb",Math.max(50,Math.min(3072,mb))).apply(); }
    public String gestureAction(int zone,String kind) { return GestureBindings.normalize(zone,kind,prefs.getString("gesture9."+(zone/3)+"."+(zone%3)+"."+kind,GestureBindings.defaultAction(zone,kind))); }
    public void setGestureAction(int zone,String kind,String action) { prefs.edit().putString("gesture9."+(zone/3)+"."+(zone%3)+"."+kind,GestureBindings.normalize(zone,kind,action)).apply(); }
    public int hudTimeoutMs() { return Math.max(1000,Math.min(10000,prefs.getInt("hud_timeout_ms",3500))); }
    public void setHudTimeoutMs(int value) { prefs.edit().putInt("hud_timeout_ms",Math.max(1000,Math.min(10000,value))).apply(); }
    public boolean memoryPreload() { return prefs.getBoolean("memory_preload",false); }
    public void setMemoryPreload(boolean enabled) { prefs.edit().putBoolean("memory_preload",enabled).apply(); }
    public float globalSpeed() { return SpeedScale.normalize(prefs.getFloat("global_speed",1f)); }
    public void setGlobalSpeed(float value) { prefs.edit().putFloat("global_speed",SpeedScale.normalize(value)).apply(); }
    public float sourceSpeed(String id) { return globalSpeed(); }
    public int sourcePreset(String id){return Math.max(0,Math.min(2,prefs.getInt("preset."+id,prefs.getBoolean("native."+id,false)?2:0)));}
    public void saveSourcePreset(String id,int preset){prefs.edit().putInt("preset."+id,preset).putBoolean("native."+id,preset==2).apply();}
    public boolean sourceNative(String id) { return prefs.getBoolean("native."+id,false); }
    public void saveSourcePlayback(String id,float speed,boolean nativeSize) { prefs.edit().putFloat("global_speed",SpeedScale.normalize(speed)).putBoolean("native."+id,nativeSize).apply(); }
    public String theme(){return prefs.getString("theme","system");}
    public void setTheme(String value){prefs.edit().putString("theme",value).apply();}
    @Override public void onOpen(SQLiteDatabase db){super.onOpen(db);db.delete("sessions",null,null);prefs.edit().remove("bookmarks").apply();}
    private final Map<String,Session> activeSessions=new HashMap<>();
    public String activeServer() { return prefs.getString("active_server", ""); }
    public void selectServer(String id) { prefs.edit().putString("active_server", id).apply(); }
    public String lastFolder(String id) { return prefs.getString("folder." + id, ""); }
    public void setLastFolder(String id, String folder) { prefs.edit().putString("folder." + id, folder).apply(); }
    public List<Server> servers() throws Exception {
        List<Server> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,config FROM servers ORDER BY rowid", null)) {
            while (c.moveToNext()) list.add(decode(c.getString(0), c.getString(1), false));
        }
        return list;
    }
    public Server server(String id) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,config FROM servers WHERE id=?", new String[]{id})) {
            if (!c.moveToFirst()) throw new IOException("服务器已删除，请在设置中重新选择");
            return decode(c.getString(0), c.getString(1), true);
        }
    }
    private Server decode(String id, String json, boolean password) throws Exception {
        JSONObject j = new JSONObject(json); Server s = new Server(); s.id = id;
        s.name = j.getString("name"); s.protocol = j.getString("protocol");
        s.host = j.optString("host"); s.port = j.optInt("port", 445); s.share = j.optString("share");
        s.rootPath = j.optString("root"); s.url = j.optString("url"); s.username = j.optString("username");
        s.domain = j.optString("domain"); s.allowHttp = j.optBoolean("http"); s.encryptSmb = j.optBoolean("encrypt");
        if (password) {
            try { s.password = vault.open(id, j.getString("password")); }
            catch (Exception e) { throw new IOException("服务器密码无法解密；请删除该配置后重新添加", e); }
        }
        return s;
    }
    public void saveServer(Server s) throws Exception {
        s.validate(); JSONObject j = new JSONObject();
        j.put("name", s.name).put("protocol", s.protocol).put("host", s.host).put("port", s.port)
                .put("share", s.share).put("root", s.rootPath).put("url", s.url).put("username", s.username)
                .put("domain", s.domain).put("http", s.allowHttp).put("encrypt", s.encryptSmb)
                .put("password", vault.seal(s.id, s.password));
        ContentValues v = new ContentValues(); v.put("id", s.id); v.put("config", j.toString());
        if (getWritableDatabase().insertWithOnConflict("servers", null, v, SQLiteDatabase.CONFLICT_REPLACE) == -1)
            throw new IOException("保存服务器失败，请检查本机可用存储空间");
        if (activeServer().isEmpty()) selectServer(s.id);
    }
    public synchronized void deleteServer(String id) {
        List<MarkedItem> marks=markedItems();marks.removeIf(mark->mark.serverId.equals(id));saveMarkedItems(marks);
        getWritableDatabase().delete("servers", "id=?", new String[]{id});
        prefs.edit().remove("folder." + id).apply();
        if (activeServer().equals(id)) selectServer("");
    }
    public synchronized String newSession(Server server,String folder,List<RemoteItem> queue,int index){
        if(queue.isEmpty()||index<0||index>=queue.size())throw new IllegalArgumentException("播放队列无效");
        Session s=new Session();s.id=UUID.randomUUID().toString();s.serverId=server.id;s.serverName=server.name;s.folder=folder;s.queue=new ArrayList<>(queue);s.index=index;s.speed=sourceSpeed(server.id);
        activeSessions.clear();activeSessions.put(s.id,s);return s.id;
    }
    public synchronized Session session(String id)throws IOException{
        Session s=activeSessions.get(id);if(s==null)throw new IOException("播放队列已失效，请重新打开视频");Session copy=new Session();copy.id=s.id;copy.serverId=s.serverId;copy.serverName=s.serverName;copy.folder=s.folder;copy.queue=new ArrayList<>(s.queue);copy.index=s.index;copy.position=s.position;copy.duration=s.duration;copy.speed=s.speed;return copy;
    }
    public synchronized void checkpoint(String id,int index,long position,long duration,float speed){
        Session s=activeSessions.get(id);if(s!=null){s.index=index;s.position=Math.max(0,position);s.duration=duration;s.speed=speed;}
    }
    public synchronized Session removeMedia(String id,String path)throws IOException{
        Session s=activeSessions.get(id);if(s==null)return null;
        String current=s.current().path;int before=s.index;s.queue.removeIf(item->item.path.equals(path));
        if(s.queue.isEmpty()){activeSessions.remove(id);return null;}
        if(current.equals(path)){s.index=Math.min(before,s.queue.size()-1);s.position=s.duration=0;}
        else{for(int i=0;i<s.queue.size();i++)if(s.queue.get(i).path.equals(current)){s.index=i;break;}}
        return session(id);
    }
    public synchronized void deleteSession(String id){activeSessions.remove(id);}
}
