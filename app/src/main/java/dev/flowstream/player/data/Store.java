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
    public boolean thumbnailMode(){return prefs.getBoolean("thumbnail_mode",false);}
    public void setThumbnailMode(boolean enabled){prefs.edit().putBoolean("thumbnail_mode",enabled).apply();}
    public boolean randomMode(){return prefs.getBoolean("random_mode",false);}
    public void setRandomMode(boolean enabled){prefs.edit().putBoolean("random_mode",enabled).apply();}
    public int preloadMb() { return Math.max(0,Math.min(50,prefs.getInt("preload_mb",10))); }
    public void setPreloadMb(int mb) { prefs.edit().putInt("preload_mb",Math.max(0,Math.min(50,mb))).apply(); }
    public float sourceSpeed(String id) { return prefs.getFloat("speed."+id,1f); }
    public boolean sourceNative(String id) { return prefs.getBoolean("native."+id,false); }
    public void saveSourcePlayback(String id,float speed,boolean nativeSize) { prefs.edit().putFloat("speed."+id,speed).putBoolean("native."+id,nativeSize).apply(); }
    public void bookmark(String server,String path,String name,long position) {
        try { org.json.JSONArray a=new org.json.JSONArray(prefs.getString("bookmarks","[]"));
            a.put(new org.json.JSONObject().put("server",server).put("path",path).put("name",name).put("position",position));
            prefs.edit().putString("bookmarks",a.toString()).apply();
        } catch(org.json.JSONException e) { throw new IllegalStateException(e); }
    }
    public org.json.JSONArray bookmarks() { try{return new org.json.JSONArray(prefs.getString("bookmarks","[]"));}catch(Exception e){return new org.json.JSONArray();} }
    public void removeBookmark(int index) { org.json.JSONArray a=bookmarks();a.remove(index);prefs.edit().putString("bookmarks",a.toString()).apply(); }
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
    public void deleteServer(String id) {
        getWritableDatabase().delete("servers", "id=?", new String[]{id});
        prefs.edit().remove("folder." + id).apply();
        if (activeServer().equals(id)) selectServer("");
    }
    public String newSession(Server server, String folder, List<RemoteItem> queue, int index) {
        if (queue.isEmpty() || queue.size() > 10000 || index < 0 || index >= queue.size()) throw new IllegalArgumentException("播放队列无效");
        String id = UUID.randomUUID().toString(); SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues(); v.put("id", id); v.put("server_id", server.id); v.put("server_name", server.name);
            v.put("folder", folder); v.put("idx", index); v.put("position", 0); v.put("duration", 0); v.put("speed", sourceSpeed(server.id)); v.put("updated", System.currentTimeMillis());
            db.insertOrThrow("sessions", null, v);
            for (int i = 0; i < queue.size(); i++) {
                RemoteItem item = queue.get(i); ContentValues q = new ContentValues();
                q.put("session_id", id); q.put("idx", i); q.put("path", item.path); q.put("name", item.name); q.put("size", item.size);
                db.insertOrThrow("queue_items", null, q);
            }
            db.execSQL("DELETE FROM sessions WHERE id NOT IN (SELECT id FROM sessions ORDER BY updated DESC LIMIT 100)");
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    public Session session(String id) throws IOException {
        Session s;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM sessions WHERE id=?", new String[]{id})) {
            if (!c.moveToFirst()) throw new IOException("会话已清除，请重新打开视频"); s = header(c);
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT path,name,size FROM queue_items WHERE session_id=? ORDER BY idx", new String[]{id})) {
            while (c.moveToNext()) s.queue.add(new RemoteItem(c.getString(0), c.getString(1), false, c.getLong(2)));
        }
        if (s.queue.isEmpty() || s.index < 0 || s.index >= s.queue.size()) throw new IOException("会话队列损坏");
        return s;
    }
    private Session header(Cursor c) {
        Session s = new Session(); s.id = c.getString(c.getColumnIndexOrThrow("id"));
        s.serverId = c.getString(c.getColumnIndexOrThrow("server_id")); s.serverName = c.getString(c.getColumnIndexOrThrow("server_name"));
        s.folder = c.getString(c.getColumnIndexOrThrow("folder")); s.index = c.getInt(c.getColumnIndexOrThrow("idx"));
        s.position = c.getLong(c.getColumnIndexOrThrow("position")); s.duration = c.getLong(c.getColumnIndexOrThrow("duration"));
        s.speed = SpeedScale.normalize(c.getFloat(c.getColumnIndexOrThrow("speed"))); s.updated = c.getLong(c.getColumnIndexOrThrow("updated")); return s;
    }
    public List<Session> history() {
        List<Session> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT s.*,q.path,q.name,q.size FROM sessions s JOIN queue_items q ON q.session_id=s.id AND q.idx=s.idx ORDER BY s.updated DESC LIMIT 100", null)) {
            while (c.moveToNext()) {
                Session s = header(c); s.queue.add(new RemoteItem(c.getString(c.getColumnIndexOrThrow("path")), c.getString(c.getColumnIndexOrThrow("name")), false, c.getLong(c.getColumnIndexOrThrow("size")))); result.add(s);
            }
        }
        return result;
    }
    public void checkpoint(String id, int index, long position, long duration, float speed) {
        ContentValues v = new ContentValues(); v.put("idx", index); v.put("position", Math.max(0, position));
        v.put("duration", Math.max(0, duration)); v.put("speed", SpeedScale.normalize(speed)); v.put("updated", System.currentTimeMillis());
        getWritableDatabase().update("sessions", v, "id=?", new String[]{id});
    }
    public void clearHistory() { getWritableDatabase().delete("sessions", null, null); }
    public void deleteSession(String id) { getWritableDatabase().delete("sessions", "id=?", new String[]{id}); }
}
