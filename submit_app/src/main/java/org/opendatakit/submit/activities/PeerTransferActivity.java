package org.opendatakit.submit.activities;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.design.widget.Snackbar;
import android.support.v4.app.FragmentManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.opendatakit.consts.IntentConsts;
import org.opendatakit.fragment.AlertNProgessMsgFragmentMger;
import org.opendatakit.logging.WebLogger;
import org.opendatakit.properties.CommonToolProperties;
import org.opendatakit.properties.PropertiesSingleton;
import org.opendatakit.submit.R;
import org.opendatakit.submit.service.PeerAdapter;
import org.opendatakit.submit.service.WifiDirectBroadcastReceiver;

import org.opendatakit.submit.service.actions.SyncActions;
import org.opendatakit.submit.service.peer.PeerSyncServerService;
import org.opendatakit.submit.service.peer.server.PeerSyncServer;
import org.opendatakit.submit.util.PeerSyncUtil;
import org.opendatakit.submit.util.SubmitUtil;
import org.opendatakit.sync.service.IOdkSyncServiceInterface;
import org.opendatakit.sync.service.SyncAttachmentState;
import org.opendatakit.sync.service.SyncProgressEvent;
import org.opendatakit.sync.service.SyncProgressState;
import org.opendatakit.sync.service.SyncStatus;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class PeerTransferActivity extends SubmitBaseActivity {
  public static final int W_DIRECT_UNSUPPORTED = 10;

    private List<Socket> clientSocketsToClose;
    private List<ServerSocket> serverSocketsToClose;

    private boolean receiverIsRegistered;
    private WifiP2pManager mManager;
    private WifiP2pManager.Channel mChannel;
    private WifiDirectBroadcastReceiver mReceiver;
    private IntentFilter mIntentFilter;
    private Map<String, String> androidIdToIp;
    private PeerAdapter adapter;

    private PeerSyncServer server;

    private final Handler handler = new Handler();
    private AlertNProgessMsgFragmentMger msgManager;
    private SyncActions syncAction = SyncActions.IDLE;

  // this is used for joining table stuff
    private static final String SUFFIX = "_o";
    // tag for logging
    private static final String TAG = "PeerTransferActivity";
    private String alertDialogTag = TAG + "AlertDialog";
    private String progressDialogTag = TAG + "ProgressDialog";

    IOdkSyncServiceInterface syncInterface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // basic setup
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_peer_transfer);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        androidIdToIp = new TreeMap<>();
        receiverIsRegistered = false;
        clientSocketsToClose = new ArrayList<>();
        serverSocketsToClose = new ArrayList<>();

        mManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);

        if (mManager == null) {
          Log.e(TAG, "onCreate: Wi-Fi Direct unsupported");
          setResult(W_DIRECT_UNSUPPORTED);
          finish();
          return;
        }

        mChannel = mManager.initialize(this, getMainLooper(), null);

        adapter = new PeerAdapter(androidIdToIp, this);
        mReceiver = new WifiDirectBroadcastReceiver(mManager, mChannel, this);

        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        // setup button
        final Button button = findViewById(R.id.find_peers);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
