package com.example.drawline

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.OutputStream
import java.util.*
import kotlin.math.abs

@Suppress("LiftReturnOrAssignment")
@SuppressLint("MissingPermission")

class MainActivity : AppCompatActivity() {

    private lateinit var button: Button

// Bluetooth stuff:

    // HC-05

    private val mUUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var remoteBluetoothDeviceName = "BT115200"

    // User phone

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothManager: BluetoothManager

    private var bluetoothSocket: BluetoothSocket? = null

// Phone display boundaries

    private val xMax: Float = Resources.getSystem().displayMetrics.widthPixels.toFloat() //1080F
    private val yMax: Float = 6000F

    // Just to draw clean line on bitmap, without offset
    // line will be cut by bitmap and appear not continuous

    private val yChunkOffset: Float = 10F

// Data structure

    private lateinit var xArray: Array<Float>
    private var pointsInArray: Int = 3
    private var channelCount: Int = 1
    private var xDataResolution: Float = xMax/65536 // 2^16 = 65536
    private var halfValue: Float = xMax / 2

// "Time" related variables, define how much space single chunk will occupy on bitmap
// that implies fow "fast" chart will shift on user phone

    private var dy: Float = 5F
    private var chunkDelay: Float = dy * pointsInArray

// Chart stuff

    private lateinit var imageViewChart: ImageView
    private lateinit var imageViewAxis: ImageView
    private lateinit var paintBackground: Paint
    private lateinit var paintLine: Paint
    private lateinit var bitmapChart: Bitmap
    private lateinit var canvasChart: Canvas
    private lateinit var pathChunk: Path
    private val eraser = RectF(0F, (yMax) - chunkDelay, xMax, yMax)

    private var check: Boolean = false

// Bluetooth protocol

    private var headerByte: Byte= 0x7E
    private var footerByte: Byte= 0x7F

////////////////////////////////////////////////////////////////////////////////////////////////////
///////////////////////////////////////////// CODE /////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////

    inner class BackgroundThreadRoutine: Runnable {

        private val inputStream = bluetoothSocket!!.inputStream
        private val outputStream = bluetoothSocket!!.outputStream
        private val byteArray: Array<Int> = Array(pointsInArray * 2) {0}
        private var mByteArray = ByteArray(2048) // Bytes form inputStream

        private var i = 0
        private var n = 0
        private var checkByteCount = 0

        override fun run() {

            while(true) {

                try {
                    outputStream.write(65)
                    inputStream.read(mByteArray)
                } catch (_: IOException) {
                    bluetoothSocket = null
                    check = false
                    button.text = getString(R.string.buttonConnect)
                    return
                }

                // Data frame has format:

                /**

                4 checkBytes                 data frame bytes                      4 checkBytes
                0x7E x4        (dataByte) (dataByte + 1) ... (dataByte + n)        0x7F x4

                 **/

                fun toUnsigned (byte: Int): Int {
                    if (byte < 0)
                        return (256 - abs(byte))
                    else
                        return byte
                }

                checkByteCount = 0
                i = 0
                n = 0

                // Skip check bytes until valid data is reached

                while (true) {

                    if (mByteArray[i] != headerByte)
                        checkByteCount = 0
                    else
                        checkByteCount++

                    if (++i > 40) {
                        i = -1
                        break
                    }
                    else if (checkByteCount == 4)
                        break
                }

                // Load data in array until check bytes are reached

                while (i != -1) {

                    if (n > (pointsInArray * 2) - 1) {

                        if ( mByteArray[i]  == footerByte && mByteArray[i+1] == footerByte &&
                            mByteArray[i+2] == footerByte && mByteArray[i+3] == footerByte)
                            i = 0   // We are good to go!
                        else
                            i = -1
                        break

                    } else
                        byteArray[n++] = toUnsigned(mByteArray[i++].toInt())
                }

                mByteArray.fill(0)

                if (i != -1) {

                    for (i in 0 until pointsInArray)
                        xArray[i] = convertData(byteArray[2*i] + (byteArray[(2*i)+1] shl 8))

                    prepareData()
                    runOnUiThread { imageViewChart.invalidate() }
                    byteArray.fill(0)
                }
            }
        }
    }

