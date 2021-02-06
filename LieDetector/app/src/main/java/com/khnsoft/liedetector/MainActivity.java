package com.khnsoft.liedetector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.data.DataSource;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.fitness.data.Value;
import com.google.android.gms.fitness.request.DataSourcesRequest;
import com.google.android.gms.fitness.request.OnDataPointListener;
import com.google.android.gms.fitness.request.SensorRequest;
import com.neurosky.connection.*;
import com.neurosky.connection.DataType.MindDataType;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
	static final String TAG = "@@@";
	static final int AUTH_REQUEST = 1;
	
	private TextView curStatus;
	private Button switchBtn;
	private Button selectMindwaveDevice;
	private Button sendBtn;
	private Button getBtn;
	private TextView heartrateStatus;
	private LinearLayout waveLayout;
	private DrawWaveView waveView;
	
	private TgStreamReader tgStreamReader;
	private BluetoothAdapter mBluetoothAdapter;
	private BluetoothDevice mBluetoothDevice;
	private int badPacketCount = 0;
	private String address = null;
	
	private boolean isRunning = false;
	private long timeStart;
	
	private PowerManager powerManager;
	private PowerManager.WakeLock wakeLock;
	private boolean isGoogleConnected = false;
	private GoogleApiClient googleApiClient;
	private boolean authInProgress = false;
	private OnDataPointListener onDataPointListener;
	
	private StringBuffer sb = null;
	private boolean mindwaveRawReady = false;
	private boolean mindwaveReady = false;
	private boolean heartrateReady = false;
	private String[] mindwaveData = {"","","","","","","","","",""};
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		PermissionRequest.builder(this)
				.permissions(Manifest.permission.BODY_SENSORS)
				.beforeRequest((permissions, resolver) -> {
					Log.i("@@@", "before: " + permissions);
					resolver.ok();
				})
				.request();
		
		curStatus = findViewById(R.id.cur_status);
		switchBtn = findViewById(R.id.switch_btn);
		heartrateStatus = findViewById(R.id.heartrate_status);
		selectMindwaveDevice = findViewById(R.id.select_mindwave_device);
		sendBtn = findViewById(R.id.send_btn);
		getBtn = findViewById(R.id.get_btn);
		
		switchBtn.setOnClickListener(view -> {
			if (!isRunning) {
				sb = new StringBuffer();
				sb.append("time\traw\tdelta\ttheta\tlowAlpha\thighAlpha\tlowBeta\thighBeta\tlowGamma\tmiddleGamma\theartrate\n");
				isRunning = true;
				badPacketCount = 0;
				timeStart = System.currentTimeMillis();
				startMindwave();
				switchBtn.setText("탐지 중지");
				curStatus.setText("현재 상태: 실행");
				if (isGoogleConnected) {
					findDataSources();
					registerDataSourceListener(DataType.TYPE_HEART_RATE_BPM);
					wakeLock.acquire();
				} else {
					if (MainActivity.this.googleApiClient != null)
						MainActivity.this.googleApiClient.connect();
				}
			} else {
				isRunning = false;
				if (tgStreamReader != null) {
					tgStreamReader.stop();
				}
				switchBtn.setText("탐지 시작");
				curStatus.setText("현재 상태: 정지");
				unregisterFitnessDataListener();
				try {
					if (wakeLock.isHeld())
						wakeLock.release();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
		
		selectMindwaveDevice.setOnClickListener(view -> {
			scanMindwaveDevice();
		});
		
		try {
			mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
			if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
				showToast("블루투스 기능을 켜주세요.");
				finish();
			}
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}
		
		sendBtn.setOnClickListener(v -> {
			if (!isRunning && sb != null) {
				try {
					curStatus.setText("현재 상태: Waiting for result");
					String sRet = new HttpAsyncTask().execute(sb.toString(), "0").get();
					curStatus.setText("현재 상태: " + sRet);
				} catch (Exception e) {
					e.printStackTrace();
				}
			} else {
				showToast("탐지가 완료된 후에 다시 시도해주세요.");
			}
		});
		
		getBtn.setOnClickListener(v -> {
			if (!isRunning && sb != null) {
				try {
					curStatus.setText("현재 상태: Waiting for detection");
					String sRet = new HttpAsyncTask().execute(sb.toString(), "1").get();
					curStatus.setText("현재 상태: " + sRet);
				} catch (Exception e) {
					e.printStackTrace();
				}
			} else {
				showToast("탐지가 완료된 후에 다시 시도해주세요.");
			}
		});
		
		waveLayout = findViewById(R.id.wave_layout);
		setUpDrawWaveView();
		
		BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
		
		powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "WAKELOCK:");
		initGoogleApiClient();
	}
	
	// Related to Heartrate API START
	
	private void initGoogleApiClient() {
		this.googleApiClient = new GoogleApiClient.Builder(this)
				.addApi(Fitness.SENSORS_API)
				.addScope(new Scope(Scopes.FITNESS_BODY_READ))
				.addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
					@Override
					public void onConnected(@Nullable Bundle bundle) {
						isGoogleConnected = true;
					}
					
					@Override
					public void onConnectionSuspended(int i) {
					}
				})
				.addOnConnectionFailedListener(result -> {
					if (!result.hasResolution()) {
						MainActivity.this.finish();
						return;
					}
					
					if (!authInProgress) {
						try {
							authInProgress = true;
							result.startResolutionForResult(MainActivity.this, AUTH_REQUEST);
						} catch (Exception e) {
							e.printStackTrace();
						}
					} else {
						MainActivity.this.finish();
					}
				})
				.build();
	}
	
	private void findDataSources() {
		Fitness.SensorsApi.findDataSources(googleApiClient, new DataSourcesRequest.Builder()
				.setDataTypes(DataType.TYPE_HEART_RATE_BPM)
				.setDataSourceTypes(DataSource.TYPE_RAW)
				.build())
				.setResultCallback(dataSourcesResult -> {
					for (DataSource dataSource : dataSourcesResult.getDataSources()) {
						if (dataSource.getDataType().equals(DataType.TYPE_HEART_RATE_BPM) && onDataPointListener == null) {
							registerDataSourceListener(DataType.TYPE_HEART_RATE_BPM);
						}
					}
				});
	}
	
	private void registerDataSourceListener(DataType dataType) {
		onDataPointListener = dataPoint -> {
			for (Field field : dataPoint.getDataType().getFields()) {
				Value aValue = dataPoint.getValue(field);
				Log.i(TAG, "registerDataSourceListener: Data Received");
				heartrateStatus.setText("심박수: " + aValue.asFloat());
				mindwaveData[9] = ""+aValue.asFloat();
				heartrateReady = true;
				writeData();
			}
		};
		
		Fitness.SensorsApi.add(
				googleApiClient,
				new SensorRequest.Builder()
						.setDataType(dataType)
						.setSamplingRate(2, TimeUnit.SECONDS)
						.setAccuracyMode(SensorRequest.ACCURACY_MODE_DEFAULT)
						.build(),
				onDataPointListener);
	}
	
	private void unregisterFitnessDataListener() {
		if (this.onDataPointListener == null) {
			return;
		}
		if (this.googleApiClient == null) {
			return;
		}
		if (!this.googleApiClient.isConnected()) {
			return;
		}
		
		Fitness.SensorsApi.remove(
				this.googleApiClient,
				this.onDataPointListener);
	}
	
	@Override
	protected void onStop() {
		super.onStop();
		unregisterFitnessDataListener();
		
		if (this.googleApiClient != null && this.googleApiClient.isConnected()) {
			this.googleApiClient.disconnect();
		}
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		if (requestCode == AUTH_REQUEST) {
			authInProgress = false;
			
			if (resultCode == RESULT_OK) {
				if (!this.googleApiClient.isConnecting() && !this.googleApiClient.isConnected()) {
					this.googleApiClient.connect();
				}
			}
		}
	}
	// Related to Heartrate API END
	
	// Related to Mindwave START
	private void setUpDrawWaveView() {
		waveView = new DrawWaveView(getApplicationContext());
		waveLayout.addView(waveView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		waveView.setValue(2048, 2048, -2048);
	}
	
	private void updateWaveView(int data) {
		if (waveView != null) {
			waveView.updateData(data);
		}
	}
	
	private void startMindwave() {
		if (address != null) {
			BluetoothDevice bd = mBluetoothAdapter.getRemoteDevice(address);
			createStreamReader(bd);
			tgStreamReader.connectAndStart();
		} else {
			showToast("Mindwave 기기를 먼저 연결해주세요.");
		}
	}
	
	private TgStreamReader createStreamReader(BluetoothDevice bd) {
		if (tgStreamReader == null) {
			tgStreamReader = new TgStreamReader(bd, tgCallback);
			tgStreamReader.startLog();
		} else {
			tgStreamReader.changeBluetoothDevice(bd);
			tgStreamReader.setTgStreamHandler(tgCallback);
		}
		
		return tgStreamReader;
	}
	
	private static final int MSG_UPDATE_BAD_PACKET = 1001;
	private static final int MSG_UPDATE_STATE = 1002;
	private boolean isReadFilter = false;
	
	private Handler LinkDetectedHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case 1234:
					tgStreamReader.MWM15_getFilterType();
					isReadFilter = true;
					break;
				case 1235:
					tgStreamReader.MWM15_setFilterType(MindDataType.FilterType.FILTER_60HZ);
					LinkDetectedHandler.sendEmptyMessageDelayed(1237, 1000);
					break;
				case 1236:
					tgStreamReader.MWM15_setFilterType(MindDataType.FilterType.FILTER_50HZ);
					LinkDetectedHandler.sendEmptyMessageDelayed(1237, 1000);
					break;
				case 1237:
					tgStreamReader.MWM15_getFilterType();
					break;
				case MindDataType.CODE_FILTER_TYPE:
					if (isReadFilter) {
						isReadFilter = false;
						if (msg.arg1 == MindDataType.FilterType.FILTER_50HZ.getValue()) {
							LinkDetectedHandler.sendEmptyMessageDelayed(1235, 1000);
						} else if (msg.arg1 == MindDataType.FilterType.FILTER_60HZ.getValue()) {
							LinkDetectedHandler.sendEmptyMessageDelayed(1236, 1000);
						}
					}
					break;
				case MindDataType.CODE_RAW:
					Log.i(TAG, "RAW_WAVE: " + msg.arg1);
					updateWaveView(msg.arg1);
					mindwaveData[0] = ""+msg.arg1;
					mindwaveRawReady = true;
					writeData();
					break;
				case MindDataType.CODE_EEGPOWER:
					EEGPower power = (EEGPower) msg.obj;
					if (power.isValidate()) {
						mindwaveData[1] = ""+power.delta;
						mindwaveData[2] = ""+power.theta;
						mindwaveData[3] = ""+power.lowAlpha;
						mindwaveData[4] = ""+power.highAlpha;
						mindwaveData[5] = ""+power.lowBeta;
						mindwaveData[6] = ""+power.highBeta;
						mindwaveData[7] = ""+power.lowGamma;
						mindwaveData[8] = ""+power.middleGamma;
						mindwaveReady = true;
						writeData();
					}
					break;
				default:
					break;
			}
			super.handleMessage(msg);
		}
	};
	
	private int currentState = 0;
	private TgStreamHandler tgCallback = new TgStreamHandler() {
		@Override
		public void onDataReceived(int datatype, int data, Object obj) {
			if (isRunning) {
				Message msg = LinkDetectedHandler.obtainMessage();
				msg.what = datatype;
				msg.arg1 = data;
				msg.obj = obj;
				LinkDetectedHandler.sendMessage(msg);
			}
		}
		
		@Override
		public void onStatesChanged(int connectionStates) {
			currentState = connectionStates;
			switch (connectionStates) {
				case ConnectionStates.STATE_CONNECTED:
					showToast("Mindwave 연결됨");
					break;
				case ConnectionStates.STATE_WORKING:
					LinkDetectedHandler.sendEmptyMessageDelayed(1234, 5000);
					break;
			}
			Message msg = LinkDetectedHandler.obtainMessage();
			msg.what = MSG_UPDATE_STATE;
			msg.arg1 = connectionStates;
			LinkDetectedHandler.sendMessage(msg);
		}
		
		@Override
		public void onChecksumFail(byte[] bytes, int i, int i1) {
			badPacketCount++;
			Message msg = LinkDetectedHandler.obtainMessage();
			msg.what = MSG_UPDATE_BAD_PACKET;
			msg.arg1 = badPacketCount;
			LinkDetectedHandler.sendMessage(msg);
		}
		
		@Override
		public void onRecordFail(int i) {
		
		}
	};
	
	private ListView listSelect;
	private BTDeviceListAdapter deviceListAdapter = null;
	private Dialog selectDialog;
	
	private void scanMindwaveDevice() {
		if (mBluetoothAdapter.isDiscovering()) {
			mBluetoothAdapter.cancelDiscovery();
		}
		
		setUpDeviceListView();
		IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
		this.registerReceiver(mReceiver, filter);
		
		mBluetoothAdapter.startDiscovery();
	}
	
	private void setUpDeviceListView() {
		LayoutInflater inflater = LayoutInflater.from(this);
		View view = inflater.inflate(R.layout.dialog_select_device, null);
		listSelect = view.findViewById(R.id.list_select);
		selectDialog = new Dialog(this, R.style.dialog1);
		selectDialog.setContentView(view);
		
		deviceListAdapter = new BTDeviceListAdapter(this);
		listSelect.setAdapter(deviceListAdapter);
		listSelect.setOnItemClickListener(selectDeviceItemClickListener);
		
		selectDialog.setOnCancelListener((arg0) -> {
			MainActivity.this.unregisterReceiver(mReceiver);
		});
		
		selectDialog.show();
		
		Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
		for (BluetoothDevice device : pairedDevices) {
			deviceListAdapter.addDevice(device);
		}
		deviceListAdapter.notifyDataSetChanged();
	}
	
	private AdapterView.OnItemClickListener selectDeviceItemClickListener = new AdapterView.OnItemClickListener() {
		@Override
		public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
			if (mBluetoothAdapter.isDiscovering()) {
				mBluetoothAdapter.cancelDiscovery();
			}
			
			MainActivity.this.unregisterReceiver(mReceiver);
			
			mBluetoothDevice = deviceListAdapter.getDevice(position);
			selectDialog.dismiss();
			selectDialog = null;
			
			address = mBluetoothDevice.getAddress();
			
			BluetoothDevice remoteDevice = mBluetoothAdapter.getRemoteDevice(mBluetoothDevice.getAddress());
			
			tgStreamReader = createStreamReader(remoteDevice);
			tgStreamReader.connectAndStart();
		}
	};
	
	private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			
			if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				
				deviceListAdapter.addDevice(device);
				deviceListAdapter.notifyDataSetChanged();
			}
		}
	};
	// Related to Mindwave END
	
	
	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		new PermissionMatcher(MainActivity.this, requestCode, permissions, grantResults)
				.is(Manifest.permission.BODY_SENSORS, isOk -> Log.i("@@@", "BODY_SENSORS: " + isOk))
				.denied(denied -> true)
				.never(never -> {
					showToast("어플을 다시 설치하고 권한을 허용해주세요.");
					finish();
				});
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
	}
	
	private void writeData() {
		//if (heartrateReady && mindwaveReady && mindwaveRawReady) {
		sb.append(String.format("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", System.currentTimeMillis() - timeStart, mindwaveData[0], mindwaveData[1], mindwaveData[2],
				mindwaveData[3], mindwaveData[4], mindwaveData[5], mindwaveData[6], mindwaveData[7], mindwaveData[8], mindwaveData[9]));
		//heartrateReady = false;
		//mindwaveRawReady = false;
		//mindwaveReady = false;
		//}
		
		//*
		if (heartrateReady) {
			mindwaveData[9] = null;
			heartrateReady = false;
		}
		if (mindwaveReady) {
			mindwaveData[1] = null;
			mindwaveData[2] = null;
			mindwaveData[3] = null;
			mindwaveData[4] = null;
			mindwaveData[5] = null;
			mindwaveData[6] = null;
			mindwaveData[7] = null;
			mindwaveData[8] = null;
			mindwaveReady = false;
		}
		if (mindwaveRawReady) {
			mindwaveData[0] = null;
			heartrateReady = false;
		}
	}
	
	private void showToast(String msg) {
		this.runOnUiThread(() -> {
			Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
		});
	}
}

