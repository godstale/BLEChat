package com.hardcopy.blechat.bluetooth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.hardcopy.blechat.utils.Logs;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;



public class BleManager {

	// Debugging
	private static final String TAG = "BleManager";
	
	// Constants that indicate the current connection state
	public static final int STATE_ERROR = -1;
	public static final int STATE_NONE = 0;		// Initialized
	public static final int STATE_IDLE = 1;		// Not connected
	public static final int STATE_SCANNING = 2;	// Scanning
	public static final int STATE_CONNECTING = 13;	// Connecting
	public static final int STATE_CONNECTED = 16;	// Connected
	
    // Message types sent from the BluetoothManager to Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;
	
	public static final long SCAN_PERIOD = 5*1000;	// Stops scanning after a pre-defined scan period.
	public static final long SCAN_INTERVAL = 5*60*1000;
	
	// System, Management
	private static Context mContext = null;
	private static BleManager mBleManager = null;		// Singleton pattern
	private final Handler mHandler;
	
	// Bluetooth
	private final BluetoothAdapter mBluetoothAdapter;
	private BluetoothAdapter.LeScanCallback mLeScanCallback = null;
	
	private ArrayList<BluetoothDevice> mDeviceList = new ArrayList<BluetoothDevice>();
	private BluetoothDevice mDefaultDevice = null;
	
	private BluetoothGatt mBluetoothGatt = null;
	
	private ArrayList<BluetoothGattService> mGattServices 
			= new ArrayList<BluetoothGattService>();
	private BluetoothGattService mDefaultService = null;
	private ArrayList<BluetoothGattCharacteristic> mGattCharacteristics 
			= new ArrayList<BluetoothGattCharacteristic>();
	private ArrayList<BluetoothGattCharacteristic> mWritableCharacteristics 
			= new ArrayList<BluetoothGattCharacteristic>();
	private BluetoothGattCharacteristic mDefaultChar = null;
	
	
	// Parameters
	private int mState = -1;
	
	
	/**
	 * Constructor. Prepares a new Bluetooth session.
	 * @param context  The UI Activity Context
	 * @param handler  A Listener to receive messages back to the UI Activity
	 */
	private BleManager(Context context, Handler h) {
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		mState = STATE_NONE;
		mHandler = h;
		mContext = context;
		
		if(mContext == null)
			return;
	}
	
	public synchronized static BleManager getInstance(Context c, Handler h) {
		if(mBleManager == null)
			mBleManager = new BleManager(c, h);
		
		return mBleManager;
	}

	public synchronized void finalize() {
		// Make sure we're not doing discovery anymore
		if (mBluetoothAdapter != null) {
			mState = STATE_IDLE;
			mBluetoothAdapter.stopLeScan(mLeScanCallback);
			disconnect();
		}

		mDefaultDevice = null;
		mBluetoothGatt = null;
		mDefaultService = null;
		mGattServices.clear();
		mGattCharacteristics.clear();
		mWritableCharacteristics.clear();
		
		if(mContext == null)
			return;
        
		// Don't forget this!!
		// Unregister broadcast listeners
//		mContext.unregisterReceiver(mReceiver);
	}
	
	
	
	/*****************************************************
	 *	Private methods
	 ******************************************************/
	
	/**
	 * This method extracts UUIDs from advertised data
	 * Because Android native code has bugs in parsing 128bit UUID
	 * use this method instead.
	 */
	
	private void stopScanning() {
		if(mState < STATE_CONNECTING) {
			mState = STATE_IDLE;
			mHandler.obtainMessage(MESSAGE_STATE_CHANGE, STATE_IDLE, 0).sendToTarget();
		}
		mBluetoothAdapter.stopLeScan(mLeScanCallback);
	}
	
