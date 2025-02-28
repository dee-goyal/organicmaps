package app.organicmaps;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import app.organicmaps.display.DisplayManager;
import app.organicmaps.location.LocationHelper;
import app.organicmaps.util.Config;
import app.organicmaps.util.LocationUtils;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.util.concurrency.UiThread;
import app.organicmaps.util.log.Logger;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;

public class SplashActivity extends AppCompatActivity
{
  private static final String TAG = SplashActivity.class.getSimpleName();
  private static final String EXTRA_ACTIVITY_TO_START = "extra_activity_to_start";
  public static final String EXTRA_INITIAL_INTENT = "extra_initial_intent";

  private static final long DELAY = 100;

  private boolean mCanceled = false;

  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private ActivityResultLauncher<String[]> mPermissionRequest;
  @NonNull
  private ActivityResultLauncher<Intent> mApiRequest;

  @NonNull
  private final Runnable mInitCoreDelayedTask = new Runnable()
  {
    @Override
    public void run()
    {
      init();
    }
  };

  @NonNull
  public static void start(@NonNull Context context,
                           @Nullable Class<? extends Activity> activityToStart,
                           @Nullable Intent initialIntent)
  {
    Intent intent = new Intent(context, SplashActivity.class);
    if (activityToStart != null)
      intent.putExtra(EXTRA_ACTIVITY_TO_START, activityToStart);
    if (initialIntent != null)
      intent.putExtra(EXTRA_INITIAL_INTENT, initialIntent);
    context.startActivity(intent);
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);

    final Context context = getApplicationContext();
    final String theme = Config.getCurrentUiTheme(context);
    if (ThemeUtils.isDefaultTheme(context, theme))
      setTheme(R.style.MwmTheme_Splash);
    else if (ThemeUtils.isNightTheme(context, theme))
      setTheme(R.style.MwmTheme_Night_Splash);
    else
      throw new IllegalArgumentException("Attempt to apply unsupported theme: " + theme);

    UiThread.cancelDelayedTasks(mInitCoreDelayedTask);
    Config.updateCounters(this);
    setContentView(R.layout.activity_splash);
    mPermissionRequest = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
        result -> Config.setLocationRequested());
    mApiRequest = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
      setResult(result.getResultCode(), result.getData());
      finish();
    });

    if (DisplayManager.from(this).isCarDisplayUsed())
    {
      startActivity(new Intent(this, MapPlaceholderActivity.class));
      finish();
    }
  }

  @Override
  protected void onResume()
  {
    super.onResume();
    if (mCanceled)
      return;
    if (!Config.isLocationRequested() && !LocationUtils.checkCoarseLocationPermission(this))
    {
      Logger.d(TAG, "Requesting location permissions");
      mPermissionRequest.launch(new String[]{
          ACCESS_COARSE_LOCATION,
          ACCESS_FINE_LOCATION
      });
      return;
    }

    UiThread.runLater(mInitCoreDelayedTask, DELAY);
  }

  @Override
  protected void onPause()
  {
    super.onPause();
    UiThread.cancelDelayedTasks(mInitCoreDelayedTask);
  }

  @Override
  protected void onDestroy()
  {
    super.onDestroy();
    mPermissionRequest.unregister();
    mPermissionRequest = null;
    mApiRequest.unregister();
    mApiRequest = null;
  }

  private void showFatalErrorDialog(@StringRes int titleId, @StringRes int messageId)
  {
    mCanceled = true;
    new MaterialAlertDialogBuilder(this, R.style.MwmTheme_AlertDialog)
        .setTitle(titleId)
        .setMessage(messageId)
        .setNegativeButton(R.string.ok, (dialog, which) -> SplashActivity.this.finish())
        .setCancelable(false)
        .show();
  }

  private void init()
  {
    MwmApplication app = MwmApplication.from(this);
    boolean asyncContinue = false;
    try
    {
      asyncContinue = app.init(this::processNavigation);
    } catch (IOException e)
    {
      showFatalErrorDialog(R.string.dialog_error_storage_title, R.string.dialog_error_storage_message);
      return;
    }

    if (Config.isFirstLaunch(this) && LocationUtils.checkLocationPermission(this))
    {
      final LocationHelper locationHelper = app.getLocationHelper();
      locationHelper.onEnteredIntoFirstRun();
      if (!locationHelper.isActive())
        locationHelper.start();
    }

    if (!asyncContinue)
      processNavigation();
  }

  // Called from MwmApplication::nativeInitFramework like callback.
  @Keep
  @SuppressWarnings({"unused", "unchecked"})
  public void processNavigation()
  {
    Intent input = getIntent();
    Intent result = new Intent(this, DownloadResourcesLegacyActivity.class);
    if (input != null)
    {
      if (input.hasExtra(EXTRA_ACTIVITY_TO_START))
      {
        result = new Intent(this,
                            (Class<? extends Activity>) input.getSerializableExtra(EXTRA_ACTIVITY_TO_START));
      }

      Intent initialIntent = input.hasExtra(EXTRA_INITIAL_INTENT) ?
                           input.getParcelableExtra(EXTRA_INITIAL_INTENT) :
                           input;
      result.putExtra(EXTRA_INITIAL_INTENT, initialIntent);
      if (!initialIntent.hasCategory(Intent.CATEGORY_LAUNCHER))
      {
        /// @todo Is it ok that we don't call setFirstStartDialogSeen here?
        // Wait for the result from MwmActivity for API callers.
        mApiRequest.launch(result);
        return;
      }
    }

    Config.setFirstStartDialogSeen(this);
    startActivity(result);
    finish();
  }
}
