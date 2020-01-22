package com.gracker.myic;

import java.io.ByteArrayOutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import com.acs.smartcard.Reader;
import com.acs.smartcard.Reader.OnStateChangeListener;

/**
 * A simple app to read Malaysian IC using ACS smart card readers.
 *
 * @author Jeffrey Loh
 * @version 1.0, January 2020
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

    private static final String[] stateStrings = { "Unknown", "Absent",
            "Present", "Swallowed", "Powered", "Negotiable", "Specific" };

    private UsbManager mManager;
    private Reader mReader;
    private PendingIntent mPermissionIntent;

    private static final int MAX_LINES = 25;
    private TextView mResponseTextView;
    private Spinner mReaderSpinner;
    private ArrayAdapter<String> mReaderAdapter;
    private Button mListButton;
    private Button mOpenButton;
    private Button mCloseButton;
    private Button mReadIcButton;
    private CheckBox mReadPhotoCheckBox;
    private CheckBox mDebugCheckBox;
    private ImageView mImageViewPhoto;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action)) {

                synchronized (this) {

                    UsbDevice device = (UsbDevice) intent
                            .getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false)) {

                        if (device != null) {

                            // Open reader
                            logMsg("Opening reader: " + device.getDeviceName()
                                    + "...");
                            new OpenTask().execute(device);
                        }

                    } else {

                        logMsg("Permission denied for device "
                                + device.getDeviceName());

                        // Enable open button
                        mOpenButton.setEnabled(true);
                    }
                }

            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {

                synchronized (this) {

                    // Update reader list
                    mReaderAdapter.clear();
                    for (UsbDevice device : mManager.getDeviceList().values()) {
                        if (mReader.isSupported(device)) {
                            mReaderAdapter.add(device.getDeviceName());
                        }
                    }

                    UsbDevice device = (UsbDevice) intent
                            .getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (device != null && device.equals(mReader.getDevice())) {

                        // Disable buttons
                        mCloseButton.setEnabled(false);
                        //mReadIcButton.setEnabled(false);

                        // Close reader
                        logMsg("Closing reader...");
                        new CloseTask().execute();
                    }
                }
            }
        }
    };

    private class OpenTask extends AsyncTask<UsbDevice, Void, Exception> {

        @Override
        protected Exception doInBackground(UsbDevice... params) {

            Exception result = null;

            try {

                mReader.open(params[0]);

            } catch (Exception e) {

                result = e;
            }

            return result;
        }

        @Override
        protected void onPostExecute(Exception result) {
            if (result != null) {
                logMsg(result.toString());
            } else {

                logMsg("Reader name: " + mReader.getReaderName());

                int numSlots = mReader.getNumSlots();
                logMsg("Number of slots: " + numSlots);

                // Enable buttons
                mCloseButton.setEnabled(true);
                mReadIcButton.setEnabled(true);
            }
        }
    }

    private class CloseTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {

            mReader.close();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            mOpenButton.setEnabled(true);
        }

    }

    private class ReadIcResult {
        public Exception e;

        public String gmpc_name;
        public String org_name;
        public String ic;
        public String gender;
        public String name;
        public String oldIc;
        public String dob;
        public String birthPlace;
        public String issueDate;
        public String citizenship;
        public String race;
        public String religion;
        public String address1;
        public String address2;
        public String address3;
        public String postcode;
        public String city;
        public String state;
    }

    private class ReadIcProgress {
        public byte[] command;
        public int commandLength;
        public byte[] response;
        public int responseLength;
        public Exception e;
    }

    private class ReadIcParams {
        public int slotNum;
        public boolean readphoto;
        public boolean debug;
    }

    private class ReadIcTask extends
            AsyncTask<ReadIcParams, ReadIcProgress, ReadIcResult> {

        private String ConvertBCDDate(byte[] dataBytes, int offset)
        {
            int year = 0;
            for (int b = offset; b < offset + 2; b++)
                year = (year * 100) + ((dataBytes[b] >> 4) & 0xf) * 10 + (dataBytes[b] & 0xf);
            int month = ((dataBytes[offset + 2] >> 4) & 0xf) * 10 + (dataBytes[offset + 2] & 0xf);
            int day = ((dataBytes[offset+3] >> 4) & 0xf) * 10 + (dataBytes[offset+3] & 0xf);

            return day + "-" + month + "-" + year;
        }

        private String ConvertBCDPostcode(byte[] dataBytes, int offset)
        {
            int postcode = 0;
            for (int b = offset; b < offset + 3; b++)
                postcode = (postcode * 100) + ((dataBytes[b] >> 4) & 0xf) * 10 + (dataBytes[b] & 0xf);
            return "" + (postcode / 10);
        }

        private byte[] sendApdu(int slotNum, String hexString, boolean debug){
            byte[] command = null;
            byte[] response = new byte[65536];
            int responseLength = 0;

            byte[] result = null;

            ReadIcProgress progress = new ReadIcProgress();

            try {
                command = toByteArray(hexString);
                responseLength = mReader.transmit(slotNum,
                        command, command.length, response,
                        response.length);

                progress.command = command;
                progress.commandLength = command.length;
                progress.response = response;
                progress.responseLength = responseLength;
                progress.e = null;

                result = Arrays.copyOf(response, responseLength);
            } catch (Exception e) {
                progress.command = null;
                progress.commandLength = 0;
                progress.response = null;
                progress.responseLength = 0;
                progress.e = e;
            }

            if (debug)
                publishProgress(progress);

            return result;
        }

        public byte[] getPic(int slotNum, boolean debug) {
            byte[] byteArray;
            int offset = 0x03;
            int length = 0xff;
            int max    = 4000;

            ByteArrayOutputStream output = new ByteArrayOutputStream();

            boolean go = true;
            while (true) {
                String tmp = Integer.toHexString(offset);

                String tmpOffset = null;

                if (tmp.length() == 1) {
                    tmpOffset = "0" + tmp + " 00";
                } else {
                    tmpOffset = tmp.substring(1,3) + " 0" + tmp.substring(0,1);
                }

                try {
                    sendApdu(slotNum, "C8 32 00 00 05 08 00 00 FF 00", debug);
                    sendApdu(slotNum, "CC 00 00 00 08 02 00 01 00 " + tmpOffset + " FF 00", debug);
                    byte[] tempArray = sendApdu(slotNum, "CC 06 00 00 FF", debug);

                    output.write(tempArray, 0, tempArray.length - 2); // get rid of 0x90, 0x00 at the end of array
                } catch (Exception e) {
                    return null;
                }

                if ((offset + length) > max)
                    break;

                offset += length;
            }

            byteArray = output.toByteArray();
            return byteArray;
        }

        @Override
        protected ReadIcResult doInBackground(ReadIcParams... params) {

            ReadIcResult result = new ReadIcResult();

            try {
                byte[] atr = mReader.power(params[0].slotNum, 1); // 1 Cold Reset 2 Warm Reset
            } catch (Exception e) {
                result.e = e;
                return result;
            }

            sendApdu(params[0].slotNum, "00 A4 04 00 0A A0 00 00 00 74 4A 50 4E 00 10", params[0].debug);
            sendApdu(params[0].slotNum, "00 C0 00 00 05", params[0].debug);

            sendApdu(params[0].slotNum, "C8 32 00 00 05 08 00 00 E6 00", params[0].debug);
            sendApdu(params[0].slotNum, "CC 00 00 00 08 01 00 01 00 03 00 E6 00", params[0].debug);
            byte[] jpn1_0 = sendApdu(params[0].slotNum, "CC 06 00 00 E6", params[0].debug);

            if (jpn1_0.length >= 0xE0) {
                result.org_name = new String(jpn1_0, 0, 150).trim();
                result.gmpc_name = new String(jpn1_0, 150, 30+30+20).trim();
            }

            sendApdu(params[0].slotNum, "C8 32 00 00 05 08 00 00 A0 00", params[0].debug);
            sendApdu(params[0].slotNum, "CC 00 00 00 08 01 00 01 00 E9 00 A0 00", params[0].debug);
            byte[] jpn1_1 = sendApdu(params[0].slotNum, "CC 06 00 00 A0", params[0].debug);

            if (jpn1_1.length >= 0xA0) {
                result.name = new String(jpn1_1, 0, 40).trim();
                result.ic = new String(jpn1_1, 40, 13).trim();
                result.gender = new String(jpn1_1, 40 + 13, 1);
                result.oldIc = new String(jpn1_1, 40 + 13 + 1, 8);
                result.dob = ConvertBCDDate(jpn1_1, 40 + 13 + 1 + 8);
                result.birthPlace = new String(jpn1_1, 66, 25);
                result.issueDate = ConvertBCDDate(jpn1_1, 66 + 25);
                result.citizenship = new String(jpn1_1, 95, 18).trim();
                result.race = new String(jpn1_1, 113, 25).trim();
                result.religion = new String(jpn1_1, 138, 11).trim();
            }

            sendApdu(params[0].slotNum, "C8 32 00 00 05 08 00 00 94 00", params[0].debug);
            sendApdu(params[0].slotNum, "CC 00 00 00 08 04 00 01 00 03 00 94 00", params[0].debug);
            byte[] jpn1_4 = sendApdu(params[0].slotNum, "CC 06 00 00 94", params[0].debug);

            if (jpn1_4.length >= 0x94) {
                result.address1 = new String(jpn1_4, 0, 30).trim();
                result.address2 = new String(jpn1_4, 30, 30).trim();
                result.address3 = new String(jpn1_4, 60, 30).trim();
                result.postcode = ConvertBCDPostcode(jpn1_4, 90);
                result.city = new String(jpn1_4, 90 + 3, 25 ).trim();
                result.state = new String(jpn1_4, 90 + 3 + 25, 30 ).trim();
            }

            if (params[0].readphoto) {
                try {
                    byte[] byteArray = getPic(params[0].slotNum, params[0].debug);
                    final Bitmap bmp = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length);

                    runOnUiThread(new Runnable() {

                        @Override
                        public void run() {
                            mImageViewPhoto.setImageBitmap(Bitmap.createScaledBitmap(bmp, mImageViewPhoto.getWidth(),
                                    mImageViewPhoto.getHeight(), false));
                        }
                    });
                } catch (Exception e) { }
            }
            return result;
        }

        @Override
        protected void onPostExecute(ReadIcResult result) {
            logMsg("-------------------------------------------------");
            if (result.e != null) {
                logMsg("Exception: " + result.e.toString() + " " + result.e.getMessage());
            } else {
                logMsg("Org Name = " + result.org_name);
                logMsg("GMPC Name = " + result.gmpc_name);
                logMsg("Name = " + result.name);
                logMsg("IC = " + result.ic);
                logMsg("Gender = " + result.gender);
                logMsg("Old IC = " + result.oldIc);
                logMsg("DOB = " + result.dob);
                logMsg("Birth Place = " + result.birthPlace);
                logMsg("Issue Date = " + result.issueDate);
                logMsg("Citizenship = " + result.citizenship);
                logMsg("Race = " + result.race);
                logMsg("Religion = " + result.religion);

                logMsg("Address1 = " + result.address1);
                logMsg("Address2 = " + result.address2);
                logMsg("Address3 = " + result.address3);
                logMsg("Postcode = " + result.postcode);
                logMsg("City = " + result.city);
                logMsg("State = " + result.state);
           }
        }

        @Override
        protected void onProgressUpdate(ReadIcProgress... progress) {
            if (progress[0].e != null) {
                logMsg(progress[0].e.toString());
            } else {
                logMsg("ReadIc Command:");
                logBuffer(progress[0].command, progress[0].commandLength);

                logMsg("ReadIc Response:");
                logBuffer(progress[0].response, progress[0].responseLength);
            }
        }
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Get USB manager
        mManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // Initialize reader
        mReader = new Reader(mManager);
        mReader.setOnStateChangeListener(new OnStateChangeListener() {

            @Override
            public void onStateChange(int slotNum, int prevState, int currState) {

                if (prevState < Reader.CARD_UNKNOWN
                        || prevState > Reader.CARD_SPECIFIC) {
                    prevState = Reader.CARD_UNKNOWN;
                }

                if (currState < Reader.CARD_UNKNOWN
                        || currState > Reader.CARD_SPECIFIC) {
                    currState = Reader.CARD_UNKNOWN;
                }

                // Create output string
                final String outputString = "Slot " + slotNum + ": "
                        + stateStrings[prevState] + " -> "
                        + stateStrings[currState];

                // Show output
                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {
                        logMsg(outputString);
                    }
                });
            }
        });

        // Register receiver for USB permission
        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(
                ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mReceiver, filter);

        // Initialize response text view
        mResponseTextView = (TextView) findViewById(R.id.main_text_view_response);
        mResponseTextView.setMovementMethod(new ScrollingMovementMethod());
        mResponseTextView.setMaxLines(MAX_LINES);
        mResponseTextView.setText("");

        // Initialize reader spinner
        mReaderAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item);
        for (UsbDevice device : mManager.getDeviceList().values()) {
            if (mReader.isSupported(device)) {
                mReaderAdapter.add(device.getDeviceName());
            }
        }
        mReaderSpinner = (Spinner) findViewById(R.id.main_spinner_reader);
        mReaderSpinner.setAdapter(mReaderAdapter);

        // Initialize list button
        mListButton = (Button) findViewById(R.id.main_button_list);
        mListButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {

                mReaderAdapter.clear();
                for (UsbDevice device : mManager.getDeviceList().values()) {
                    if (mReader.isSupported(device)) {
                        mReaderAdapter.add(device.getDeviceName());
                    }
                }
            }
        });

        // Initialize open button
        mOpenButton = (Button) findViewById(R.id.main_button_open);
        mOpenButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {

                boolean requested = false;

                // Disable open button
                mOpenButton.setEnabled(false);

                String deviceName = (String) mReaderSpinner.getSelectedItem();

                if (deviceName != null) {

                    // For each device
                    for (UsbDevice device : mManager.getDeviceList().values()) {

                        // If device name is found
                        if (deviceName.equals(device.getDeviceName())) {

                            // Request permission
                            mManager.requestPermission(device,
                                    mPermissionIntent);

                            requested = true;
                            break;
                        }
                    }
                }

                if (!requested) {

                    // Enable open button
                    mOpenButton.setEnabled(true);
                }
            }
        });

        // Initialize close button
        mCloseButton = (Button) findViewById(R.id.main_button_close);
        mCloseButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                // Disable buttons
                mCloseButton.setEnabled(false);
                //mReadIcButton.setEnabled(false);

                // Close reader
                logMsg("Closing reader...");
                new CloseTask().execute();
            }
        });


        // Initialize readic readphoto buttons
        mReadIcButton = (Button) findViewById(R.id.main_button_readic);
        mReadIcButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                // Get slot number
                int slotNum = 0;

                ReadIcParams rparams = new ReadIcParams();
                rparams.slotNum = slotNum;
                rparams.debug = mDebugCheckBox.isChecked();
                rparams.readphoto = mReadPhotoCheckBox.isChecked();
                new ReadIcTask().execute(rparams);
            }
        });

        mReadPhotoCheckBox = (CheckBox) findViewById(R.id.checkBoxReadPhoto);
        mDebugCheckBox = (CheckBox) findViewById(R.id.checkBoxDebug);
        mImageViewPhoto = (ImageView) findViewById((R.id.imageView));

        // Disable buttons
        mCloseButton.setEnabled(false);
        //mReadIcButton.setEnabled(false);

        // Hide input window
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    @Override
    protected void onDestroy() {

        // Close reader
        mReader.close();

        // Unregister receiver
        unregisterReceiver(mReceiver);

        super.onDestroy();
    }

    /**
     * Logs the message.
     *
     * @param msg
     *            the message.
     */
    private void logMsg(String msg) {

        DateFormat dateFormat = new SimpleDateFormat("[dd-MM-yyyy HH:mm:ss]: ");
        Date date = new Date();
        String oldMsg = mResponseTextView.getText().toString();

        mResponseTextView
                .setText(oldMsg + "\n" + dateFormat.format(date) + msg);

        if (mResponseTextView.getLineCount() > MAX_LINES) {
            mResponseTextView.scrollTo(0,
                    (mResponseTextView.getLineCount() - MAX_LINES)
                            * mResponseTextView.getLineHeight());
        }
    }

    /**
     * Logs the contents of buffer.
     *
     * @param buffer
     *            the buffer.
     * @param bufferLength
     *            the buffer length.
     */
    private void logBuffer(byte[] buffer, int bufferLength) {

        String bufferString = "";

        for (int i = 0; i < bufferLength; i++) {

            String hexChar = Integer.toHexString(buffer[i] & 0xFF);
            if (hexChar.length() == 1) {
                hexChar = "0" + hexChar;
            }

            if (i % 16 == 0) {

                if (bufferString != "") {

                    logMsg(bufferString);
                    bufferString = "";
                }
            }

            bufferString += hexChar.toUpperCase() + " ";
        }

        if (bufferString != "") {
            logMsg(bufferString);
        }
    }

    /**
     * Converts the HEX string to byte array.
     *
     * @param hexString
     *            the HEX string.
     * @return the byte array.
     */
    private byte[] toByteArray(String hexString) {

        int hexStringLength = hexString.length();
        byte[] byteArray = null;
        int count = 0;
        char c;
        int i;

        // Count number of hex characters
        for (i = 0; i < hexStringLength; i++) {

            c = hexString.charAt(i);
            if (c >= '0' && c <= '9' || c >= 'A' && c <= 'F' || c >= 'a'
                    && c <= 'f') {
                count++;
            }
        }

        byteArray = new byte[(count + 1) / 2];
        boolean first = true;
        int len = 0;
        int value;
        for (i = 0; i < hexStringLength; i++) {

            c = hexString.charAt(i);
            if (c >= '0' && c <= '9') {
                value = c - '0';
            } else if (c >= 'A' && c <= 'F') {
                value = c - 'A' + 10;
            } else if (c >= 'a' && c <= 'f') {
                value = c - 'a' + 10;
            } else {
                value = -1;
            }

            if (value >= 0) {

                if (first) {

                    byteArray[len] = (byte) (value << 4);

                } else {

                    byteArray[len] |= value;
                    len++;
                }

                first = !first;
            }
        }

        return byteArray;
    }

    /**
     * Converts the integer to HEX string.
     *
     * @param i
     *            the integer.
     * @return the HEX string.
     */
    private String toHexString(int i) {

        String hexString = Integer.toHexString(i);
        if (hexString.length() % 2 != 0) {
            hexString = "0" + hexString;
        }

        return hexString.toUpperCase();
    }

    /**
     * Converts the byte array to HEX string.
     *
     * @param buffer
     *            the buffer.
     * @return the HEX string.
     */
    private String toHexString(byte[] buffer) {

        String bufferString = "";

        for (int i = 0; i < buffer.length; i++) {

            String hexChar = Integer.toHexString(buffer[i] & 0xFF);
            if (hexChar.length() == 1) {
                hexChar = "0" + hexChar;
            }

            bufferString += hexChar.toUpperCase() + " ";
        }

        return bufferString;
    }
}
