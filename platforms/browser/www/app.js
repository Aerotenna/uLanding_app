//
// Copyright 2014, Andreas Lundquist
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// DFRobot - Bluno - Hello World
// version: 0.1 - 2014-11-21
//

// Route all console logs to Evothings studio log
if (window.hyper && window.hyper.log) { console.log = hyper.log; };

document.addEventListener(
	'deviceready',
	function() { evothings.scriptsLoaded(app.initialize) },
	false);

var app = {};

//app.DFRBLU_SERVICE_UUID = 'dfb0';
app.DFRBLU_SERVICE_UUID = '0000dfb0-0000-1000-8000-00805f9b34fb';
app.DFRBLU_CHAR_RXTX_UUID = '0000dfb1-0000-1000-8000-00805f9b34fb';
app.DFRBLU_TX_UUID_DESCRIPTOR = '00002902-0000-1000-8000-00805f9b34fb';

	// Discovered devices.
	app.knownDevices = {};

	// Reference to the device we are connecting to.
	app.connectee = null;

	// Handle to the connected device.
	app.deviceHandle = null;

	// Handles to characteristics and descriptor for reading and
	// writing data from/to the Arduino using the BLE shield.
	app.characteristicRead = null;
	app.characteristicWrite = null;
	app.descriptorNotification = null;
	
	// Data that is plotted on the canvas.
	app.dataPoints = [];
	app.magnitude = 1000;
	app.max = 100;
	app.tempMax = 100;
	app.prevValue = 1;

app.initialize = function()
{
	app.connected = false;
};

app.startScan = function()
{
	app.disconnect();

	console.log('Scanning started...');

	app.devices = {};

	var htmlString =
		'<img src="img/loader_small.gif" ' +
			'style="display:inline; vertical-align:middle">' +
		'<p style="display:inline">   Scanning...</p>';

	$('#scanResultView').append($(htmlString));

	$('#scanResultView').show();

	function onScanSuccess(device)
	{
		if (device.name != null)
		{
			app.devices[device.address] = device;

			console.log(
				'Found: ' + device.name + ', ' +
				device.address + ', ' + device.rssi);

			var htmlString =
				'<div class="deviceContainer" onclick="app.connectTo(\'' +
					device.address + '\')">' +
				'<p class="deviceName">' + device.name + '</p>' +
				'<p class="deviceAddress">' + device.address + '</p>' +
				'</div>';

			$('#scanResultView').append($(htmlString));
		}
	}

	function onScanFailure(errorCode)
	{
		// Show an error message to the user
		app.disconnect('Failed to scan for devices.');

		// Write debug information to console.
		console.log('Error ' + errorCode);
	}

	evothings.easyble.reportDeviceOnce(true);
	evothings.easyble.startScan(onScanSuccess, onScanFailure);

	$('#startView').hide();
};

app.dataPoints = [];

app.setLoadingLabel = function(message)
{
	console.log(message);
	$('#loadingStatus').text(message);
}

app.connectTo = function(address)
{
	device = app.devices[address];

	$('#loadingView').css('display', 'table');

	app.setLoadingLabel('Trying to connect to ' + device.name);

	function onConnectSuccess(device)
	{
		function onServiceSuccess(device)
		{
			// Application is now connected
			app.connected = true;
			app.device = device;

			console.log('Connected to ' + device.name);

			$('#loadingView').hide();
			$('#scanResultView').hide();
			$('#controlView').show();

			device.enableNotification(
				app.DFRBLU_CHAR_RXTX_UUID,
				app.receivedData,
				function(errorcode) {
					console.log('BLE enableNotification error: ' + errorCode);
				});
		}

		function onServiceFailure(errorCode)
		{
			// Disconnect and show an error message to the user.
			app.disconnect('Device is not from DFRobot');

			// Write debug information to console.
			console.log('Error reading services: ' + errorCode);
		}

		app.setLoadingLabel('Identifying services...');

		// Connect to the appropriate BLE service
		device.readServices([app.DFRBLU_SERVICE_UUID], onServiceSuccess, onServiceFailure);
	}

	function onConnectFailure(errorCode)
	{
		// Disconnect and show an error message to the user.
		app.disconnect('Failed to connect to device');

		// Write debug information to console
		console.log('Error ' + errorCode);
	}

	// Stop scanning
	evothings.easyble.stopScan();

	// Connect to our device
	console.log('Identifying service for communication');
	device.connect(onConnectSuccess, onConnectFailure);
};