	/**
	 * Check services and looking for writable characteristics
	 */
	private int checkGattServices(List<BluetoothGattService> gattServices) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Logs.d("# BluetoothAdapter not initialized");
            return -1;
        }
        
		for (BluetoothGattService gattService : gattServices) {
			// Default service info
			Logs.d("# GATT Service: "+gattService.toString());
			
			// Remember service
			mGattServices.add(gattService);
			
			// Extract characteristics
			List<BluetoothGattCharacteristic> gattCharacteristics = gattService.getCharacteristics();
			for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
				// Remember characteristic
				mGattCharacteristics.add(gattCharacteristic);
				Logs.d("# GATT Char: "+gattCharacteristic.toString());
				
				boolean isWritable = isWritableCharacteristic(gattCharacteristic);
				if(isWritable) {
					mWritableCharacteristics.add(gattCharacteristic);
				}
				
				boolean isReadable = isReadableCharacteristic(gattCharacteristic); 
				if(isReadable) {
					readCharacteristic(gattCharacteristic);
				}
				
				if(isNotificationCharacteristic(gattCharacteristic)) {
					setCharacteristicNotification(gattCharacteristic, true);
					if(isWritable && isReadable) {
						mDefaultChar = gattCharacteristic;
					}
				}
			}
		}
		
		return mWritableCharacteristics.size();
	}
	
	private boolean isWritableCharacteristic(BluetoothGattCharacteristic chr) {
		if(chr == null) return false;
		
		final int charaProp = chr.getProperties();
		if (((charaProp & BluetoothGattCharacteristic.PROPERTY_WRITE) |
				(charaProp & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) > 0) {
			Logs.d("# Found writable characteristic");
			return true;
		} else {
			Logs.d("# Not writable characteristic");
			return false;
		}
	}
	
	private boolean isReadableCharacteristic(BluetoothGattCharacteristic chr) {
		if(chr == null) return false;
		
		final int charaProp = chr.getProperties();
		if((charaProp & BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
			Logs.d("# Found readable characteristic");
			return true;
		} else {
			Logs.d("# Not readable characteristic");
			return false;
		}
	}
	
	private boolean isNotificationCharacteristic(BluetoothGattCharacteristic chr) {
		if(chr == null) return false;
		
		final int charaProp = chr.getProperties();
		if((charaProp & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
			Logs.d("# Found notification characteristic");
			return true;
		} else {
			Logs.d("# Not notification characteristic");
			return false;
		}
    }
	
    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Logs.d("# BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }
    
    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Logs.d("# BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);
    }
	
	
	/*****************************************************
	 *	Public methods
	 ******************************************************/
	
	public void setScanCallback(BluetoothAdapter.LeScanCallback cb) {
		mLeScanCallback = cb;
	}
	
	public int getState() {
		return mState;
	}
	
	public boolean scanLeDevice(final boolean enable) {
		boolean isScanStarted = false;
		if (enable) {
			if(mState == STATE_SCANNING)
				return false;
			
			if(mBluetoothAdapter.startLeScan(mLeScanCallback)) {
				mState = STATE_SCANNING;
				mDeviceList.clear();

				// If you want to scan for only specific types of peripherals
				// call below function instead
				//startLeScan(UUID[], BluetoothAdapter.LeScanCallback);
				
				// Stops scanning after a pre-defined scan period.
				mHandler.postDelayed(new Runnable() {
						@Override
						public void run() {
							stopScanning();
						}
					}, SCAN_PERIOD);
				
				mHandler.obtainMessage(MESSAGE_STATE_CHANGE, STATE_SCANNING, 0).sendToTarget();
				isScanStarted = true;
			}
		} else {
			if(mState < STATE_CONNECTING) {
				mState = STATE_IDLE;
				mHandler.obtainMessage(MESSAGE_STATE_CHANGE, STATE_IDLE, 0).sendToTarget();
			}
			stopScanning();
		}
		
		return isScanStarted;
	}
	
	public boolean scanLeDevice(final boolean enable, UUID[] uuid) {
		boolean isScanStarted = false;
		if (enable) {
			if(mState == STATE_SCANNING)
				return false;
			
			if(mBluetoothAdapter.startLeScan(uuid, mLeScanCallback)) {
				mState = STATE_SCANNING;
				mDeviceList.clear();

				// If you want to scan for only specific types of peripherals
				// call below function instead
				//startLeScan(UUID[], BluetoothAdapter.LeScanCallback);
				
				// Stops scanning after a pre-defined scan period.
				mHandler.postDelayed(new Runnable() {
						@Override
						public void run() {
							stopScanning();
						}
					}, SCAN_PERIOD);
				
				mHandler.obtainMessage(MESSAGE_STATE_CHANGE, STATE_SCANNING, 0).sendToTarget();
				isScanStarted = true;
			}
		} else {
			if(mState < STATE_CONNECTING) {
				mState = STATE_IDLE;
				mHandler.obtainMessage(MESSAGE_STATE_CHANGE, STATE_IDLE, 0).sendToTarget();
			}
			stopScanning();
		}
		
		return isScanStarted;
	}
	
	public boolean connectGatt(Context c, boolean bAutoReconnect, BluetoothDevice device) {
		if(c == null || device == null)
			return false;

		mGattServices.clear();
		mGattCharacteristics.clear();
		mWritableCharacteristics.clear();
		
		mBluetoothGatt = device.connectGatt(c, bAutoReconnect, mGattCallback);
		mDefaultDevice = device;
		
		mState = STATE_CONNECTING;
		mHandler.obtainMessage(MESSAGE_STATE_CHANGE, STATE_CONNECTING, 0).sendToTarget();
		return true;
	}
	
	public boolean connectGatt(Context c, boolean bAutoReconnect, String address) {
		if(c == null || address == null)
			return false;
		
		if(mBluetoothGatt != null && mDefaultDevice != null
				&& address.equals(mDefaultDevice.getAddress())) {
			 if (mBluetoothGatt.connect()) {
				 mState = STATE_CONNECTING;
				 return true;
			 }
		}
		
		BluetoothDevice device = 
				BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
		if (device == null) {
			Logs.d("# Device not found.  Unable to connect.");
			return false;
		}
		
		mGattServices.clear();
		mGattCharacteristics.clear();
		mWritableCharacteristics.clear();
		
		mBluetoothGatt = device.connectGatt(c, bAutoReconnect, mGattCallback);
		mDefaultDevice = device;
		
		mState = STATE_CONNECTING;
		mHandler.obtainMessage(MESSAGE_STATE_CHANGE, STATE_CONNECTING, 0).sendToTarget();
		return true;
	}
	
    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Logs.d("# BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }
	
	public boolean write(BluetoothGattCharacteristic chr, byte[] data) {
		if (mBluetoothGatt == null) {
		    Logs.d(TAG, "# BluetoothGatt not initialized");
		    return false;
		}
		
		BluetoothGattCharacteristic writableChar = null;
		
		if(chr == null) {
			if(mDefaultChar == null) {
				for(BluetoothGattCharacteristic bgc : mWritableCharacteristics) {
					if(isWritableCharacteristic(bgc)) {
						writableChar = bgc;
					}
				}
				if(writableChar == null) {
					Logs.d(TAG, "# Write failed - No available characteristic");
					return false;
				}
			} else {
				if(isWritableCharacteristic(mDefaultChar)) {
					Logs.d("# Default GattCharacteristic is PROPERY_WRITE | PROPERTY_WRITE_NO_RESPONSE");
					writableChar = mDefaultChar;
				} else {
					Logs.d("# Default GattCharacteristic is not writable");
					mDefaultChar = null;
					return false;
				}
			}
		} else {
			if (isWritableCharacteristic(chr)) {
				Logs.d("# user GattCharacteristic is PROPERY_WRITE | PROPERTY_WRITE_NO_RESPONSE");
				writableChar = chr;
			} else {
				Logs.d("# user GattCharacteristic is not writable");
				return false;
			}
		}
		
		writableChar.setValue(data);
		writableChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
		mBluetoothGatt.writeCharacteristic(writableChar);
		mDefaultChar = writableChar;
		return true;
	}
	
	public void setWritableCharacteristic(BluetoothGattCharacteristic chr) {
		mDefaultChar = chr;
	}
	
	public ArrayList<BluetoothGattService> getServices() {
		return mGattServices;
	}
	
	public ArrayList<BluetoothGattCharacteristic> getCharacteristics() {
		return mGattCharacteristics;
	}
	
	public ArrayList<BluetoothGattCharacteristic> getWritableCharacteristics() {
		return mWritableCharacteristics;
	}
	
	
	/*****************************************************
	 *	Handler, Listener, Timer, Sub classes
	 ******************************************************/
	
	// Various callback methods defined by the BLE API.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mState = STATE_CONNECTED;
                Logs.d(TAG, "# Connected to GATT server.");
                mHandler.obtainMessage(MESSAGE_STATE_CHANGE, STATE_CONNECTED, 0).sendToTarget();
                
                gatt.discoverServices();
                
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mState = STATE_IDLE;
                Logs.d(TAG, "# Disconnected from GATT server.");
                mHandler.obtainMessage(MESSAGE_STATE_CHANGE, STATE_IDLE, 0).sendToTarget();
                mBluetoothGatt = null;
        		mGattServices.clear();
                mDefaultService = null;
        		mGattCharacteristics.clear();
        		mWritableCharacteristics.clear();
                mDefaultChar = null;
                mDefaultDevice = null;
            }
        }

        @Override
        // New services discovered
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
            	Logs.d(TAG, "# New GATT service discovered.");
            	checkGattServices(gatt.getServices());
            } else {
                Logs.d(TAG, "# onServicesDiscovered received: " + status);
            }
        }

        @Override
        // Result of a characteristic read operation
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
            	// We've received data from remote
            	Logs.d(TAG, "# Read characteristic: "+characteristic.toString());
            	
            	/*
            	 * onCharacteristicChanged callback receives same message
            	 * 
            	final byte[] data = characteristic.getValue();
            	if (data != null && data.length > 0) {
            		final StringBuilder stringBuilder = new StringBuilder(data.length);
            		//for(byte byteChar : data)
            		//	stringBuilder.append(String.format("%02X ", byteChar));
            		stringBuilder.append(data);
            		Logs.d(TAG, stringBuilder.toString());
            		
            		mHandler.obtainMessage(MESSAGE_READ, new String(data)).sendToTarget();
            	}
            	
            	if(mDefaultChar == null && isWritableCharacteristic(characteristic)) {
            		mDefaultChar = characteristic;
            	}
            	*/
            }
        }
        
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        	// We've received data from remote
        	Logs.d(TAG, "# onCharacteristicChanged: "+characteristic.toString());
        	
        	final byte[] data = characteristic.getValue();
        	if (data != null && data.length > 0) {
        		final StringBuilder stringBuilder = new StringBuilder(data.length);
        		//for(byte byteChar : data)
        		//	stringBuilder.append(String.format("%02X ", byteChar));
        		stringBuilder.append(data);
        		Logs.d(TAG, stringBuilder.toString());
        		
        		mHandler.obtainMessage(MESSAGE_READ, new String(data)).sendToTarget();
        	}
        	
        	if(mDefaultChar == null && isWritableCharacteristic(characteristic)) {
        		mDefaultChar = characteristic;
        	}
        };
    };
	
	
}
