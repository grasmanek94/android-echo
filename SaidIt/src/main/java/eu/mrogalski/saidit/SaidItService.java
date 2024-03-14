package eu.mrogalski.saidit;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

import simplesound.pcm.WavAudioFormat;
import simplesound.pcm.WavFileWriter;
import static eu.mrogalski.saidit.SaidIt.*;

public class SaidItService extends Service {
    static final String TAG = SaidItService.class.getSimpleName();

    volatile int SAMPLE_RATE;
    volatile int FILL_RATE;


    File wavFile;
    AudioRecord audioRecord; // used only in the audio thread
    WavFileWriter wavFileWriter; // used only in the audio thread
    final AudioMemory audioMemory = new AudioMemory(); // used only in the audio thread
    volatile private int readLimit = Integer.MAX_VALUE; // used to control responsiveness of audio thread

    HandlerThread audioThread;
    Handler audioHandler; // used to post messages to audio thread

    @Override
    public void onCreate() {

        Log.d(TAG, "Reading native sample rate");

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        SAMPLE_RATE = preferences.getInt(SAMPLE_RATE_KEY, AudioTrack.getNativeOutputSampleRate (AudioManager.STREAM_MUSIC));
        Log.d(TAG, "Sample rate: " + SAMPLE_RATE);
        FILL_RATE = 2 * SAMPLE_RATE;

        audioThread = new HandlerThread("audioThread", Thread.MAX_PRIORITY);
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());

