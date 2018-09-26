package org.thoughtcrime.securesms.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import org.thoughtcrime.securesms.logging.Log;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.dependencies.InjectableType;
import org.thoughtcrime.securesms.gcm.GcmBroadcastReceiver;
import org.thoughtcrime.securesms.jobmanager.requirements.NetworkRequirement;
import org.thoughtcrime.securesms.jobmanager.requirements.NetworkRequirementProvider;
import org.thoughtcrime.securesms.jobmanager.requirements.RequirementListener;
import org.thoughtcrime.securesms.jobs.PushContentReceiveJob;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.signalservice.api.SignalServiceMessagePipe;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

public class MessageRetrievalService extends Service implements InjectableType, RequirementListener {

  private static final String TAG = MessageRetrievalService.class.getSimpleName();

  public static final  String ACTION_ACTIVITY_STARTED  = "ACTIVITY_STARTED";
  public static final  String ACTION_ACTIVITY_FINISHED = "ACTIVITY_FINISHED";
  public static final  String ACTION_PUSH_RECEIVED     = "PUSH_RECEIVED";
  public static final  String ACTION_INITIALIZE        = "INITIALIZE";
  public static final  int    FOREGROUND_ID            = 313399;

  private static final long   REQUEST_TIMEOUT_MINUTES  = 1;

  private NetworkRequirement         networkRequirement;
  private NetworkRequirementProvider networkRequirementProvider;
  private BroadcastReceiver connectivityChangeReceiver;
  private ConnectivityManager.NetworkCallback connectivityChangeCallback;

  @Inject
  public SignalServiceMessageReceiver receiver;

  private int                    activeActivities = 0;
  private List<Intent>           pushPending      = new LinkedList<>();
  private MessageRetrievalThread retrievalThread  = null;

  public static SignalServiceMessagePipe pipe = null;
  public static AtomicReference<SignalServiceMessagePipe> pipeReference = new AtomicReference<>();

