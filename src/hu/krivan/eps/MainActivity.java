package hu.krivan.eps;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

import android.app.ActionBar;
import android.app.ActionBar.OnNavigationListener;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.Toast;

import com.freddymartens.android.widgets.Gauge;

public class MainActivity extends Activity implements Runnable {
	private static final String TAG = "DemoKit";
	private static final String ACTION_USB_PERMISSION = "hu.krivan.eps.action.USB_PERMISSION";
	public static final byte LED_POLL_CMD = 1;
	public static final byte OVERRIDE_CMD = 2;

	private UsbManager mUsbManager;
	private PendingIntent mPermissionIntent;
	private boolean mPermissionRequestPending;
	private UsbAccessory mAccessory;
	private ParcelFileDescriptor mFileDescriptor;
	private FileInputStream mInputStream;
	private FileOutputStream mOutputStream;
	private int mSelectedSeat = 1;

	private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (ACTION_USB_PERMISSION.equals(action)) {
				synchronized (this) {
					UsbAccessory accessory = (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
					if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
						openAccessory(accessory);
					} else {
						Toast.makeText(getApplicationContext(), "permission denied", Toast.LENGTH_SHORT).show();
						Log.d(TAG, "permission denied for accessory " + accessory);
					}
					mPermissionRequestPending = false;
				}
			} else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
				UsbAccessory accessory = (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
				if (accessory != null && accessory.equals(mAccessory)) {
					closeAccessory();
				}
			}
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
		mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
		IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
		filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
		registerReceiver(mUsbReceiver, filter);

		if (getLastNonConfigurationInstance() != null) {
			mAccessory = (UsbAccessory) getLastNonConfigurationInstance();
			openAccessory(mAccessory);
		}

		setContentView(R.layout.activity_main);

		((SeekBar) findViewById(R.id.seekBar1)).setOnSeekBarChangeListener(new OnSeekBarChangeListener() {

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				// g.setValue(seekBar.getProgress() * 1500.0f / 100.0f );

				sendCommand(OVERRIDE_CMD, (byte) mSelectedSeat, seekBar.getProgress());
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
			}
		});

		ActionBar actionBar = getActionBar();
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
		actionBar.setListNavigationCallbacks(new ArrayAdapter<String>(this, R.layout.spinner_dropdown_item,
				new String[] { "Seat #1", "Seat #2", "Seat #3", "Seat #4", "Seat #5", "Seat #6" }),
				new OnNavigationListener() {

					@Override
					public boolean onNavigationItemSelected(int itemPosition, long itemId) {
						mSelectedSeat = itemPosition + 1;
						return true;
					}
				});
		
		findViewById(R.id.button1).setOnClickListener(new OnClickListener() {
			
			@Override
			public void onClick(View v) {
				((SeekBar) findViewById(R.id.seekBar1)).setProgress(50);
				sendCommand(OVERRIDE_CMD, (byte) mSelectedSeat, 50);
			}
		});
	}

	@Override
	public void onResume() {
		super.onResume();

		Intent intent = getIntent();
		if (mInputStream != null && mOutputStream != null) {
			return;
		}

		UsbAccessory[] accessories = mUsbManager.getAccessoryList();
		UsbAccessory accessory = (accessories == null ? null : accessories[0]);
		if (accessory != null) {
			if (mUsbManager.hasPermission(accessory)) {
				openAccessory(accessory);
			} else {
				synchronized (mUsbReceiver) {
					if (!mPermissionRequestPending) {
						mUsbManager.requestPermission(accessory, mPermissionIntent);
						mPermissionRequestPending = true;
					}
				}
			} // if/else 2
		} else {
			Log.d(TAG, "mAccessory is null");
		} // if/else 1

		// ((Gauge) findViewById(R.id.meter1)).setValue(600.0f);
	} // onResume

	@Override
	protected void onPause() {
		closeAccessory();
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		unregisterReceiver(mUsbReceiver);
		super.onDestroy();
	}

	private void openAccessory(UsbAccessory accessory) {
		mFileDescriptor = mUsbManager.openAccessory(accessory);
		if (mFileDescriptor != null) {
			mAccessory = accessory;
			FileDescriptor fd = mFileDescriptor.getFileDescriptor();
			mInputStream = new FileInputStream(fd);
			mOutputStream = new FileOutputStream(fd);
			Thread thread = new Thread(null, this, "DemoKit");
			thread.start();
			Log.d(TAG, "accessory opened");
			Toast.makeText(getApplicationContext(), "opened", Toast.LENGTH_SHORT).show();
			enableControls(true);

			TimerTask tt = new TimerTask() {

				@Override
				public void run() {
					sendCommand(LED_POLL_CMD, (byte) mSelectedSeat, 0);
				}
			};

			new Timer().scheduleAtFixedRate(tt, 0, 5000);

		} else {
			Toast.makeText(getApplicationContext(), "failed", Toast.LENGTH_SHORT).show();
			Log.d(TAG, "accessory open fail");
		}
	}

	private void enableControls(boolean b) {
	}

	private void closeAccessory() {
		enableControls(false);

		try {
			if (mFileDescriptor != null) {
				mFileDescriptor.close();
				Toast.makeText(getApplicationContext(), "closed", Toast.LENGTH_SHORT).show();
			}
		} catch (IOException e) {
		} finally {
			mFileDescriptor = null;
			mAccessory = null;
		}
	}

	public void run() {
		int resp = 0;

		try {
			final byte[] msg = new byte[3];
			while (mInputStream.read(msg) != -1) {
				runOnUiThread(new Runnable() {
					public void run() {

						if (msg[0] == LED_POLL_CMD) {
							((Gauge) findViewById(R.id.meter1)).setValue(((float)msg[2]) / 2.55f);
						}
					}
				});
			}
		} catch (IOException e) {
			runOnUiThread(new Runnable() {
				public void run() {

					// ((TextView) findViewById(R.id.textView1)).setText("IOEx");
				}
			});
		}
	}

	private final void sendCommand(byte command, byte target, int value) {
		byte[] buffer = new byte[3];
		if (value > 255)
			value = 255;

		buffer[0] = command;
		buffer[1] = target;
		buffer[2] = (byte) value;
		if (mOutputStream != null && buffer[1] != -1) {
			try {
				mOutputStream.write(buffer);
			} catch (IOException e) {
				Log.e(TAG, "write failed", e);
			}
		}
	}
}