//                LocalBroadcastManager.getInstance(PeerTransferActivity.this).registerReceiver(mReceiver, mIntentFilter);
                registerReceiver(mReceiver, mIntentFilter);
                receiverIsRegistered = true;
                if (mManager != null) {
                    mManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            // request available peers from the wifi p2p manager. This is an
                            // asynchronous call and the calling activity is notified with a
                            // callback on PeerListListener.onPeersAvailable()
                            Log.i(TAG, "discover peers success");
                        }

                        @Override
                        public void onFailure(int reasonCode) {
                            Log.i(TAG, "discover peers failure - reasonCode: " + reasonCode);
                        }
                    });
                }

            }
        });

        // setup recycler view
        RecyclerView peerListRv = findViewById(R.id.peer_list);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        peerListRv.setLayoutManager(layoutManager);
        peerListRv.setAdapter(adapter);
        adapter.notifyDataSetChanged();

        bindToPeerSyncServerService();

        // display device id
        TextView yourId = findViewById(R.id.your_id);
        yourId.setText(PeerSyncUtil.getAndroidId(this));

        if (savedInstanceState != null) {
            msgManager = AlertNProgessMsgFragmentMger
                    .restoreInitMessaging(SubmitUtil.getSecondaryAppName(getAppName()), alertDialogTag, progressDialogTag,
                            savedInstanceState);
        }

        // if message manager was not created from saved state, create fresh
        if (msgManager == null) {
            msgManager = new AlertNProgessMsgFragmentMger(SubmitUtil.getSecondaryAppName(getAppName()), alertDialogTag,
                    progressDialogTag, false, false);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (msgManager != null) {
            msgManager.addStateToSaveStateBundle(outState);
        }
    }

    public void bindToSyncService(String androidId) {
        Log.i(TAG, "bindToSyncService: " + androidId);

        if (androidId == null) {
            return;
        }

        final String secondaryAppName = SubmitUtil.getSecondaryAppName(getAppName());
        PropertiesSingleton propertiesSingleton = CommonToolProperties.get(this, secondaryAppName);
        propertiesSingleton.setProperties(Collections.singletonMap(
                CommonToolProperties.KEY_SYNC_SERVER_URL,
                IntentConsts.SubmitPeerSync.URI_SCHEME + androidIdToIp.get(androidId) + ":8080/" + secondaryAppName
        ));
        Log.e(TAG, "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" + IntentConsts.SubmitPeerSync.URI_SCHEME + androidIdToIp.get(androidId) + ":8080/" + secondaryAppName);
        propertiesSingleton.setProperties(Collections.singletonMap(
                CommonToolProperties.KEY_FIRST_LAUNCH,
                Boolean.toString(false)
        ));

        Intent intent = new Intent()
                .setClassName(IntentConsts.Sync.APPLICATION_NAME, IntentConsts.Sync.SYNC_SERVICE_CLASS);
        syncAction = SyncActions.START_SYNC;

        this.bindService(intent, new ServiceConnection() {
          @Override
          public void onServiceConnected(ComponentName name, IBinder service) {
            syncInterface = IOdkSyncServiceInterface.Stub.asInterface(service);
            try {
              fixSubmitStates();
              syncInterface.synchronizeWithServer(secondaryAppName, SyncAttachmentState.NONE);
              syncAction = SyncActions.START_SYNC;

              handler.post(new Runnable() {
                @Override
                public void run() {
                  // TODO: Put strings in xml
                  msgManager.createProgressDialog("Transferring", "Initiating data transfer", getSupportFragmentManager());
                }
              });

              monitorProgress();
            } catch (RemoteException e) {
              e.printStackTrace();
            }
          }

          @Override
          public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "services disconnected");
          }
        }, Context.BIND_AUTO_CREATE | Context.BIND_ADJUST_WITH_ACTIVITY);
    }

    public void bindToPeerSyncServerService() {
      Intent peerServiceIntent = new Intent()
          .setClass(this, PeerSyncServerService.class);

      bindService(peerServiceIntent, new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
          Log.e(TAG, "onServiceConnected: connected to PeerSyncServer");

          server = ((PeerSyncServerService.PeerSyncServerBinder) service).getServer();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
          server = null;
        }
      }, Context.BIND_AUTO_CREATE | Context.BIND_ADJUST_WITH_ACTIVITY);
    }

    public void startPeerSyncServer() {
      if (server == null) {
        new Handler().postDelayed(new Runnable() {
          @Override
          public void run() {
            startPeerSyncServer();
          }
        }, 500);

        return;
      }

      if (server.isAlive()) {
        return;
      }

      server.stop();
      try {
        server.start();
      } catch (IOException e) {
        Log.e(TAG, "startPeerSyncServer: ", e);

        Snackbar
            .make(
                findViewById(R.id.peer_transfer_content),
                "Unable to start service",
                Snackbar.LENGTH_LONG
            )
            .show();
      }
    }

    /**
     * Lets the user know we are connected to a peer and waiting for
     * them to initiate and complete sync
     */
    public void displayWaitingForPeerMessage() {
        TextView isWaiting = findViewById(R.id.is_waiting);
        isWaiting.setText("Connected to peer .. waiting");
    }

    /**
     * Updates list of peers with given androidId and peerAddress
     *
     * @param androidId
     * @param peerAddress
     */
    public void peerAddressCallback(String androidId, InetAddress peerAddress) {

        Log.i(TAG, "peer androidId: " + androidId);
        Log.i(TAG, "peer Address: " + peerAddress.getHostAddress());

        androidIdToIp.put(androidId, peerAddress.getHostAddress());

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                adapter.notifyDataSetChanged();
            }
        });

    }

  private void monitorProgress() {
    if (!msgManager.hasDialogBeenCreated()) {
      // We are in transition. Wait and try again
      handler.postDelayed(new Runnable() {@Override public void run() { monitorProgress(); }}, 100);
      return;
    }

    if (syncInterface == null || syncAction == SyncActions.IDLE) {
      resetDialogAndStateMachine();
      return;
    }

    final SyncStatus status;
    final SyncProgressEvent event;
    try {
      status = syncInterface.getSyncStatus(SubmitUtil.getSecondaryAppName(getAppName()));
      event = syncInterface.getSyncProgressEvent(SubmitUtil.getSecondaryAppName(getAppName()));
    } catch (RemoteException e) {
      // TODO: How can we handle this error? Can we recover?
      WebLogger.getLogger(SubmitUtil.getSecondaryAppName(getAppName())).d(TAG, "[monitorProgress] Remote exception");
      e.printStackTrace();
      resetDialogAndStateMachine();
      return;
    }

    if (syncAction == SyncActions.START_SYNC && status == SyncStatus.NONE) {
      handler.postDelayed(new Runnable() {@Override public void run() { monitorProgress(); }}, 100);
      return;
    } else if (status == SyncStatus.SYNCING) {
      syncAction = SyncActions.MONITOR_SYNCING;

      SyncProgressState progress = (event.progressState != null) ? event.progressState : SyncProgressState.INACTIVE;
      String message;
      switch (progress) {
        case APP_FILES:
          message = "Copying app files";
          break;
        case TABLE_FILES:
          message = "Copying table files";
          break;
        case ROWS:
          message = "Copying row data";
          break;
        default:
          message = "Copying files";
      }

      FragmentManager fm =  getSupportFragmentManager();
      msgManager.createProgressDialog("Copying", message, fm);
      fm.executePendingTransactions();
      msgManager.updateProgressDialogMessage(message, event.curProgressBar, event.maxProgressBar, fm);

      handler.postDelayed(new Runnable() {@Override public void run() { monitorProgress(); }}, 100);
    } else if (syncAction == SyncActions.MONITOR_SYNCING && (status == SyncStatus.SYNC_COMPLETE || status == SyncStatus.NONE)) {
      FragmentManager fm =  getSupportFragmentManager();
      msgManager.clearDialogsAndRetainCurrentState(fm);
      fm.executePendingTransactions();
      // TODO: Needs a fragment id
      //msgManager.createAlertDialog("Sync Complete", "The files have been successfully synced", fm);
      syncAction = SyncActions.IDLE;
    }

  }

  private void resetDialogAndStateMachine() {
    msgManager.dismissAlertDialog(getSupportFragmentManager());
    syncAction = SyncActions.IDLE;
  }

  /* unregister the broadcast receiver */
    @Override
    protected void onPause() {
        super.onPause();
        msgManager.clearDialogsAndRetainCurrentState(getSupportFragmentManager());
        if (receiverIsRegistered) {
            try {
                unregisterReceiver(mReceiver);
            } catch (IllegalArgumentException e) {
                // ignore
                // somehow not registered
            }
            receiverIsRegistered = false;
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        WebLogger.getLogger(SubmitUtil.getSecondaryAppName(getAppName())).i(TAG, "[onResume]");

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                monitorProgress();
            }
        }, 100);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        WebLogger.getLogger(SubmitUtil.getSecondaryAppName(getAppName())).i(TAG, "[onDestroy]");
    }

    /**
     * register a client and server socket to close when the activity stops
     *
     * @param serverSocket
     * @param clientSocket
     */
    public void closeSocketsOnExit(ServerSocket serverSocket, Socket clientSocket) {
        clientSocketsToClose.add(clientSocket);
        serverSocketsToClose.add(serverSocket);
    }

    /**
     * when activity stops, close all sockets
     */
    public void onStop() {
        super.onStop();
        for (Socket socket : clientSocketsToClose) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        for (ServerSocket socket : serverSocketsToClose) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

  @Override
  public void databaseAvailable() {}

  @Override
  public void databaseUnavailable() {}
}