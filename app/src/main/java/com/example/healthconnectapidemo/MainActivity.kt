package com.example.healthconnectapidemo

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.Permission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (HealthConnectClient.isAvailable(this)) {
            // Health Connect is available
            checkPermissionsAndRun()
        } else {
            Toast.makeText(
                this, "Health Connect is not available", Toast.LENGTH_SHORT
            ).show()
        }

        val stepsEditText = findViewById<AppCompatEditText>(R.id.etsteps)
        val caloriesEditText = findViewById<AppCompatEditText>(R.id.etcalories)

        findViewById<AppCompatButton>(R.id.btnUpdate).setOnClickListener {
            val steps = stepsEditText.text.toString().toLong()
            val calories = caloriesEditText.text.toString().toDouble()

            val client = HealthConnectClient.getOrCreate(this)
            insertData(client, steps, calories)

            // clear input fields after insertion and close the keyboard
            stepsEditText.text?.clear()
            caloriesEditText.text?.clear()
            caloriesEditText.onEditorAction(EditorInfo.IME_ACTION_DONE)
        }


    }

    private fun checkPermissionsAndRun() {
        // 1
        val client = HealthConnectClient.getOrCreate(this)

        // 2
        val permissionsSet = setOf(
            Permission.createWritePermission(StepsRecord::class),
            Permission.createReadPermission(StepsRecord::class),
            Permission.createWritePermission(TotalCaloriesBurnedRecord::class),
            Permission.createReadPermission(TotalCaloriesBurnedRecord::class),
        )

        // 3
        // Create the permissions launcher.
        val requestPermissionActivityContract = client
            .permissionController
            .createRequestPermissionActivityContract()

        val requestPermissions = registerForActivityResult(
            requestPermissionActivityContract
        ) { granted ->
            if (granted.containsAll(permissionsSet)) {
                // Permissions successfully granted
                lifecycleScope.launch {
                    onPermissionAvailable(client)
                }
            } else {
                Toast.makeText(
                    this, "Permissions not granted", Toast.LENGTH_SHORT
                ).show()
            }
        }

        // 4
        lifecycleScope.launch {
            val granted = client.permissionController
                .getGrantedPermissions(permissionsSet)
            if (granted.containsAll(permissionsSet)) {
                // Permissions already granted
                onPermissionAvailable(client)
            } else {
                // Permissions not granted, request permissions.
                requestPermissions.launch(permissionsSet)
            }
        }
    }

    private suspend fun onPermissionAvailable(client: HealthConnectClient) {
        readData(client)
    }

    private fun insertData(client: HealthConnectClient, steps: Long, caloriesBurned: Double) {
        // 1
        val startTime = ZonedDateTime.now().minusSeconds(1).toInstant()
        val endTime = ZonedDateTime.now().toInstant()

        // 2
        val records = listOf(
            StepsRecord(
                count = steps,
                startTime = startTime,
                endTime = endTime,
                startZoneOffset = null,
                endZoneOffset = null,
            ),
            TotalCaloriesBurnedRecord(
                energy = Energy.calories(caloriesBurned),
                startTime = startTime,
                endTime = endTime,
                startZoneOffset = null,
                endZoneOffset = null,
            )
        )

        // 3
        lifecycleScope.launch {
            val insertRecords = client.insertRecords(records)
            readData(client)
            if (insertRecords.recordUidsList.isNotEmpty()) {
                runOnUiThread{
                    Toast.makeText(
                        this@MainActivity,
                        "Records inserted successfully",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private suspend fun readDailyRecords(client: HealthConnectClient) {
        // 1
        val today = ZonedDateTime.now()
        val startOfDay = today.truncatedTo(ChronoUnit.DAYS)
        val timeRangeFilter = TimeRangeFilter.between(
            startOfDay.toLocalDateTime(),
            today.toLocalDateTime()
        )

        // 2
        val stepsRecordRequest = ReadRecordsRequest(StepsRecord::class, timeRangeFilter)
        val numberOfStepsToday = client.readRecords(stepsRecordRequest)
            .records
            .sumOf { it.count }
        val stepsTextView = findViewById<AppCompatTextView>(R.id.tvstepsCount)
        stepsTextView.text = numberOfStepsToday.toString()

        // 3
        val caloriesRecordRequest = ReadRecordsRequest(
            TotalCaloriesBurnedRecord::class,
            timeRangeFilter
        )
        val caloriesBurnedToday = client.readRecords(caloriesRecordRequest)
            .records
            .sumOf { it.energy.inCalories }
        val caloriesTextView = findViewById<AppCompatTextView>(R.id.tvCaloriesCount)
        caloriesTextView.text = caloriesBurnedToday.toString()
    }

    private suspend fun readAggregatedData(client: HealthConnectClient) {
        // 1
        val today = ZonedDateTime.now()
        val startOfDayOfThisMonth = today.withDayOfMonth(1)
            .truncatedTo(ChronoUnit.DAYS)
        val elapsedDaysInMonth = Duration.between(startOfDayOfThisMonth, today)
            .toDays() + 1
        val timeRangeFilter = TimeRangeFilter.between(
            startOfDayOfThisMonth.toInstant(),
            today.toInstant()
        )

        // 2
        val data = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL
                ),
                timeRangeFilter = timeRangeFilter,
            )
        )

        // 3
        val steps = data[StepsRecord.COUNT_TOTAL] ?: 0
        val averageSteps = steps / elapsedDaysInMonth
        val stepsAverageTextView = findViewById<AppCompatTextView>(R.id.tvStepsAvgCount)
        stepsAverageTextView.text = averageSteps.toString()

        // 4
        val caloriesBurned = data[TotalCaloriesBurnedRecord.ENERGY_TOTAL]
            ?.inCalories ?: 0.0
        val averageCaloriesBurned = caloriesBurned / elapsedDaysInMonth
        val caloriesAverageTextView = findViewById<AppCompatTextView>(
            R.id.tvAvgCaloriesCount
        )
        caloriesAverageTextView.text = getString(R.string.format_calories_average)
            .format(averageCaloriesBurned)
    }

    private suspend fun readData(client: HealthConnectClient) {
        readDailyRecords(client)
        readAggregatedData(client)
    }


}

