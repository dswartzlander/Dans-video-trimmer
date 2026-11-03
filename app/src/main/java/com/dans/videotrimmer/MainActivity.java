package com.dans.videotrimmer;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.graphics.Color;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.media.MediaFormat;
import android.media.MediaCodec;
import android.view.View;
import android.widget.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
  private Uri source;
  private VideoView player;
  private SeekBar from, to;
  private TextView info, queueView;
  private long duration;
  private final ArrayList<long[]> ranges = new ArrayList<>();
  private boolean busy = false;
  private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(24, 24, 24, 24);
    layout.setBackgroundColor(Color.rgb(17,20,29));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(layout);
    setContentView(scroll);
    TextView title = label("DAN'S VIDEO TRIMMER");
    title.setTextSize(24);
    layout.addView(title);
    Button open = button("OPEN VIDEO", layout);
    player = new VideoView(this);
    layout.addView(player, new LinearLayout.LayoutParams(-1, 500));
    button("PLAY / PAUSE", layout).setOnClickListener(v -> {
      if (source == null) return;
      if (player.isPlaying()) player.pause(); else player.start();
    });
    layout.addView(label("START"));
    from = new SeekBar(this); from.setMax(1000); layout.addView(from);
    layout.addView(label("END"));
    to = new SeekBar(this); to.setMax(1000); to.setProgress(1000); layout.addView(to);
    info = label("Choose a video to begin"); layout.addView(info);
    queueView = label("No clips queued"); layout.addView(queueView);
    button("ADD SELECTED CLIP TO LIST", layout).setOnClickListener(v -> {
      if (source == null || busy) return;
      long[] r = selected();
      if (r[1] <= r[0]) { info.setText("End must be after start"); return; }
      ranges.add(r);
      queueView.setText(ranges.size() + " clips queued");
    });
    button("CLEAR CLIP LIST", layout).setOnClickListener(v -> {
      if (!busy) { ranges.clear(); queueView.setText("No clips queued"); }
    });
    button("EXPORT SELECTED CLIP OR QUEUED CLIPS", layout).setOnClickListener(v -> export());
    layout.addView(label("Saved to Movies/Dans Video Trimmer. Original is preserved. Cuts align to video keyframes."));
    open.setOnClickListener(v -> {
      if (busy) return;
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("video/*");
      startActivityForResult(i, 1);
    });
    SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
      public void onProgressChanged(SeekBar bar,int value,boolean user) {
        if (from.getProgress() > to.getProgress()) {
          if (bar == from) to.setProgress(value); else from.setProgress(value);
        }
        if (duration > 0) info.setText("Selected: " + fmt(selected()[0]) + " - " + fmt(selected()[1]));
      }
      public void onStartTrackingTouch(SeekBar bar) {}
      public void onStopTrackingTouch(SeekBar bar) {
        if (source != null) player.seekTo((int)selected()[0]);
      }
    };
    from.setOnSeekBarChangeListener(listener);
    to.setOnSeekBarChangeListener(listener);
    player.setOnPreparedListener(mp -> {
      duration = Math.max(1, mp.getDuration());
      from.setProgress(0); to.setProgress(1000);
      info.setText("Video loaded: " + fmt(duration));
    });
  }

  private TextView label(String s) {
    TextView t = new TextView(this);
    t.setText(s); t.setTextColor(Color.WHITE); t.setTextSize(16);
    t.setPadding(0,12,0,12); return t;
  }
  private Button button(String s,LinearLayout parent) {
    Button b = new Button(this); b.setText(s); parent.addView(b); return b;
  }
  private String fmt(long ms) { return String.format(java.util.Locale.US,"%02d:%02d.%03d",ms/60000,(ms/1000)%60,ms%1000); }
  private long[] selected() { return new long[]{duration*from.getProgress()/1000,duration*to.getProgress()/1000}; }

  @Override protected void onActivityResult(int request,int result,Intent data) {
    super.onActivityResult(request,result,data);
    if (request == 1 && result == RESULT_OK && data != null) {
      source = data.getData(); duration = 0; ranges.clear();
      queueView.setText("No clips queued");
      player.setVideoURI(source);
      player.requestFocus();
    }
  }

  private void export() {
    if (busy || source == null || duration <= 0) return;
    ArrayList<long[]> jobs = new ArrayList<>();
    if (ranges.isEmpty()) jobs.add(selected()); else jobs.addAll(ranges);
    for (long[] r:jobs) if (r[1] <= r[0]) { info.setText("Invalid clip range"); return; }
    busy = true;
    info.setText("Exporting " + jobs.size() + " clip(s)...");
    executor.execute(() -> {
      int success = 0;
      String error = "";
      for (long[] r:jobs) {
        try { saveClip(r[0],r[1],success+1); success++; }
        catch (Exception e) { error = e.getMessage(); break; }
      }
      final int count = success;
      final String failure = error;
      runOnUiThread(() -> {
        busy = false;
        info.setText(failure.isEmpty() ? "Saved " + count + " clip(s) to Movies/Dans Video Trimmer" : "Saved " + count + " clip(s). Error: " + failure);
      });
    });
  }

  private void saveClip(long startMs,long endMs,int number) throws Exception {
    MediaExtractor extractor = new MediaExtractor();
    Uri outputUri = null;
    MediaMuxer muxer = null;
    boolean started = false;
    try {
      extractor.setDataSource(this,source,null);
      int tracks = extractor.getTrackCount();
      int[] indexes = new int[tracks];
      java.util.Arrays.fill(indexes,-1);
      ContentValues values = new ContentValues();
      values.put(MediaStore.Video.Media.DISPLAY_NAME,"DansClip_"+System.currentTimeMillis()+"_"+number+".mp4");
      values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
      values.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Dans Video Trimmer");
      values.put(MediaStore.Video.Media.IS_PENDING,1);
      outputUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);
      if (outputUri == null) throw new Exception("Unable to create output");
      android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(outputUri,"w");
      if (fd == null) throw new Exception("Unable to open output");
      try {
        muxer = new MediaMuxer(fd.getFileDescriptor(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int maxSize = 1024*1024;
        int selectedCount = 0;
        for (int i=0;i<tracks;i++) {
          MediaFormat f = extractor.getTrackFormat(i);
          String mime = f.getString(MediaFormat.KEY_MIME);
          if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
            indexes[i] = muxer.addTrack(f); selectedCount++;
            if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
              maxSize = Math.max(maxSize,f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
          }
        }
        if (selectedCount == 0) throw new Exception("No audio/video tracks");
        muxer.start(); started = true;
        extractor.seekTo(startMs*1000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        long baseUs = -1;
        ByteBuffer buffer = ByteBuffer.allocateDirect(Math.min(Math.max(maxSize,1024*1024),32*1024*1024));
        MediaCodec.BufferInfo sampleInfo = new MediaCodec.BufferInfo();
        while (true) {
          int track = extractor.getSampleTrackIndex();
          if (track < 0) break;
          long time = extractor.getSampleTime();
          if (time < 0 || time >= endMs*1000) break;
          int size = extractor.readSampleData(buffer,0);
          if (size < 0) break;
          if (indexes[track] >= 0) {
            if (baseUs < 0) baseUs = time;
            sampleInfo.offset=0; sampleInfo.size=size;
            sampleInfo.presentationTimeUs=Math.max(0,time-baseUs);
            sampleInfo.flags=extractor.getSampleFlags();
            muxer.writeSampleData(indexes[track],buffer,sampleInfo);
          }
          extractor.advance();
        }
        muxer.stop(); started=false;
      } finally { fd.close(); }
      muxer.release(); muxer=null;
      ContentValues done = new ContentValues();
      done.put(MediaStore.Video.Media.IS_PENDING,0);
      getContentResolver().update(outputUri,done,null,null);
      outputUri=null;
    } finally {
      extractor.release();
      if (muxer != null) {
        if (started) try { muxer.stop(); } catch(Exception ignored) {}
        try { muxer.release(); } catch(Exception ignored) {}
      }
      if (outputUri != null) getContentResolver().delete(outputUri,null,null);
    }
  }
}