    /**
     * @brief       Simple Number Conversion Function. After conversion,
     *              value is ready to be displayed on User phone screen
     * @param       x: 16 bit value transferred by bluetooth
     * @retrieval   Converted value in float
     */
    private fun convertData (x: Int): Float {
        return  if      (x  < 0x7FFF)    x * xDataResolution + halfValue
        else    if      (x  > 0x8000)    x * xDataResolution - halfValue
        else    if      (x == 0x8000)    0F
        else         /* (x == 0x7FFF) */ xMax
    }

    /**
     * @brief       Tries to open socket and connect with remote bluetooth device
     * @param       remoteBluetoothDeviceName: name of remote bluetooth device
     *              that we will try connect to
     * @retrieval   None
     */
    private fun establishBluetoothCommunication(remoteBluetoothDeviceName: String = "BT115200") {

        val deviceNameRegex = Regex(remoteBluetoothDeviceName)
        val bondedDevices: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices
        var remoteBluetoothDevice: BluetoothDevice? = null

        // First we search in bondedDevices (which appeared to be 'set')
        // for device with specific name and if we found one, we set it
        // as device used for future connection

        bondedDevices.forEach { device ->
            if (deviceNameRegex.containsMatchIn(device.name)) {
                remoteBluetoothDevice = device
                return@forEach
            }
        }

        // If we didn't found any, we return from function and inform user
        // that device with provided name is not in paired devices

        if (remoteBluetoothDevice == null) {
            Toast.makeText(
                this,
                "Cannot find device with given name, or device is not paired!",
                Toast.LENGTH_LONG
            )
                .show()
            Toast.makeText(
                this,
                "Make sure you are paired with remote device and provided valid name!",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // If we managed to find device in paired devices we try to establish
        // valid connection by creating socket, and trying to connect to it

        // since our remote device is considered as server we use createRfcommSocketToServiceRecord
        // to connect to bluetooth device, if we cannot connect exception is thrown and we return
        // null and inform user that bluetooth device might be powered off, if operation is successful
        // we return from function with connected socket and we are ready for data transfer

        try {
            bluetoothSocket = remoteBluetoothDevice!!.createRfcommSocketToServiceRecord(mUUID)
            bluetoothSocket?.connect()
            Toast.makeText(this, "Connected!", Toast.LENGTH_SHORT).show()
            button.text = getString(R.string.buttonStart)
            return
        } catch (e: IOException) {
            e.printStackTrace()
        }

        Toast.makeText(this, "Cannot connect with remote device!",
            Toast.LENGTH_SHORT).show()
        Toast.makeText(this, "Make sure remote device is turned on!",
            Toast.LENGTH_LONG).show()
        return
    }

    /**
     * @brief       Updates Canvas by points that were given in xArray
     * @retrieval   None
     */
    private fun prepareData() {

        // Shift already drawn bitmap by specified dy value, replace drawn lines
        // that still are drawn on canvas in pathChunk place by background color
        // (that's the best approach I could come up with, and it's working fine)

        canvasChart.drawBitmap(bitmapChart, 0F, -chunkDelay, null)
        canvasChart.drawRect(eraser, paintBackground)

        // Draw new line by using points provided in xArray
        // (make sure xArray is updated before drawing data)

        for (i in 0 until pointsInArray) {
            pathChunk.lineTo(xArray[i], ((yMax - yChunkOffset) - chunkDelay) + (i + 1) * dy)
        }

        // Draw newly created path on Canvas

        canvasChart.drawPath(pathChunk, paintLine)

        // Clear pathChunk object from drawn lines, and set starting point
        // for next data as last point from already drawn set of points

        pathChunk.reset()
        pathChunk.moveTo(xArray[(pointsInArray - 1)], (yMax - yChunkOffset) - chunkDelay)
    }
    /**
     * @brief       Chart Initialization Function
     * @retrieval   None
     */
    private fun chartInit() {

        // Initializing the elements from the layout file

        imageViewChart = findViewById(R.id.imageView_chart)
        imageViewAxis = findViewById(R.id.imageView_axis)
        button = findViewById(R.id.start)

        // Creating the chart bitmap and canvas

        bitmapChart = Bitmap.createBitmap(xMax.toInt(), yMax.toInt(), Bitmap.Config.ARGB_8888)
        canvasChart = Canvas(bitmapChart)
        canvasChart.drawColor(Color.BLACK)

        // Setting the bitmap to the ImageView

        imageViewChart.setImageBitmap(bitmapChart)

        // Initializing the drawing elements

        paintLine = Paint()
        paintLine.color = Color.RED
        paintLine.style = Paint.Style.STROKE
        paintLine.strokeCap = Paint.Cap.ROUND
        paintLine.strokeJoin = Paint.Join.ROUND
        paintLine.strokeWidth = 4F
        paintLine.isAntiAlias = true

        paintBackground = Paint()
        paintBackground.color = Color.BLACK
        paintBackground.style = Paint.Style.FILL

        // Setting starting point of chart

        pathChunk = Path()
        pathChunk.moveTo(-10F, (yMax - yChunkOffset) - chunkDelay + dy)

        // Initializing the array for x values (DEFAULT INIT)
        // array consist of data points representing 16 bits
        // values read and converted values ready to be displayed
        // + additional delay field (last element of array)

        xArray = Array(pointsInArray * channelCount + 1) { 0F }
    }
    /**
     * @brief       Bluetooth Initialization Function
     * @retrieval   None
     */
    private fun bluetoothInit() {

        // After we check if permissions are granted this function will be invoked

        fun requestBluetoothEnable() {

            // Create bluetoothManager and bluetoothAdapter objects
            // to interact with bluetooth hardware

            bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter

            // If bluetooth is enabled, proceed with further bluetooth initialization

            if (bluetoothAdapter.isEnabled) {
                return
            }

            // Otherwise start activity for result which is BluetoothAdapter.ACTION_REQUEST_ENABLE

            val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            val resultLauncher =
                registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    if (result.resultCode != Activity.RESULT_OK) {
                        Toast.makeText(
                            this,
                            "You need to enable bluetooth",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            "Bluetooth enabled!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            resultLauncher.launch(enableBluetoothIntent)
        }

        // Declare all needed permissions, based on used Android version

        val requestedPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            // VERSION.SDK_INT < S
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        // Check if application already has all necessary permissions

        var allPermissionsGranted = true

        for (permission in requestedPermissions) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                allPermissionsGranted = false
            }
        }

        // If all permissions are already given, proceed with bluetooth initialization

        if (allPermissionsGranted) {
            requestBluetoothEnable()
            return
        }

        // If not, request permissions from user

        val resultLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                allPermissionsGranted = true
                for (permission in permissions.entries) {
                    if (!permission.value) {
                        allPermissionsGranted = false
                        break
                    }
                }

                // If user denied any permission, return from callback function

                if (!allPermissionsGranted) {
                    Toast.makeText(
                        this,
                        "Bluetooth permissions denied! Closing application",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }

                // If user gave us permissions proceed with further Bluetooth initialization

                else {
                    Toast.makeText(
                        this,
                        "Bluetooth permissions granted!",
                        Toast.LENGTH_SHORT
                    ).show()
                    requestBluetoothEnable()
                }
            }
        resultLauncher.launch(requestedPermissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chartInit()
        bluetoothInit()

        var backgroundThreadRoutine: BackgroundThreadRoutine
        var thread: Thread? = null

        button.setOnClickListener {

            if (bluetoothSocket != null) {
                check = !check
                if (thread == null || thread?.isAlive == false) {

                    // If there is no thread, or previous one is not alive, we have newly created socket

                    backgroundThreadRoutine = BackgroundThreadRoutine()
                    thread = Thread(backgroundThreadRoutine)
                    thread!!.start()
                }
                button.text = getString(if (check) R.string.buttonStop else R.string.buttonStart)

            } else {

                // if there is no connection start new one or ask user to enable bluetooth

                if (bluetoothAdapter.isEnabled) {
                    establishBluetoothCommunication(remoteBluetoothDeviceName)
                } else {
                    Toast.makeText(
                        this,
                        "You need to enable bluetooth",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothSocket?.inputStream?.close()
        bluetoothSocket?.close()
    }
}
