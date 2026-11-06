package com.mobiletracker.android.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.telephony.*
import android.util.Log
import androidx.core.content.ContextCompat
import com.mobiletracker.core.engine.CellObservation
import com.mobiletracker.core.engine.CellularTriangulation
import com.mobiletracker.core.engine.TowerCandidate
import com.mobiletracker.core.engine.TriangulatedPosition
import java.util.concurrent.Executor

/**
 * Android Cellular Monitor & Passive Triangulation Engine
 *
 * Adheres to modern Android OS constraints:
 * - Requires ACCESS_FINE_LOCATION for cell tower scanning.
 * - Utilizes TelephonyCallback.CellInfoListener on Android 12+ (API 31+) to circumvent
 *   system rate-limiting on TelephonyManager.getAllCellInfo().
 * - Queries pre-downloaded SQLite R-Tree virtual table (rtree_cell_towers) for zero-data localization.
 */
class AndroidCellularMonitor(
    private val context: Context,
    private val sqLiteDatabase: SQLiteDatabase,
    private val executor: Executor,
    private val onLocationResolved: (TriangulatedPosition) -> Unit
) {
    companion object {
        private const val TAG = "AndroidCellularMonitor"
    }

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val triangulationEngine = CellularTriangulation()
    private var telephonyCallback: Any? = null

    /**
     * Registers asynchronous listener for continuous cell tower observation.
     */
    @SuppressLint("MissingPermission")
    fun startListening() {
        if (!hasLocationPermission()) {
            Log.e(TAG, "ACCESS_FINE_LOCATION permission not granted. Cannot query cellular modem.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+) Modern TelephonyCallback API
            val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                override fun onCellInfoChanged(cellInfoList: MutableList<CellInfo>) {
                    Log.d(TAG, "Asynchronous CellInfo update received: ${cellInfoList.size} towers")
                    processCellInfoBatch(cellInfoList)
                }
            }
            telephonyManager.registerTelephonyCallback(executor, callback)
            this.telephonyCallback = callback
        } else {
            // Legacy PhoneStateListener fallback
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                    cellInfo?.let { processCellInfoBatch(it) }
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CELL_INFO)
            this.telephonyCallback = listener
        }
    }

    fun stopListening() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let {
                telephonyManager.unregisterTelephonyCallback(it)
            }
        } else {
            @Suppress("DEPRECATION")
            (telephonyCallback as? PhoneStateListener)?.let {
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }
        telephonyCallback = null
    }

    /**
     * Extracts physical cellular telemetry and performs spatial R-Tree resolution.
     */
    private fun processCellInfoBatch(cellInfoList: List<CellInfo>) {
        val observations = mutableListOf<CellObservation>()

        for (info in cellInfoList) {
            when (info) {
                is CellInfoLte -> {
                    val identity = info.cellIdentity
                    val signal = info.cellSignalStrength
                    val mcc = identity.mccString?.toIntOrNull() ?: identity.mcc
                    val mnc = identity.mncString?.toIntOrNull() ?: identity.mnc
                    val lac = identity.tac
                    val cid = identity.ci.toLong()

                    if (cid != CellInfo.UNAVAILABLE.toLong() && cid != 0x7FFFFFFF.toLong()) {
                        observations.add(
                            CellObservation(
                                mcc = mcc,
                                mnc = mnc,
                                lac = lac,
                                cid = cid,
                                rssiDbm = signal.rsrp,
                                timingAdvance = signal.timingAdvance.takeIf { it != CellInfo.UNAVAILABLE }
                            )
                        )
                    }
                }
                is CellInfoNr -> {
                    // 5G New Radio support
                    val identity = info.cellIdentity as? CellIdentityNr
                    val signal = info.cellSignalStrength as? CellSignalStrengthNr
                    if (identity != null && signal != null) {
                        val mcc = identity.mccString?.toIntOrNull() ?: 0
                        val mnc = identity.mncString?.toIntOrNull() ?: 0
                        val tac = identity.tac
                        val nci = identity.nci

                        observations.add(
                            CellObservation(
                                mcc = mcc,
                                mnc = mnc,
                                lac = tac,
                                cid = nci,
                                rssiDbm = signal.ssRsrp,
                                timingAdvance = null
                            )
                        )
                    }
                }
                is CellInfoGsm -> {
                    val identity = info.cellIdentity
                    val signal = info.cellSignalStrength
                    val mcc = identity.mccString?.toIntOrNull() ?: identity.mcc
                    val mnc = identity.mncString?.toIntOrNull() ?: identity.mnc
                    val lac = identity.lac
                    val cid = identity.cid.toLong()

                    if (cid != CellInfo.UNAVAILABLE.toLong()) {
                        observations.add(
                            CellObservation(
                                mcc = mcc,
                                mnc = mnc,
                                lac = lac,
                                cid = cid,
                                rssiDbm = signal.dbm,
                                timingAdvance = null
                            )
                        )
                    }
                }
            }
        }

        if (observations.isEmpty()) return

        // Perform offline spatial lookup against local SQLite R-Tree table
        val matchedTowers = queryLocalRTree(observations)
        val position = triangulationEngine.triangulate(observations, matchedTowers)

        position?.let {
            Log.i(TAG, "Triangulated offline position: Lat=${it.latitude}, Lon=${it.longitude}, Acc=${it.estimatedAccuracyMeters}m using ${it.participatingTowers} towers")
            onLocationResolved(it)
        }
    }

    /**
     * Executes R-Tree spatial intersection join against cell_tower_metadata.
     */
    private fun queryLocalRTree(observations: List<CellObservation>): Map<Long, TowerCandidate> {
        val candidates = mutableMapOf<Long, TowerCandidate>()
        if (observations.isEmpty()) return candidates

        val cids = observations.map { it.cid }.joinToString(",")
        val query = """
            SELECT m.cid, m.cell_idx, (r.min_lat + r.max_lat)/2.0 as lat, (r.min_lon + r.max_lon)/2.0 as lon, m.range_meters, m.samples
            FROM cell_tower_metadata m
            JOIN rtree_cell_towers r ON m.cell_idx = r.cell_idx
            WHERE m.cid IN ($cids)
        """.trimIndent()

        try {
            sqLiteDatabase.rawQuery(query, null).use { cursor ->
                val cidCol = cursor.getColumnIndexOrThrow("cid")
                val idxCol = cursor.getColumnIndexOrThrow("cell_idx")
                val latCol = cursor.getColumnIndexOrThrow("lat")
                val lonCol = cursor.getColumnIndexOrThrow("lon")
                val rangeCol = cursor.getColumnIndexOrThrow("range_meters")
                val samplesCol = cursor.getColumnIndexOrThrow("samples")

                while (cursor.moveToNext()) {
                    val cid = cursor.getLong(cidCol)
                    candidates[cid] = TowerCandidate(
                        cellIdx = cursor.getLong(idxCol),
                        lat = cursor.getDouble(latCol),
                        lon = cursor.getDouble(lonCol),
                        rangeMeters = cursor.getInt(rangeCol),
                        samples = cursor.getInt(samplesCol)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute spatial query on SQLite R-Tree", e)
        }

        return candidates
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