app.sendData = function(data)
{
	if (app.connected)
	{
		function onMessageSendSucces()
		{
			console.log('Succeded to send message.');
		}

		function onMessageSendFailure(errorCode)
		{
			console.log('Failed to send data with error: ' + errorCode);
			app.disconnect('Failed to send data');
		}

		data = new Uint8Array(data);

		app.device.writeCharacteristic(
			app.DFRBLU_CHAR_RXTX_UUID,
			data,
			onMessageSendSucces,
			onMessageSendFailure);
	}
	else
	{
		// Disconnect and show an error message to the user.
		app.disconnect('Disconnected');

		// Write debug information to console
		console.log('Error - No device connected.');
	}
};

app.receivedData = function(data)
{
	if (app.connected)
	{
		var data = new Uint8Array(data);
		var prevValue = app.prevValue;

		if (data[0] === 0xAD)
		{
			console.log('Data received: [' +
				data[0] +', ' + data[1] +', ' + data[2] + ', ' + data[3] + ']');
			
			
			var value = (data[2] << 8) | data[1];
			var mag = (data[4] << 8) | data[3];
			
			console.log(value);
			value = value*0.327;

			//if (value < 0.55) {
			//	value = 0;
			//}

			//if (value > (2 + prevValue)) {
			//	value = prevValue + (value - prevValue)*0.4;
			//}
			//if (value < (prevValue - 2)) {
			//	value = prevValue - (prevValue - value)*0.4;
			//}

			//app.prevValue = value;

			value2 = Math.round(value*100)/100;
			$('#analogDigitalResult').text(value2);
			//$('#analogDigitalResult2').text(mag);
			value = value*100;
			
			var new_data = new Uint16Array([value]);
			app.drawLines(new_data);
			//app.drawLines([new DataView(data).getUint16(0, true)]);
		}
	}
	else
	{
		// Disconnect and show an error message to the user.
		app.disconnect('Disconnected');

		// Write debug information to console
		console.log('Error - No device connected.');
	}
};

app.drawLines = function(dataArray)
{
	var canvas = document.getElementById('canvas');
	var context = canvas.getContext('2d');
	var dataPoints = app.dataPoints;

	dataPoints.push(dataArray);
	if (dataPoints.length > (canvas.width/2))
	{
		dataPoints.splice(0, (dataPoints.length - (canvas.width/2)));
	}



	function calcY(i)
	{
		return (i * canvas.height) / app.magnitude;
	}

	function drawLine(offset, color)
	{
		context.strokeStyle = color;

		context.beginPath();
		context.moveTo(0, (canvas.height - calcY(dataPoints[dataPoints.length-1][offset])));
		app.tempMax = 100;
		var x = 1;

		if (dataPoints.length > 2) {
			for (var i = dataPoints.length - 2; i >= 2; i--)
			{
				var w = calcY(dataPoints[i][offset]);
				var u = calcY(dataPoints[i - 1][offset]);
				var z = calcY(dataPoints[i - 2][offset]);
				var y = (w + u + z) / 3;
				if (y > app.tempMax)
				{
					app.tempMax = y;
				}
				context.lineTo(x, (canvas.height - y));
				x = x + 2;
			}
		}
		app.max = (app.tempMax*app.magnitude)/(canvas.height - 10);
		if (app.max > 1000) {
			app.magnitude = app.max;
		} else {
			app.magnitude = 1000;
		}

		context.fillText("-- " + (Math.round(app.magnitude/100) - 1) + " m", 0, 10);
		context.fillText("-- 0 m", 0, 199);
		context.stroke();
	}

	context.clearRect(0, 0, canvas.width, canvas.height);
	drawLine(0, '#0AD');
},

app.disconnect = function(errorMessage)
{
	if (errorMessage)
	{
		navigator.notification.alert(errorMessage, function() {});
	}

	app.connected = false;
	app.device = null;

	// Stop any ongoing scan and close devices.
	evothings.easyble.stopScan();
	evothings.easyble.closeConnectedDevices();

	console.log('Disconnected');

	$('#scanResultView').hide();
	$('#scanResultView').empty();
	$('#controlView').hide();
	$('#startView').show();
};
