package dev.flowstream.player.net;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Uses isolated app directories; never clears the user's actual video cache. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class CacheClearChecks {
    public static void run(Context base)throws Exception{
        File fixture=new File(base.getCacheDir(),"cache-clear-check-"+System.nanoTime());
        Context isolated=new ContextWrapper(base){
            public Context getApplicationContext(){return this;}
            public File getFilesDir(){File f=new File(fixture,"files");f.mkdirs();return f;}
            public File getCacheDir(){File f=new File(fixture,"cache");f.mkdirs();return f;}
            public File getDatabasePath(String name){File f=new File(fixture,"databases");f.mkdirs();return new File(f,name);}
            public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory){return android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
            public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,int mode,android.database.sqlite.SQLiteDatabase.CursorFactory factory,android.database.DatabaseErrorHandler handler){return android.database.sqlite.SQLiteDatabase.openDatabase(getDatabasePath(name).getPath(),factory,android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY,handler);}
        };
        SimpleCache cache=NextVideoCache.cache(isolated);
        String key="cache-clear-fixture";CacheSpan hole=cache.startReadWrite(key,0,4);
        File span=cache.startFile(key,0,4);
        try(FileOutputStream out=new FileOutputStream(span)){out.write(new byte[]{1,2,3,4});}
        cache.commitFile(span,4);cache.releaseHoleSpan(hole);
        if(cache.getCacheSpace()!=4)throw new AssertionError("fixture span did not persist");
        if(!span.getCanonicalPath().startsWith(new File(isolated.getFilesDir(),"video-cache").getCanonicalPath()+File.separator))throw new AssertionError("video cache must use persistent files directory");
        File legacy=new File(isolated.getCacheDir(),"next-video");legacy.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(legacy,"legacy-part"))){out.write(7);}
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
        NextVideoCache.clear(isolated,failure->{if(failure!=null)error.set(failure);if(Looper.myLooper()!=Looper.getMainLooper())error.set(new AssertionError("clear callback must run on main"));done.countDown();});
        if(!done.await(10,TimeUnit.SECONDS))throw new AssertionError("clear callback timeout");
        if(error.get()!=null)throw new AssertionError("clear failed",error.get());
        if(cache.getCacheSpace()!=0||!cache.getKeys().isEmpty()||span.exists()||legacy.exists())throw new AssertionError("disk spans and legacy cache must be removed");
        // The isolated cache object is not reused; release native locks and delete only this fixture.
        cache.release();
        try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(fixture.toPath())){
            java.util.Iterator<java.nio.file.Path> entries=paths.sorted(java.util.Comparator.reverseOrder()).iterator();
            while(entries.hasNext())java.nio.file.Files.deleteIfExists(entries.next());
        }
    }
}