        if(preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStartListening();
        }

    }

    @Override
    public void onDestroy() {
        stopRecording(null);
        innerStopListening();
    }

    @Override
    public IBinder onBind(Intent intent) {
        readLimit = FILL_RATE / 10;
        return new BackgroundRecorderBinder();
    }

    @Override
    public void onRebind(Intent intent) {
        readLimit = FILL_RATE / 10;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        readLimit = Integer.MAX_VALUE;
        return true;
    }

    public void enableListening() {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, true).commit();

        innerStartListening();
    }

    public void disableListening() {
        getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE)
                .edit().putBoolean(AUDIO_MEMORY_ENABLED_KEY, false).commit();

        innerStopListening();
    }

    int state;

    static final int STATE_READY = 0;
    static final int STATE_LISTENING = 1;
    static final int STATE_RECORDING = 2;

    private void innerStartListening() {
        switch(state) {
            case STATE_READY:
                break;
            case STATE_LISTENING:
            case STATE_RECORDING:
                return;
        }
        state = STATE_LISTENING;

        Log.d(TAG, "Queueing: START LISTENING");

        startService(new Intent(this, this.getClass()));

        final long memorySize = getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE).getLong(AUDIO_MEMORY_SIZE_KEY, Runtime.getRuntime().maxMemory() / 4);

        Notification note = new Notification( 0, null, System.currentTimeMillis() );
        note.flags |= Notification.FLAG_NO_CLEAR;
        startForeground(42, note);
        startService(new Intent(this, FakeService.class));
        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Executing: START LISTENING");
                Log.d(TAG, "Audio: INITIALIZING AUDIO_RECORD");

                audioRecord = new AudioRecord(
                       MediaRecorder.AudioSource.MIC,
                       SAMPLE_RATE,
                       AudioFormat.CHANNEL_IN_MONO,
                       AudioFormat.ENCODING_PCM_16BIT,
                       512 * 1024); // .5MB

                if(audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Audio: INITIALIZATION ERROR - releasing resources");
                    audioRecord.release();
                    audioRecord = null;
                    state = STATE_READY;
                    return;
                }

                Log.d(TAG, "Audio: STARTING AudioRecord");
                audioMemory.allocate(memorySize);

                Log.d(TAG, "Audio: STARTING AudioRecord");
                audioRecord.startRecording();
                audioHandler.post(audioReader);
            }
        });


    }

    private void innerStopListening() {
        switch(state) {
            case STATE_READY:
            case STATE_RECORDING:
                return;
            case STATE_LISTENING:
                break;
        }
        state = STATE_READY;
        Log.d(TAG, "Queueing: STOP LISTENING");

        stopForeground(true);
        stopService(new Intent(this, this.getClass()));

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Executing: STOP LISTENING");
                if(audioRecord != null)
                    audioRecord.release();
                audioHandler.removeCallbacks(audioReader);
                audioMemory.allocate(0);
            }
        });

    }

    public void dumpRecording(final float memorySeconds, final WavFileReceiver wavFileReceiver) {
        if(state != STATE_LISTENING) throw new IllegalStateException("Not listening!");

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                int prependBytes = (int)(memorySeconds * FILL_RATE);
                int bytesAvailable = audioMemory.countFilled();

                int skipBytes = Math.max(0, bytesAvailable - prependBytes);

                int useBytes = bytesAvailable - skipBytes;
                long millis  = System.currentTimeMillis() - 1000 * useBytes / FILL_RATE;
                final int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE;
                final String dateTime = DateUtils.formatDateTime(SaidItService.this, millis, flags);
                String filename = "Echo - " + dateTime + ".wav";

                final String storagePath = Environment.getExternalStorageDirectory().getAbsolutePath();
                String path = storagePath + "/" + filename;

                File file = new File(path);
                try {
                    file.createNewFile();
                } catch (IOException e) {
                    filename = filename.replace(':', '.');
                    path = storagePath + "/" + filename;
                    file = new File(path);
                }
                final WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(SAMPLE_RATE).build();
                try {
                    final WavFileWriter writer = new WavFileWriter(format, file);

                    try {
                        audioMemory.read(skipBytes, new AudioMemory.Consumer() {
                            @Override
                            public int consume(byte[] array, int offset, int count) throws IOException {
                                writer.write(array, offset, count);
                                return 0;
                            }
                        });
                    } catch (IOException e) {
                        final String errorMessage = getString(R.string.error_during_writing_history_into) + path;
                        Toast.makeText(SaidItService.this, errorMessage, Toast.LENGTH_LONG).show();
                        Log.e(TAG, errorMessage, e);

                        try {
                            writer.close();
                        } catch (IOException e2) {
                            Log.e(TAG, "CLOSING ERROR", e2);
                        }
                        if(wavFileReceiver != null)
                            wavFileReceiver.fileReady(file, writer.getTotalSampleBytesWritten() * getBytesToSeconds());
                    }

                    try {
                        writer.close();
                    } catch (IOException e) {
                        Log.e(TAG, "CLOSING ERROR", e);
                    }
                    if(wavFileReceiver != null) {
                        wavFileReceiver.fileReady(file, writer.getTotalSampleBytesWritten() * getBytesToSeconds());
                    }
                } catch (IOException e) {
                    final String errorMessage = getString(R.string.cant_create_file) + path;
                    Toast.makeText(SaidItService.this, errorMessage, Toast.LENGTH_LONG).show();
                    Log.e(TAG, errorMessage, e);
                }
            }
        });

    }

    public void startRecording(final float prependedMemorySeconds) {
        switch(state) {
            case STATE_READY:
                innerStartListening();
                break;
            case STATE_LISTENING:
                break;
            case STATE_RECORDING:
                return;
        }
        state = STATE_RECORDING;

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                int prependBytes = (int)(prependedMemorySeconds * FILL_RATE);
                int bytesAvailable = audioMemory.countFilled();

                int skipBytes = Math.max(0, bytesAvailable - prependBytes);

                int useBytes = bytesAvailable - skipBytes;
                long millis  = System.currentTimeMillis() - 1000 * useBytes / FILL_RATE;
                final int flags = DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE;
                final String dateTime = DateUtils.formatDateTime(SaidItService.this, millis, flags);
                String filename = "Echo - " + dateTime + ".wav";

                final String storagePath = Environment.getExternalStorageDirectory().getAbsolutePath();
                String path = storagePath + "/" + filename;

                wavFile = new File(path);
                try {
                    wavFile.createNewFile();
                } catch (IOException e) {
                    filename = filename.replace(':', '.');
                    path = storagePath + "/" + filename;
                    wavFile = new File(path);
                }
                WavAudioFormat format = new WavAudioFormat.Builder().sampleRate(SAMPLE_RATE).build();
                try {
                    wavFileWriter = new WavFileWriter(format, wavFile);
                } catch (IOException e) {
                    final String errorMessage = getString(R.string.cant_create_file) + path;
                    Toast.makeText(SaidItService.this, errorMessage, Toast.LENGTH_LONG).show();
                    Log.e(TAG, errorMessage, e);
                    return;
                }

                final String finalPath = path;

                if(skipBytes < bytesAvailable) {
                    try {
                        audioMemory.read(skipBytes, new AudioMemory.Consumer() {
                            @Override
                            public int consume(byte[] array, int offset, int count) throws IOException {
                                wavFileWriter.write(array, offset, count);
                                return 0;
                            }
                        });
                    } catch (IOException e) {
                        final String errorMessage = getString(R.string.error_during_writing_history_into) + finalPath;
                        Toast.makeText(SaidItService.this, errorMessage, Toast.LENGTH_LONG).show();
                        Log.e(TAG, errorMessage, e);
                        stopRecording(new SaidItFragment.NotifyFileReceiver(SaidItService.this));
                    }
                }
            }
        });

        final Notification notification = buildNotification();
        startForeground(42, notification);

    }

    public long getMemorySize() {
        return audioMemory.getAllocatedMemorySize();
    }

    public void setMemorySize(final long memorySize) {
        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        preferences.edit().putLong(AUDIO_MEMORY_SIZE_KEY, memorySize).commit();

        if(preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            audioHandler.post(new Runnable() {
                @Override
                public void run() {
                    audioMemory.allocate(memorySize);
                }
            });
        }
    }

    public int getSamplingRate() {
        return SAMPLE_RATE;
    }

    public void setSampleRate(int sampleRate) {
        switch(state) {
            case STATE_READY:
            case STATE_RECORDING:
                return;
            case STATE_LISTENING:
                break;
        }

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        preferences.edit().putInt(SAMPLE_RATE_KEY, sampleRate).commit();

        innerStopListening();
        SAMPLE_RATE = sampleRate;
        FILL_RATE = 2 * SAMPLE_RATE;
        innerStartListening();
    }

    public interface WavFileReceiver {
        public void fileReady(File file, float runtime);
    }

    public void stopRecording(final WavFileReceiver wavFileReceiver) {
        switch(state) {
            case STATE_READY:
            case STATE_LISTENING:
                return;
            case STATE_RECORDING:
                break;
        }
        state = STATE_LISTENING;

        audioHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    wavFileWriter.close();
                } catch (IOException e) {
                    Log.e(TAG, "CLOSING ERROR", e);
                }
                if(wavFileReceiver != null) {
                    wavFileReceiver.fileReady(wavFile, wavFileWriter.getTotalSampleBytesWritten() * getBytesToSeconds());
                }
                wavFileWriter = null;
            }
        });

        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        if(!preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true)) {
            innerStopListening();
        }

        stopForeground(true);
    }

    final AudioMemory.Consumer filler = new AudioMemory.Consumer() {
        @Override
        public int consume(final byte[] array, final int offset, final int count) throws IOException {

            final int bytes = Math.min(readLimit, count);
            //Log.d(TAG, "READING " + bytes + " B");
            final int read = audioRecord.read(array, offset, bytes);
            if (read == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "AUDIO RECORD ERROR - BAD VALUE");
                return 0;
            }
            if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                Log.e(TAG, "AUDIO RECORD ERROR - INVALID OPERATION");
                return 0;
            }
            if (read == AudioRecord.ERROR) {
                Log.e(TAG, "AUDIO RECORD ERROR - UNKNOWN ERROR");
                return 0;
            }
            if (wavFileWriter != null && read > 0) {
                wavFileWriter.write(array, offset, read);
            }
            audioHandler.post(audioReader);
            return read;
        }
    };
    final Runnable audioReader = new Runnable() {
        @Override
        public void run() {
            try {
                audioMemory.fill(filler);
            } catch (IOException e) {
                final String errorMessage = getString(R.string.error_during_recording_into) + wavFile.getName();
                Toast.makeText(SaidItService.this, errorMessage, Toast.LENGTH_LONG).show();
                Log.e(TAG, errorMessage, e);
                stopRecording(new SaidItFragment.NotifyFileReceiver(SaidItService.this));
            }
        }
    };

    public interface StateCallback {
        public void state(boolean listeningEnabled, boolean recording, float memorized, float totalMemory, float recorded);
    }

    public void getState(final StateCallback stateCallback) {
        final SharedPreferences preferences = this.getSharedPreferences(PACKAGE_NAME, MODE_PRIVATE);
        final boolean listeningEnabled = preferences.getBoolean(AUDIO_MEMORY_ENABLED_KEY, true);
        final boolean recording = (state == STATE_RECORDING);
        final Handler sourceHandler = new Handler();
        audioHandler.post(new Runnable() {
            @Override
            public void run() {

                audioMemory.observe(new AudioMemory.Observer() {
                    @Override
                    public void observe(final int filled, final int total, final int estimation, final boolean overwriting) {
                        int recorded = 0;
                        if(wavFileWriter != null) {
                            recorded += wavFileWriter.getTotalSampleBytesWritten();
                            recorded += estimation;
                        }
                        final float bytesToSeconds = getBytesToSeconds();

                        final int finalRecorded = recorded;
                        sourceHandler.post(new Runnable() {
                            @Override
                            public void run() {

                                stateCallback.state(listeningEnabled, recording,
                                        (overwriting ? total : filled + estimation) * bytesToSeconds,
                                        total * bytesToSeconds,
                                        finalRecorded * bytesToSeconds);
                            }
                        });
                    }
                }, FILL_RATE);
            }
        });
    }

    public float getBytesToSeconds() {
        return 1f / FILL_RATE;
    }

    class BackgroundRecorderBinder extends Binder {
        public SaidItService getService() {
            return SaidItService.this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    // Workaround for bug where recent app removal caused service to stop
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());

        PendingIntent restartServicePendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT);
        AlarmManager alarmService = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmService.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent);
    }

    private Notification buildNotification() {

        Intent intent = new Intent(this, SaidItActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this);
        notificationBuilder.setContentTitle(getString(R.string.recording));
        notificationBuilder.setUsesChronometer(true);
        notificationBuilder.setProgress(100, 50, true);
        notificationBuilder.setSmallIcon(R.drawable.ic_stat_notify_recording);
        notificationBuilder.setTicker(getString(R.string.recording));
        notificationBuilder.setContentIntent(pendingIntent);
        return notificationBuilder.build();
    }

}