class HttpAsyncTask extends AsyncTask<String, Void, String> {
	private final static String url = "http://svclaw.ipdisk.co.kr:8002/detect/save/";
	private final static String url2 = "http://svclaw.ipdisk.co.kr:8002/detect/get/";
	
	@Override
	protected String doInBackground(String... str) {
		return POST(str[0], Integer.parseInt(str[1]));
	}
	
	private String POST(String sMsg, int mode) {
		String ret = "Did not work!";
		InputStream is = null;
		HttpURLConnection httpCon = null;
		
		try {
			URL urlCon = null;
			if (mode == 0) urlCon = new URL(url);
			else if (mode == 1) urlCon = new URL(url2);
			httpCon = (HttpURLConnection) urlCon.openConnection();
			
			httpCon.setRequestProperty("Content-type", "application/json");
			httpCon.setRequestMethod("POST");
			httpCon.setDoOutput(true);
			httpCon.setConnectTimeout(3000);
			
			OutputStream os = httpCon.getOutputStream();
			os.write(sMsg.getBytes("UTF-8"));
			os.flush();
			
			int status = httpCon.getResponseCode();
			if (status != HttpURLConnection.HTTP_OK)
				is = httpCon.getErrorStream();
			else
				is = httpCon.getInputStream();
			if (is != null) ret = convertInputStreamToString(is);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			httpCon.disconnect();
		}
		
		return ret;
	}
	
	private String convertInputStreamToString(InputStream inputStream) throws IOException {
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
		String line = "";
		StringBuffer result = new StringBuffer();
		while ((line = bufferedReader.readLine()) != null) {
			result.append(line);
		}
		inputStream.close();
		return result.toString().trim();
	}
}