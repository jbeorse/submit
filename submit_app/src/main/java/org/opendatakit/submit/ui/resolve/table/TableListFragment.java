package org.opendatakit.submit.ui.resolve.table;

import android.arch.lifecycle.Observer;
import android.arch.lifecycle.ViewModelProviders;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentManager;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import org.opendatakit.consts.IntentConsts;
import org.opendatakit.fragment.AlertNProgessMsgFragmentMger;
import org.opendatakit.logging.WebLogger;
import org.opendatakit.properties.CommonToolProperties;
import org.opendatakit.properties.PropertiesSingleton;
import org.opendatakit.submit.R;
import org.opendatakit.submit.activities.PeerTransferActivity;
import org.opendatakit.submit.activities.SubmitBaseActivity;
import org.opendatakit.submit.service.actions.SyncActions;
import org.opendatakit.submit.ui.common.AbsBaseFragment;
import org.opendatakit.submit.ui.common.OnClickListenerHolder;
import org.opendatakit.submit.ui.resolve.row.RowListFragment;
import org.opendatakit.submit.util.SubmitUtil;
import org.opendatakit.sync.service.IOdkSyncServiceInterface;
import org.opendatakit.sync.service.SyncAttachmentState;
import org.opendatakit.sync.service.SyncProgressEvent;
import org.opendatakit.sync.service.SyncProgressState;
import org.opendatakit.sync.service.SyncStatus;

import java.util.Collections;
import java.util.List;

