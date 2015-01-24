/*
 * Copyright (C) 2014 Bluetooth Connection Template
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hardcopy.blechat;

import java.util.ArrayList;
import java.util.Set;

import com.hardcopy.blechat.R;
import com.hardcopy.blechat.bluetooth.BleManager;
import com.hardcopy.blechat.utils.Logs;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.AdapterView.OnItemClickListener;


/**
 * This Activity appears as a dialog. It lists any paired devices and
 * devices detected in the area after discovery. When a device is chosen
 * by the user, the MAC address of the device is sent back to the parent
 * Activity in the result Intent.
 */
public class DeviceListActivity extends Activity {
    // Debugging
    private static final String TAG = "DeviceListActivity";
    private static final boolean D = true;
    
    // Constants
	public static final long SCAN_PERIOD = 8*1000;	// Stops scanning after a pre-defined scan period.

    // Return Intent extra
    public static String EXTRA_DEVICE_ADDRESS = "device_address";

    // Member fields
    private ActivityHandler mActivityHandler;
    private BluetoothAdapter mBtAdapter;
    private BleManager mBleManager;
    private ArrayAdapter<String> mPairedDevicesArrayAdapter;
    private ArrayAdapter<String> mNewDevicesArrayAdapter;
    
    private ArrayList<BluetoothDevice> mDevices = new ArrayList<BluetoothDevice>(); 

    // UI stuff
    Button mScanButton = null;
    
    
    
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup the window
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.activity_device_list);

        // Set result CANCELED incase the user backs out
        setResult(Activity.RESULT_CANCELED);

        mActivityHandler = new ActivityHandler();
        
        // Initialize the button to perform device discovery
        mScanButton = (Button) findViewById(R.id.button_scan);
        mScanButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
            	mNewDevicesArrayAdapter.clear();
                doDiscovery();
                v.setVisibility(View.GONE);
            }
        });

        // Initialize array adapters. One for already paired devices and
        // one for newly discovered devices
        mPairedDevicesArrayAdapter = new ArrayAdapter<String>(this, R.layout.adapter_device_name);
        mNewDevicesArrayAdapter = new ArrayAdapter<String>(this, R.layout.adapter_device_name);

        // Find and set up the ListView for paired devices
        ListView pairedListView = (ListView) findViewById(R.id.paired_devices);
        pairedListView.setAdapter(mPairedDevicesArrayAdapter);
        pairedListView.setOnItemClickListener(mDeviceClickListener);

        // Find and set up the ListView for newly discovered devices
        ListView newDevicesListView = (ListView) findViewById(R.id.new_devices);
        newDevicesListView.setAdapter(mNewDevicesArrayAdapter);
        newDevicesListView.setOnItemClickListener(mDeviceClickListener);


        // Get the local Bluetooth adapter
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        // Get BLE Manager
        mBleManager = BleManager.getInstance(getApplicationContext(), null);
        mBleManager.setScanCallback(mLeScanCallback);

        // Get a set of currently paired devices
        Set<BluetoothDevice> pairedDevices = mBtAdapter.getBondedDevices();

        // If there are paired devices, add each one to the ArrayAdapter
        if (pairedDevices.size() > 0) {
            findViewById(R.id.title_paired_devices).setVisibility(View.VISIBLE);
            for (BluetoothDevice device : pairedDevices) {
                mPairedDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
            }
        } else {
            String noDevices = getResources().getText(R.string.none_paired).toString();
            mPairedDevicesArrayAdapter.add(noDevices);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Make sure we're not doing discovery anymore
        if (mBtAdapter != null) {
            mBtAdapter.cancelDiscovery();
        }
    }

    /**
     * Start device discover with the BluetoothAdapter
     */
    private void doDiscovery() {
        if(D) Log.d(TAG, "doDiscovery()");

        // Indicate scanning in the title
        setProgressBarIndeterminateVisibility(true);
        setTitle(R.string.scanning);

        // Turn on sub-title for new devices
        findViewById(R.id.title_new_devices).setVisibility(View.VISIBLE);

        // Empty cache
        mDevices.clear();
        
        // If we're already discovering, stop it
        if (mBleManager.getState() == BleManager.STATE_SCANNING) {
        	mBleManager.scanLeDevice(false);
        }

        // Request discover from BluetoothAdapter
        mBleManager.scanLeDevice(true);
        
		// Stops scanning after a pre-defined scan period.
		mActivityHandler.postDelayed(new Runnable() {
				@Override
				public void run() {
					stopDiscovery();
				}
			}, SCAN_PERIOD);
    }
    
    /**
     * Stop device discover
     */
    private void stopDiscovery() {
    	// Indicate scanning in the title
    	setProgressBarIndeterminateVisibility(false);
    	setTitle(R.string.bt_title);
    	// Show scan button
    	mScanButton.setVisibility(View.VISIBLE);
    	mBleManager.scanLeDevice(false);
    }
    
    /**
     * Check if it's already cached
     */
    private boolean checkDuplicated(BluetoothDevice device) {
    	for(BluetoothDevice dvc : mDevices) {
    		if(device.getAddress().equalsIgnoreCase(dvc.getAddress())) {
    			return true;
    		}
    	}
    	return false;
    }

    /**
     * The on-click listener for all devices in the ListViews
     */
    private OnItemClickListener mDeviceClickListener = new OnItemClickListener() {
        public void onItemClick(AdapterView<?> av, View v, int arg2, long arg3) {
            // Cancel discovery because it's costly and we're about to connect
            mBtAdapter.cancelDiscovery();

            // Get the device MAC address, which is the last 17 chars in the View
            String info = ((TextView) v).getText().toString();
            if(info != null && info.length() > 16) {
                String address = info.substring(info.length() - 17);
                Log.d(TAG, "User selected device : " + address);

                // Create the result Intent and include the MAC address
                Intent intent = new Intent();
                intent.putExtra(EXTRA_DEVICE_ADDRESS, address);

                // Set result and finish this Activity
                setResult(Activity.RESULT_OK, intent);
                finish();
            }
        }
    };


    /**
     * BLE scan callback
     */
    private BluetoothAdapter.LeScanCallback mLeScanCallback = 
    		new BluetoothAdapter.LeScanCallback() {
    	@Override
    	public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
            Logs.d("# Scan device rssi is " + rssi);
            runOnUiThread(new Runnable() {
            	@Override
            	public void run() {
            		if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            			if(!checkDuplicated(device)) {
                			mNewDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
                			mNewDevicesArrayAdapter.notifyDataSetChanged();
            				mDevices.add(device);
            			}
            		}
            	}
            });
    	}
    };
    
	public class ActivityHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch(msg.what) {}
			super.handleMessage(msg);
		}
	}

}
