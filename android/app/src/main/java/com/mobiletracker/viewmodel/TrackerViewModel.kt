package com.mobiletracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobiletracker.core.engine.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CallMirrorEvent(
    val callerName: String,
    val callerNumber: String,
    val timestampMs: Long
)

class TrackerViewModel : ViewModel() {

    private val fusionArbiter = LocationFusionArbiter()

    private val _fusedLocation = MutableStateFlow<FusedLocationResult?>(null)
    val fusedLocation: StateFlow<FusedLocationResult?> = _fusedLocation.asStateFlow()

    private val _powerState = MutableStateFlow(PowerState.CELL_MAINTENANCE)
    val powerState: StateFlow<PowerState> = _powerState.asStateFlow()

    private val _aoaAzimuth = MutableStateFlow(18.4)
    val aoaAzimuth: StateFlow<Double?> = _aoaAzimuth.asStateFlow()

    private val _incomingCall = MutableStateFlow<CallMirrorEvent?>(null)
    val incomingCall: StateFlow<CallMirrorEvent?> = _incomingCall.asStateFlow()

    // 4 Modalities Enabled Flags
    var isGpsHardwareOn = true
    var isBleHardwareOn = true
    var isCellModemOn = true
    var isMeshActive = false

    init {
        refreshLocationFix()
    }

    fun refreshLocationFix() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()

            val gps = if (isGpsHardwareOn) LocationFixCandidate(
                sourceType = LocationSourceType.GPS_GNSS,
                latitude = 37.774929,
                longitude = -122.419416,
                accuracyMeters = 8.5f,
                timestampMs = now
            ) else null

            val ble = if (isBleHardwareOn) LocationFixCandidate(
                sourceType = LocationSourceType.BLE_AOA,
                latitude = 37.774910,
                longitude = -122.419405,
                accuracyMeters = 1.8f,
                timestampMs = now,
                metadata = mapOf("azimuth" to 18.4)
            ) else null

            val cell = if (isCellModemOn) LocationFixCandidate(
                sourceType = LocationSourceType.CELLULAR_RTREE,
                latitude = 37.774950,
                longitude = -122.419380,
                accuracyMeters = 140.0f,
                timestampMs = now
            ) else null

            val mesh = if (isMeshActive) LocationFixCandidate(
                sourceType = LocationSourceType.P2P_MESH_ANCHOR,
                latitude = 37.774900,
                longitude = -122.419420,
                accuracyMeters = 35.0f,
                timestampMs = now
            ) else null

            val result = fusionArbiter.evaluateAndFuse(
                gpsCandidate = gps,
                isGpsHardwareOn = isGpsHardwareOn,
                bleCandidate = ble,
                isBleHardwareOn = isBleHardwareOn,
                cellCandidate = cell,
                isCellModemOn = isCellModemOn,
                meshCandidate = mesh,
                isMeshActive = isMeshActive,
                currentTimeMs = now
            )

            _fusedLocation.value = result
        }
    }

    fun toggleSource(type: LocationSourceType) {
        when (type) {
            LocationSourceType.GPS_GNSS -> isGpsHardwareOn = !isGpsHardwareOn
            LocationSourceType.BLE_AOA -> isBleHardwareOn = !isBleHardwareOn
            LocationSourceType.CELLULAR_RTREE -> isCellModemOn = !isCellModemOn
            LocationSourceType.P2P_MESH_ANCHOR -> isMeshActive = !isMeshActive
        }
        refreshLocationFix()
    }

    fun simulateIncomingCall(callerName: String, callerNumber: String) {
        _incomingCall.value = CallMirrorEvent(callerName, callerNumber, System.currentTimeMillis())
    }

    fun dismissIncomingCall() {
        _incomingCall.value = null
    }
}