public class TableListFragment extends AbsBaseFragment
    implements OnClickListenerHolder<ConflictingTable>,
    View.OnClickListener,
    ServiceConnection {
  private static final String TAG = TableListFragment.class.getSimpleName();
  private String alertDialogTag = TAG + "AlertDialog";
  private String progressDialogTag = TAG + "ProgressDialog";

  private TableListViewModel viewModel;
  private TableListAdapter adapter;
  private final Handler handler = new Handler();
  private AlertNProgessMsgFragmentMger msgManager;
  private Button localSyncBtn;
  private Button launchPeerTransferBtn;
  private SyncActions syncAction = SyncActions.IDLE;
  private boolean isBond;

  IOdkSyncServiceInterface syncInterface;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    this.viewModel = ViewModelProviders
        .of(this)
        .get(TableListViewModel.class);

    this.viewModel.setAppName(getAppName());

    this.adapter = new TableListAdapter(this);

    // TODO: could cause ANR
    ((SubmitBaseActivity) requireActivity()).fixSubmitStates();

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

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_resolve_table_list, container, false);
  }

  @Override
  public void onViewCreated(@NonNull final View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    configRecyclerView(ViewCompat.<RecyclerView>requireViewById(view, R.id.resolve_table_list));

    viewModel.getTables().observe(getViewLifecycleOwner(), new Observer<List<ConflictingTable>>() {
      @Override
      public void onChanged(@Nullable List<ConflictingTable> conflictingTables) {
        adapter.submitList(conflictingTables);

        boolean tableEmpty = conflictingTables == null || conflictingTables.isEmpty();

        int recyclerViewVisibility = tableEmpty ? View.GONE : View.VISIBLE;
        int localSyncVisibility = tableEmpty ? View.VISIBLE : View.GONE;

        ViewCompat
            .requireViewById(view, R.id.resolve_table_list)
            .setVisibility(recyclerViewVisibility);

        ViewCompat
            .requireViewById(view, R.id.resolve_table_list_empty)
            .setVisibility(localSyncVisibility);

        localSyncBtn = ViewCompat
            .requireViewById(view, R.id.resolve_table_local_btn);

        localSyncBtn.setVisibility(localSyncVisibility);
        localSyncBtn.setOnClickListener(TableListFragment.this);

        launchPeerTransferBtn = ViewCompat
                .requireViewById(view, R.id.launch_peer_transfer_btn);
        launchPeerTransferBtn.setOnClickListener(new View.OnClickListener() {
          public void onClick(View v) {
            Intent i = new Intent(getActivity(), PeerTransferActivity.class);
            startActivity(i);
          }
        });
      }
    });

    viewModel.findTablesInConflict();
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    if (msgManager != null) {
      msgManager.addStateToSaveStateBundle(outState);
    }
  }

  @Override
  public void onPause() {
    msgManager.clearDialogsAndRetainCurrentState(getFragmentManager());

    super.onPause();
  }

  @Override
  public void onResume() {
    super.onResume();

    WebLogger.getLogger(SubmitUtil.getSecondaryAppName(getAppName())).i(TAG, "[" + getId() + "] [onResume]");

    enableButtons();
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


    if (isBond) {
      requireContext().unbindService(this);
    }

    WebLogger.getLogger(SubmitUtil.getSecondaryAppName(getAppName())).i(TAG, "[" + getId() + "] [onDestroy]");
  }

  private void configRecyclerView(@NonNull RecyclerView rv) {
    LinearLayoutManager layoutManager =
        new LinearLayoutManager(requireContext());
    DividerItemDecoration itemDecoration =
        new DividerItemDecoration(requireContext(), layoutManager.getOrientation());

    rv.setLayoutManager(layoutManager);
    rv.addItemDecoration(itemDecoration);
    rv.setAdapter(adapter);
  }

  @Override
  public View.OnClickListener getListener(final ConflictingTable item) {
    return new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        requireFragmentManager()
            .beginTransaction()
            .replace(R.id.main_content, RowListFragment.newInstance(item.getId()))
            .addToBackStack(null)
            .commit();
      }
    };
  }

  @Override
  public void onClick(View v) {
    if (v.getId() != R.id.resolve_table_local_btn) {
      return;
    }

    final String appName = SubmitUtil.getSecondaryAppName(getAppName());

    // TODO: move PropertiesSingleton init
    PropertiesSingleton propertiesSingleton = CommonToolProperties.get(requireActivity(), appName);
    propertiesSingleton.setProperties(Collections.singletonMap(
        CommonToolProperties.KEY_SYNC_SERVER_URL,
        IntentConsts.SubmitLocalSync.URI_SCHEME + "sync"
    ));
    propertiesSingleton.setProperties(Collections.singletonMap(
        CommonToolProperties.KEY_FIRST_LAUNCH,
        Boolean.toString(false)
    ));

    syncAction = SyncActions.START_SYNC;
    Intent intent = new Intent()
        .setClassName(IntentConsts.Sync.APPLICATION_NAME, IntentConsts.Sync.SYNC_SERVICE_CLASS);

    isBond = requireContext().bindService(
        intent,
        this,
        Context.BIND_AUTO_CREATE | Context.BIND_ADJUST_WITH_ACTIVITY
    );
  }

  @Override
  public void onServiceConnected(ComponentName name, IBinder service) {
    Log.i(TAG, "onServiceConnected: " + name);

    disableButtons();
    syncInterface = IOdkSyncServiceInterface.Stub.asInterface(service);

    try {
      ((SubmitBaseActivity) requireActivity()).fixSubmitStates();

      syncInterface.synchronizeWithServer(
          SubmitUtil.getSecondaryAppName(getAppName()),
          SyncAttachmentState.NONE
      );
      syncAction = SyncActions.START_SYNC;

      handler.post(new Runnable() {
        @Override
        public void run() {
          // TODO: Put strings in xml
          msgManager.createProgressDialog("Copying", "Initiating file copy", getFragmentManager());
        }
      });

      monitorProgress();
    } catch (RemoteException e) {
      Log.e(TAG, "onServiceConnected: ", e);
    }
  }


  @Override
  public void onServiceDisconnected(ComponentName name) {
    Log.e(TAG, "onServiceDisconnected: " + name);
  }

  private void disableButtons() {
    if (localSyncBtn != null) {
      localSyncBtn.setEnabled(false);
    }
    if (launchPeerTransferBtn != null) {
      launchPeerTransferBtn.setEnabled(false);
    }
  }

  void enableButtons() {
    if (localSyncBtn != null) {
      localSyncBtn.setEnabled(true);
    }
    if (launchPeerTransferBtn != null) {
      launchPeerTransferBtn.setEnabled(true);
    }
  }

  private void monitorProgress() {
    if (getActivity() == null || !msgManager.hasDialogBeenCreated() || !this.isResumed()) {
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
      WebLogger.getLogger(SubmitUtil.getSecondaryAppName(getAppName())).d(TAG, "[" + getId() + "] [monitorProgress] Remote exception");
      e.printStackTrace();
      resetDialogAndStateMachine();
      return;
    }

    if (syncAction == SyncActions.START_SYNC && status == SyncStatus.NONE) {
      handler.postDelayed(new Runnable() {@Override public void run() { monitorProgress(); }}, 100);
      return;
    } else if (status == SyncStatus.SYNCING) {
      syncAction = SyncActions.MONITOR_SYNCING;
      disableButtons();

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

      FragmentManager fm =  getFragmentManager();
      msgManager.createProgressDialog("Copying", message, fm);
      fm.executePendingTransactions();
      msgManager.updateProgressDialogMessage(message, event.curProgressBar, event.maxProgressBar, fm);

      handler.postDelayed(new Runnable() {@Override public void run() { monitorProgress(); }}, 100);
    } else if (syncAction == SyncActions.MONITOR_SYNCING && (status == SyncStatus.SYNC_COMPLETE || status == SyncStatus.NONE)) {
      FragmentManager fm =  getFragmentManager();
      msgManager.clearDialogsAndRetainCurrentState(fm);
      fm.executePendingTransactions();

      try {
        syncInterface.clearAppSynchronizer(SubmitUtil.getSecondaryAppName(getAppName()));
      } catch (RemoteException e) {
        // TODO: How can we handle this error? Can we recover?
        WebLogger.getLogger(SubmitUtil.getSecondaryAppName(getAppName())).d(TAG, "[" + getId() + "] [monitorProgress] Remote exception");
        e.printStackTrace();
        resetDialogAndStateMachine();
        msgManager.createAlertDialog("Copy Error", "An error occurred with the copy", fm, getId());
        return;
      }

      msgManager.createAlertDialog("Copy Complete", "The files have been successfully copied", fm, getId());
      localSyncBtn.setVisibility(View.GONE);
      launchPeerTransferBtn.setVisibility(View.VISIBLE);
      enableButtons();
      syncAction = SyncActions.IDLE;
    }

  }

  private void resetDialogAndStateMachine() {
    msgManager.dismissAlertDialog(getFragmentManager());
    syncAction = SyncActions.IDLE;
    enableButtons();
  }
}
