/*
Copyright 2014 Evothings AB

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.evothings;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.bluetooth.*;
import android.bluetooth.BluetoothAdapter.LeScanCallback;
import android.content.*;
import android.app.Activity;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Iterator;
import java.io.UnsupportedEncodingException;
import android.util.Base64;

public class BLE extends CordovaPlugin implements LeScanCallback {
	// Used by startScan().
	private CallbackContext mScanCallbackContext;

	// Used by reset().
	private CallbackContext mResetCallbackContext;

	// The Android application Context.
	private Context mContext;

	private boolean mRegisteredReceiver = false;

	// Called when the device's Bluetooth powers on.
	// Used by startScan() and connect() to wait for power-on if Bluetooth was off when the function was called.
	private Runnable mOnPowerOn;

	// Used to send error messages to the JavaScript side if Bluetooth power-on fails.
	private CallbackContext mPowerOnCallbackContext;

	// Map of connected devices.
	HashMap<Integer, GattHandler> mGatt = null;

	// Monotonically incrementing key to the Gatt map.
	int mNextGattHandle = 1;

	// Called each time cordova.js is loaded.
	@Override
	public void initialize(final CordovaInterface cordova, CordovaWebView webView) {
		super.initialize(cordova, webView);
		mContext = webView.getContext();

		if(!mRegisteredReceiver) {
			mContext.registerReceiver(new BluetoothStateReceiver(), new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
			mRegisteredReceiver = true;
		}
	}

	// Handles JavaScript-to-native function calls.
	// Returns true if a supported function was called, false otherwise.
	@Override
	public boolean execute(String action, CordovaArgs args, final CallbackContext callbackContext)
		throws JSONException
	{
		if("startScan".equals(action)) { startScan(args, callbackContext); return true; }
		else if("stopScan".equals(action)) { stopScan(args, callbackContext); return true; }
		else if("connect".equals(action)) { connect(args, callbackContext); return true; }
		else if("close".equals(action)) { close(args, callbackContext); return true; }
		else if("rssi".equals(action)) { rssi(args, callbackContext); return true; }
		else if("services".equals(action)) { services(args, callbackContext); return true; }
		else if("characteristics".equals(action)) { characteristics(args, callbackContext); return true; }
		else if("descriptors".equals(action)) { descriptors(args, callbackContext); return true; }
		else if("readCharacteristic".equals(action)) { readCharacteristic(args, callbackContext); return true; }
		else if("readDescriptor".equals(action)) { readDescriptor(args, callbackContext); return true; }
		else if("writeCharacteristic".equals(action)) { writeCharacteristic(args, callbackContext); return true; }
		else if("writeDescriptor".equals(action)) { writeDescriptor(args, callbackContext); return true; }
		else if("enableNotification".equals(action)) { enableNotification(args, callbackContext); return true; }
		else if("disableNotification".equals(action)) { disableNotification(args, callbackContext); return true; }
		else if("testCharConversion".equals(action)) { testCharConversion(args, callbackContext); return true; }
		else if("reset".equals(action)) { reset(args, callbackContext); return true; }
		return false;
	}

	/**
	* Called when the WebView does a top-level navigation or refreshes.
	*
	* Plugins should stop any long-running processes and clean up internal state.
	*
	* Does nothing by default.
	*
	* Our version should stop any ongoing scan, and close any existing connections.
	*/
	@Override
	public void onReset() {
		if(mScanCallbackContext != null) {
			BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
			a.stopLeScan(this);
			mScanCallbackContext = null;
		}
		if(mGatt != null) {
			Iterator<GattHandler> itr = mGatt.values().iterator();
			while(itr.hasNext()) {
				GattHandler gh = itr.next();
				if(gh.mGatt != null)
					gh.mGatt.close();
			}
			mGatt.clear();
		}
	}

	// Possibly asynchronous.
	// Ensures Bluetooth is powered on, then calls the Runnable \a onPowerOn.
	// Calls cc.error if power-on fails.
	private void checkPowerState(BluetoothAdapter adapter, CallbackContext cc, Runnable onPowerOn) {
		if(adapter == null) {
			return;
		}
		if(adapter.getState() == BluetoothAdapter.STATE_ON) {
			// Bluetooth is ON
			onPowerOn.run();
		} else {
			mOnPowerOn = onPowerOn;
			mPowerOnCallbackContext = cc;
			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			cordova.startActivityForResult(this, enableBtIntent, 0);
		}
	}

	// Called whe the Bluetooth power-on request is completed.
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent intent) {
		Runnable onPowerOn = mOnPowerOn;
		CallbackContext cc = mPowerOnCallbackContext;
		mOnPowerOn = null;
		mPowerOnCallbackContext = null;
		if(resultCode == Activity.RESULT_OK) {
			onPowerOn.run();
		} else {
			if(resultCode == Activity.RESULT_CANCELED) {
				cc.error("Bluetooth power-on canceled");
			} else {
				cc.error("Bluetooth power-on failed, code "+resultCode);
			}
		}
	}

	// These three functions each send a JavaScript callback *without* removing the callback context, as is default.

	private void keepCallback(final CallbackContext callbackContext, JSONObject message) {
		PluginResult r = new PluginResult(PluginResult.Status.OK, message);
		r.setKeepCallback(true);
		callbackContext.sendPluginResult(r);
	}

	private void keepCallback(final CallbackContext callbackContext, String message) {
		PluginResult r = new PluginResult(PluginResult.Status.OK, message);
		r.setKeepCallback(true);
		callbackContext.sendPluginResult(r);
	}

	private void keepCallback(final CallbackContext callbackContext, byte[] message) {
		PluginResult r = new PluginResult(PluginResult.Status.OK, message);
		r.setKeepCallback(true);
		callbackContext.sendPluginResult(r);
	}

	// API implementation. See ble.js for documentation.
	private void startScan(final CordovaArgs args, final CallbackContext callbackContext) {
		final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		final LeScanCallback self = this;
		checkPowerState(adapter, callbackContext, new Runnable() {
			@Override
			public void run() {
				if(!adapter.startLeScan(self)) {
					callbackContext.error("Android function startLeScan failed");
					return;
				}
				mScanCallbackContext = callbackContext;
			}
		});
	}

	// Called during scan, when a device advertisement is received.
	public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
		if(mScanCallbackContext == null) {
			return;
		}
		try {
			//System.out.println("onLeScan "+device.getAddress()+" "+rssi+" "+device.getName());
			JSONObject o = new JSONObject();
			o.put("address", device.getAddress());
			o.put("rssi", rssi);
			o.put("name", device.getName());
			o.put("scanRecord", Base64.encodeToString(scanRecord, Base64.NO_WRAP));
			keepCallback(mScanCallbackContext, o);
		} catch(JSONException e) {
			mScanCallbackContext.error(e.toString());
		}
	}

	// API implementation.
	private void stopScan(final CordovaArgs args, final CallbackContext callbackContext) {
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		adapter.stopLeScan(this);
		mScanCallbackContext = null;
	}

	// API implementation.
	private void connect(final CordovaArgs args, final CallbackContext callbackContext) {
		final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		checkPowerState(adapter, callbackContext, new Runnable() {
			@Override
			public void run() {
				try {
					// Each device connection has a GattHandler, which handles the events the can happen to the connection.
					// The implementation of the GattHandler class is found at the end of this file.
					GattHandler gh = new GattHandler(mNextGattHandle, callbackContext);
					gh.mGatt = adapter.getRemoteDevice(args.getString(0)).connectGatt(mContext, true, gh);
					// Note that gh.mGatt and this.mGatt are different object and have different types.
					if(mGatt == null)
						mGatt = new HashMap<Integer, GattHandler>();
					Object res = mGatt.put(mNextGattHandle, gh);
					assert(res == null);
					mNextGattHandle++;
				} catch(Exception e) {
					e.printStackTrace();
					callbackContext.error(e.toString());
				}
			}
		});
	}

	// API implementation.
	private void close(final CordovaArgs args, final CallbackContext callbackContext) {
		try {
			GattHandler gh = mGatt.get(args.getInt(0));
			gh.mGatt.close();
			mGatt.remove(args.getInt(0));
		} catch(JSONException e) {
			e.printStackTrace();
			callbackContext.error(e.toString());
		}
	}

	// API implementation.
	private void rssi(final CordovaArgs args, final CallbackContext callbackContext) {
		GattHandler gh = null;
		try {
			gh = mGatt.get(args.getInt(0));
			if(gh.mRssiContext != null) {
				callbackContext.error("Previous call to rssi() not yet completed!");
				return;
			}
			gh.mRssiContext = callbackContext;
			if(!gh.mGatt.readRemoteRssi()) {
				gh.mRssiContext = null;
				callbackContext.error("readRemoteRssi");
			}
		} catch(Exception e) {
			e.printStackTrace();
			if(gh != null) {
				gh.mRssiContext = null;
			}
			callbackContext.error(e.toString());
		}
	}

	// API implementation.
	private void services(final CordovaArgs args, final CallbackContext callbackContext) {
		try {
			final GattHandler gh = mGatt.get(args.getInt(0));
			gh.mOperations.add(new Runnable() {
				@Override
				public void run() {
					gh.mCurrentOpContext = callbackContext;
					if(!gh.mGatt.discoverServices()) {
						gh.mCurrentOpContext = null;
						callbackContext.error("discoverServices");
						gh.process();
					}
				}
			});
			gh.process();
		} catch(Exception e) {
			e.printStackTrace();
			callbackContext.error(e.toString());
		}
	}

	// API implementation.
	private void characteristics(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		JSONArray a = new JSONArray();
		for(BluetoothGattCharacteristic c : gh.mServices.get(args.getInt(1)).getCharacteristics()) {
			if(gh.mCharacteristics == null)
				gh.mCharacteristics = new HashMap<Integer, BluetoothGattCharacteristic>();
			Object res = gh.mCharacteristics.put(gh.mNextHandle, c);
			assert(res == null);

			JSONObject o = new JSONObject();
			o.put("handle", gh.mNextHandle);
			o.put("uuid", c.getUuid().toString());
			o.put("permissions", c.getPermissions());
			o.put("properties", c.getProperties());
			o.put("writeType", c.getWriteType());

			gh.mNextHandle++;
			a.put(o);
		}
		callbackContext.success(a);
	}

	// API implementation.
	private void descriptors(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		JSONArray a = new JSONArray();
		for(BluetoothGattDescriptor d : gh.mCharacteristics.get(args.getInt(1)).getDescriptors()) {
			if(gh.mDescriptors == null)
				gh.mDescriptors = new HashMap<Integer, BluetoothGattDescriptor>();
			Object res = gh.mDescriptors.put(gh.mNextHandle, d);
			assert(res == null);

			JSONObject o = new JSONObject();
			o.put("handle", gh.mNextHandle);
			o.put("uuid", d.getUuid().toString());
			o.put("permissions", d.getPermissions());

			gh.mNextHandle++;
			a.put(o);
		}
		callbackContext.success(a);
	}

	// API implementation.
	private void readCharacteristic(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		gh.mOperations.add(new Runnable() {
			@Override
			public void run() {
				try {
					gh.mCurrentOpContext = callbackContext;
					if(!gh.mGatt.readCharacteristic(gh.mCharacteristics.get(args.getInt(1)))) {
						gh.mCurrentOpContext = null;
						callbackContext.error("readCharacteristic");
						gh.process();
					}
				} catch(JSONException e) {
					e.printStackTrace();
					callbackContext.error(e.toString());
					gh.process();
				}
			}
		});
		gh.process();
	}

	// API implementation.
	private void readDescriptor(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		gh.mOperations.add(new Runnable() {
			@Override
			public void run() {
				try {
					gh.mCurrentOpContext = callbackContext;
					if(!gh.mGatt.readDescriptor(gh.mDescriptors.get(args.getInt(1)))) {
						gh.mCurrentOpContext = null;
						callbackContext.error("readDescriptor");
						gh.process();
					}
				} catch(JSONException e) {
					e.printStackTrace();
					callbackContext.error(e.toString());
					gh.process();
				}
			}
		});
		gh.process();
	}

	// API implementation.
	private void writeCharacteristic(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		gh.mOperations.add(new Runnable() {
			@Override
			public void run() {
				try {
					gh.mCurrentOpContext = callbackContext;
					BluetoothGattCharacteristic c = gh.mCharacteristics.get(args.getInt(1));
					System.out.println("writeCharacteristic("+args.getInt(0)+", "+args.getInt(1)+", "+args.getString(2)+")");
					c.setValue(args.getArrayBuffer(2));
					if(!gh.mGatt.writeCharacteristic(c)) {
						gh.mCurrentOpContext = null;
						callbackContext.error("writeCharacteristic");
						gh.process();
					}
				} catch(JSONException e) {
					e.printStackTrace();
					callbackContext.error(e.toString());
					gh.process();
				}
			}
		});
		gh.process();
	}

	// API implementation.
	private void writeDescriptor(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		gh.mOperations.add(new Runnable() {
			@Override
			public void run() {
				try {
					gh.mCurrentOpContext = callbackContext;
					BluetoothGattDescriptor d = gh.mDescriptors.get(args.getInt(1));
					d.setValue(args.getArrayBuffer(2));
					if(!gh.mGatt.writeDescriptor(d)) {
						gh.mCurrentOpContext = null;
						callbackContext.error("writeDescriptor");
						gh.process();
					}
				} catch(JSONException e) {
					e.printStackTrace();
					callbackContext.error(e.toString());
					gh.process();
				}
			}
		});
		gh.process();
	}

	// API implementation.
	private void enableNotification(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		BluetoothGattCharacteristic c = gh.mCharacteristics.get(args.getInt(1));
		gh.mNotifications.put(c, callbackContext);
		if(!gh.mGatt.setCharacteristicNotification(c, true)) {
			callbackContext.error("setCharacteristicNotification");
		}
	}

	// API implementation.
	private void disableNotification(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		BluetoothGattCharacteristic c = gh.mCharacteristics.get(args.getInt(1));
		gh.mNotifications.remove(c);
		if(gh.mGatt.setCharacteristicNotification(c, false)) {
			callbackContext.success();
		} else {
			callbackContext.error("setCharacteristicNotification");
		}
	}

	// API implementation.
	private void testCharConversion(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		byte[] b = {(byte)args.getInt(0)};
		callbackContext.success(b);
	}

	// API implementation.
	private void reset(final CordovaArgs args, final CallbackContext cc) throws JSONException {
		mResetCallbackContext = null;
		BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
		if(mScanCallbackContext != null) {
			a.stopLeScan(this);
			mScanCallbackContext = null;
		}
		int state = a.getState();
		//STATE_OFF, STATE_TURNING_ON, STATE_ON, STATE_TURNING_OFF.
		if(state == BluetoothAdapter.STATE_TURNING_ON) {
			// reset in progress; wait for STATE_ON.
			mResetCallbackContext = cc;
			return;
		}
		if(state == BluetoothAdapter.STATE_TURNING_OFF) {
			// reset in progress; wait for STATE_OFF.
			mResetCallbackContext = cc;
			return;
		}
		if(state == BluetoothAdapter.STATE_OFF) {
			boolean res = a.enable();
			if(res) {
				mResetCallbackContext = cc;
			} else {
				cc.error("enable");
			}
			return;
		}
		if(state == BluetoothAdapter.STATE_ON) {
			boolean res = a.disable();
			if(res) {
				mResetCallbackContext = cc;
			} else {
				cc.error("disable");
			}
			return;
		}
		cc.error("Unknown state: "+state);
	}

	// Receives notification about Bluetooth power on and off. Used by reset().
	class BluetoothStateReceiver extends BroadcastReceiver {
		public void onReceive(Context context, Intent intent) {
			BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
			int state = a.getState();
			System.out.println("BluetoothState: "+a);
			if(mResetCallbackContext != null) {
				if(state == BluetoothAdapter.STATE_OFF) {
					boolean res = a.enable();
					if(!res) {
						mResetCallbackContext.error("enable");
						mResetCallbackContext = null;
					}
				}
				if(state == BluetoothAdapter.STATE_ON) {
					mResetCallbackContext.success();
					mResetCallbackContext = null;
				}
			}
		}
	};

	/* Running more than one operation of certain types on remote Gatt devices
	* seem to cause it to stop responding.
	* The known types are 'read' and 'write'.
	* I've added 'services' to be on the safe side.
	* 'rssi' and 'notification' should be safe.
	*/

	// This class handles callbacks pertaining to device connections.
	// Also maintains the per-device operation queue.
	private class GattHandler extends BluetoothGattCallback {
		// Local copy of the key to BLE.mGatt. Fed by BLE.mNextGattHandle.
		final int mHandle;

		// The queue of operations.
		LinkedList<Runnable> mOperations = new LinkedList<Runnable>();

		// connect() and rssi() are handled separately from other operations.
		CallbackContext mConnectContext, mRssiContext, mCurrentOpContext;

		// The Android API connection.
		BluetoothGatt mGatt;

		// Maps of integer to Gatt subobject.
		HashMap<Integer, BluetoothGattService> mServices;
		HashMap<Integer, BluetoothGattCharacteristic> mCharacteristics;
		HashMap<Integer, BluetoothGattDescriptor> mDescriptors;

		// Monotonically incrementing key to the subobject maps.
		int mNextHandle = 1;

		// Notification callbacks. The BluetoothGattCharacteristic object, as found in the mCharacteristics map, is the key.
		HashMap<BluetoothGattCharacteristic, CallbackContext> mNotifications =
			new HashMap<BluetoothGattCharacteristic, CallbackContext>();

		GattHandler(int h, CallbackContext cc) {
			mHandle = h;
			mConnectContext = cc;
		}

		// Run the next operation, if any.
		void process() {
			if(mCurrentOpContext != null)
				return;
			Runnable r = mOperations.poll();
			if(r == null)
				return;
			r.run();
		}

		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			if(status == BluetoothGatt.GATT_SUCCESS) {
				try {
					JSONObject o = new JSONObject();
					o.put("deviceHandle", mHandle);
					o.put("state", newState);
					keepCallback(mConnectContext, o);
				} catch(JSONException e) {
					e.printStackTrace();
					assert(false);
				}
			} else {
				mConnectContext.error(status);
			}
		}
		@Override
		public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
			CallbackContext c = mRssiContext;
			mRssiContext = null;
			if(status == BluetoothGatt.GATT_SUCCESS) {
				c.success(rssi);
			} else {
				c.error(status);
			}
		}
		@Override
		public void onServicesDiscovered(BluetoothGatt g, int status) {
			if(status == BluetoothGatt.GATT_SUCCESS) {
				List<BluetoothGattService> services = g.getServices();
				JSONArray a = new JSONArray();
				for(BluetoothGattService s : services) {
					// give the service a handle.
					if(mServices == null)
						mServices = new HashMap<Integer, BluetoothGattService>();
					Object res = mServices.put(mNextHandle, s);
					assert(res == null);

					try {
						JSONObject o = new JSONObject();
						o.put("handle", mNextHandle);
						o.put("uuid", s.getUuid().toString());
						o.put("type", s.getType());

						mNextHandle++;
						a.put(o);
					} catch(JSONException e) {
						e.printStackTrace();
						assert(false);
					}
				}
				mCurrentOpContext.success(a);
			} else {
				mCurrentOpContext.error(status);
			}
			mCurrentOpContext = null;
			process();
		}
		@Override
		public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
			if(status == BluetoothGatt.GATT_SUCCESS) {
				mCurrentOpContext.success(c.getValue());
			} else {
				mCurrentOpContext.error(status);
			}
			mCurrentOpContext = null;
			process();
		}
		@Override
		public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
			if(status == BluetoothGatt.GATT_SUCCESS) {
				mCurrentOpContext.success(d.getValue());
			} else {
				mCurrentOpContext.error(status);
			}
			mCurrentOpContext = null;
			process();
		}
		@Override
		public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
			if(status == BluetoothGatt.GATT_SUCCESS) {
				mCurrentOpContext.success();
			} else {
				mCurrentOpContext.error(status);
			}
			mCurrentOpContext = null;
			process();
		}
		@Override
		public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
			if(status == BluetoothGatt.GATT_SUCCESS) {
				mCurrentOpContext.success();
			} else {
				mCurrentOpContext.error(status);
			}
			mCurrentOpContext = null;
			process();
		}
		@Override
		public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
			CallbackContext cc = mNotifications.get(c);
			keepCallback(cc, c.getValue());
		}
	};
}
