package com.topshelf.launcher;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a YouTube stream for the trailer player.
 *  - A video or sound stream is read in 10 MB pieces, each asked for with YouTube's own
 *    "&range=start-end" (as Nuvio does): YouTube slows down, then cuts, a connection that tries to
 *    read a whole stream in one go, but serves pieces at full speed.
 *  - When the stream's address was got over IPv4 (YtStreams.v4), it's read over IPv4 too (V4Http):
 *    YouTube's addresses only work from the address that asked for them.
 * Anything else is read as it is.
 */
final class YtChunks extends BaseDataSource {
    static final long CHUNK = 10L * 1024 * 1024;

    static final class Factory implements DataSource.Factory {
        @Override public DataSource createDataSource() { return new YtChunks(); }
    }

    private DefaultHttpDataSource plain;      // anything not on YouTube's servers (or not over IPv4)
    private InputStream in;
    private V4Http.Resp resp;
    private Uri uri;
    private boolean opened;
    private long left;                         // bytes still to give (C.LENGTH_UNSET: until the end)
    // the piece-at-a-time way, over Android's own connections
    private DataSpec spec; private long start, end, inChunk; private boolean chunking;

    YtChunks() { super(true); }

    private DefaultHttpDataSource plain() {
        if (plain == null) plain = new DefaultHttpDataSource.Factory().setConnectTimeoutMs(10000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(true).createDataSource();
        return plain;
    }

    @Override public long open(DataSpec ds) throws IOException {
        uri = ds.uri;
        String host = ds.uri.getHost();
        boolean yt = host != null && host.contains("googlevideo.com");
        boolean stream = yt && "/videoplayback".equals(ds.uri.getPath());
        long clen = -1;
        try { String c = ds.uri.getQueryParameter("clen"); if (c != null) clen = Long.parseLong(c); } catch (Throwable ignored) { }
        if (yt && YtStreams.v4) {
            transferInitializing(ds);
            if (stream) {
                long total = clen > 0 ? clen : YtStreams.sizeOf(ds.uri.toString());
                if (total <= 0) throw new HttpDataSource.HttpDataSourceException("size of the stream unknown", ds, androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSource.HttpDataSourceException.TYPE_OPEN);
                long a = ds.position, b = ds.length != C.LENGTH_UNSET ? Math.min(total - 1, a + ds.length - 1) : total - 1;
                in = new YtStreams.Pieces(ds.uri.toString(), a, b);
                left = b - a + 1;
            } else {
                // YouTube's adaptive list and its parts: one plain request (over IPv4)
                Map<String, String> hd = new LinkedHashMap<>();
                if (ds.position > 0 || ds.length != C.LENGTH_UNSET) hd.put("Range", "bytes=" + ds.position + "-" + (ds.length != C.LENGTH_UNSET ? String.valueOf(ds.position + ds.length - 1) : ""));
                resp = V4Http.open(ds.uri.toString(), "GET", hd, null, 15000, false);
                if (resp.code >= 400) { int c = resp.code; resp.close(); resp = null; throw new HttpDataSource.InvalidResponseCodeException(c, "", null, Collections.<String, List<String>>emptyMap(), ds, new byte[0]); }
                in = resp.body;
                String cl = resp.header("content-length");
                left = cl != null ? Long.parseLong(cl) : ds.length;
            }
            opened = true;
            transferStarted(ds);
            return left;
        }
        if (!stream) { chunking = false; long n = plain().open(ds); opened = true; return n; }
        // pieces over Android's own connections
        chunking = true;
        spec = ds; start = ds.position; left = ds.length;
        if (left == C.LENGTH_UNSET && clen > 0) left = Math.max(0, clen - start);
        next();
        opened = true;
        return left;
    }

    private void next() throws IOException {
        end = left != C.LENGTH_UNSET ? Math.min(start + CHUNK - 1, start + left - 1) : start + CHUNK - 1;
        Uri u = spec.uri.buildUpon().appendQueryParameter("range", start + "-" + end).build();
        DataSpec piece = spec.buildUpon().setUri(u).setPosition(0).setLength(C.LENGTH_UNSET).build();
        inChunk = 0;
        plain().open(piece);
    }

    @Override public int read(byte[] buf, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (in != null) {
            if (left == 0) return C.RESULT_END_OF_INPUT;
            int n = in.read(buf, off, left == C.LENGTH_UNSET ? len : (int) Math.min(len, left));
            if (n < 0) return C.RESULT_END_OF_INPUT;
            if (left != C.LENGTH_UNSET) left -= n;
            bytesTransferred(n);
            return n;
        }
        if (!chunking) return plain().read(buf, off, len);
        if (left == 0) return C.RESULT_END_OF_INPUT;
        int n = plain().read(buf, off, len);
        if (n == C.RESULT_END_OF_INPUT) {
            long got = inChunk;
            plain().close();
            if (got < end - start + 1) return C.RESULT_END_OF_INPUT;     // a short piece: that was the end
            start += got;
            if (left != C.LENGTH_UNSET) { left -= got; if (left <= 0) return C.RESULT_END_OF_INPUT; }
            try { next(); } catch (IOException e) { return C.RESULT_END_OF_INPUT; }
            n = plain().read(buf, off, len);
            if (n == C.RESULT_END_OF_INPUT) return n;
        }
        inChunk += n;
        return n;
    }

    @Override public Uri getUri() { return in != null ? uri : plain != null && plain.getUri() != null ? plain.getUri() : uri; }

    @Override public void close() throws IOException {
        boolean v4 = in != null;
        try { if (in != null) in.close(); } catch (Throwable ignored) { }
        if (resp != null) resp.close();
        in = null; resp = null; spec = null;
        if (plain != null) plain.close();
        if (opened && v4) transferEnded();
        opened = false;
    }
}
