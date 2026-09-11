package dev.flowstream.player.net;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.*;
import com.hierynomus.smbj.share.File;
import dev.flowstream.core.MediaPaths;
import dev.flowstream.player.data.Server;
import java.io.EOFException;
import java.io.IOException;
import java.util.EnumSet;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class SmbDataSource extends BaseDataSource {
    private final Server server;
    private final SmbPool pool;
    private final byte[] ahead = new byte[256 * 1024];
    private int aheadOffset, aheadLength;
    private SmbConnection connection;
    private File file;
    private Uri uri;
    private long position, remaining;
    private boolean opened;
    public SmbDataSource(Server s, SmbPool pool) { super(true); this.server = s; this.pool = pool; }
    @Override public long open(DataSpec spec) throws IOException {
        transferInitializing(spec);
        try {
            if (!"smbx".equals(spec.uri.getScheme()) || !server.id.equals(spec.uri.getHost())) throw new IOException("SMB 来源不匹配");
            String path = MediaPaths.normalize(spec.uri.getPath());
            connection = pool.acquire();
            file = connection.share.openFile(server.smbPath(path),
                    EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.allOf(SMB2ShareAccess.class),
                    SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
            long size = file.getFileInformation(FileStandardInformation.class).getEndOfFile();
            if (spec.position < 0 || spec.position > size) throw new EOFException("读取位置超出文件末尾");
            uri = spec.uri; position = spec.position; remaining = size - position;
            if (spec.length != C.LENGTH_UNSET) remaining = Math.min(remaining, spec.length);
            aheadOffset = aheadLength = 0;
            opened = true; transferStarted(spec); return remaining;
        } catch (Exception e) { cleanupFile(); if (connection != null) pool.invalidate(connection); throw new IOException("SMB 打开视频失败", e); }
    }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (!opened || file == null) throw new IOException("数据源尚未打开");
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        try {
            if (aheadOffset == aheadLength) {
                aheadOffset = 0;
                aheadLength = file.read(ahead, position, 0, (int) Math.min(ahead.length, remaining));
                if (aheadLength <= 0) throw new EOFException("SMB 文件提前结束或连接中断");
            }
            int n = (int) Math.min(Math.min(length, aheadLength - aheadOffset), remaining);
            System.arraycopy(ahead, aheadOffset, buffer, offset, n);
            aheadOffset += n; position += n; remaining -= n; bytesTransferred(n); return n;
        } catch (Exception e) { cleanupFile(); pool.invalidate(connection); throw new IOException("SMB 读取中断", e); }
    }
    @Nullable @Override public Uri getUri() { return uri; }
    @Override public void close() {
        cleanupFile(); uri = null; aheadOffset = aheadLength = 0;
        if (opened) { opened = false; transferEnded(); }
    }
    private void cleanupFile() { if (file != null) { try { file.close(); } catch (Exception ignored) { } file = null; } }
}