  @Override
  public void onCreate() {
    super.onCreate();
    ApplicationContext.getInstance(this).injectDependencies(this);

    networkRequirement         = new NetworkRequirement(this);
    networkRequirementProvider = new NetworkRequirementProvider(this);

    networkRequirementProvider.setListener(this);

    retrievalThread = new MessageRetrievalThread();
    retrievalThread.start();

    monitorNetworkIfNecessary();
    setForegroundIfNecessary();
  }

  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) return START_STICKY;

    if      (ACTION_ACTIVITY_STARTED.equals(intent.getAction()))  incrementActive();
    else if (ACTION_ACTIVITY_FINISHED.equals(intent.getAction())) decrementActive();
    else if (ACTION_PUSH_RECEIVED.equals(intent.getAction()))     incrementPushReceived(intent);

    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();

    if (retrievalThread != null) {
      retrievalThread.stopThread();
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (connectivityChangeCallback != null) {
        final ConnectivityManager connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        connectivityManager.unregisterNetworkCallback(connectivityChangeCallback);
      }
    } else {
      if (connectivityChangeReceiver != null) {
        unregisterReceiver(connectivityChangeReceiver);
      }
    }

    sendBroadcast(new Intent("org.thoughtcrime.securesms.RESTART"));
  }

  @Override
  public void onRequirementStatusChanged() {
    synchronized (this) {
      notifyAll();
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private void monitorNetworkIfNecessary() {
    if (TextSecurePreferences.isGcmDisabled(this)) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        final ConnectivityManager connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        connectivityChangeCallback = new ConnectivityManager.NetworkCallback() {
          private Network current;

          private void update(Network network) {
            final Network previous = current;
            current = network;

            Log.d(TAG, "Currently active network: " + network);

            if (previous != null && (current == null || !current.equals(previous))) {
              Log.d(TAG,
                    "Active network changed (" + previous + " -> " + current +
                    "); interrupting the retrieval thread to recycle the pipe.");

              retrievalThread.interrupt();
            }
          }

          @Override
          public void onAvailable(Network network) {
            final ConnectivityManager connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

            update(connectivityManager.getActiveNetwork());
          }

          @Override
          public void onLost(Network network) {
            final ConnectivityManager connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

            update(connectivityManager.getActiveNetwork());
          }
        };

        connectivityManager.registerNetworkCallback(new NetworkRequest.Builder().build(),
                                                    connectivityChangeCallback);
      } else {
        connectivityChangeReceiver = new BroadcastReceiver() {
          private int current = -1;

          @Override
          public void onReceive(Context context, Intent intent) {
            final ConnectivityManager connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

            final NetworkInfo info = connectivityManager.getActiveNetworkInfo();
            final int previous = current;

            if (info == null) {
              current = -1;
            } else if (info.isConnected()) {
              current = info.getType();
            }

            Log.d(TAG, "Currently active network: " + current);

            if (previous != -1 && previous != current) {
              Log.d(TAG,
                    "Active network changed (" + previous + " -> " + current +
                    "); interrupting the retrieval thread to recycle the pipe.");
              retrievalThread.interrupt();
            }
          }
        };

        registerReceiver(connectivityChangeReceiver,
                         new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
      }
    }
  }

  private void setForegroundIfNecessary() {
    if (TextSecurePreferences.isGcmDisabled(this)) {
      NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationChannels.OTHER);
      builder.setContentTitle(getString(R.string.MessageRetrievalService_signal));
      builder.setContentText(getString(R.string.MessageRetrievalService_background_connection_enabled));
      builder.setPriority(NotificationCompat.PRIORITY_MIN);
      builder.setWhen(0);
      builder.setSmallIcon(R.drawable.ic_signal_grey_24dp);
      startForeground(FOREGROUND_ID, builder.build());
    }
  }

  private synchronized void incrementActive() {
    activeActivities++;
    Log.d(TAG, "Active Count: " + activeActivities);
    notifyAll();
  }

  private synchronized void decrementActive() {
    activeActivities--;
    Log.d(TAG, "Active Count: " + activeActivities);
    notifyAll();
  }

  private synchronized void incrementPushReceived(Intent intent) {
    pushPending.add(intent);
    notifyAll();
  }

  private synchronized void decrementPushReceived() {
    if (!pushPending.isEmpty()) {
      Intent intent = pushPending.remove(0);
      GcmBroadcastReceiver.completeWakefulIntent(intent);
      notifyAll();
    }
  }

  private synchronized boolean isConnectionNecessary() {
    boolean isGcmDisabled = TextSecurePreferences.isGcmDisabled(this);

    Log.d(TAG, String.format("Network requirement: %s, active activities: %s, push pending: %s, gcm disabled: %b",
                             networkRequirement.isPresent(), activeActivities, pushPending.size(), isGcmDisabled));

    return TextSecurePreferences.isPushRegistered(this)                       &&
           TextSecurePreferences.isWebsocketRegistered(this)                  &&
           (activeActivities > 0 || !pushPending.isEmpty() || isGcmDisabled)  &&
           networkRequirement.isPresent();
  }

  private synchronized void waitForConnectionNecessary() {
    while (!isConnectionNecessary()) {
      try {
        wait();
      } catch (InterruptedException e) {
        Log.d(TAG, "Retrieval thread interrupted while not connected; ignoring.");
      }
    }
  }

  private void shutdown(SignalServiceMessagePipe pipe) {
    try {
      pipe.shutdown();
    } catch (Throwable t) {
      Log.w(TAG, t);
    }
  }

  public static void registerActivityStarted(Context activity) {
    Intent intent = new Intent(activity, MessageRetrievalService.class);
    intent.setAction(MessageRetrievalService.ACTION_ACTIVITY_STARTED);
    activity.startService(intent);
  }

  public static void registerActivityStopped(Context activity) {
    Intent intent = new Intent(activity, MessageRetrievalService.class);
    intent.setAction(MessageRetrievalService.ACTION_ACTIVITY_FINISHED);
    activity.startService(intent);
  }

  public static @Nullable SignalServiceMessagePipe getPipe() {
    return pipe;
  }

  public static AtomicReference<SignalServiceMessagePipe> getPipeReference() {
    return pipeReference;
  }

  private class MessageRetrievalThread extends Thread implements Thread.UncaughtExceptionHandler {

    private AtomicBoolean stopThread = new AtomicBoolean(false);

    MessageRetrievalThread() {
      super("MessageRetrievalService");
      setUncaughtExceptionHandler(this);
    }

    @Override
    public void run() {
      while (!stopThread.get()) {
        Log.i(TAG, "Waiting for websocket state change....");
        waitForConnectionNecessary();

        Log.i(TAG, "Making websocket connection....");
        final SignalServiceMessagePipe localPipe = receiver.createMessagePipe();

        pipe = localPipe;
        pipeReference.set(localPipe);

        try {
          while (isConnectionNecessary() && !stopThread.get() && !interrupted()) {
            try {
              Log.i(TAG, "Reading message...");
              localPipe.read(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES,
                             envelope -> {
                               Log.i(TAG, "Retrieved envelope! " + envelope.getSource());

                               PushContentReceiveJob receiveJob = new PushContentReceiveJob(MessageRetrievalService.this);
                               receiveJob.handle(envelope);

                               decrementPushReceived();
                             });
            } catch (TimeoutException e) {
              Log.w(TAG, "Application level read timeout...");
            } catch (InvalidVersionException e) {
              Log.w(TAG, e);
            }
          }
        } catch (InterruptedException e) {
          Log.d(TAG, "Retrieval thread interrupted.");
        } catch (IOException e) {
          Log.d(TAG, "Message pipe failed: " + e.getMessage());
        } catch (Throwable e) {
          Log.w(TAG, e);
        } finally {
          Log.w(TAG, "Shutting down pipe...");
          shutdown(localPipe);
        }

        Log.i(TAG, "Looping...");
      }

      Log.i(TAG, "Exiting...");
    }

    private void stopThread() {
      stopThread.set(true);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
      Log.w(TAG, "*** Uncaught exception!");
      Log.w(TAG, e);
    }
  }
}
