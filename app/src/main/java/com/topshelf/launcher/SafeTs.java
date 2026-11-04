package com.topshelf.launcher;

import android.net.Uri;
import android.util.SparseArray;

import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;
import androidx.media3.extractor.ts.TsPayloadReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MPEG-TS reading that survives a damaged frame. Some channels (ESPN U) now and then send an audio
 * (AC-3) frame whose header is garbage; the player's reader trips over it and the whole stream stops
 * with an error. Here each track's reader is wrapped: a frame it can't make sense of is dropped and
 * the reader picks up again at the next packet start, so you get a blip instead of an error.
 */
final class SafeTs implements ExtractorsFactory {
    static volatile int dropped;          // damaged frames skipped (shown in Stream info)

    private final int flags, searchBytes;
    private final DefaultExtractorsFactory rest;

    SafeTs(int tsFlags, int searchBytes) {
        flags = tsFlags; this.searchBytes = searchBytes;
        rest = new DefaultExtractorsFactory().setTsExtractorFlags(tsFlags).setTsExtractorTimestampSearchBytes(searchBytes);
    }

    @Override public Extractor[] createExtractors() { return withTs(rest.createExtractors()); }
    @Override public Extractor[] createExtractors(Uri uri, Map<String, List<String>> headers) { return withTs(rest.createExtractors(uri, headers)); }

    private Extractor[] withTs(Extractor[] others) {
        List<Extractor> out = new ArrayList<>();
        out.add(ts());
        for (Extractor e : others) if (!(e instanceof TsExtractor)) out.add(e);
        return out.toArray(new Extractor[0]);
    }

    @SuppressWarnings("deprecation")
    private Extractor ts() {
        return new TsExtractor(TsExtractor.MODE_SINGLE_PMT, new TimestampAdjuster(0), new Factory(new DefaultTsPayloadReaderFactory(flags)), searchBytes);
    }

    /** the same for HLS (a channel's HLS version carries the same damaged frames in its TS segments) */
    static androidx.media3.exoplayer.hls.HlsExtractorFactory hls() { return new Hls(); }

    private static final class Hls implements androidx.media3.exoplayer.hls.HlsExtractorFactory {
        private final androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory rest = new androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory();
        @SuppressWarnings("deprecation")
        @Override public androidx.media3.exoplayer.hls.HlsMediaChunkExtractor createExtractor(Uri uri, androidx.media3.common.Format format,
                List<androidx.media3.common.Format> captions, TimestampAdjuster ta, Map<String, List<String>> headers,
                androidx.media3.extractor.ExtractorInput input, androidx.media3.exoplayer.analytics.PlayerId playerId) throws java.io.IOException {
            boolean ts = false;
            try { ts = new TsExtractor().sniff(input); } catch (java.io.EOFException ignored) { } finally { input.resetPeekPosition(); }
            if (!ts) return rest.createExtractor(uri, format, captions, ta, headers, input, playerId);
            // as the library's own HLS set-up does it, with each track's reader guarded
            int fl = DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM;
            List<androidx.media3.common.Format> cc;
            if (captions != null) { fl |= DefaultTsPayloadReaderFactory.FLAG_OVERRIDE_CAPTION_DESCRIPTORS; cc = captions; }
            else cc = java.util.Collections.singletonList(new androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.APPLICATION_CEA608).build());
            String codecs = format.codecs;
            if (codecs != null && !codecs.isEmpty()) {
                if (!androidx.media3.common.MimeTypes.containsCodecsCorrespondingToMimeType(codecs, androidx.media3.common.MimeTypes.AUDIO_AAC)) fl |= DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM;
                if (!androidx.media3.common.MimeTypes.containsCodecsCorrespondingToMimeType(codecs, androidx.media3.common.MimeTypes.VIDEO_H264)) fl |= DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM;
            }
            TsExtractor x = new TsExtractor(TsExtractor.MODE_HLS, TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, androidx.media3.extractor.text.SubtitleParser.Factory.UNSUPPORTED,
                    ta, new Factory(new DefaultTsPayloadReaderFactory(fl, cc)), TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES);
            return new androidx.media3.exoplayer.hls.BundledHlsMediaChunkExtractor(x, format, ta);
        }
    }

    private static final class Factory implements TsPayloadReader.Factory {
        private final TsPayloadReader.Factory in;
        Factory(TsPayloadReader.Factory in) { this.in = in; }
        @Override public SparseArray<TsPayloadReader> createInitialPayloadReaders() { return in.createInitialPayloadReaders(); }
        @Override public TsPayloadReader createPayloadReader(int streamType, TsPayloadReader.EsInfo esInfo) {
            TsPayloadReader r = in.createPayloadReader(streamType, esInfo);
            return r == null ? null : new Guard(r);
        }
    }

    private static final class Guard implements TsPayloadReader {
        private final TsPayloadReader in;
        Guard(TsPayloadReader in) { this.in = in; }
        @Override public void init(TimestampAdjuster ta, ExtractorOutput out, TrackIdGenerator ids) { in.init(ta, out, ids); }
        @Override public void seek() { in.seek(); }
        @Override public void consume(ParsableByteArray data, int fl) throws ParserException {
            try { in.consume(data, fl); }
            catch (RuntimeException e) {          // a damaged frame: skip to the next packet start
                dropped++;
                try { in.seek(); } catch (RuntimeException ignored) { }
                data.setPosition(data.limit());
            }
        }
    }
}
